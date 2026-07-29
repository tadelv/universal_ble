package com.navideck.universal_ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.LinkedList

private const val NUM_SCAN_DURATIONS_KEPT = 5
private const val EXCESSIVE_SCANNING_PERIOD_MS = 30 * 1000L
private const val TAG = "UniversalBlePlugin"

/**
 * A safe wrapper for Bluetooth LE scanning operations that prevents excessive scanning.
 *
 * This class manages BLE scanning while adhering to Android's scanning frequency limits by:
 * - Tracking scan start times over a 30-second window
 * - Limiting to 5 scan operations within this window
 * - Automatically scheduling delayed scans when frequency limits are exceeded
 * - Providing safe start/stop scan operations with error handling
 *
 * The scanner will automatically delay new scan requests if the frequency limit is reached,
 * and will retry once sufficient time has passed. This helps prevent scan failure errors
 * and ensures compliance with Android's scanning restrictions.
 *
 * @property bluetoothManager The system's BluetoothManager used for scanning operations
 */
@SuppressLint("MissingPermission")
class SafeScanner(
    private val bluetoothManager: BluetoothManager,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val startTimes = LinkedList<Long>()
    private var awaitingScan = false
    private var isScanning = false
    private var resultCallback: ScanCallback? = null
    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            resultCallback?.onScanFailed(errorCode)
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            resultCallback?.onScanResult(callbackType, result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            resultCallback?.onBatchScanResults(results)
        }
    }

    fun startScan(filters: List<ScanFilter>, settings: ScanSettings, callback: ScanCallback): Int? {
        val now = SystemClock.elapsedRealtime()
        startTimes.removeAll { now - it > EXCESSIVE_SCANNING_PERIOD_MS }

        if (startTimes.size >= NUM_SCAN_DURATIONS_KEPT) {
            if (awaitingScan) {
                Log.e(TAG, "startScan: too frequent, awaiting scan..")
                return null
            }

            awaitingScan = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                callback.onScanFailed(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY)
            }
            val delay = startTimes.first() + EXCESSIVE_SCANNING_PERIOD_MS - now + 2_000
            Log.e(TAG, "startScan: too frequent, schedule auto-start after $delay ms $startTimes")

            handler.postDelayed({
                Log.d(TAG, "Retrying scan after delay")
                awaitingScan = false
                startScan(filters, settings, callback)?.let(callback::onScanFailed)
            }, delay)
            return null
        } else {
            awaitingScan = false
            val scanner = bluetoothManager.adapter.bluetoothLeScanner
            if (scanner == null) {
                isScanning = false
                return ScanCallback.SCAN_FAILED_INTERNAL_ERROR
            }
            try {
                resultCallback = callback
                scanner.startScan(filters, settings, scanCallback)
                startTimes.addLast(now)
                isScanning = true
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Scan : $e")
                isScanning = false
                return ScanCallback.SCAN_FAILED_INTERNAL_ERROR
            }
        }
    }

    fun stopScan() {
        awaitingScan = false
        handler.removeCallbacksAndMessages(null)
        bluetoothManager.adapter.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    fun isScanning(): Boolean {
        return isScanning || awaitingScan
    }
}
