package com.navideck.universal_ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

internal class SafeScannerTest {
    @Test
    fun successfulStartMarksScanningActive() {
        val scanner = mock(BluetoothLeScanner::class.java)
        val safeScanner = scanner(scanner)
        val settings = mock(ScanSettings::class.java)
        val callback = mock(ScanCallback::class.java)

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            assertNull(safeScanner.startScan(emptyList(), settings, callback))
        }

        assertTrue(safeScanner.isScanning())
        verify(scanner).startScan(anyList(), eq(settings), any(ScanCallback::class.java))
    }

    @Test
    fun missingScannerReportsFailure() {
        val safeScanner = scanner(null)
        val callback = mock(ScanCallback::class.java)

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            assertEquals(
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR,
                safeScanner.startScan(emptyList(), mock(ScanSettings::class.java), callback),
            )
        }

        assertFalse(safeScanner.isScanning())
        verify(callback, never()).onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
    }

    @Test
    fun synchronousStartFailureReportsFailure() {
        val scanner = mock(BluetoothLeScanner::class.java)
        val safeScanner = scanner(scanner)
        val settings = mock(ScanSettings::class.java)
        val callback = mock(ScanCallback::class.java)
        doThrow(IllegalStateException("failed"))
            .`when`(scanner).startScan(anyList(), eq(settings), any(ScanCallback::class.java))

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            mockStatic(Log::class.java).use {
                assertEquals(
                    ScanCallback.SCAN_FAILED_INTERNAL_ERROR,
                    safeScanner.startScan(emptyList(), settings, callback),
                )
            }
        }

        assertFalse(safeScanner.isScanning())
        verify(callback, never()).onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
    }

    @Test
    fun nativeFailureClearsActiveState() {
        val scanner = mock(BluetoothLeScanner::class.java)
        val safeScanner = scanner(scanner)
        val settings = mock(ScanSettings::class.java)
        val callback = mock(ScanCallback::class.java)
        var nativeCallback: ScanCallback? = null
        doAnswer {
            nativeCallback = it.arguments[2] as ScanCallback
            null
        }.`when`(scanner).startScan(anyList(), eq(settings), any(ScanCallback::class.java))

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            safeScanner.startScan(emptyList(), settings, callback)
        }
        nativeCallback!!.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

        assertFalse(safeScanner.isScanning())
        verify(callback).onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
    }

    @Test
    fun throttledRetryRemainsReportedAsScanning() {
        val scanner = mock(BluetoothLeScanner::class.java)
        val handler = mock(Handler::class.java)
        val safeScanner = scanner(scanner, handler)
        val settings = mock(ScanSettings::class.java)
        val callback = mock(ScanCallback::class.java)

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }
                .thenReturn(0L, 1L, 2L, 3L, 4L, 5L)
            mockStatic(Log::class.java).use {
                repeat(5) {
                    safeScanner.startScan(emptyList(), settings, callback)
                    safeScanner.stopScan()
                }
                safeScanner.startScan(emptyList(), settings, callback)
            }
        }

        assertTrue(safeScanner.isScanning())
        verify(scanner, times(5)).startScan(anyList(), eq(settings), any(ScanCallback::class.java))
        verify(handler).postDelayed(any(Runnable::class.java), eq(31_995L))
    }

    private fun scanner(
        scanner: BluetoothLeScanner?,
        handler: Handler = mock(Handler::class.java),
    ): SafeScanner {
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.bluetoothLeScanner).thenReturn(scanner)
        return SafeScanner(manager, handler)
    }
}
