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
import android.os.SystemClock
import android.util.Log
import io.getstream.webrtc.sample.compose.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

        // 写队列上限：超过后优先丢弃旧的鼠标移动包（按键类事件不丢）
        private const val MAX_QUEUE_SIZE = 128
        // 单包最大写重试次数，超过后丢弃该包，避免队头阻塞
        private const val MAX_WRITE_RETRIES = 5
        private const val WRITE_RETRY_DELAY_MS = 4L
    }

    /** 连接意外断开时的回调（主动 disconnect 不触发），由上层决定是否重连 */
    var onUnexpectedDisconnect: (() -> Unit)? = null

    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var mtu: Int = 23 // default BLE MTU

    // MARK: - Write queue (串行化所有 GATT 写，写失败有限重试)

    /**
     * droppable = true 的包（鼠标移动/滚轮）在队列超限时优先被丢弃；
     * 按键类事件（keydown/keyup 等）保序排队，不主动丢弃。
     */
    private class QueuedPacket(val data: ByteArray, val droppable: Boolean)

    private val writeQueue = ConcurrentLinkedQueue<QueuedPacket>()
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BleWrite")
    }
    /** executor 上是否已有 drain 循环在跑 */
    private val pumping = AtomicBoolean(false)
    // 以下状态主要在 writeExecutor 线程访问，clearWriteQueue 可能从 GATT 回调线程写
    @Volatile private var retryCount = 0

    // Connection state
    private val _statusText = MutableStateFlow(context.getString(R.string.ble_status_disconnected))
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    // Deduplication state（可能被多个回调线程访问，读写需持锁）
    private var lastSentModifiersMask: UByte = 0x00u
    private val modifiersLock = Any()

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
        clearWriteQueue()
        _isReady.value = false
        _connectedDeviceName.value = null
        _statusText.value = context.getString(R.string.ble_status_disconnected)
    }

    // MARK: - Public API: Keyboard HID

    fun setModifiers(mask: UByte) {
        synchronized(modifiersLock) {
            if (mask == lastSentModifiersMask) return
            lastSentModifiersMask = mask
        }
        sendFrame(CMD_SET_MODIFIERS, byteArrayOf(mask.toByte()))
    }

    fun sendKeyDown(modifiersMask: UByte, keycode: UByte) {
        if (keycode == 0x00u.toUByte()) return
        // Sync modifiers first if needed
        val needModifiers: Boolean
        synchronized(modifiersLock) {
            needModifiers = modifiersMask != lastSentModifiersMask
            if (needModifiers) lastSentModifiersMask = modifiersMask
        }
        if (needModifiers) {
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
                    clearWriteQueue() // 陈旧的位移/按键事件对端已无意义，全部丢弃
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
            pumpWrites() // 连接就绪，唤醒可能在等待的队列
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
        // 鼠标移动/滚轮是高频且可丢帧的事件
        val droppable = cmd == CMD_MOUSE_MOVE || cmd == CMD_MOUSE_SCROLL
        enqueuePacket(packet, droppable)
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
                enqueuePacket(buildPacket(batch), droppable = false)
                batch = mutableListOf()
                batchSize = 0
            }
            batch.add(frame)
            batchSize += frame.size
        }

        if (batch.isNotEmpty()) {
            enqueuePacket(buildPacket(batch), droppable = false)
        }
    }

    // MARK: - Private: Write queue

    /** 入队并启动 drain 循环，非阻塞，任何线程可调用 */
    private fun enqueuePacket(packet: ByteArray, droppable: Boolean) {
        // 队列超限时丢旧的 droppable 包（旧位移），给按键事件腾出空间
        while (writeQueue.size >= MAX_QUEUE_SIZE) {
            val victim = writeQueue.firstOrNull { it.droppable } ?: return // 全是按键事件，丢新包
            writeQueue.remove(victim)
        }
        writeQueue.add(QueuedPacket(packet, droppable))
        pumpWrites()
    }

    /**
     * 串行 drain：peek 队头包 -> 写成功才 poll 出队；
     * 写失败有限重试（带短退避），超过上限丢弃该包，避免队头阻塞。
     */
    private fun pumpWrites() {
        if (!pumping.compareAndSet(false, true)) return
        writeExecutor.execute {
            try {
                while (true) {
                    val entry = writeQueue.peek() ?: break
                    if (writeCharacteristic == null) break // 未就绪，留在队列头等重连/就绪
                    if (tryWrite(entry.data)) {
                        writeQueue.poll()
                        retryCount = 0
                        continue
                    }
                    retryCount++
                    if (retryCount >= MAX_WRITE_RETRIES) {
                        writeQueue.poll()
                        retryCount = 0
                        Log.w(TAG, "Dropping packet after $MAX_WRITE_RETRIES failed write attempts")
                        continue
                    }
                    SystemClock.sleep(WRITE_RETRY_DELAY_MS)
                }
            } finally {
                pumping.set(false)
                // 与 enqueue 竞态兜底：退出前又有新包入队则再驱动一轮
                if (writeQueue.isNotEmpty() && writeCharacteristic != null) pumpWrites()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun tryWrite(packet: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic, packet, characteristic.writeType
                ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = packet
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Write failed: ${e.message}")
            false
        }
    }

    private fun clearWriteQueue() {
        writeQueue.clear()
        retryCount = 0
    }
}
