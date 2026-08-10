package com.navideck.universal_ble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry
import java.util.IdentityHashMap
import java.util.UUID
import androidx.core.content.edit

@SuppressLint("MissingPermission")
class UniversalBlePlugin : UniversalBlePlatformChannel, BluetoothGattCallback(), FlutterPlugin,
    UniversalBleAndroidChannel,
    ActivityAware, PluginRegistry.ActivityResultListener,
    PluginRegistry.RequestPermissionsResultListener {
    private val bluetoothEnableRequestCode = 2342313
    private val bluetoothDisableRequestCode = 2342414
    private val permissionRequestCode = 2342515
    private val advertisePermissionRequestCode = 2342616
    private var permissionHandler: PermissionHandler? = null
    private var callbackChannel: UniversalBleCallbackChannel? = null

    private var mainThreadHandler: Handler? = null
    private lateinit var context: Context
    private var activity: Activity? = null
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var safeScanner: SafeScanner
    private val cachedServicesMap = mutableMapOf<String, List<String>>()
    private val universalBleFilterUtil = UniversalBleFilterUtil()
    private lateinit var peripheralPlugin: UniversalBlePeripheralPlugin

    // Flutter Futures
    private var bluetoothEnableRequestFuture: ((Result<Boolean>) -> Unit)? = null
    private var bluetoothDisableRequestFuture: ((Result<Boolean>) -> Unit)? = null
    private val discoverServicesFutureList = mutableListOf<DiscoverServicesFuture>()
    private val mtuResultFutureList = mutableListOf<MtuResultFuture>()
    private val readResultFutureList = mutableListOf<ReadResultFuture>()
    private val writeResultFutureList = mutableListOf<WriteResultFuture>()
    private val subscriptionResultFutureList = mutableListOf<SubscriptionResultFuture>()
    private val localNotificationStates = IdentityHashMap<BluetoothGatt, MutableSet<String>>()
    private val temporaryDiscoveryCleanups = IdentityHashMap<BluetoothGatt, () -> Unit>()
    private val ownedGatts = IdentityHashMap<BluetoothGatt, Unit>()
    private val pairResultFutures = mutableMapOf<String, (Result<Boolean>) -> Unit>()
    private val rssiResultFutureList = mutableListOf<RssiResultFuture>()
    private val autoConnectDevices = mutableSetOf<String>()

    // Disconnecting too soon after connectGatt can strand a connection the
    // Android stack no longer tracks (no gatt handle to tear it down with).
    // Enforce a minimum gap, like flutter_blue_plus' androidDelay.
    // https://issuetracker.google.com/issues/37121040
    private val connectTimestamps = mutableMapOf<String, Long>()
    private val disconnectTimestamps = mutableMapOf<String, Long>()
    private val pendingConnects = mutableMapOf<String, Runnable>()
    private val minConnectDisconnectGapMs = 2000L
    private val minDisconnectConnectGapMs = 2000L

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        mainThreadHandler = Handler(Looper.getMainLooper())
        permissionHandler = PermissionHandler(
            context,
            permissionRequestCode,
            advertisePermissionRequestCode,
        )
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        safeScanner = SafeScanner(bluetoothManager)

        UniversalBlePlatformChannel.setUp(flutterPluginBinding.binaryMessenger, this)
        UniversalBleAndroidChannel.setUp(flutterPluginBinding.binaryMessenger, this)
        peripheralPlugin = UniversalBlePeripheralPlugin(
            flutterPluginBinding.applicationContext,
            bluetoothManager,
            flutterPluginBinding.binaryMessenger
        )
        UniversalBlePeripheralChannel.setUp(flutterPluginBinding.binaryMessenger, peripheralPlugin)
        callbackChannel = UniversalBleCallbackChannel(flutterPluginBinding.binaryMessenger)


        val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiverCompat(broadcastReceiver, intentFilter, exported = true)
        cachedServicesMap.putAll(getCachedServicesMap())
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        safeScanner.stopScan()
        cleanUpCentralState(null, ownedGatts.keys.toList())
        disconnectTimestamps.clear()
        context.unregisterReceiver(broadcastReceiver)
        peripheralPlugin.dispose()
        UniversalBlePlatformChannel.setUp(binding.binaryMessenger, null)
        UniversalBlePeripheralChannel.setUp(binding.binaryMessenger, null)
        UniversalBleAndroidChannel.setUp(binding.binaryMessenger, null)
        callbackChannel = null
        mainThreadHandler = null
        permissionHandler = null
    }

    override fun getBluetoothAvailabilityState(callback: (Result<AvailabilityState>) -> Unit) {
        callback(
            Result.success(
                bluetoothManager.adapter?.state?.toAvailabilityState()
                    ?: AvailabilityState.UNKNOWN
            )
        )
    }

    override fun hasPermissions(withAndroidFineLocation: Boolean): Boolean {
        return permissionHandler?.hasPermissions(withAndroidFineLocation) ?: false
    }

    override fun requestPermissions(
        withAndroidFineLocation: Boolean,
        callback: (Result<Unit>) -> Unit,
    ) {
        if (permissionHandler == null) {
            callback(
                Result.failure(
                    createFlutterError(
                        UniversalBleErrorCode.FAILED,
                        "PermissionHandler is not initialized"
                    )
                )
            )
        }
        permissionHandler?.requestPermissions(withAndroidFineLocation, callback)
    }

    override fun enableBluetooth(callback: (Result<Boolean>) -> Unit) {
        if (bluetoothManager.adapter.isEnabled) {
            callback(Result.success(true))
            return
        }
        if (bluetoothEnableRequestFuture != null) {
            callback(
                Result.failure(
                    createFlutterError(
                        UniversalBleErrorCode.OPERATION_IN_PROGRESS,
                        "Bluetooth enable request in progress"
                    )
                )
            )
            return
        }
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        activity?.startActivityForResult(enableBtIntent, bluetoothEnableRequestCode)
        bluetoothEnableRequestFuture = callback
    }

    override fun disableBluetooth(callback: (Result<Boolean>) -> Unit) {
        if (!bluetoothManager.adapter.isEnabled) {
            callback(Result.success(true))
            return
        }
        if (bluetoothDisableRequestFuture != null) {
            callback(
                Result.failure(
                    createFlutterError(
                        UniversalBleErrorCode.OPERATION_IN_PROGRESS,
                        "Bluetooth disable request in progress"
                    )
                )
            )
            return
        }
        val disableBtIntent = Intent("android.bluetooth.adapter.action.REQUEST_DISABLE")
        activity?.startActivityForResult(disableBtIntent, bluetoothDisableRequestCode)
        bluetoothDisableRequestFuture = callback
    }

    override fun startScan(filter: UniversalScanFilter?, config: UniversalScanConfig?) {
        if (!isBluetoothAvailable()) throw createFlutterError(
            UniversalBleErrorCode.BLUETOOTH_NOT_ENABLED,
            "Bluetooth not enabled"
        )

        val builder = ScanSettings.Builder()
        val legacy = config?.android?.legacy
        config?.android?.let { androidConfig ->
            androidConfig.scanMode?.parse()?.let { scanMode ->
                builder.setScanMode(scanMode)
            }
            androidConfig.reportDelayMillis?.let { reportDelay ->
                builder.setReportDelay(reportDelay)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                androidConfig.callbackType?.let { types ->
                    val combined = types.mapNotNull { it.parse() }.fold(0) { acc, v -> acc or v }
                    if (combined != 0) builder.setCallbackType(combined)
                }
                androidConfig.matchMode?.parse()?.let { builder.setMatchMode(it) }
                androidConfig.numOfMatches?.parse()?.let { builder.setNumOfMatches(it) }
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (legacy == true) {
                builder.setLegacy(true)
            } else {
                builder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                builder.setLegacy(false)
            }
        }
        val settings = builder.build()

        val usesCustomFilters = filter?.usesCustomFilters() ?: false

        val errorCode = try {
            val filterServices = filter?.withServices?.toUUIDList() ?: emptyList()
            var scanFilters = emptyList<ScanFilter>()

            // Set custom scan filter only if required
            if (usesCustomFilters) {
                UniversalBleLogger.logError("Using Custom Filters")
                universalBleFilterUtil.scanFilter = filter
                universalBleFilterUtil.serviceFilterUUIDS = filterServices
            } else {
                universalBleFilterUtil.scanFilter = null
                scanFilters = filter?.toScanFilters(filterServices) ?: emptyList()
            }

            safeScanner.startScan(
                scanFilters, settings, scanCallback
            )
        } catch (e: Exception) {
            throw createFlutterError(
                UniversalBleErrorCode.FAILED,
                "Failed to start Scan",
                details = e.toString()
            )
        }
        if (errorCode != null) throw createFlutterError(
            UniversalBleErrorCode.SCAN_FAILED,
            errorCode.parseScanErrorMessage(),
            details = errorCode.toString()
        )
    }

    override fun stopScan() {
        if (!isBluetoothAvailable()) throw createFlutterError(
            UniversalBleErrorCode.BLUETOOTH_NOT_ENABLED,
            "Bluetooth not enabled"
        )
        // check if already scanning
        safeScanner.stopScan()
    }

    override fun isScanning(): Boolean {
        return safeScanner.isScanning()
    }

    override fun connect(
        deviceId: String,
        autoConnect: Boolean?,
        platformConfig: ConnectionPlatformConfig?,
    ) {
        val connectionKey = deviceId.connectionKey()
        // Note: platformConfig only carries Apple-specific options
        // If already connected, send connected message,
        // if connecting, do nothing
        deviceId.findGatt()?.let {
            val currentState = bluetoothManager.getConnectionState(it.device, BluetoothProfile.GATT)
            if (currentState == BluetoothGatt.STATE_CONNECTED) {
                UniversalBleLogger.logError("$deviceId Already connected")
                mainThreadHandler?.post {
                    callbackChannel?.onConnectionChanged(deviceId, true, null) {}
                }
                return
            } else if (currentState == BluetoothGatt.STATE_CONNECTING) {
                throw createFlutterError(
                    UniversalBleErrorCode.CONNECTION_IN_PROGRESS,
                    "Connection already in progress"
                )
            }
        }

        if (pendingConnects.containsKey(connectionKey)) {
            throw createFlutterError(
                UniversalBleErrorCode.CONNECTION_IN_PROGRESS,
                "Connection already scheduled"
            )
        }

        val shouldAutoConnect = autoConnect ?: false
        if (shouldAutoConnect) {
            autoConnectDevices.add(connectionKey)
        } else {
            autoConnectDevices.remove(connectionKey)
        }
        val reconnectDelay = remainingReconnectDelay(
            SystemClock.elapsedRealtime(),
            disconnectTimestamps[connectionKey],
            minDisconnectConnectGapMs,
        )
        if (reconnectDelay > 0) {
            UniversalBleLogger.logDebug(
                "Delaying connect of $deviceId by ${reconnectDelay}ms (disconnect-connect gap)"
            )
            lateinit var pendingConnect: Runnable
            pendingConnect = Runnable {
                val remainingDelay = remainingReconnectDelay(
                    SystemClock.elapsedRealtime(),
                    disconnectTimestamps[connectionKey],
                    minDisconnectConnectGapMs,
                )
                executePendingConnect(
                    remainingDelay,
                    { delay -> mainThreadHandler?.postDelayed(pendingConnect, delay) },
                ) {
                    pendingConnects.remove(connectionKey)
                    disconnectTimestamps.remove(connectionKey)
                    connectNow(deviceId, shouldAutoConnect)
                }?.let { error ->
                    UniversalBleLogger.logError(
                        "Delayed connect start failed for $deviceId: $error"
                    )
                    notifyDisconnected(
                        deviceId,
                        "CONNECT_START_FAILED: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
            pendingConnects[connectionKey] = pendingConnect
            mainThreadHandler?.postDelayed(pendingConnect, reconnectDelay)
            return
        }
        disconnectTimestamps.remove(connectionKey)
        connectNow(deviceId, shouldAutoConnect)
    }

    private fun connectNow(deviceId: String, shouldAutoConnect: Boolean) {
        val connectionKey = deviceId.connectionKey()
        deviceId.findGatt()?.let {
            val currentState = bluetoothManager.getConnectionState(it.device, BluetoothProfile.GATT)
            if (currentState == BluetoothGatt.STATE_CONNECTED) {
                mainThreadHandler?.post {
                    callbackChannel?.onConnectionChanged(deviceId, true, null) {}
                }
                return
            }
            if (currentState == BluetoothGatt.STATE_CONNECTING) return
        }
        val remoteDevice = bluetoothManager.adapter.getRemoteDevice(deviceId)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            remoteDevice.connectGatt(
                context,
                shouldAutoConnect,
                this,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                requireNotNull(mainThreadHandler),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            remoteDevice.connectGatt(
                context,
                shouldAutoConnect,
                this,
                BluetoothDevice.TRANSPORT_LE
            )
        } else {
            remoteDevice.connectGatt(context, shouldAutoConnect, this)
        }
        connectTimestamps[connectionKey] = SystemClock.elapsedRealtime()
        gatt.saveCacheIfNeeded()
        ownedGatts[gatt] = Unit
    }

    private fun completeGattCallback(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            action()
        } else {
            (mainThreadHandler ?: Handler(Looper.getMainLooper())).post(action)
        }
    }

    override fun disconnect(deviceId: String) {
        val connectionKey = deviceId.connectionKey()
        autoConnectDevices.remove(connectionKey)
        pendingConnects.remove(connectionKey)?.let { mainThreadHandler?.removeCallbacks(it) }
        val gatt = deviceId.findGatt()
        if (gatt == null) {
            cleanUpConnections(deviceId)
            notifyDisconnected(deviceId, null)
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - (connectTimestamps[connectionKey] ?: 0L)
        val remaining = minConnectDisconnectGapMs - elapsed
        if (remaining > 0) {
            UniversalBleLogger.logDebug(
                "Delaying disconnect of $deviceId by ${remaining}ms (connect-disconnect gap)"
            )
            mainThreadHandler?.postDelayed({ cleanConnection(gatt) }, remaining)
        } else {
            cleanConnection(gatt)
        }
    }

    override fun getConnectionState(deviceId: String): BleConnectionState {
        try {
            val connectionState = bluetoothManager.getConnectionState(
                bluetoothManager.adapter.getRemoteDevice(deviceId),
                BluetoothProfile.GATT
            )
            return if (deviceId.isKnownGatt() || connectionState == BluetoothGatt.STATE_DISCONNECTED || connectionState == BluetoothGatt.STATE_DISCONNECTING) {
                connectionState.toBleConnectionState()
            } else {
                // Might be connected with device, but not with app
                UniversalBleLogger.logError("Device might be connected but not known to this app")
                BleConnectionState.DISCONNECTED
            }
        } catch (_: Exception) {
            return BleConnectionState.DISCONNECTED
        }
    }

    override fun setLogLevel(logLevel: BleLogLevel) {
        UniversalBleLogger.setLogLevel(logLevel)
    }

    override fun hasBluetoothAdvertisePermission(): Boolean {
        return permissionHandler?.hasBluetoothAdvertisePermission() ?: false
    }

    override fun requestBluetoothAdvertisePermission(callback: (Result<Boolean>) -> Unit) {
        val handler = permissionHandler
        if (handler == null) {
            callback(
                Result.failure(
                    createFlutterError(
                        UniversalBleErrorCode.FAILED,
                        "PermissionHandler is not initialized",
                    ),
                ),
            )
            return
        }
        handler.requestBluetoothAdvertisePermission(callback)
    }

    override fun clearGattCache(deviceId: String) {
        val gatt = deviceId.toBluetoothGatt()
        try {
            val refresh = gatt.javaClass.getMethod("refresh")
            val result = refresh.invoke(gatt) as? Boolean ?: false
            if (!result) {
                throw createFlutterError(
                    UniversalBleErrorCode.FAILED,
                    "gatt.refresh() returned false"
                )
            }
        } catch (e: FlutterError) {
            throw e
        } catch (e: Exception) {
            throw createFlutterError(
                UniversalBleErrorCode.FAILED,
                "Failed to clear GATT cache",
                e.toString()
            )
        }
    }

    override fun readRssi(deviceId: String, callback: (Result<Long>) -> Unit) {
        try {
            val gatt = deviceId.toBluetoothGatt()
            if (gatt.readRemoteRssi()) {
                rssiResultFutureList.add(RssiResultFuture(gatt, deviceId, callback))
            } else {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.FAILED,
                            "Failed to read RSSI"
                        )
                    )
                )
            }
        } catch (e: FlutterError) {
            callback(Result.failure(e))
        }
    }

    override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
        val callbackGatt = gatt ?: return
        val deviceId = callbackGatt.device.address
        completeGattCallback {
            if (cleanUpIfStale(callbackGatt)) return@completeGattCallback
            rssiResultFutureList.removeAll {
                if (it.gatt === callbackGatt && it.deviceId == deviceId) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        it.result(Result.success(rssi.toLong()))
                    } else {
                        it.result(
                            Result.failure(
                                createFlutterError(
                                    UniversalBleErrorCode.FAILED,
                                    "Failed to read RSSI"
                                )
                            )
                        )
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun discoverServices(
        deviceId: String,
        withDescriptors: Boolean,
        callback: (Result<List<UniversalBleService>>) -> Unit,
    ) {
        try {
            val gatt = deviceId.toBluetoothGatt()
            if (gatt.discoverServices()) {
                discoverServicesFutureList.add(
                    DiscoverServicesFuture(
                        gatt,
                        deviceId,
                        withDescriptors,
                        callback
                    )
                )
            } else {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.FAILED,
                            "Failed to discover services"
                        )
                    )
                )
            }
        } catch (e: FlutterError) {
            callback(Result.failure(e))
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        completeGattCallback {
            if (cleanUpIfStale(gatt)) return@completeGattCallback
            if (status != BluetoothGatt.GATT_SUCCESS) {
                discoverServicesFutureList.removeAll {
                    if (it.gatt === gatt && it.deviceId == gatt.device.address) {
                        it.result(
                            Result.failure(
                                createFlutterError(
                                    UniversalBleErrorCode.FAILED,
                                    "Failed to discover services"
                                )
                            )
                        )
                        true
                    } else {
                        false
                    }
                }
                return@completeGattCallback
            }
            setCachedServices(gatt.device.address, gatt.services.map { it.uuid.toString() })
            discoverServicesFutureList.removeAll {
                if (it.gatt === gatt && it.deviceId == gatt.device.address) {
                    it.result(Result.success(gatt.services.map { service ->
                        UniversalBleService(
                            uuid = service.uuid.toString(),
                            characteristics = service.characteristics.map { char ->
                                UniversalBleCharacteristic(
                                    uuid = char.uuid.toString(),
                                    properties = char.getPropertiesList(),
                                    descriptors = if (it.withDescriptors) char.descriptors.map { descriptor ->
                                        UniversalBleDescriptor(descriptor.uuid.toString())
                                    } else listOf()
                                )
                            }
                        )
                    }))
                    true
                } else {
                    false
                }
            }
        }
    }


    override fun setNotifiable(
        deviceId: String,
        service: String,
        characteristic: String,
        bleInputProperty: BleInputProperty,
        callback: (Result<Unit>) -> Unit,
    ) {
        try {
            UniversalBleLogger.logDebug("SET_NOTIFY -> $deviceId $service $characteristic input=$bleInputProperty")
            val gatt = deviceId.toBluetoothGatt()
            val gattCharacteristic: BluetoothGattCharacteristic? =
                gatt.getCharacteristic(service, characteristic)

            if (gattCharacteristic == null) {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.CHARACTERISTIC_NOT_FOUND,
                            "characteristic not found"
                        )
                    )
                )
                return
            }

            val descriptor: BluetoothGattDescriptor? =
                gattCharacteristic.getDescriptor(ccdCharacteristic)

            val (value, enable) = when (bleInputProperty) {
                BleInputProperty.NOTIFICATION -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE to true
                BleInputProperty.INDICATION -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE to true
                else -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE to false
            }

            val previousLocalState = isLocalNotificationEnabled(gatt, gattCharacteristic)
            if (!setLocalNotificationState(gatt, gattCharacteristic, enable)) {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.FAILED,
                            "Failed to update local notification routing"
                        )
                    )
                )
                return
            }

            if (descriptor == null) {
                UniversalBleLogger.logDebug("CCCD Descriptor not found")
                callback(Result.success(Unit))
                return
            }

            val pending = SubscriptionResultFuture(
                gatt,
                gatt.device.address,
                gattCharacteristic.uuid.toString(),
                gattCharacteristic.service.uuid.toString(),
                previousLocalState,
                callback
            )
            subscriptionResultFutureList.add(pending)

            val status = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    if (gatt.writeDescriptor(descriptor)) {
                        BluetoothStatusCodes.SUCCESS
                    } else {
                        BluetoothGatt.GATT_FAILURE
                    }
                }
            } catch (e: Exception) {
                subscriptionResultFutureList.remove(pending)
                restoreLocalNotificationState(pending, gattCharacteristic)
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.FAILED,
                            "Failed to update descriptor",
                            e.toString()
                        )
                    )
                )
                return
            }

            if (status != BluetoothStatusCodes.SUCCESS) {
                subscriptionResultFutureList.remove(pending)
                restoreLocalNotificationState(pending, gattCharacteristic)
                callback(
                    Result.failure(
                        createFlutterError(
                            status.parseBluetoothStatusCodeError()
                                ?: UniversalBleErrorCode.FAILED,
                            "Failed to update descriptor"
                        )
                    )
                )
            }
        } catch (e: FlutterError) {
            callback(Result.failure(e))
        } catch (e: Exception) {
            callback(
                Result.failure(
                    createFlutterError(
                        UniversalBleErrorCode.FAILED,
                        "Failed to update subscription state",
                        e.toString()
                    )
                )
            )
        }
    }

    private fun notificationKey(characteristic: BluetoothGattCharacteristic): String =
        "${characteristic.service.uuid}/${characteristic.uuid}"

    private fun isLocalNotificationEnabled(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean = localNotificationStates[gatt]?.contains(notificationKey(characteristic)) == true

    private fun setLocalNotificationState(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, enabled)) return false
        val key = notificationKey(characteristic)
        if (enabled) {
            localNotificationStates.getOrPut(gatt) { mutableSetOf() }.add(key)
        } else {
            localNotificationStates[gatt]?.let { states ->
                states.remove(key)
                if (states.isEmpty()) localNotificationStates.remove(gatt)
            }
        }
        return true
    }

    private fun restoreLocalNotificationState(
        pending: SubscriptionResultFuture,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (!setLocalNotificationState(pending.gatt, characteristic, pending.previousLocalState)) {
            UniversalBleLogger.logError(
                "Failed to restore local notification routing for ${pending.deviceId}"
            )
        }
    }

    override fun readValue(
        deviceId: String,
        service: String,
        characteristic: String,
        callback: (Result<ByteArray>) -> Unit,
    ) {
        try {
            UniversalBleLogger.logDebug("READ -> $deviceId $service $characteristic")
            val gatt = deviceId.toBluetoothGatt()
            val gattCharacteristic = gatt.getCharacteristic(service, characteristic)
            if (gattCharacteristic == null) {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.CHARACTERISTIC_NOT_FOUND,
                            "Unknown characteristic"
                        )
                    )
                )
                return
            }
            if (!gatt.readCharacteristic(gattCharacteristic)) {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.CHARACTERISTIC_NOT_FOUND,
                            "$characteristic not found",
                        )
                    )
                )
                return
            }

            readResultFutureList.add(
                ReadResultFuture(
                    gatt,
                    gatt.device.address,
                    gattCharacteristic.uuid.toString(),
                    gattCharacteristic.service.uuid.toString(),
                    callback
                )
            )
        } catch (e: FlutterError) {
            callback(Result.failure(e))
        } catch (e: Exception) {
            callback(
                Result.failure(
                    createFlutterError(
                        UniversalBleErrorCode.READ_FAILED,
                        "Failed to read value",
                        e.toString()
                    )
                )
            )
        }
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        completeGattCallback {
            if (cleanUpIfStale(gatt)) return@completeGattCallback
            readResultFutureList.removeAll {
                if (it.gatt === gatt &&
                    it.deviceId == gatt.device.address &&
                    it.characteristicId == characteristic.uuid.toString() &&
                    it.serviceId == characteristic.service.uuid.toString()
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        it.result(Result.success(value))
                    } else {
                        UniversalBleLogger.logError(
                            "READ_FAILED <- ${gatt.device.address} ${characteristic.uuid} status=$status"
                        )
                        it.result(
                            Result.failure(
                                createFlutterError(
                                    gattStatusToUniversalBleErrorCode(status),
                                    "Failed to read",
                                    status.toString()
                                )
                            )
                        )
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun writeValue(
        deviceId: String,
        service: String,
        characteristic: String,
        value: ByteArray,
        bleOutputProperty: BleOutputProperty,
        callback: (Result<Unit>) -> Unit,
    ) {
        try {
            UniversalBleLogger.logDebug("WRITE -> $deviceId $service $characteristic len=${value.size} property=$bleOutputProperty")
            val gatt = deviceId.toBluetoothGatt()
            val gattCharacteristic = gatt.getCharacteristic(service, characteristic)
            if (gattCharacteristic == null) {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.CHARACTERISTIC_NOT_FOUND,
                            "$characteristic not found",
                        )
                    )
                )
                return
            }

            var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (bleOutputProperty == BleOutputProperty.WITH_RESPONSE) {
                if (gattCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                    callback(
                        Result.failure(
                            createFlutterError(
                                UniversalBleErrorCode.CHARACTERISTIC_DOES_NOT_SUPPORT_WRITE,
                                "Characteristic does not support write withResponse"
                            )
                        )
                    )
                    return
                }
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else if (bleOutputProperty == BleOutputProperty.WITHOUT_RESPONSE) {
                if (gattCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
                    callback(
                        Result.failure(
                            createFlutterError(
                                UniversalBleErrorCode.CHARACTERISTIC_DOES_NOT_SUPPORT_WRITE_WITHOUT_RESPONSE,
                                "Characteristic does not support write withoutResponse"
                            )
                        )
                    )
                    return
                }
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }


            val writeFuture = WriteResultFuture(
                gatt,
                gatt.device.address,
                gattCharacteristic.uuid.toString(),
                gattCharacteristic.service.uuid.toString(),
                callback
            )

            writeResultFutureList.add(writeFuture)

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(gattCharacteristic, value, writeType)
            } else {
                @Suppress("DEPRECATION")
                gattCharacteristic.value = value
                gattCharacteristic.writeType = writeType
                @Suppress("DEPRECATION")
                val status = gatt.writeCharacteristic(gattCharacteristic)
                if (status) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }

            if (result != BluetoothGatt.GATT_SUCCESS) {
                writeResultFutureList.remove(writeFuture)
                callback(
                    Result.failure(
                        createFlutterError(
                            gattStatusToUniversalBleErrorCode(result),
                            "Failed to write",
                            result.toString()
                        )
                    )
                )
            }
        } catch (e: FlutterError) {
            callback(Result.failure(e))
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        val callbackGatt = gatt ?: return
        completeGattCallback {
            if (cleanUpIfStale(callbackGatt)) return@completeGattCallback
            val matches = writeResultFutureList.filter {
                it.gatt === callbackGatt &&
                    it.deviceId == callbackGatt.device.address &&
                    it.characteristicId == characteristic.uuid.toString() &&
                    it.serviceId == characteristic.service?.uuid?.toString()
            }
            writeResultFutureList.removeAll(matches)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                UniversalBleLogger.logError(
                    "WRITE_FAILED <- ${callbackGatt.device.address} ${characteristic.uuid} status=$status"
                )
            }
            for (future in matches) {
                try {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        future.result(Result.success(Unit))
                    } else {
                        future.result(
                            Result.failure(
                                createFlutterError(
                                    gattStatusToUniversalBleErrorCode(status),
                                    "Failed to write",
                                    status.toString()
                                )
                            )
                        )
                    }
                } catch (e: Exception) {
                    UniversalBleLogger.logError("Write completion delivery failed: $e")
                }
            }
        }
    }


    override fun requestMtu(deviceId: String, expectedMtu: Long, callback: (Result<Long>) -> Unit) {
        UniversalBleLogger.logDebug("REQUEST_MTU -> $deviceId expected=$expectedMtu")
        try {
            val gatt = deviceId.toBluetoothGatt()
            gatt.requestMtu(expectedMtu.toInt())
            mtuResultFutureList.add(MtuResultFuture(gatt, deviceId, callback))
        } catch (e: FlutterError) {
            callback(Result.failure(e))
        }
    }

    override fun requestConnectionPriority(
        deviceId: String,
        priority: BleConnectionPriority,
        callback: (Result<Unit>) -> Unit,
    ) {
        try {
            val gatt = deviceId.toBluetoothGatt()
            val androidPriority = when (priority) {
                BleConnectionPriority.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                BleConnectionPriority.HIGH_PERFORMANCE -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
                BleConnectionPriority.LOW_POWER -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            }
            val success = gatt.requestConnectionPriority(androidPriority)
            if (success) {
                callback(Result.success(Unit))
            } else {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.FAILED,
                            "requestConnectionPriority returned false",
                        ),
                    ),
                )
            }
        } catch (e: FlutterError) {
            callback(Result.failure(e))
        } catch (e: Exception) {
            callback(
                Result.failure(
                    createFlutterError(
                        UniversalBleErrorCode.FAILED,
                        "requestConnectionPriority failed",
                        e.toString()
                    )
                )
            )
        }
    }

    // BluetoothGattCallback.onConnectionUpdated is @hide (not in public android.jar).
    // Same method name is required so the framework invokes it at runtime; no `override`.
    @Suppress("unused")
    fun onConnectionUpdated(
        gatt: BluetoothGatt?,
        interval: Int,
        latency: Int,
        timeout: Int,
        status: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        completeGattCallback {
            val callbackGatt = gatt ?: return@completeGattCallback
            if (!callbackGatt.isCurrentGatt()) return@completeGattCallback
            val deviceId = callbackGatt.device.address
            UniversalBleLogger.logDebug(
                "onConnectionUpdated -> $deviceId interval=$interval latency=$latency timeout=$timeout status=$status"
            )
            callbackChannel?.onConnectionParametersUpdated(
                BleConnectionParametersUpdated(
                    deviceId = deviceId,
                    interval = interval.toLong(),
                    latency = latency.toLong(),
                    supervisionTimeout = timeout.toLong(),
                    status = status.toLong(),
                )
            ) {}
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        val callbackGatt = gatt ?: return
        val deviceId = callbackGatt.device.address
        completeGattCallback {
            if (cleanUpIfStale(callbackGatt)) return@completeGattCallback
            mtuResultFutureList.removeAll {
                if (it.gatt === callbackGatt && it.deviceId == deviceId) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        it.result(Result.success(mtu.toLong()))
                    } else {
                        it.result(
                            Result.failure(
                                createFlutterError(
                                    UniversalBleErrorCode.FAILED,
                                    "Failed to change MTU"
                                )
                            )
                        )
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun isPaired(deviceId: String, callback: (Result<Boolean>) -> Unit) {
        val remoteDevice: BluetoothDevice =
            bluetoothManager.adapter.getRemoteDevice(deviceId)
        callback(Result.success(remoteDevice.isBonded()))
    }

    override fun pair(deviceId: String, callback: (Result<Boolean>) -> Unit) {
        try {
            val remoteDevice = bluetoothManager.adapter.getRemoteDevice(deviceId)
            val pendingFuture = pairResultFutures.remove(deviceId)

            // If already paired, return and complete pending futures
            if (remoteDevice.isBonded()) {
                pendingFuture?.let { it(Result.success(true)) }
                callback(Result.success(true))
                return
            }

            // throw error if we already have a pending future
            if (pendingFuture != null) {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.OPERATION_IN_PROGRESS,
                            "Pairing already in progress"
                        )
                    )
                )
                return
            }

            // Make a Pair request and complete future from Pair Update intent
            if (remoteDevice.createBond()) {
                pairResultFutures[deviceId] = callback
            } else {
                callback(
                    Result.failure(
                        createFlutterError(
                            UniversalBleErrorCode.PAIRING_FAILED,
                            "Failed to pair"
                        )
                    )
                )
            }
        } catch (e: Exception) {
            callback(
                Result.failure(
                    createFlutterError(UniversalBleErrorCode.FAILED, e.toString())
                )
            )
        }

    }

    override fun unPair(deviceId: String) {
        val remoteDevice: BluetoothDevice =
            bluetoothManager.adapter.getRemoteDevice(deviceId)
        if (remoteDevice.isBonded()) {
            remoteDevice.removeBond()
        }
    }

    override fun getSystemDevices(
        withServices: List<String>,
        callback: (Result<List<UniversalBleScanResult>>) -> Unit,
    ) {
        val devices =
            bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        val complete = { filtered: List<BluetoothDevice> ->
            callback(
                Result.success(
                    filtered.map {
                        UniversalBleScanResult(
                            name = it.name,
                            deviceId = it.address,
                            isPaired = it.isBonded(),
                            manufacturerDataList = null,
                            serviceData = null,
                            rssi = null,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                )
            )
        }
        if (withServices.isEmpty()) {
            complete(devices)
        } else {
            filterDevicesByServices(devices, withServices, complete)
        }
    }

    private fun filterDevicesByServices(
        devices: List<BluetoothDevice>,
        withServices: List<String>,
        callback: (List<BluetoothDevice>) -> Unit,
    ) {
        if (devices.all { cachedServicesMap[it.address] != null }) {
            callback(devices.filter { device ->
                cachedServicesMap[device.address]?.any { uuid -> withServices.contains(uuid) } == true
            })
            return
        }

        val resultMap = mutableMapOf<String, Boolean>()
        val handler = requireNotNull(mainThreadHandler)
        var remaining = devices.size
        var completed = false
        lateinit var timeout: Runnable
        fun complete() {
            if (completed) return
            completed = true
            handler.removeCallbacks(timeout)
            callback(devices.filter { resultMap[it.address] == true })
        }
        timeout = Runnable { complete() }
        handler.postDelayed(timeout, devices.size * 2_000L)

        devices.forEach { device ->
            discoverServicesOffAlreadyConnectedDevice(device) { uuids ->
                if (completed) return@discoverServicesOffAlreadyConnectedDevice
                if (device.address in resultMap) return@discoverServicesOffAlreadyConnectedDevice
                resultMap[device.address] =
                    uuids?.any { uuid -> withServices.contains(uuid) } == true
                remaining--
                if (remaining == 0) complete()
            }
        }
    }

    private fun discoverServicesOffAlreadyConnectedDevice(
        device: BluetoothDevice,
        callback: (List<String>?) -> Unit,
    ) {
        // Check if already cached
        cachedServicesMap[device.address]?.let {
            callback(it)
            return
        }

        // To avoid duplicate callback
        var isUpdated = false
        fun updateCallback(uuids: List<String>?) {
            if (isUpdated) return
            isUpdated = true
            callback(uuids)
        }

        // If its a known gatt, just discover services
        device.address.findGatt()?.let { gatt ->
            gatt.services?.let { services ->
                updateCallback(services.map { service -> service.uuid.toString() })
                return
            }

            if (gatt.discoverServices()) {
                discoverServicesFutureList.add(
                    DiscoverServicesFuture(
                        gatt,
                        device.address,
                        false
                    ) { uuids: Result<List<UniversalBleService>> ->
                        if (uuids.isSuccess) {
                            updateCallback(uuids.getOrNull()?.map { it.uuid })
                        } else {
                            updateCallback(null)
                        }
                    }
                )
                return
            }
        }

        var temporaryGatt: BluetoothGatt? = null
        var timeout: Runnable? = null
        fun finish(uuids: List<String>?) {
            if (isUpdated) return
            isUpdated = true
            timeout?.let { mainThreadHandler?.removeCallbacks(it) }
            temporaryGatt?.let { gatt ->
                temporaryDiscoveryCleanups.remove(gatt)
                try {
                    gatt.disconnect()
                } catch (e: Exception) {
                    UniversalBleLogger.logError("Failed to disconnect temporary gatt for ${device.address}: $e")
                }
                try {
                    gatt.close()
                } catch (e: Exception) {
                    UniversalBleLogger.logError("Failed to close temporary gatt for ${device.address}: $e")
                }
            }
            callback(uuids)
        }

        val callbackHandler = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                completeGattCallback {
                    if (gatt !== temporaryGatt) return@completeGattCallback
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                        if (gatt?.discoverServices() == true) return@completeGattCallback
                        finish(null)
                    } else {
                        finish(null)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                completeGattCallback {
                    if (gatt !== temporaryGatt) return@completeGattCallback
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val uuids = gatt?.services?.map { it.uuid.toString() }
                        if (uuids != null) {
                            setCachedServices(device.address, uuids)
                        }
                        finish(uuids)
                    } else {
                        finish(null)
                    }
                }
            }
        }

        temporaryGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(
                context,
                false,
                callbackHandler,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                requireNotNull(mainThreadHandler),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callbackHandler, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callbackHandler)
        }
        temporaryGatt?.let { gatt ->
            temporaryDiscoveryCleanups[gatt] = { finish(null) }
            timeout = Runnable { finish(null) }
            mainThreadHandler?.postDelayed(requireNotNull(timeout), 2000L)
        } ?: finish(null)
    }

    private fun disposeTemporaryDiscoveryGatts() {
        temporaryDiscoveryCleanups.values.toList().forEach { it() }
    }

    private fun cleanUpConnections(deviceId: String) {
        val connectionKey = deviceId.connectionKey()
        val seen = IdentityHashMap<BluetoothGatt, Unit>()
        (
            readResultFutureList.map { it.gatt } +
                writeResultFutureList.map { it.gatt } +
                subscriptionResultFutureList.map { it.gatt } +
                mtuResultFutureList.map { it.gatt } +
                discoverServicesFutureList.map { it.gatt } +
                rssiResultFutureList.map { it.gatt } +
                localNotificationStates.keys
            )
            .filter { it.device.address.connectionKey() == connectionKey }
            .forEach {
                if (seen.put(it, Unit) == null) cleanUpConnection(it)
            }
    }

    private fun cleanUpIfStale(gatt: BluetoothGatt): Boolean {
        if (gatt.isCurrentGatt()) return false
        cleanUpConnection(gatt)
        return true
    }

    private fun cleanUpConnection(gatt: BluetoothGatt) {
        val deviceId = gatt.device.address
        val deviceDisconnectedError: FlutterError = createFlutterError(
            UniversalBleErrorCode.DEVICE_DISCONNECTED,
            "Device Disconnected",
        )
        readResultFutureList.removeAll {
            if (it.gatt === gatt) {
                it.result(Result.failure(deviceDisconnectedError))
                true
            } else {
                false
            }
        }
        val pendingWrites = writeResultFutureList.filter { it.gatt === gatt }
        writeResultFutureList.removeAll(pendingWrites)
        for (future in pendingWrites) {
            try {
                future.result(Result.failure(deviceDisconnectedError))
            } catch (e: Exception) {
                UniversalBleLogger.logError("Write completion delivery failed: $e")
            }
        }
        subscriptionResultFutureList.removeAll {
            if (it.gatt === gatt) {
                it.result(Result.failure(deviceDisconnectedError))
                true
            } else {
                false
            }
        }
        mtuResultFutureList.removeAll {
            if (it.gatt === gatt) {
                it.result(Result.failure(deviceDisconnectedError))
                true
            } else {
                false
            }
        }
        discoverServicesFutureList.removeAll {
            if (it.gatt === gatt) {
                it.result(Result.failure(deviceDisconnectedError))
                true
            } else {
                false
            }
        }
        rssiResultFutureList.removeAll {
            if (it.gatt === gatt) {
                it.result(Result.failure(deviceDisconnectedError))
                true
            } else {
                false
            }
        }
        localNotificationStates.remove(gatt)
    }

    private fun cleanConnection(gatt: BluetoothGatt) {
        val deviceId = gatt.device.address
        if (!gatt.isCurrentGatt()) {
            cleanUpConnection(gatt)
            gatt.disconnect()
            closeGatt(gatt)
            return
        }
        gatt.disconnect()
        cleanUpConnection(gatt)
        // A connect attempt that never reached STATE_CONNECTED may get no
        // onConnectionStateChange callback after disconnect(); close and
        // report here or the GATT client leaks (Android caps them at 32,
        // and exhaustion surfaces as GATT 133 elsewhere).
        val state = bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT)
        if (state != BluetoothProfile.STATE_CONNECTED) {
            connectTimestamps.remove(deviceId.connectionKey())
            gatt.removeCacheIfCurrent()
            closeGatt(gatt)
            notifyDisconnected(deviceId, null)
        }
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        ownedGatts.remove(gatt)
        gatt.close()
    }

    private fun notifyDisconnected(deviceId: String, error: String?) {
        mainThreadHandler?.post {
            disconnectTimestamps[deviceId.connectionKey()] = SystemClock.elapsedRealtime()
            callbackChannel?.onConnectionChanged(deviceId, false, error) {}
        }
    }

    private fun onBondStateUpdate(deviceId: String, bonded: Boolean, error: String? = null) {
        val future = pairResultFutures.remove(deviceId)
        future?.let { it(Result.success(bonded)) }
        mainThreadHandler?.post {
            callbackChannel?.onPairStateChange(deviceId, bonded, error) {}
        }
    }

    // With the adapter off, every connection and pending operation is dead.
    // Android does not reliably deliver per-device callbacks when the adapter
    // goes down, so fail them here and close the GATT clients — leaked
    // clients (capped at 32 system-wide) later surface as GATT 133.
    private fun cleanUpOnAdapterOff() {
        cleanUpCentralState("ADAPTER_OFF", ownedGatts.keys.toList())
    }

    private fun cleanUpCentralState(
        notificationError: String?,
        gatts: List<BluetoothGatt>,
    ) {
        disposeTemporaryDiscoveryGatts()
        val pendingDeviceIds = pendingConnects.keys.toList()
        pendingConnects.values.forEach { mainThreadHandler?.removeCallbacks(it) }
        pendingConnects.clear()
        for (gatt in gatts) {
            val deviceId = gatt.device.address
            cleanUpConnection(gatt)
            connectTimestamps.remove(deviceId.connectionKey())
            gatt.removeCacheIfCurrent()
            try {
                closeGatt(gatt)
            } catch (e: Exception) {
                UniversalBleLogger.logError("Failed to close gatt for $deviceId: $e")
            }
        }
        if (notificationError != null) {
            (pendingDeviceIds + gatts.map { it.device.address })
                .distinctBy { it.connectionKey() }
                .forEach { notifyDisconnected(it, notificationError) }
        }
        autoConnectDevices.clear()
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                if (state == BluetoothAdapter.STATE_OFF) {
                    cleanUpOnAdapterOff()
                }
                mainThreadHandler?.post {
                    callbackChannel?.onAvailabilityChanged(
                        bluetoothManager.adapter?.state?.toAvailabilityState()
                            ?: AvailabilityState.UNKNOWN
                    ) {}
                }
            } else if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val bondStateChange = intent.getBondStateChange()
                if (bondStateChange == null) {
                    UniversalBleLogger.logError("No device found in ACTION_BOND_STATE_CHANGED intent")
                    return
                }
                // get pairing failed error
                when (bondStateChange.state) {
                    BluetoothDevice.BOND_BONDING -> {
                        UniversalBleLogger.logVerbose("${bondStateChange.device.address} BOND_BONDING")
                    }

                    BluetoothDevice.BOND_BONDED -> {
                        onBondStateUpdate(bondStateChange.device.address, true)
                    }

                    BluetoothDevice.ERROR -> {
                        onBondStateUpdate(bondStateChange.device.address, false, "Failed to Pair")
                    }

                    BluetoothDevice.BOND_NONE -> {
                        UniversalBleLogger.logError("${bondStateChange.device.address} BOND_NONE")
                        onBondStateUpdate(bondStateChange.device.address, false)
                    }
                }
            }
        }
    }


    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            val message = errorCode.parseScanErrorMessage()
            UniversalBleLogger.logError("OnScanFailed: $message")
            mainThreadHandler?.post {
                callbackChannel?.onScanFailed(errorCode.toLong(), message) {}
            }
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {

            // UniversalBleLogger.logVerbose("onScanResult: $result")
            var serviceUuids: Array<UUID> = arrayOf()
            result.device.uuids?.forEach {
                serviceUuids += it.uuid
            }
            result.scanRecord?.serviceUuids?.forEach {
                if (!serviceUuids.contains(it.uuid)) {
                    serviceUuids += it.uuid
                }
            }

            val name = result.resolvedDeviceName
            val manufacturerDataList = result.manufacturerDataList
            val serviceData = result.serviceData

            if (!universalBleFilterUtil.filterDevice(
                    name,
                    manufacturerDataList,
                    serviceUuids
                )
            ) return


            mainThreadHandler?.post {
                callbackChannel?.onScanResult(
                    UniversalBleScanResult(
                        name = name,
                        deviceId = result.device.address,
                        isPaired = result.device.isBonded(),
                        manufacturerDataList = manufacturerDataList,
                        serviceData = serviceData,
                        rssi = result.rssi.toLong(),
                        services = serviceUuids.map { it.toString() }.toList(),
                        timestamp = System.currentTimeMillis()
                    )
                ) {}
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            UniversalBleLogger.logVerbose("onBatchScanResults: $results")
        }
    }


    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        completeGattCallback {
            UniversalBleLogger.logDebug(
                "onConnectionStateChange-> Status: $status ${status.parseHciErrorCode()}, NewState: $newState"
            )

            if (!gatt.isCurrentGatt()) {
                cleanUpConnection(gatt)
                gatt.disconnect()
                closeGatt(gatt)
                return@completeGattCallback
            }

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                val connectionKey = gatt.device.address.connectionKey()
                pendingConnects.remove(connectionKey)?.let { mainThreadHandler?.removeCallbacks(it) }
                disconnectTimestamps.remove(connectionKey)
                callbackChannel?.onConnectionChanged(
                    gatt.device.address, true, status.parseHciErrorCode()
                ) {}
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                val deviceId = gatt.device.address
                val shouldAutoConnect = autoConnectDevices.contains(deviceId.connectionKey())

            // Always clean up internal state (futures, etc.)
                connectTimestamps.remove(deviceId.connectionKey())
                cleanUpConnection(gatt)

            // Send connection changed callback
                notifyDisconnected(deviceId, status.parseHciErrorCode())

            // NOTE: no native GATT-133 retry here (removed 2026-07-14).
            // The status is surfaced to Dart via onConnectionChanged
            // (parseHciErrorCode → "gattError"); retry policy is the
            // caller's. A native retry raced app-level reconnects with
            // competing connectGatt clients — itself a 133 cause.
                if (!shouldAutoConnect) {
                // Only close GATT resources when autoConnect is disabled
                    gatt.removeCacheIfCurrent()
                    gatt.disconnect()
                    UniversalBleLogger.logDebug("Closing gatt for ${gatt.device.name}")
                    closeGatt(gatt)
                }
            // When autoConnect is enabled, keep GATT open for Android to reconnect
            }
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        completeGattCallback {
            if (!gatt.isCurrentGatt()) {
                UniversalBleLogger.logWarning(
                    "Ignoring notification from stale GATT client for ${gatt.device.address}"
                )
                return@completeGattCallback
            }
            UniversalBleLogger.logVerbose(
                "NOTIFY <- ${gatt.device.address} ${characteristic.uuid} len=${value.size}"
            )
            callbackChannel?.onValueChanged(
                deviceIdArg = gatt.device.address,
                characteristicIdArg = characteristic.uuid.toString(),
                valueArg = value,
                timestampArg = System.currentTimeMillis()
            ) {}
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor?,
        status: Int,
    ) {
        completeGattCallback {
            val callbackGatt = gatt ?: return@completeGattCallback
            if (cleanUpIfStale(callbackGatt)) return@completeGattCallback
            val callbackDescriptor = descriptor ?: return@completeGattCallback
            if (callbackDescriptor.uuid.toString() == ccdCharacteristic.toString()) {
                updateSubscriptionState(callbackGatt, callbackDescriptor.characteristic, status)
            }
        }
    }

    private fun updateSubscriptionState(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        subscriptionResultFutureList.removeAll {
            if (it.gatt === gatt &&
                it.characteristicId == characteristic.uuid.toString() &&
                it.serviceId == characteristic.service.uuid.toString()
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    restoreLocalNotificationState(it, characteristic)
                    it.result(
                        Result.failure(
                            createFlutterError(
                                gattStatusToUniversalBleErrorCode(status),
                                "Failed to update subscription state",
                                status.toString()
                            )
                        )
                    )
                } else {
                    it.result(Result.success(Unit))
                }
                true
            } else {
                false
            }
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        return bluetoothManager.isBluetoothEnabled()
    }

    private fun setCachedServices(deviceId: String, services: List<String>) {
        val cachedServicesSharedPref = context.getSharedPreferences(
            "com.navideck.universal_ble.services",
            Context.MODE_PRIVATE
        )
        cachedServicesSharedPref.edit { putStringSet(deviceId, services.toSet()) }
        cachedServicesMap[deviceId] = services
    }

    private fun getCachedServicesMap(): Map<String, List<String>> {
        val cachedServicesSharedPref = context.getSharedPreferences(
            "com.navideck.universal_ble.services",
            Context.MODE_PRIVATE
        )
        return cachedServicesSharedPref.all.mapValues { (_, value) ->
            (value as? Set<*>)?.map { it.toString() } ?: emptyList()
        }
    }

    /// Depreciated Members, ( Requires to support older android devices )
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        onCharacteristicRead(gatt, characteristic, characteristic.value, status)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        onCharacteristicChanged(gatt, characteristic, characteristic.value)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == bluetoothEnableRequestCode) {
            val future = bluetoothEnableRequestFuture ?: return false
            future(Result.success(resultCode == Activity.RESULT_OK))
            bluetoothEnableRequestFuture = null
            return true
        } else if (requestCode == bluetoothDisableRequestCode) {
            val future = bluetoothDisableRequestFuture ?: return false
            future(Result.success(resultCode == Activity.RESULT_OK))
            bluetoothDisableRequestFuture = null
            return true
        }
        return false
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        peripheralPlugin.attachActivity(binding.activity)
        binding.addActivityResultListener(this)
        binding.addRequestPermissionsResultListener(this)
        permissionHandler?.attachActivity(binding.activity)
    }

    override fun onDetachedFromActivity() {
        activity = null
        peripheralPlugin.attachActivity(null)
        permissionHandler?.attachActivity(null)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // Activity will be reattached, keep the reference
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        peripheralPlugin.attachActivity(binding.activity)
        permissionHandler?.attachActivity(binding.activity)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        return permissionHandler?.handlePermissionResult(requestCode, permissions, grantResults)
            ?: false
    }
}
