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
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
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
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val pendingConnect = mock(Runnable::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val pendingConnects = plugin.field<MutableMap<String, Runnable>>("pendingConnects")
        val disconnectTimestamps = plugin.field<MutableMap<String, Long>>("disconnectTimestamps")

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("callbackChannel", callbackChannel)
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
            val events = mockingDetails(callbackChannel).invocations
                .filter { it.method.name == "onConnectionChanged" }
                .map { Triple(it.arguments[0], it.arguments[1], it.arguments[2]) }
            assertEquals(listOf(Triple(deviceId, true, null)), events)
        } finally {
            gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun failedConnectedCallbackReportsErrorAndClosesOnlyItsGatt() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val failedGatt = mock(BluetoothGatt::class.java)
        val failedDevice = mock(BluetoothDevice::class.java)
        val otherGatt = mock(BluetoothGatt::class.java)
        val otherDevice = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val otherDeviceId = "11:22:33:44:55:66"
        val ownedGatts = plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("callbackChannel", callbackChannel)
        `when`(failedGatt.device).thenReturn(failedDevice)
        `when`(failedDevice.address).thenReturn(deviceId)
        `when`(otherGatt.device).thenReturn(otherDevice)
        `when`(otherDevice.address).thenReturn(otherDeviceId)
        failedGatt.saveCacheIfNeeded()
        otherGatt.saveCacheIfNeeded()
        ownedGatts[failedGatt] = Unit
        ownedGatts[otherGatt] = Unit

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
                plugin.onConnectionStateChange(failedGatt, 133, BluetoothGatt.STATE_CONNECTED)
            }

            val events = mockingDetails(callbackChannel).invocations
                .filter { it.method.name == "onConnectionChanged" }
                .map { Triple(it.arguments[0], it.arguments[1], it.arguments[2]) }
            assertEquals(listOf(Triple(deviceId, false, "Unknown Error 133")), events)
            verify(failedGatt).disconnect()
            verify(failedGatt).close()
            assertNull(deviceId.findGatt())
            assertFalse(ownedGatts.containsKey(failedGatt))
            assertSame(otherGatt, otherDeviceId.findGatt())
            assertTrue(ownedGatts.containsKey(otherGatt))
            verify(otherGatt, never()).disconnect()
            verify(otherGatt, never()).close()
        } finally {
            failedGatt.removeCacheIfCurrent()
            otherGatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun failedConnectedNotificationDoesNotAffectReplacementInstalledBeforeDelivery() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val failedGatt = mock(BluetoothGatt::class.java)
        val replacementGatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val disconnectTimestamps = plugin.field<MutableMap<String, Long>>("disconnectTimestamps")
        val ownedGatts = plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")
        val postedTasks = mutableListOf<Runnable>()

        doAnswer {
            postedTasks.add(it.arguments[0] as Runnable)
            true
        }.`when`(handler).post(any(Runnable::class.java))
        plugin.setField("mainThreadHandler", handler)
        plugin.setField("callbackChannel", callbackChannel)
        `when`(failedGatt.device).thenReturn(device)
        `when`(replacementGatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        failedGatt.saveCacheIfNeeded()
        ownedGatts[failedGatt] = Unit

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
                plugin.onConnectionStateChange(failedGatt, 133, BluetoothGatt.STATE_CONNECTED)
                postedTasks.removeAt(0).run()
                replacementGatt.saveCacheIfNeeded()
                postedTasks.removeAt(0).run()
            }

            assertSame(replacementGatt, deviceId.findGatt())
            assertFalse(disconnectTimestamps.containsKey(deviceId.connectionKey()))
            verifyNoMoreInteractions(callbackChannel)
        } finally {
            replacementGatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun cleanConnectionNotificationDoesNotAffectReplacementInstalledBeforeDelivery() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val manager = mock(BluetoothManager::class.java)
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val staleGatt = mock(BluetoothGatt::class.java)
        val replacementGatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val disconnectTimestamps = plugin.field<MutableMap<String, Long>>("disconnectTimestamps")
        val postedTasks = mutableListOf<Runnable>()

        doAnswer {
            postedTasks.add(it.arguments[0] as Runnable)
            true
        }.`when`(handler).post(any(Runnable::class.java))
        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.setField("callbackChannel", callbackChannel)
        `when`(staleGatt.device).thenReturn(device)
        `when`(replacementGatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        staleGatt.saveCacheIfNeeded()

        try {
            plugin.javaClass.getDeclaredMethod("cleanConnection", BluetoothGatt::class.java)
                .apply { isAccessible = true }.invoke(plugin, staleGatt)
            replacementGatt.saveCacheIfNeeded()
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
                postedTasks.single().run()
            }

            assertSame(replacementGatt, deviceId.findGatt())
            assertFalse(disconnectTimestamps.containsKey(deviceId.connectionKey()))
            verifyNoMoreInteractions(callbackChannel)
        } finally {
            replacementGatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun disconnectedCallbackNotificationDoesNotAffectReplacementInstalledBeforeDelivery() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val staleGatt = mock(BluetoothGatt::class.java)
        val replacementGatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val disconnectTimestamps = plugin.field<MutableMap<String, Long>>("disconnectTimestamps")
        val postedTasks = mutableListOf<Runnable>()

        doAnswer {
            postedTasks.add(it.arguments[0] as Runnable)
            true
        }.`when`(handler).post(any(Runnable::class.java))
        plugin.setField("mainThreadHandler", handler)
        plugin.setField("callbackChannel", callbackChannel)
        `when`(staleGatt.device).thenReturn(device)
        `when`(replacementGatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        staleGatt.saveCacheIfNeeded()

        try {
            plugin.onConnectionStateChange(staleGatt, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_DISCONNECTED)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) postedTasks.removeAt(0).run()
            assertEquals(1, postedTasks.size)
            replacementGatt.saveCacheIfNeeded()
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
                postedTasks.forEach { it.run() }
            }

            assertSame(replacementGatt, deviceId.findGatt())
            assertFalse(disconnectTimestamps.containsKey(deviceId.connectionKey()))
            verifyNoMoreInteractions(callbackChannel)
        } finally {
            replacementGatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun failedConnectedCallbackRetainsGattWhenCloseThrows() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val failedGatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val ownedGatts = plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("callbackChannel", callbackChannel)
        `when`(failedGatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        doThrow(IllegalStateException("close failed")).`when`(failedGatt).close()
        failedGatt.saveCacheIfNeeded()
        ownedGatts[failedGatt] = Unit

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
                mockStatic(Log::class.java).use {
                    plugin.onConnectionStateChange(failedGatt, 133, BluetoothGatt.STATE_CONNECTED)
                }
            }

            assertSame(failedGatt, deviceId.findGatt())
            assertTrue(ownedGatts.containsKey(failedGatt))
        } finally {
            failedGatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun staleFailedConnectedCallbackDoesNotRetireSameAddressReplacement() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val failedGatt = mock(BluetoothGatt::class.java)
        val replacementGatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("callbackChannel", callbackChannel)
        `when`(failedGatt.device).thenReturn(device)
        `when`(replacementGatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        failedGatt.saveCacheIfNeeded()
        replacementGatt.saveCacheIfNeeded()

        try {
            plugin.onConnectionStateChange(failedGatt, 133, BluetoothGatt.STATE_CONNECTED)

            verifyNoMoreInteractions(callbackChannel)
            verify(failedGatt).disconnect()
            verify(failedGatt).close()
            assertSame(replacementGatt, deviceId.findGatt())
            verify(replacementGatt, never()).disconnect()
            verify(replacementGatt, never()).close()
        } finally {
            replacementGatt.removeCacheIfCurrent()
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
    fun engineDetachClosesOnlyOwnedConnectionsAndUnregistersCentralChannel() {
        val plugin = UniversalBlePlugin()
        val otherPlugin = UniversalBlePlugin()
        val handler = handler()
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        val scanner = mock(BluetoothLeScanner::class.java)
        val context = mock(Context::class.java)
        val peripheral = mock(UniversalBlePeripheralPlugin::class.java)
        val binding = mock(FlutterPlugin.FlutterPluginBinding::class.java)
        val messenger = mock(BinaryMessenger::class.java)
        val ownedGatt = mock(BluetoothGatt::class.java)
        val otherGatt = mock(BluetoothGatt::class.java)
        val ownedDevice = mock(BluetoothDevice::class.java)
        val otherDevice = mock(BluetoothDevice::class.java)
        val ownedDeviceId = "AA:BB:CC:DD:EE:FF"
        val otherDeviceId = "11:22:33:44:55:66"

        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.bluetoothLeScanner).thenReturn(scanner)
        `when`(binding.binaryMessenger).thenReturn(messenger)
        `when`(ownedGatt.device).thenReturn(ownedDevice)
        `when`(otherGatt.device).thenReturn(otherDevice)
        `when`(ownedDevice.address).thenReturn(ownedDeviceId)
        `when`(otherDevice.address).thenReturn(otherDeviceId)
        plugin.setField("mainThreadHandler", handler)
        plugin.setField("safeScanner", SafeScanner(manager, handler))
        plugin.setField("context", context)
        plugin.setField("peripheralPlugin", peripheral)
        plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")[ownedGatt] = Unit
        otherPlugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")[otherGatt] = Unit
        ownedGatt.saveCacheIfNeeded()
        otherGatt.saveCacheIfNeeded()

        try {
            plugin.onDetachedFromEngine(binding)

            verify(ownedGatt).close()
            assertNull(ownedDeviceId.findGatt())
            verify(otherGatt, never()).close()
            assertSame(otherGatt, otherDeviceId.findGatt())
            verify(messenger).setMessageHandler(
                eq("dev.flutter.pigeon.universal_ble.UniversalBlePlatformChannel.disconnect"),
                isNull(),
            )
        } finally {
            ownedGatt.removeCacheIfCurrent()
            otherGatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun failedCloseKeepsExactOwnerUntilLaterCleanupSucceeds() {
        val plugin = UniversalBlePlugin()
        val gatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val owned = plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        doThrow(IllegalStateException("close failed")).doNothing().`when`(gatt).close()
        owned[gatt] = Unit
        gatt.saveCacheIfNeeded()

        try {
            plugin.invoke("cleanUpOnAdapterOff")
            assertSame(gatt, deviceId.findGatt())
            assertTrue(owned.containsKey(gatt))
            assertEquals(1, owned.size)

            plugin.invoke("cleanUpOnAdapterOff")
            assertNull(deviceId.findGatt())
            assertFalse(owned.containsKey(gatt))
            assertEquals(0, owned.size)
            plugin.invoke("cleanUpOnAdapterOff")
            verify(gatt, times(2)).close()
            assertEquals(0, owned.size)
        } finally {
            gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun staleOwnerCleanupNeverRemovesSameAddressReplacement() {
        val plugin = UniversalBlePlugin()
        val stale = mock(BluetoothGatt::class.java)
        val replacement = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val owned = plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")
        `when`(stale.device).thenReturn(device)
        `when`(replacement.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        doThrow(IllegalStateException("close failed")).doNothing().`when`(stale).close()
        owned[stale] = Unit
        stale.saveCacheIfNeeded()
        replacement.saveCacheIfNeeded()

        try {
            plugin.invoke("cleanUpOnAdapterOff")
            assertSame(replacement, deviceId.findGatt())
            assertTrue(owned.containsKey(stale))
            assertEquals(1, owned.size)

            plugin.invoke("cleanUpOnAdapterOff")
            assertSame(replacement, deviceId.findGatt())
            assertFalse(owned.containsKey(stale))
            assertEquals(0, owned.size)
            verify(stale, times(2)).close()
            verify(replacement, never()).close()
        } finally {
            replacement.removeCacheIfCurrent()
        }
    }

    @Test
    fun failedCloseDoesNotReportSuccessfulTeardown() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val messenger = mock(BinaryMessenger::class.java)
        val messages = mutableListOf<List<*>>()
        val gatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        val owned = plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        doThrow(IllegalStateException("close failed")).`when`(gatt).close()
        plugin.setField("mainThreadHandler", handler)
        doAnswer {
            val buffer = it.arguments[1] as ByteBuffer
            buffer.flip()
            messages.add(StandardMessageCodec.INSTANCE.decodeMessage(buffer) as List<*>)
            null
        }.`when`(messenger).send(
            eq("dev.flutter.pigeon.universal_ble.UniversalBleCallbackChannel.onConnectionChanged"),
            any(ByteBuffer::class.java),
            any(BinaryMessenger.BinaryReply::class.java),
        )
        plugin.setField("callbackChannel", UniversalBleCallbackChannel(messenger))
        owned[gatt] = Unit
        gatt.saveCacheIfNeeded()

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
                plugin.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_DISCONNECTED)
            }
            assertSame(gatt, deviceId.findGatt())
            assertTrue(owned.containsKey(gatt))
            assertEquals(1, messages.size)
            assertEquals(deviceId, messages.single()[0])
            assertEquals(false, messages.single()[1])
            assertEquals("GATT_CLOSE_FAILED", messages.single()[2])
        } finally {
            gatt.removeCacheIfCurrent()
        }
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
    fun explicitEstablishedDisconnectClosesGattWhenCallbackIsMissing() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val manager = mock(BluetoothManager::class.java)
        val device = mock(BluetoothDevice::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        var fallback: Runnable? = null

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("callbackChannel", callbackChannel)
        plugin.setField("bluetoothManager", manager)
        plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")[gatt] = Unit
        plugin.field<MutableMap<String, Long>>("connectTimestamps")[deviceId.connectionKey()] = 1L
        `when`(manager.getConnectionState(device, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        `when`(handler.postDelayed(any(Runnable::class.java), any(Long::class.javaPrimitiveType)))
            .thenAnswer {
                fallback = it.arguments[0] as Runnable
                true
            }
        gatt.saveCacheIfNeeded()

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(5_000L)
                plugin.disconnect(deviceId)
            }

            assertTrue(fallback != null, "established disconnect should schedule a bounded fallback")
            verify(handler).postDelayed(eq(fallback!!), eq(2_000L))
            verify(gatt).disconnect()
            verify(gatt, never()).close()
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(7_000L)
                fallback!!.run()
            }
            verify(gatt).close()
            assertNull(deviceId.findGatt())
            assertEquals(listOf(Triple(deviceId, false, "DISCONNECT_CALLBACK_TIMEOUT")),
                mockingDetails(callbackChannel).invocations
                    .filter { it.method.name == "onConnectionChanged" }
                    .map { Triple(it.arguments[0], it.arguments[1], it.arguments[2]) })
        } finally {
            gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun explicitDisconnectFallbackRetainsGattWhenCloseThrowsAndReportsCloseFailure() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        val manager = mock(BluetoothManager::class.java)
        val device = mock(BluetoothDevice::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        var fallback: Runnable? = null
        val ownedGatts = plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("callbackChannel", callbackChannel)
        plugin.setField("bluetoothManager", manager)
        ownedGatts[gatt] = Unit
        `when`(manager.getConnectionState(device, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        `when`(handler.postDelayed(any(Runnable::class.java), any(Long::class.javaPrimitiveType)))
            .thenAnswer { fallback = it.arguments[0] as Runnable; true }
        doThrow(IllegalStateException("close failed")).`when`(gatt).close()
        gatt.saveCacheIfNeeded()

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(5_000L)
                plugin.disconnect(deviceId)
            }
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(7_000L)
                fallback!!.run()
            }

            assertSame(gatt, deviceId.findGatt())
            assertTrue(ownedGatts.containsKey(gatt))
            val events = mockingDetails(callbackChannel).invocations
                .filter { it.method.name == "onConnectionChanged" }
                .map { Triple(it.arguments[0], it.arguments[1], it.arguments[2]) }
            assertEquals(listOf(Triple(deviceId, false, "GATT_CLOSE_FAILED")), events)
            plugin.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_CONNECTED)
            assertEquals(listOf(Triple(deviceId, false, "GATT_CLOSE_FAILED")),
                mockingDetails(callbackChannel).invocations
                    .filter { it.method.name == "onConnectionChanged" }
                    .map { Triple(it.arguments[0], it.arguments[1], it.arguments[2]) })
            assertTrue(ownedGatts.containsKey(gatt))
            verify(gatt).close()
        } finally {
            gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun explicitDisconnectCallbackCancelsFallback() {
        val plugin = UniversalBlePlugin()
        val handler = handler(runPostedTasks = true)
        val manager = mock(BluetoothManager::class.java)
        val device = mock(BluetoothDevice::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        var fallback: Runnable? = null

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")[gatt] = Unit
        plugin.field<MutableMap<String, Long>>("connectTimestamps")[deviceId.connectionKey()] = 1L
        `when`(manager.getConnectionState(device, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        `when`(handler.postDelayed(any(Runnable::class.java), any(Long::class.javaPrimitiveType)))
            .thenAnswer {
                fallback = it.arguments[0] as Runnable
                true
            }
        gatt.saveCacheIfNeeded()

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(5_000L)
                plugin.disconnect(deviceId)
            }
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(6_000L)
                plugin.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_DISCONNECTED)
            }

            verify(handler).removeCallbacks(fallback!!)
            assertFalse(plugin.field<IdentityHashMap<BluetoothGatt, Runnable>>("pendingDisconnectFallbacks")
                .containsKey(gatt))
            fallback!!.run()
            verify(gatt, times(1)).close()
        } finally {
            gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun staleDisconnectFallbackClosesOnlyOriginalAndIgnoresLateConnectedCallback() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val manager = mock(BluetoothManager::class.java)
        val device = mock(BluetoothDevice::class.java)
        val originalGatt = mock(BluetoothGatt::class.java)
        val replacementGatt = mock(BluetoothGatt::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        var fallback: Runnable? = null

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")[originalGatt] = Unit
        plugin.field<MutableMap<String, Long>>("connectTimestamps")[deviceId.connectionKey()] = 1L
        `when`(manager.getConnectionState(device, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)
        `when`(originalGatt.device).thenReturn(device)
        `when`(replacementGatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        `when`(handler.postDelayed(any(Runnable::class.java), any(Long::class.javaPrimitiveType)))
            .thenAnswer {
                fallback = it.arguments[0] as Runnable
                true
            }
        originalGatt.saveCacheIfNeeded()

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(5_000L)
                plugin.disconnect(deviceId)
            }
            val disconnectTimestamps = plugin.field<MutableMap<String, Long>>("disconnectTimestamps")
            disconnectTimestamps[deviceId.connectionKey()] = 99L
            plugin.onConnectionStateChange(originalGatt, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_CONNECTED)
            assertEquals(99L, disconnectTimestamps[deviceId.connectionKey()])
            assertTrue(plugin.field<IdentityHashMap<BluetoothGatt, Runnable>>("pendingDisconnectFallbacks")
                .containsKey(originalGatt))
            replacementGatt.saveCacheIfNeeded()
            plugin.field<MutableMap<String, Long>>("connectTimestamps")[deviceId.connectionKey()] = 8_000L
            fallback!!.run()

            assertEquals(8_000L, plugin.field<MutableMap<String, Long>>("connectTimestamps")[deviceId.connectionKey()])
            verify(originalGatt).close()
            verify(replacementGatt, never()).close()
            assertSame(replacementGatt, deviceId.findGatt())
        } finally {
            replacementGatt.removeCacheIfCurrent()
            originalGatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun replacementBeforeTimeoutNotificationDoesNotReceiveOldDisconnect() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val manager = mock(BluetoothManager::class.java)
        val device = mock(BluetoothDevice::class.java)
        val originalGatt = mock(BluetoothGatt::class.java)
        val replacementGatt = mock(BluetoothGatt::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"
        var fallback: Runnable? = null
        var notification: Runnable? = null

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")[originalGatt] = Unit
        `when`(manager.getConnectionState(device, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)
        `when`(originalGatt.device).thenReturn(device)
        `when`(replacementGatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        `when`(handler.postDelayed(any(Runnable::class.java), any(Long::class.javaPrimitiveType)))
            .thenAnswer { fallback = it.arguments[0] as Runnable; true }
        `when`(handler.post(any(Runnable::class.java))).thenAnswer {
            notification = it.arguments[0] as Runnable
            true
        }
        originalGatt.saveCacheIfNeeded()

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(5_000L)
                plugin.disconnect(deviceId)
            }
            fallback!!.run()
            assertTrue(notification != null)
            replacementGatt.saveCacheIfNeeded()
            plugin.field<MutableMap<String, Long>>("connectTimestamps")[deviceId.connectionKey()] = 8_000L
            notification!!.run()

            assertEquals(8_000L, plugin.field<MutableMap<String, Long>>("connectTimestamps")[deviceId.connectionKey()])
            assertFalse(plugin.field<MutableMap<String, Long>>("disconnectTimestamps")
                .containsKey(deviceId.connectionKey()))
            assertSame(replacementGatt, deviceId.findGatt())
            verify(replacementGatt, never()).close()
        } finally {
            replacementGatt.removeCacheIfCurrent()
            originalGatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun failedFallbackSchedulingClosesOriginalImmediately() {
        val plugin = UniversalBlePlugin()
        val handler = handler()
        val manager = mock(BluetoothManager::class.java)
        val device = mock(BluetoothDevice::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val deviceId = "AA:BB:CC:DD:EE:FF"

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.field<IdentityHashMap<BluetoothGatt, Unit>>("ownedGatts")[gatt] = Unit
        `when`(manager.getConnectionState(device, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        gatt.saveCacheIfNeeded()

        try {
            mockStatic(SystemClock::class.java).use { clock ->
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(5_000L)
                plugin.disconnect(deviceId)
            }
            verify(gatt).close()
            assertNull(deviceId.findGatt())
            assertTrue(plugin.field<IdentityHashMap<BluetoothGatt, Runnable>>("pendingDisconnectFallbacks")
                .isEmpty())
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
        verify(handler, times(2)).postDelayed(any(Runnable::class.java), eq(2_000L))
    }

    @Test
    fun filteredSystemDeviceDiscoveryClosesOnlyTemporaryGatt() {
        val plugin = UniversalBlePlugin()
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        val handler = handler(runPostedTasks = true)
        val context = mock(Context::class.java)
        val device = mock(BluetoothDevice::class.java)
        val temporaryGatt = mock(BluetoothGatt::class.java)
        val currentGatt = mock(BluetoothGatt::class.java)
        val currentDevice = mock(BluetoothDevice::class.java)
        val deviceId = "11:22:33:44:55:66"
        var gattCallback: BluetoothGattCallback? = null
        var result: Result<List<UniversalBleScanResult>>? = null

        plugin.setField("mainThreadHandler", handler)
        plugin.setField("bluetoothManager", manager)
        plugin.setField("context", context)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(manager.getConnectedDevices(BluetoothProfile.GATT)).thenReturn(listOf(device))
        `when`(device.address).thenReturn(deviceId)
        `when`(currentGatt.device).thenReturn(currentDevice)
        `when`(currentDevice.address).thenReturn(deviceId)
        doAnswer {
            gattCallback = it.arguments[2] as BluetoothGattCallback
            temporaryGatt
        }.`when`(device).connectGatt(eq(context), eq(false), any(BluetoothGattCallback::class.java))

        plugin.getSystemDevices(listOf("0000180f-0000-1000-8000-00805f9b34fb")) { result = it }
        currentGatt.saveCacheIfNeeded()

        try {
            gattCallback!!.onServicesDiscovered(temporaryGatt, BluetoothGatt.GATT_FAILURE)

            assertTrue(result!!.isSuccess)
            assertSame(currentGatt, device.address.findGatt())
            verify(temporaryGatt).disconnect()
            verify(temporaryGatt).close()
            verify(currentGatt, never()).disconnect()
            verify(currentGatt, never()).close()
        } finally {
            currentGatt.removeCacheIfCurrent()
        }
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
