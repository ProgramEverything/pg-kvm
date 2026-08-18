package com.arkj.compose.stream.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import io.getstream.webrtc.sample.compose.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 单个摄像头的 BLE 连接（per-session，类比 UvcCameraVideoCapture 持有 ctrlBlock/uvcCamera）。
 *
 * 持有 GATT 连接、write characteristic、MTU 等会话级资源，
 * 负责 Protocol v2 组帧和 HID 指令发送。
 *
 * Protocol v2 framing:
 *   [0xAA magic][0x01 version] then one or more TLV frames:
 *     [cmd:1][payload_len:1][payload:len]
 *
 * Frames are packed into each write up to the MTU; remaining frames go into subsequent writes.
 */
@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    val device: BluetoothDevice
) {
    companion object {
        private const val TAG = "BleConnection"

        // MUST MATCH the ESP32 firmware UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("2D2A0001-8A5A-4E76-A2E3-1E57D9A1B001")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("2D2A0002-8A5A-4E76-A2E3-1E57D9A1B001")

        // Protocol v2 header
        private const val MAGIC: Byte = 0xAA.toByte()
        private const val VERSION: Byte = 0x01

        // Command opcodes
        private const val CMD_SET_MODIFIERS: Byte = 0x01
        private const val CMD_KEY_DOWN: Byte = 0x02
        private const val CMD_KEY_UP: Byte = 0x03
        private const val CMD_KEY_TAP: Byte = 0x04
        private const val CMD_MOUSE_MOVE: Byte = 0x10
        private const val CMD_MOUSE_SCROLL: Byte = 0x11
        private const val CMD_MOUSE_CLICK: Byte = 0x12
        private const val CMD_MOUSE_BUTTON_DOWN: Byte = 0x13
        private const val CMD_MOUSE_BUTTON_UP: Byte = 0x14
    }

    /** 连接意外断开时的回调（主动 disconnect 不触发），由上层决定是否重连 */
    var onUnexpectedDisconnect: (() -> Unit)? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var mtu: Int = 23 // default BLE MTU

    // Connection state
    private val _statusText = MutableStateFlow(context.getString(R.string.ble_status_disconnected))
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    // Deduplication state
    private var lastSentModifiersMask: UByte = 0x00u

    val deviceAddress: String get() = device.address

    // MARK: - Lifecycle

    /** 发起 GATT 连接（异步），状态通过 [statusText] / [isReady] 观察 */
    fun connect() {
        _statusText.value = context.getString(R.string.ble_status_connecting)
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            _statusText.value = context.getString(R.string.ble_status_connect_rejected)
        }
    }

    /** 主动断开连接（静默，不触发 onUnexpectedDisconnect） */
    fun disconnect() {
        onUnexpectedDisconnect = null
        try {
            bluetoothGatt?.close()
        } catch (_: SecurityException) {}
        bluetoothGatt = null
        writeCharacteristic = null
        _isReady.value = false
        _connectedDeviceName.value = null
        _statusText.value = context.getString(R.string.ble_status_disconnected)
    }

    // MARK: - Public API: Keyboard HID

    fun setModifiers(mask: UByte) {
        if (mask == lastSentModifiersMask) return
        lastSentModifiersMask = mask
        sendFrame(CMD_SET_MODIFIERS, byteArrayOf(mask.toByte()))
    }

    fun sendKeyDown(modifiersMask: UByte, keycode: UByte) {
        if (keycode == 0x00u.toUByte()) return
        // Sync modifiers first if needed
        if (modifiersMask != lastSentModifiersMask) {
            lastSentModifiersMask = modifiersMask
            sendFrame(CMD_SET_MODIFIERS, byteArrayOf(modifiersMask.toByte()))
        }
        sendFrame(CMD_KEY_DOWN, byteArrayOf(keycode.toByte()))
    }

    fun sendKeyUp(keycode: UByte) {
        if (keycode == 0x00u.toUByte()) return
        sendFrame(CMD_KEY_UP, byteArrayOf(keycode.toByte()))
    }

    fun sendKeyTap(modifiers: UByte, keycode: UByte) {
        if (keycode == 0x00u.toUByte()) return
        sendFrame(CMD_KEY_TAP, byteArrayOf(modifiers.toByte(), keycode.toByte()))
    }

    fun sendKeyTaps(taps: List<HID.HIDCommand>) {
        if (taps.isEmpty()) return
        val frames = taps.map { (mod, key) ->
            buildFrame(CMD_KEY_TAP, byteArrayOf(mod.toByte(), key.toByte()))
        }
        writeBatchedFrames(frames)
    }

    // MARK: - Public API: Mouse HID

    fun sendMouseMove(dx: Int, dy: Int) {
        sendFrame(
            CMD_MOUSE_MOVE,
            byteArrayOf(dx.coerceIn(-128, 127).toByte(), dy.coerceIn(-128, 127).toByte())
        )
    }

    fun sendMouseScroll(dx: Int, dy: Int) {
        sendFrame(
            CMD_MOUSE_SCROLL,
            byteArrayOf(dx.coerceIn(-128, 127).toByte(), dy.coerceIn(-128, 127).toByte())
        )
    }

    fun sendMouseClick(button: UByte) {
        sendFrame(CMD_MOUSE_CLICK, byteArrayOf(button.toByte()))
    }

    fun sendMouseButtonDown(button: UByte) {
        sendFrame(CMD_MOUSE_BUTTON_DOWN, byteArrayOf(button.toByte()))
    }

    fun sendMouseButtonUp(button: UByte) {
        sendFrame(CMD_MOUSE_BUTTON_UP, byteArrayOf(button.toByte()))
    }

    // MARK: - Private: BLE connection

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state: $status -> $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _statusText.value = context.getString(R.string.ble_status_discovering)
                    try {
                        gatt.requestMtu(512)
                    } catch (e: SecurityException) {}
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasReady = _isReady.value
                    _statusText.value = context.getString(R.string.ble_status_disconnected_unexpected)
                    _isReady.value = false
                    _connectedDeviceName.value = null
                    writeCharacteristic = null
                    try {
                        gatt.close()
                    } catch (_: SecurityException) {}
                    // 意外断开时通知上层（可能需要自动重连）
                    onUnexpectedDisconnect?.invoke()
                    if (!wasReady) onUnexpectedDisconnect = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed: $mtu (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@BleConnection.mtu = mtu
            }
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {}
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "Services discovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _statusText.value = context.getString(R.string.ble_status_discover_failed)
                return
            }

            val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: run {
                _statusText.value = context.getString(R.string.ble_status_service_not_found)
                return
            }

            val characteristic: BluetoothGattCharacteristic =
                service.getCharacteristic(WRITE_CHAR_UUID) ?: run {
                    _statusText.value = context.getString(R.string.ble_status_char_not_found)
                    return
                }

            // Use WRITE_TYPE_NO_RESPONSE if supported for lower latency
            if (characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            ) {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            writeCharacteristic = characteristic
            _isReady.value = true
            _connectedDeviceName.value = gatt.device.name ?: gatt.device.address
            _statusText.value = context.getString(R.string.ble_status_connected, _connectedDeviceName.value ?: "")
            Log.d(TAG, "BLE connection ready!")
        }
    }

    // MARK: - Private: Protocol framing

    private val maxPacketSize: Int
        get() = maxOf(20, mtu - 3)

    private fun buildFrame(cmd: Byte, payload: ByteArray): ByteArray {
        val len = payload.size.toByte()
        val frame = ByteArray(2 + payload.size)
        frame[0] = cmd
        frame[1] = len
        payload.copyInto(frame, 2)
        return frame
    }

    private fun sendFrame(cmd: Byte, payload: ByteArray) {
        val frame = buildFrame(cmd, payload)
        val packet = buildPacket(listOf(frame))
        writePacket(packet)
    }

    private fun buildPacket(frames: List<ByteArray>): ByteArray {
        val totalLen = 2 + frames.sumOf { it.size }
        val packet = ByteArray(totalLen)
        packet[0] = MAGIC
        packet[1] = VERSION
        var offset = 2
        for (frame in frames) {
            frame.copyInto(packet, offset)
            offset += frame.size
        }
        return packet
    }

    /**
     * Batches frames into packets, splitting at the MTU boundary.
     */
    private fun writeBatchedFrames(frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        val maxSize = maxPacketSize - 2 // subtract header size

        var batch = mutableListOf<ByteArray>()
        var batchSize = 0

        for (frame in frames) {
            if (batchSize + frame.size > maxSize && batch.isNotEmpty()) {
                writePacket(buildPacket(batch))
                batch = mutableListOf()
                batchSize = 0
            }
            batch.add(frame)
            batchSize += frame.size
        }

        if (batch.isNotEmpty()) {
            writePacket(buildPacket(batch))
        }
    }

    @Suppress("DEPRECATION")
    private fun writePacket(packet: ByteArray) {
        val characteristic = writeCharacteristic ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(
                    characteristic, packet, characteristic.writeType
                )
            } else {
                characteristic.value = packet
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Write failed: ${e.message}")
        }
    }
}
