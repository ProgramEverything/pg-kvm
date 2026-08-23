package com.arkj.compose.stream.input

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket server that receives keyboard/mouse input events from the browser
 * and forwards them to the currently active BLE connection for transmission to ESP32.
 *
 * Runs on port 3001 (separate from the WebRTC signaling server on port 3000).
 */
class InputRelayServer(
    /** 始终返回当前活跃摄像头的 BLE 连接（可能为 null，未连接时） */
    private val activeConnection: () -> BleConnection?
) {
    companion object {
        private const val TAG = "InputRelayServer"
        private const val DEFAULT_PORT = 3001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var webSocketServer: WebSocketServer? = null

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    // Track modifier state for keyboard input
    private var currentModifiers: UByte = 0x00u

    // Track pressed keys for proper key-up handling
    private val pressedKeys = mutableSetOf<UByte>()

    /** Track which client is the active input client. */
    private var activeInputClient: WebSocket? = null

    /** 当前活跃的 BLE 连接（可能为 null） */
    private val ble: BleConnection? get() = activeConnection()

    // -------- Lifecycle --------

    /**
     * 启动输入中继服务器。阻塞等待端口绑定结果。
     * @throws IOException 端口绑定失败或超时
     */
    fun start(port: Int = DEFAULT_PORT): String {
        // 确保旧实例已释放端口
        stop()

        val startupLatch = CountDownLatch(1)
        val bindError = AtomicReference<Exception?>(null)
        val started = AtomicBoolean(false)

        webSocketServer = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                Log.i(TAG, "Input client connected: ${conn.remoteSocketAddress}")
                // First client to connect becomes the active input client
                if (activeInputClient == null) {
                    activeInputClient = conn
                }
                _clientCount.value = connections.size
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                Log.i(TAG, "Input client disconnected: ${conn.remoteSocketAddress}")
                if (activeInputClient == conn) {
                    activeInputClient = null
                    // Release all held keys
                    releaseAllKeys()
                }
                // Assign a new active client if available
                if (activeInputClient == null) {
                    activeInputClient = connections.firstOrNull()
                }
                _clientCount.value = connections.size
            }

            override fun onMessage(conn: WebSocket, message: String) {
                // Only process messages from the active input client
                if (activeInputClient != null && activeInputClient != conn) {
                    Log.d(TAG, "Ignoring message from non-active client")
                    return
                }
                // If no active client, assign this one
                if (activeInputClient == null) {
                    activeInputClient = conn
                }
                handleInputMessage(message)
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                // conn == null 表示服务器级错误（端口绑定失败等），且 onStart 尚未回调
                if (conn == null && !started.get()) {
                    bindError.set(ex)
                    startupLatch.countDown()
                }
                Log.e(TAG, "WebSocket error: ${ex.message}")
            }

            override fun onStart() {
                started.set(true)
                Log.i(TAG, "Input relay server started on port $port")
                _isRunning.value = true
                startupLatch.countDown()
            }
        }

        webSocketServer?.start()

        // 等待端口绑定结果（成功或失败）
        if (!startupLatch.await(5, TimeUnit.SECONDS)) {
            stop()
            throw IOException("input relay server startup timed out on port $port")
        }
        bindError.get()?.let { ex ->
            stop()
            throw IOException("input relay server failed to bind port $port: ${ex.message}", ex)
        }

        return "ws://${getLocalIpAddress()}:$port"
    }

    fun stop() {
        releaseAllKeys()
        try {
            // 等待 selector 线程退出并释放端口，否则快速重启会绑定失败
            webSocketServer?.stop(2000)
        } catch (_: Exception) {}
        webSocketServer = null
        _isRunning.value = false
        _clientCount.value = 0
        activeInputClient = null
        Log.i(TAG, "Input relay server stopped")
    }

    // -------- Message handling --------

    private fun handleInputMessage(message: String) {
        val json = try {
            JSONObject(message)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON: ${e.message}")
            return
        }

        val type = json.optString("type", "")
        if (type.isEmpty()) {
            Log.w(TAG, "Message missing type field")
            return
        }

        // Route to the active connection; skip if not ready
        val ble = activeConnection()
        if (ble == null || !ble.isReady.value) {
            Log.d(TAG, "Active BLE connection not ready, ignoring input: $type")
            return
        }

        try {
            when (type) {
                "key-down" -> handleKeyDown(json)
                "key-up" -> handleKeyUp(json)
                "key-press" -> handleKeyPress(json)
                "mouse-move" -> handleMouseMove(json)
                "mouse-down" -> handleMouseDown(json)
                "mouse-up" -> handleMouseUp(json)
                "mouse-click" -> handleMouseClick(json)
                "mouse-scroll" -> handleMouseScroll(json)
                else -> Log.w(TAG, "Unknown input type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling input $type: ${e.message}")
        }
    }

    // -------- Keyboard handlers --------

    private fun handleKeyDown(json: JSONObject) {
        val code = json.optString("code", "")
        val key = json.optString("key", "")

        // Check if this is a modifier key
        val modifierBit = HID.mapKeyToModifier(key)
        if (modifierBit != null) {
            currentModifiers = (currentModifiers.toInt() or modifierBit.toInt()).toUByte()
            ble?.setModifiers(currentModifiers)
            return
        }

        // Get HID keycode from code
        val hidKeycode = HID.mapCodeToHID(code) ?: run {
            // Try character-based mapping as fallback
            if (key.length == 1) {
                val cmd = HID.mapCharacterToHID(key[0])
                if (cmd != null) {
                    ble?.sendKeyDown(cmd.modifiers, cmd.keycode)
                    pressedKeys.add(cmd.keycode)
                }
            }
            return
        }

        ble?.sendKeyDown(currentModifiers, hidKeycode)
        pressedKeys.add(hidKeycode)
    }

    private fun handleKeyUp(json: JSONObject) {
        val code = json.optString("code", "")
        val key = json.optString("key", "")

        // Check if this is a modifier key release
        val modifierBit = HID.mapKeyToModifier(key)
        if (modifierBit != null) {
            currentModifiers = (currentModifiers.toInt() and modifierBit.toInt().inv()).toUByte()
            ble?.setModifiers(currentModifiers)
            return
        }

        // Get HID keycode from code
        val hidKeycode = HID.mapCodeToHID(code) ?: run {
            if (key.length == 1) {
                val cmd = HID.mapCharacterToHID(key[0])
                if (cmd != null) {
                    ble?.sendKeyUp(cmd.keycode)
                    pressedKeys.remove(cmd.keycode)
                }
            }
            return
        }

        ble?.sendKeyUp(hidKeycode)
        pressedKeys.remove(hidKeycode)
    }

    private fun handleKeyPress(json: JSONObject) {
        val char = json.optString("char", "")
        if (char.isEmpty()) return

        val taps = char.mapNotNull { HID.mapCharacterToHID(it) }
        if (taps.isNotEmpty()) {
            ble?.sendKeyTaps(taps)
        }
    }

    // -------- Mouse handlers --------

    private fun handleMouseMove(json: JSONObject) {
        val dx = json.optInt("dx", 0)
        val dy = json.optInt("dy", 0)
        if (dx != 0 || dy != 0) {
            ble?.sendMouseMove(dx, dy)
        }
    }

    private fun handleMouseDown(json: JSONObject) {
        val button = mapMouseButton(json.optString("button", "left"))
        ble?.sendMouseButtonDown(button)
    }

    private fun handleMouseUp(json: JSONObject) {
        val button = mapMouseButton(json.optString("button", "left"))
        ble?.sendMouseButtonUp(button)
    }

    private fun handleMouseClick(json: JSONObject) {
        val button = mapMouseButton(json.optString("button", "left"))
        ble?.sendMouseClick(button)
    }

    private fun handleMouseScroll(json: JSONObject) {
        val dx = json.optInt("dx", 0)
        val dy = json.optInt("dy", 0)
        if (dx != 0 || dy != 0) {
            ble?.sendMouseScroll(dx, dy)
        }
    }

    // -------- Helpers --------

    private fun mapMouseButton(button: String): UByte {
        return when (button.lowercase()) {
            "left" -> HID.MOUSE_LEFT
            "right" -> HID.MOUSE_RIGHT
            "middle" -> HID.MOUSE_MIDDLE
            else -> HID.MOUSE_LEFT
        }
    }

    /** Release all held keys (called on client disconnect). */
    private fun releaseAllKeys() {
        for (keycode in pressedKeys) {
            ble?.sendKeyUp(keycode)
        }
        pressedKeys.clear()
        // Reset modifiers
        if (currentModifiers != 0x00u.toUByte()) {
            currentModifiers = 0x00u.toUByte()
            ble?.setModifiers(currentModifiers)
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}