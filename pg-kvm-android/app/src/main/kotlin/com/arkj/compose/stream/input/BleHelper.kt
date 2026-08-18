package com.arkj.compose.stream.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE 全局 Helper（唯一实例，类比 UvcCameraHelper 持有 USBMonitor）。
 *
 * 只负责全局资源：BluetoothAdapter、BLE 扫描、扫描结果维护。
 * 每个摄像头的 GATT 连接由 [BleConnection] 承载，通过 [createConnection] 创建。
 */
@SuppressLint("MissingPermission")
class BleHelper(context: Context) {

    companion object {
        private const val TAG = "BleHelper"
    }

    /** Data class for scanned device info shown in UI. */
    data class ScannedDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val device: BluetoothDevice
    )

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    /** 蓝牙是否可用（已开启且支持 BLE） */
    val isBluetoothEnabled: Boolean get() = bluetoothAdapter.isEnabled

    /** 通过地址获取 BluetoothDevice（用于配对记录的自动回连） */
    fun getRemoteDevice(address: String): BluetoothDevice? = try {
        bluetoothAdapter.getRemoteDevice(address)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Invalid BLE address: $address")
        null
    }

    /** 开始扫描 ESP32 BLE 设备 */
    fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off, cannot scan")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE not supported")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filter = ScanFilter.Builder().setDeviceName("KBBridge-ESP32S3").build()

        _scanResults.value = emptyList()
        _isScanning.value = true

        try {
            // Use null filter to scan all devices - ESP32 does not advertise
            // the service UUID, so filtering by UUID would miss it.
            scanner.startScan(List(1) { filter }, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Scan permission denied: ${e.message}")
            _isScanning.value = false
        }
    }

    /** 停止扫描 */
    fun stopScan() {
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {}
        _isScanning.value = false
    }

    /** 释放全局资源（停止扫描） */
    fun release() {
        stopScan()
    }

    /** 为指定设备创建一个 per-session 连接对象 */
    fun createConnection(device: BluetoothDevice): BleConnection =
        BleConnection(appContext, device)

    // MARK: - Private

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            val rssi = result.rssi

            val currentList = _scanResults.value.toMutableList()
            // Update or add the device
            val existingIndex = currentList.indexOfFirst { it.address == device.address }
            val scannedDevice = ScannedDevice(name, device.address, rssi, device)
            if (existingIndex >= 0) {
                currentList[existingIndex] = scannedDevice
            } else {
                currentList.add(scannedDevice)
            }
            // Sort by RSSI (strongest first)
            _scanResults.value = currentList.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error $errorCode")
            _isScanning.value = false
        }
    }
}
