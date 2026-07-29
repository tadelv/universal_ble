package com.navideck.universal_ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

internal class UniversalBlePluginTest {
    @Test
    fun missingScannerFailsStartScan() {
        val plugin = scanPlugin(null)
        val settings = mock(ScanSettings::class.java)

        val error = mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            mockConstruction(ScanSettings.Builder::class.java) { builder, _ ->
                `when`(builder.build()).thenReturn(settings)
            }.use {
                assertFailsWith<FlutterError> { plugin.startScan(null, null) }
            }
        }

        assertEquals(UniversalBleErrorCode.SCAN_FAILED.raw.toString(), error.code)
        assertEquals(ScanCallback.SCAN_FAILED_INTERNAL_ERROR.toString(), error.details)
    }

    @Test
    fun synchronousScannerFailureFailsStartScan() {
        val scanner = mock(BluetoothLeScanner::class.java)
        val plugin = scanPlugin(scanner)
        doThrow(IllegalStateException("failed")).`when`(scanner).startScan(
            anyList(),
            any(ScanSettings::class.java),
            any(ScanCallback::class.java),
        )
        val settings = mock(ScanSettings::class.java)

        val error = mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            mockConstruction(ScanSettings.Builder::class.java) { builder, _ ->
                `when`(builder.build()).thenReturn(settings)
            }.use {
                mockStatic(Log::class.java).use {
                    assertFailsWith<FlutterError> { plugin.startScan(null, null) }
                }
            }
        }

        assertEquals(UniversalBleErrorCode.SCAN_FAILED.raw.toString(), error.code)
        assertEquals(ScanCallback.SCAN_FAILED_INTERNAL_ERROR.toString(), error.details)
    }

    @Test
    fun connectedCallbackCancelsPendingReconnect() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val gatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val pendingConnect = mock(Runnable::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val pendingConnects = plugin.field<MutableMap<String, Runnable>>("pendingConnects")
        val disconnectTimestamps = plugin.field<MutableMap<String, Long>>("disconnectTimestamps")

        plugin.setField("mainThreadHandler", handler)
        pendingConnects[deviceId.connectionKey()] = pendingConnect
        disconnectTimestamps[deviceId.connectionKey()] = 1L
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        gatt.saveCacheIfNeeded()

        try {
            plugin.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_CONNECTED)

            verify(handler).removeCallbacks(pendingConnect)
            assertFalse(pendingConnects.containsKey(deviceId.connectionKey()))
            assertFalse(disconnectTimestamps.containsKey(deviceId.connectionKey()))
        } finally {
            gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun explicitDisconnectCancelsPendingReconnectCaseInsensitively() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val pendingConnect = mock(Runnable::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val pendingConnects = plugin.field<MutableMap<String, Runnable>>("pendingConnects")

        plugin.setField("mainThreadHandler", handler)
        pendingConnects[deviceId.connectionKey()] = pendingConnect

        plugin.disconnect(deviceId.lowercase())

        verify(handler).removeCallbacks(pendingConnect)
        assertFalse(pendingConnects.containsKey(deviceId.connectionKey()))
    }

    @Test
    fun adapterOffReportsPendingKnownDeviceOnce() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val gatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val pendingConnect = mock(Runnable::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val pendingConnects = plugin.field<MutableMap<String, Runnable>>("pendingConnects")

        plugin.setField("mainThreadHandler", handler)
        pendingConnects[deviceId.connectionKey()] = pendingConnect
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        gatt.saveCacheIfNeeded()

        try {
            plugin.invoke("cleanUpOnAdapterOff")
        } finally {
            gatt.removeCacheIfCurrent()
        }

        verify(handler).removeCallbacks(pendingConnect)
        verify(handler, times(1)).post(any(Runnable::class.java))
    }

    @Test
    fun mixedCaseDisconnectKeepsConnectDisconnectDelay() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        val device = mock(BluetoothDevice::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val context = mock(Context::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val connectTimestamps = plugin.field<MutableMap<String, Long>>("connectTimestamps")

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.setField("context", context)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.getRemoteDevice(deviceId)).thenReturn(device)
        `when`(device.connectGatt(context, false, plugin)).thenReturn(gatt)
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }
                .thenReturn(1_000L, 1_000L, 1_500L)
            plugin.connect(deviceId, false, null)
            plugin.disconnect(deviceId.lowercase())
        }

        try {
            assertEquals(1_000L, connectTimestamps[deviceId.connectionKey()])
            verify(handler).postDelayed(any(Runnable::class.java), eq(1_500L))
            verify(gatt, never()).disconnect()
        } finally {
            gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun nativeDisconnectRemovesMixedCaseConnectTimestamp() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        val device = mock(BluetoothDevice::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val context = mock(Context::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val connectTimestamps = plugin.field<MutableMap<String, Long>>("connectTimestamps")

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.setField("context", context)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.getRemoteDevice(deviceId.lowercase())).thenReturn(device)
        `when`(device.connectGatt(context, false, plugin)).thenReturn(gatt)
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            plugin.connect(deviceId.lowercase(), false, null)
            plugin.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_DISCONNECTED)
        }

        assertFalse(connectTimestamps.containsKey(deviceId.connectionKey()))
    }

    @Test
    fun legacyGattCallbackDefersStateChangesToMainHandler() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val gatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val connectTimestamps = plugin.field<MutableMap<String, Long>>("connectTimestamps")
        val autoConnectDevices = plugin.field<MutableSet<String>>("autoConnectDevices")

        plugin.setField("mainThreadHandler", handler)
        connectTimestamps[deviceId.connectionKey()] = 1_000L
        autoConnectDevices.add(deviceId.connectionKey())
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)

        plugin.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_DISCONNECTED)

        assertEquals(1_000L, connectTimestamps[deviceId.connectionKey()])
        verify(handler).post(any(Runnable::class.java))
    }

    @Test
    fun filteredSystemDeviceDiscoveryReturnsBeforeGattCallback() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        val context = mock(Context::class.java)
        val device = mock(BluetoothDevice::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        var result: Result<List<UniversalBleScanResult>>? = null

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.setField("context", context)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(manager.getConnectedDevices(BluetoothProfile.GATT)).thenReturn(listOf(device))
        `when`(device.address).thenReturn("11:22:33:44:55:66")
        `when`(device.connectGatt(eq(context), eq(false), any(BluetoothGattCallback::class.java)))
            .thenReturn(gatt)

        plugin.getSystemDevices(listOf("0000180f-0000-1000-8000-00805f9b34fb")) {
            result = it
        }

        assertNull(result)
        verify(handler).postDelayed(any(Runnable::class.java), eq(2_000L))
    }

    private fun handler(runPostedTasks: Boolean = false): Handler {
        val handler = mock(Handler::class.java)
        `when`(handler.post(any(Runnable::class.java))).thenAnswer {
            if (runPostedTasks) (it.arguments[0] as Runnable).run()
            true
        }
        return handler
    }

    private fun scanPlugin(scanner: BluetoothLeScanner?): UniversalBlePlugin {
        val plugin = UniversalBlePlugin()
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.isEnabled).thenReturn(true)
        `when`(adapter.bluetoothLeScanner).thenReturn(scanner)
        plugin.setField("bluetoothManager", manager)
        plugin.setField("safeScanner", SafeScanner(manager, handler()))
        return plugin
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> UniversalBlePlugin.field(name: String): T {
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    private fun UniversalBlePlugin.setField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun UniversalBlePlugin.invoke(name: String) {
        javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)
    }
}
