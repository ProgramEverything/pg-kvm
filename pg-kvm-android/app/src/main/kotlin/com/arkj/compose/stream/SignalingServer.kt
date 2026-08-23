/*
 * Copyright 2023 Stream.IO, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.arkj.compose.stream

import com.arkj.compose.stream.peer.StreamPeerConnection
import com.arkj.compose.stream.peer.StreamPeerConnectionFactory
import com.arkj.compose.stream.peer.StreamPeerType
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket-based signaling server for WebRTC stream publishing.
 *
 * Accepts WebSocket connections from browser clients, handles the SDP offer/answer
 * exchange and ICE candidate relay. Each connected browser gets its own WebRTC
 * peer connection with the local camera video and audio tracks.
 *
 * The Android device acts as the answerer: browsers send offers, and the server
 * responds with answers containing the local media tracks.
 *
 * @author Created by claude on 2026/8/11
 */
class SignalingServer {

  private companion object {
    /** 等待端口绑定完成的最长时间（秒） */
    const val STARTUP_TIMEOUT_SECONDS = 5L
  }

  private val logger by taggedLogger("SignalingServer")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private var webSocketServer: WebSocketServer? = null
  private var serverPort: Int = 3000

  /** Provider for local video/audio tracks. Set before calling [start]. */
  var trackProvider: TrackProvider? = null

  /** Factory for creating WebRTC peer connections. Set before calling [start]. */
  var peerConnectionFactory: StreamPeerConnectionFactory? = null

  /** Per-client session state. */
  private val clients = ConcurrentHashMap<String, ClientSession>()

  private val _state = MutableStateFlow(SignalingServerState.STOPPED)
  val state: StateFlow<SignalingServerState> = _state

  private val _connectedClients = MutableSharedFlow<Int>(extraBufferCapacity = 8)
  val connectedClients: SharedFlow<Int> = _connectedClients

  private val _clientAddresses = MutableStateFlow<List<String>>(emptyList())
  /** 当前已连接客户端的远程地址列表 */
  val clientAddresses: StateFlow<List<String>> = _clientAddresses

  // -------- public types --------

  /**
   * Functional interface for lazy access to local media tracks.
   * Tracks are initialized lazily in [WebRtcSessionManagerImpl] — this
   * provider pattern lets the signaling server retrieve them on demand.
   */
  interface TrackProvider {
    fun getVideoTrack(): VideoTrack?
    fun getAudioTrack(): AudioTrack?
  }

  // -------- private types --------

  private data class ClientSession(
    val id: String,
    val webSocket: WebSocket,
    val peerConnection: StreamPeerConnection,
    val remoteAddress: String
  )

  // -------- lifecycle --------

  /**
   * Starts the WebSocket signaling server.
   *
   * Waits for the port bind to complete - java-websocket binds asynchronously on
   * its selector thread, so a bind failure (e.g. port already in use) surfaces via
   * [WebSocketServer.onError] with a null connection rather than as a thrown
   * exception. We latch on both callbacks and rethrow the failure here.
   *
   * @param port Listening port, default 3000 (matches the frontend [WS_URL]).
   * @return The WebSocket URL that browsers should connect to.
   * @throws IOException if the server fails to bind the port within the timeout.
   */
  fun start(port: Int = 3000): String {
    // 确保旧实例已释放端口
    stop()
    serverPort = port

    val startupLatch = CountDownLatch(1)
    val bindError = AtomicReference<Exception?>(null)
    val started = AtomicBoolean(false)

    webSocketServer = object : WebSocketServer(InetSocketAddress(port)) {
      override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        logger.i { "[onOpen] client connected: ${conn.remoteSocketAddress}" }
        onClientConnected(conn)
      }

      override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        logger.i { "[onClose] client disconnected: ${conn.remoteSocketAddress}, code=$code, reason=$reason" }
        onClientDisconnected(conn)
      }

      override fun onMessage(conn: WebSocket, message: String) {
        logger.d { "[onMessage] ${message.take(200)}" }
        handleSignalingMessage(conn, message)
      }

      override fun onError(conn: WebSocket?, ex: Exception) {
        // conn == null 表示服务器级错误（端口绑定失败等），且 onStart 尚未回调
        if (conn == null && !started.get()) {
          bindError.set(ex)
          startupLatch.countDown()
        }
        logger.e { "[onError] conn=${conn?.remoteSocketAddress}, ${ex.message}" }
      }

      override fun onStart() {
        started.set(true)
        logger.i { "[onStart] signaling server running on port $port" }
        _state.value = SignalingServerState.RUNNING
        startupLatch.countDown()
      }
    }

    webSocketServer?.start()

    // 等待端口绑定结果（成功或失败）
    if (!startupLatch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      stop()
      throw IOException("signaling server startup timed out on port $port")
    }
    bindError.get()?.let { ex ->
      stop()
      _state.value = SignalingServerState.STOPPED
      throw IOException("signaling server failed to bind port $port: ${ex.message}", ex)
    }

    return "ws://${getLocalIpAddress()}:$port"
  }

  /**
   * Stops the signaling server and closes all client connections.
   */
  fun stop() {
    clients.values.forEach { session ->
      try {
        session.peerConnection.connection.close()
        session.webSocket.close()
      } catch (_: Exception) {
        // ignore cleanup errors
      }
    }
    clients.clear()
    _clientAddresses.value = emptyList()

    try {
      // 等待 selector 线程退出并释放端口，否则快速重启会绑定失败
      webSocketServer?.stop(2000)
    } catch (_: Exception) {
      // ignore
    }
    webSocketServer = null

    _state.value = SignalingServerState.STOPPED
    logger.i { "[stop] signaling server stopped" }
  }

  fun isRunning(): Boolean = _state.value == SignalingServerState.RUNNING

  // -------- client management --------

  private fun onClientConnected(webSocket: WebSocket) {
    val factory = peerConnectionFactory
    if (factory == null) {
      logger.e { "[onClientConnected] PeerConnectionFactory not set — rejecting client" }
      webSocket.close()
      return
    }

    val clientId = UUID.randomUUID().toString()
    val remoteAddress = webSocket.remoteSocketAddress?.toString() ?: "unknown"

    // The Android device is the PUBLISHER — it sends video to the browser.
    // We don't receive anything from the browser, so OfferToReceive is false.
    val mediaConstraints = MediaConstraints().apply {
      mandatory.addAll(
        listOf(
          MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"),
          MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
        )
      )
    }

    val peerConnection = factory.makePeerConnection(
      coroutineScope = scope,
      configuration = factory.rtcConfig,
      type = StreamPeerType.PUBLISHER,
      mediaConstraints = mediaConstraints,
      onIceCandidateRequest = { iceCandidate, _ ->
        sendIceCandidateToClient(webSocket, iceCandidate)
      }
    )

    val session = ClientSession(clientId, webSocket, peerConnection, remoteAddress)
    clients[clientId] = session

    scope.launch { _connectedClients.emit(clients.size) }
    _clientAddresses.value = clients.values.map { it.remoteAddress }
    logger.i { "[onClientConnected] created session $clientId from $remoteAddress, total clients: ${clients.size}" }
  }

  private fun onClientDisconnected(webSocket: WebSocket) {
    val entry = clients.entries.find { it.value.webSocket == webSocket } ?: return

    try {
      entry.value.peerConnection.connection.close()
    } catch (_: Exception) {
      // ignore
    }
    clients.remove(entry.key)

    _clientAddresses.value = clients.values.map { it.remoteAddress }
    scope.launch { _connectedClients.emit(clients.size) }
    logger.i { "[onClientDisconnected] removed session ${entry.key}, total clients: ${clients.size}" }
  }

  // -------- signaling --------

  private fun handleSignalingMessage(webSocket: WebSocket, message: String) {
    val json = try {
      JSONObject(message)
    } catch (e: Exception) {
      logger.e { "[handleSignalingMessage] invalid JSON: ${e.message}" }
      return
    }

    when (val type = json.optString("type", "")) {
      "offer" -> handleOffer(webSocket, json)
      "ice-candidate" -> handleIceCandidate(webSocket, json)
      else -> logger.w { "[handleSignalingMessage] unknown message type: $type" }
    }
  }

  // -------- offer / answer --------

  private fun handleOffer(webSocket: WebSocket, json: JSONObject) {
    val session = findSession(webSocket) ?: return

    val sdpJson = json.optJSONObject("sdp")
    if (sdpJson == null) {
      logger.e { "[handleOffer] missing sdp field" }
      return
    }

    val sdpString = sdpJson.optString("sdp", "")
    if (sdpString.isEmpty()) {
      logger.e { "[handleOffer] empty SDP" }
      return
    }

    logger.i { "[handleOffer] received offer from client ${session.id}" }

    scope.launch {
      try {
        val pc = session.peerConnection

        // Attach local tracks before creating the answer
        val videoTrack = trackProvider?.getVideoTrack()
        val audioTrack = trackProvider?.getAudioTrack()

        if (videoTrack != null) {
          pc.connection.addTrack(videoTrack)
          logger.d { "[handleOffer] added video track to peer connection" }
        } else {
          logger.w { "[handleOffer] video track is null — streaming without video" }
        }

        if (audioTrack != null) {
          pc.connection.addTrack(audioTrack)
          logger.d { "[handleOffer] added audio track to peer connection" }
        }

        // Set the browser's offer as the remote description
        val offerSdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
        pc.setRemoteDescription(offerSdp)

        // Create the answer
        val answer = pc.createAnswer().getOrThrow()

        // Set our answer as the local description
        pc.setLocalDescription(answer)

        // Send the answer back to the browser
        val answerJson = JSONObject().apply {
          put("type", "answer")
          put("sdp", JSONObject().apply {
            put("type", "answer")
            put("sdp", answer.description)
          })
        }
        sendToClient(webSocket, answerJson)
        logger.i { "[handleOffer] sent answer to client ${session.id}" }
      } catch (e: Exception) {
        logger.e { "[handleOffer] failed: ${e.message}" }
      }
    }
  }

  // -------- ICE candidate exchange --------

  private fun handleIceCandidate(webSocket: WebSocket, json: JSONObject) {
    val session = findSession(webSocket) ?: return

    val candidateJson = json.optJSONObject("candidate") ?: return
    val sdp = candidateJson.optString("candidate", "")
    val sdpMid = candidateJson.optString("sdpMid", "")
    val sdpMLineIndex = candidateJson.optInt("sdpMLineIndex", 0)

    // Empty candidate signals end of ICE gathering — no action needed
    if (sdp.isEmpty()) {
      logger.d { "[handleIceCandidate] empty candidate (end of gathering), skipping" }
      return
    }

    logger.d { "[handleIceCandidate] from client ${session.id}: sdpMid=$sdpMid, index=$sdpMLineIndex" }

    scope.launch {
      try {
        session.peerConnection.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
      } catch (e: Exception) {
        logger.e { "[handleIceCandidate] failed: ${e.message}" }
      }
    }
  }

  private fun sendIceCandidateToClient(webSocket: WebSocket, candidate: IceCandidate) {
    // Empty SDP signals end of gathering — no need to send to browser
    if (candidate.sdp.isEmpty()) return

    val message = JSONObject().apply {
      put("type", "ice-candidate")
      put("candidate", JSONObject().apply {
        put("candidate", candidate.sdp)
        put("sdpMid", candidate.sdpMid)
        put("sdpMLineIndex", candidate.sdpMLineIndex)
      })
    }
    sendToClient(webSocket, message)
  }

  // -------- helpers --------

  private fun sendToClient(webSocket: WebSocket, message: JSONObject) {
    if (webSocket.isOpen) {
      webSocket.send(message.toString())
      logger.d { "[sendToClient] ${message.toString().take(200)}" }
    }
  }

  private fun findSession(webSocket: WebSocket): ClientSession? {
    val session = clients.values.find { it.webSocket == webSocket }
    if (session == null) {
      logger.w { "[findSession] no session for WebSocket ${webSocket.remoteSocketAddress}" }
    }
    return session
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
    } catch (_: Exception) {
      // fall through
    }
    return "127.0.0.1"
  }
}

enum class SignalingServerState {
  RUNNING,
  STOPPED
}