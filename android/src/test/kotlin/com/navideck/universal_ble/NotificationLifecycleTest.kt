package com.navideck.universal_ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Handler
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

internal class NotificationLifecycleTest {
    @Test
    fun enablesLocalRoutingBeforeWritingCccd() {
        val fixture = fixture()
        `when`(fixture.gatt.writeDescriptor(fixture.descriptor)).thenReturn(true)
        var result: Result<Unit>? = null

        try {
            fixture.plugin.setNotifiable(
                deviceId,
                serviceId,
                characteristicId,
                BleInputProperty.NOTIFICATION,
            ) { result = it }

            assertNull(result)
            inOrder(fixture.gatt).apply {
                verify(fixture.gatt).setCharacteristicNotification(fixture.characteristic, true)
                verify(fixture.gatt).writeDescriptor(fixture.descriptor)
            }

            fixture.plugin.onDescriptorWrite(
                fixture.gatt,
                fixture.descriptor,
                BluetoothGatt.GATT_SUCCESS,
            )
            assertTrue(result!!.isSuccess)
        } finally {
            fixture.gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun immediateCccdFailureRestoresLocalRouting() {
        val fixture = fixture()
        `when`(fixture.gatt.writeDescriptor(fixture.descriptor)).thenReturn(false)
        var result: Result<Unit>? = null

        try {
            fixture.plugin.setNotifiable(
                deviceId,
                serviceId,
                characteristicId,
                BleInputProperty.NOTIFICATION,
            ) { result = it }

            assertTrue(result!!.isFailure)
            inOrder(fixture.gatt).apply {
                verify(fixture.gatt).setCharacteristicNotification(fixture.characteristic, true)
                verify(fixture.gatt).writeDescriptor(fixture.descriptor)
                verify(fixture.gatt).setCharacteristicNotification(fixture.characteristic, false)
            }
        } finally {
            fixture.gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun asynchronousCccdFailureRestoresLocalRouting() {
        val fixture = fixture()
        `when`(fixture.gatt.writeDescriptor(fixture.descriptor)).thenReturn(true)
        var result: Result<Unit>? = null

        try {
            fixture.plugin.setNotifiable(
                deviceId,
                serviceId,
                characteristicId,
                BleInputProperty.NOTIFICATION,
            ) { result = it }
            assertNull(result)

            fixture.plugin.onDescriptorWrite(
                fixture.gatt,
                fixture.descriptor,
                BluetoothGatt.GATT_FAILURE,
            )

            assertTrue(result!!.isFailure)
            verify(fixture.gatt).setCharacteristicNotification(fixture.characteristic, false)
        } finally {
            fixture.gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun staleNotificationIsIgnored() {
        val old = fixture(save = false)
        val current = fixture(save = false)
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        old.plugin.setField("callbackChannel", callbackChannel)
        old.gatt.saveCacheIfNeeded()
        current.gatt.saveCacheIfNeeded()

        try {
            old.plugin.onCharacteristicChanged(old.gatt, old.characteristic, byteArrayOf(1))
            verifyNoInteractions(callbackChannel)
        } finally {
            current.gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun staleReadCannotCompleteCurrentOperation() {
        val old = fixture(save = false)
        val current = fixture(save = false)
        var completed = false
        val pending = ReadResultFuture(
            current.gatt,
            deviceId,
            characteristicId,
            serviceId,
        ) { completed = true }
        current.plugin.field<MutableList<ReadResultFuture>>("readResultFutureList").add(pending)
        old.gatt.saveCacheIfNeeded()
        current.gatt.saveCacheIfNeeded()

        try {
            current.plugin.onCharacteristicRead(
                old.gatt,
                old.characteristic,
                byteArrayOf(1),
                BluetoothGatt.GATT_SUCCESS,
            )
            assertFalse(completed)
            assertTrue(
                current.plugin.field<MutableList<ReadResultFuture>>("readResultFutureList")
                    .contains(pending)
            )
        } finally {
            current.gatt.removeCacheIfCurrent()
        }
    }

    @Test
    fun staleDisconnectCannotRemoveCurrentGatt() {
        val old = fixture(save = false)
        val current = fixture(save = false)
        val callbackChannel = mock(UniversalBleCallbackChannel::class.java)
        old.plugin.setField("callbackChannel", callbackChannel)
        old.gatt.saveCacheIfNeeded()
        current.gatt.saveCacheIfNeeded()

        try {
            old.plugin.onConnectionStateChange(
                old.gatt,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothGatt.STATE_DISCONNECTED,
            )

            assertSame(current.gatt, deviceId.findGatt())
            verify(old.gatt).close()
            verifyNoInteractions(callbackChannel)
        } finally {
            current.gatt.removeCacheIfCurrent()
        }
    }

    private fun fixture(save: Boolean = true): GattFixture {
        val plugin = UniversalBlePlugin()
        val handler = mock(Handler::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val device = mock(BluetoothDevice::class.java)
        val service = mock(BluetoothGattService::class.java)
        val characteristic = mock(BluetoothGattCharacteristic::class.java)
        val descriptor = mock(BluetoothGattDescriptor::class.java)
        val serviceUuid = UUID.fromString(serviceId)
        val characteristicUuid = UUID.fromString(characteristicId)
        val descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        `when`(handler.post(any(Runnable::class.java))).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }
        `when`(gatt.device).thenReturn(device)
        `when`(device.address).thenReturn(deviceId)
        `when`(gatt.getService(serviceUuid)).thenReturn(service)
        `when`(service.getCharacteristic(characteristicUuid)).thenReturn(characteristic)
        `when`(service.uuid).thenReturn(serviceUuid)
        `when`(characteristic.uuid).thenReturn(characteristicUuid)
        `when`(characteristic.service).thenReturn(service)
        `when`(characteristic.getDescriptor(descriptorUuid)).thenReturn(descriptor)
        `when`(descriptor.uuid).thenReturn(descriptorUuid)
        `when`(descriptor.characteristic).thenReturn(characteristic)
        `when`(gatt.setCharacteristicNotification(characteristic, true)).thenReturn(true)
        `when`(gatt.setCharacteristicNotification(characteristic, false)).thenReturn(true)
        plugin.setField("mainThreadHandler", handler)
        if (save) gatt.saveCacheIfNeeded()
        return GattFixture(plugin, gatt, characteristic, descriptor)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> UniversalBlePlugin.field(name: String): T =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun UniversalBlePlugin.setField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private class GattFixture(
        val plugin: UniversalBlePlugin,
        val gatt: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
        val descriptor: BluetoothGattDescriptor,
    )

    private companion object {
        const val deviceId = "AA:BB:CC:DD:EE:FF"
        const val serviceId = "0000fff0-0000-1000-8000-00805f9b34fb"
        const val characteristicId = "0000fff4-0000-1000-8000-00805f9b34fb"
    }
}
