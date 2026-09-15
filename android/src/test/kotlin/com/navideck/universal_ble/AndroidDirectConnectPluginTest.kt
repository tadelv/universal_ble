package com.navideck.universal_ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

internal class AndroidDirectConnectPluginTest {
    private val machine = "AA:BB:CC:DD:EE:01"
    private val scale = "AA:BB:CC:DD:EE:02"

    @Test
    fun dualConnectIsSerializedWithoutClosingHealthyPeer() = withFixture { f ->
        f.connect(machine)
        f.connect(scale)
        f.pump()
        assertEquals(listOf(machine), f.created)
        f.connected(machine)
        f.pump()
        assertEquals(listOf(machine, scale), f.created)
        verify(f.gatts.getValue(machine), never()).disconnect()
        verify(f.gatts.getValue(machine), never()).close()
    }

    @Test
    fun native133ClosesFirstGattBeforeStartingPeer() = withFixture { f ->
        f.connect(scale)
        f.connect(machine)
        f.pump()
        f.disconnected(scale, 133)
        f.pump()
        assertEquals(listOf("create:$scale", "close:$scale", "create:$machine"), f.events)
    }

    @Test
    fun connectedCallbackWithErrorStatusIsRejectedAndClosedBeforePeerStarts() = withFixture { f ->
        f.connect(scale)
        f.connect(machine)
        f.pump()
        val failedGatt = f.gatts.getValue(scale)

        f.connectionChanged(scale, 133, BluetoothProfile.STATE_CONNECTED)
        f.pump()

        assertEquals(listOf("create:$scale", "close:$scale", "create:$machine"), f.events)
        assertFalse(f.ownedGatts().containsKey(failedGatt))
        assertFalse(failedGatt.isCurrentGatt())
    }

    @Test
    fun queuedDisconnectNeverCreatesGatt() = withFixture { f ->
        f.connect(machine)
        f.connect(scale)
        f.pump()
        f.plugin.disconnect(scale.lowercase())
        f.connected(machine)
        f.pump()
        assertEquals(listOf(machine), f.created)
    }

    @Test
    fun activeCancellationWaitsForCloseEvenWithLateConnectedCallback() = withFixture { f ->
        f.connect(scale)
        f.connect(machine)
        f.pump()
        f.plugin.disconnect(scale)
        f.connected(scale)
        f.pump()
        assertEquals(listOf(scale), f.created)
        verify(f.gatts.getValue(scale), never()).close()
        assertFailsWith<FlutterError> { f.connect(scale) }
        f.advance(2_000)
        assertEquals(listOf("create:$scale", "close:$scale", "create:$machine"), f.events)
    }

    @Test
    fun closeFailureRetainsNativeOwnershipAndFailsQueuedPeerUntilRetrySucceeds() = withFixture { f ->
        f.closeFailuresRemaining[scale] = 1
        f.connect(scale)
        f.connect(machine)
        f.pump()
        val scaleGatt = f.gatts.getValue(scale)

        f.plugin.disconnect(scale)
        f.advance(2_000)

        assertEquals(listOf(scale), f.created)
        assertTrue(f.ownedGatts().containsKey(scaleGatt))
        assertSame(scaleGatt, scale.findGatt())
        assertFailsWith<FlutterError> { f.connect(machine) }

        f.advance(250)

        assertFalse(f.ownedGatts().containsKey(scaleGatt))
        assertFalse(scaleGatt.isCurrentGatt())
        f.connect(machine)
        f.pump()
        assertEquals(
            listOf(scale),
            f.created,
            "Recovery-blocked waiter keeps the existing reconnect cooldown semantics",
        )
        f.advance(1_750)
        assertEquals(listOf(scale, machine), f.created)
    }

    @Test
    fun exhaustedCloseRetriesStayRecoveryBlockedUntilLaterExactOwnerCleanup() = withFixture { f ->
        f.closeFailuresRemaining[scale] = 10
        f.connect(scale)
        f.pump()
        val scaleGatt = f.gatts.getValue(scale)

        f.plugin.disconnect(scale)
        f.advance(2_000)
        f.advance(250)
        f.advance(250)

        assertTrue(f.ownedGatts().containsKey(scaleGatt))
        assertSame(scaleGatt, scale.findGatt())
        assertFailsWith<FlutterError> { f.connect(machine) }

        f.closeFailuresRemaining[scale] = 0
        f.disconnected(scale, 0)
        f.pump()

        assertFalse(f.ownedGatts().containsKey(scaleGatt))
        assertFalse(scaleGatt.isCurrentGatt())
        f.connect(machine)
        f.pump()
        assertEquals(listOf(scale, machine), f.created)
    }

    @Test
    fun adapterOffCancelsQueuedAndAlreadyPostedAdmission() = withFixture { f ->
        f.connect(scale)
        f.connect(machine)
        f.plugin.invokePrivate("cleanUpOnAdapterOff")
        f.pump()
        assertTrue(f.created.isEmpty())
        f.connect(machine)
        f.pump()
        assertTrue(f.created.isEmpty(), "Existing reconnect cooldown still applies")
        f.advance(2_000)
        assertEquals(listOf(machine), f.created)
    }

    @Test
    fun connectGattFailureDoesNotBlockNextDevice() = withFixture { f ->
        f.failingStarts.add(scale)
        f.connect(scale)
        f.connect(machine)
        f.pump()
        assertEquals(listOf(machine), f.created)
    }

    @Test
    fun staleCallbackCannotReleaseCurrentDeviceAdmission() = withFixture { f ->
        f.connect(scale)
        f.pump()
        val oldGatt = f.gatts.getValue(scale)
        f.disconnected(scale, 133)
        f.pump()
        f.advance(2_000)
        f.connect(scale)
        f.connect(machine)
        f.pump()
        f.plugin.onConnectionStateChange(oldGatt, 0, BluetoothProfile.STATE_DISCONNECTED)
        f.pump()
        assertEquals(listOf(scale, scale), f.created)
        f.connected(scale)
        f.pump()
        assertEquals(listOf(scale, scale, machine), f.created)
    }

    @Test
    fun reconnectCooldownIsCancelledWithoutLateGattCreation() = withFixture { f ->
        f.plugin.field<MutableMap<String, Long>>("disconnectTimestamps")[scale] = f.now
        f.connect(scale)
        f.connect(machine)
        f.pump()
        assertTrue(f.created.isEmpty())
        f.plugin.disconnect(scale)
        f.pump()
        assertEquals(listOf(machine), f.created)
        f.advance(2_000)
        assertEquals(listOf(machine), f.created)
    }

    @Test
    fun repeatedSuccessfulRecoveryDoesNotAccumulateOwnedGatts() = withFixture { f ->
        repeat(25) { cycle ->
            val id = if (cycle % 2 == 0) scale else machine
            f.connect(id)
            f.pump()
            f.disconnected(id, 133)
            f.pump()
            assertTrue(f.ownedGatts().isEmpty(), "owned GATT leaked after cycle $cycle")
            f.advance(2_000)
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { f.now }
            try {
                test(f)
                assertFalse(f.overlapDetected, "Native direct connects overlapped")
            } finally {
                f.plugin.invokePrivate("cleanUpOnAdapterOff")
                f.pump()
            }
        }
    }

    private class Fixture {
        val plugin = UniversalBlePlugin()
        val created = mutableListOf<String>()
        val events = mutableListOf<String>()
        val gatts = mutableMapOf<String, BluetoothGatt>()
        val failingStarts = mutableSetOf<String>()
        val closeFailuresRemaining = mutableMapOf<String, Int>()
        var now = 10_000L
        var overlapDetected = false
        private val states = mutableMapOf<String, Int>()
        private val nativePending = mutableSetOf<BluetoothGatt>()
        private val tasks = mutableListOf<Pair<Runnable, Long>>()
        private val handler = mock(Handler::class.java)
        private val manager = mock(BluetoothManager::class.java)
        private val adapter = mock(BluetoothAdapter::class.java)
        private val context = mock(Context::class.java)

        init {
            plugin.setField("mainThreadHandler", handler)
            plugin.setField("bluetoothManager", manager)
            plugin.setField("context", context)
            `when`(manager.adapter).thenReturn(adapter)
            `when`(adapter.isEnabled).thenReturn(true)
            `when`(handler.post(any(Runnable::class.java))).thenAnswer {
                tasks.add(it.arguments[0] as Runnable to now)
                true
            }
            `when`(handler.postDelayed(any(Runnable::class.java), anyLong())).thenAnswer {
                tasks.add((it.arguments[0] as Runnable) to (now + (it.arguments[1] as Long)))
                true
            }
            doAnswer { call ->
                tasks.removeAll { it.first === call.arguments[0] }
                null
            }.`when`(handler).removeCallbacks(any(Runnable::class.java))
            `when`(adapter.getRemoteDevice(anyString())).thenAnswer { call ->
                val id = (call.arguments[0] as String).connectionKey()
                val device = mock(BluetoothDevice::class.java)
                `when`(device.address).thenReturn(id)
                `when`(manager.getConnectionState(device, BluetoothProfile.GATT)).thenAnswer {
                    states[id] ?: BluetoothProfile.STATE_DISCONNECTED
                }
                `when`(device.connectGatt(context, false, plugin)).thenAnswer {
                    if (id in failingStarts) throw IllegalStateException("injected start failure")
                    if (nativePending.isNotEmpty()) {
                        overlapDetected = true
                        throw IllegalStateException("injected GATT 133: concurrent direct connects")
                    }
                    val gatt = mock(BluetoothGatt::class.java)
                    `when`(gatt.device).thenReturn(device)
                    doAnswer {
                        val remaining = closeFailuresRemaining[id] ?: 0
                        if (remaining > 0) {
                            closeFailuresRemaining[id] = remaining - 1
                            throw IllegalStateException("injected close failure for $id")
                        }
                        events.add("close:$id")
                        nativePending.remove(gatt)
                        null
                    }.`when`(gatt).close()
                    states[id] = BluetoothProfile.STATE_CONNECTING
                    nativePending.add(gatt)
                    gatts[id] = gatt
                    created.add(id)
                    events.add("create:$id")
                    gatt
                }
                device
            }
        }

        fun connect(id: String) = plugin.connect(id, false, null)

        fun ownedGatts(): IdentityHashMap<BluetoothGatt, Unit> = plugin.field("ownedGatts")

        fun connected(id: String) {
            connectionChanged(id, 0, BluetoothProfile.STATE_CONNECTED)
        }

        fun disconnected(id: String, status: Int) {
            connectionChanged(id, status, BluetoothProfile.STATE_DISCONNECTED)
        }

        fun connectionChanged(id: String, status: Int, newState: Int) {
            val gatt = gatts.getValue(id)
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                nativePending.remove(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                nativePending.remove(gatt)
            }
            states[id] = newState
            plugin.onConnectionStateChange(gatt, status, newState)
        }

        fun advance(millis: Long) {
            now += millis
            pump()
        }

        fun pump() {
            var iterations = 0
            while (true) {
                val index = tasks.indexOfFirst { it.second <= now }
                if (index < 0) return
                check(++iterations < 100) { "Unbounded handler dispatch" }
                tasks.removeAt(index).first.run()
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> UniversalBlePlugin.field(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

private fun UniversalBlePlugin.setField(name: String, value: Any?) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun UniversalBlePlugin.invokePrivate(name: String) {
    javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)
}
