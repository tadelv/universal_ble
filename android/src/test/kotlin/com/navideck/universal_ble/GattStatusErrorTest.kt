package com.navideck.universal_ble

import android.bluetooth.BluetoothGatt
import kotlin.test.Test
import kotlin.test.assertEquals

internal class GattStatusErrorTest {

    @Test
    fun gatt133IsDistinctAndPreservesRawStatus() {
        val status = 133
        val code = gattStatusToUniversalBleErrorCode(status)
        val error = createFlutterError(code, "Failed to write", status.toString())

        assertEquals(UniversalBleErrorCode.GATT_ERROR, code)
        assertEquals(UniversalBleErrorCode.GATT_ERROR.raw.toString(), error.code)
        assertEquals("133", error.details)
    }

    @Test
    fun otherGattStatusesKeepTheirMappings() {
        mapOf(
            BluetoothGatt.GATT_READ_NOT_PERMITTED to UniversalBleErrorCode.READ_NOT_PERMITTED,
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED to UniversalBleErrorCode.WRITE_NOT_PERMITTED,
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION to UniversalBleErrorCode.INSUFFICIENT_AUTHENTICATION,
            BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION to UniversalBleErrorCode.INSUFFICIENT_AUTHORIZATION,
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION to UniversalBleErrorCode.INSUFFICIENT_ENCRYPTION,
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED to UniversalBleErrorCode.OPERATION_NOT_SUPPORTED,
            BluetoothGatt.GATT_INVALID_OFFSET to UniversalBleErrorCode.INVALID_OFFSET,
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH to UniversalBleErrorCode.INVALID_ATTRIBUTE_LENGTH,
            BluetoothGatt.GATT_CONNECTION_CONGESTED to UniversalBleErrorCode.CONNECTION_FAILED,
            BluetoothGatt.GATT_FAILURE to UniversalBleErrorCode.FAILED,
        ).forEach { (status, expected) ->
            assertEquals(expected, gattStatusToUniversalBleErrorCode(status), "status $status")
        }
        assertEquals(UniversalBleErrorCode.UNKNOWN_ERROR, gattStatusToUniversalBleErrorCode(999))
    }
}
