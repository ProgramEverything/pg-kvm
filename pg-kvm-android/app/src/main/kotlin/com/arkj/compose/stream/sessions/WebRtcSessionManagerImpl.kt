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

package com.arkj.compose.stream.sessions

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.view.Surface
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.arkj.compose.models.CameraDeviceInfo
import com.arkj.compose.models.ResolutionInfo
import com.arkj.compose.models.StreamInfo
import com.arkj.compose.server.WebStreamPageServer
import com.arkj.compose.server.controller.DeviceApiController
import com.arkj.compose.stream.SignalingServer
import com.arkj.compose.stream.input.BleConnection
import com.arkj.compose.stream.input.BleHelper
import com.arkj.compose.stream.input.BlePairingStore
import com.arkj.compose.stream.input.InputRelayServer
import com.arkj.compose.stream.peer.StreamPeerConnectionFactory
import com.jiangdg.usb.USBMonitor
import io.getstream.log.taggedLogger
import io.getstream.webrtc.sample.compose.R
import com.arkj.video.UvcCameraHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val LocalWebRtcSessionManager: ProvidableCompositionLocal<WebRtcSessionManager> =
  staticCompositionLocalOf { error("WebRtcSessionManager was not initialized!") }

/**
 * SessionManager：统一维护全局唯一对象和每摄像头会话。
 *
 * 全局唯一：UvcCameraHelper（USBMonitor）、BleHelper（BluetoothAdapter/扫描）、
 *          BlePairingStore（配对持久化）、InputRelayServer、WebRTC 管线。
 * 每摄像头一个：CameraSession（capturer + 配对 BLE 连接），同一时刻仅一个活跃会话持有资源。
 *
 * BLE 配对语义：每个摄像头对应 0..1 个 ESP32；切换摄像头时断开旧连接，
 * 若新摄像头有持久化的配对记录则自动回连。
 */
class WebRtcSessionManagerImpl(
  private val context: Context,
  override val peerConnectionFactory: StreamPeerConnectionFactory,
  override val signalingServer: SignalingServer
) : WebRtcSessionManager {
  private val logger by taggedLogger("Call:LocalWebRtcSessionManager")

  private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    /** BLE 意外断开后的自动重连间隔与最大尝试次数 */
    private const val BLE_RECONNECT_DELAY_MS = 2000L
    private const val BLE_MAX_RECONNECT_ATTEMPTS = 3
    private const val INPUT_RELAY_SERVER_PORT = 3001
  }

  // ===== 全局唯一对象 =====

  private val bleHelper = BleHelper(context)
  private val pairingStore = BlePairingStore(context)
  private val relayServer = InputRelayServer { activeSession()?.bleConnection }

  private val uvcCameraHelper by lazy {
    UvcCameraHelper(context).also(::setupUsbListeners)
  }

  // ===== 每摄像头会话 =====

  /** cameraId -> 会话。非活跃会话处于 dormant（已释放资源），仅保留标识 */
  private val sessions = ConcurrentHashMap<String, CameraSession>()

  /** 当前活跃（UI 显示）的摄像头 ID */
  private var activeCameraId: String? = null

  private fun activeSession(): CameraSession? = activeCameraId?.let { sessions[it] }

  // ===== 全局 WebRTC 管线（单一 videoTrack，切换摄像头时热替换 capturer） =====

  // used to send local video track to the fragment
  private val _videoTrack = MutableStateFlow<VideoTrack?>(null)
  override val videoTrack: StateFlow<VideoTrack?> = _videoTrack

  private val _cameraList = MutableStateFlow<List<CameraDeviceInfo>>(emptyList())
  override val cameraList: StateFlow<List<CameraDeviceInfo>> = _cameraList

  private val _currentCamera = MutableStateFlow<CameraDeviceInfo?>(null)
  override val currentCamera: StateFlow<CameraDeviceInfo?> = _currentCamera

  private val _streamInfo = MutableStateFlow(StreamInfo())
  override val streamInfo: StateFlow<StreamInfo> = _streamInfo

  private val _isCapturing = MutableStateFlow(false)
  override val isCapturing: StateFlow<Boolean> = _isCapturing

  private val _isConnected = MutableStateFlow(false)
  override val isConnected: StateFlow<Boolean> = _isConnected

  private val _isPreviewing = MutableStateFlow(true)  // 默认开启预览
  override val isPreviewing: StateFlow<Boolean> = _isPreviewing

  // declaring video constraints and setting OfferToReceiveVideo to true
  // this step is mandatory to create valid offer and answer
  private val mediaConstraints = MediaConstraints().apply {
    mandatory.addAll(
      listOf(
        MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"),
        MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
      )
    )
  }

  private var surfaceTextureHelper: SurfaceTextureHelper? = null
  private var videoSource: VideoSource? = null

  override val connectedClient: MutableStateFlow<List<UUID>> = MutableStateFlow(emptyList())

  /** 串流是否正在运行 */
  private var isStreamServerRunning = false

  /** 保存上次的预览 Surface，切换摄像头后由新会话复用 */
  private var lastPreviewSurface: Surface? = null

  // ===== BLE 状态（对外 = 活跃摄像头的连接状态；扫描为全局状态） =====

  private val _bleStatus = MutableStateFlow(context.getString(R.string.ble_status_disconnected))
  override val bleStatus: StateFlow<String> = _bleStatus

  private val _isBleReady = MutableStateFlow(false)
  override val isBleReady: StateFlow<Boolean> = _isBleReady

  override val isBleScanning: StateFlow<Boolean> = bleHelper.isScanning

  override val scannedBleDevices: StateFlow<List<BleHelper.ScannedDevice>> = bleHelper.scanResults

  private val _connectedBleDeviceName = MutableStateFlow<String?>(null)
  override val connectedBleDeviceName: StateFlow<String?> = _connectedBleDeviceName

  override val inputClientCount: StateFlow<Int> = relayServer.clientCount

  /** 活跃会话 BLE 状态的镜像收集任务 */
  private var bleStateMirrorJob: Job? = null

  // ==================== 生命周期 ====================

  override fun onSessionScreenReady() {
    val source = peerConnectionFactory.makeVideoSource(false)
    videoSource = source
    val track = peerConnectionFactory.makeVideoTrack(
      source = source,
      trackId = "Video${UUID.randomUUID()}"
    )
    _videoTrack.value = track

    surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", peerConnectionFactory.eglBaseContext)

    // 获取已连接的 USB 摄像头列表（同时触发 uvcCameraHelper 初始化和插拔监听）
    val cameraList = uvcCameraHelper.cameraList
    _cameraList.value = cameraList

    if (cameraList.isNotEmpty()) {
      activateCamera(cameraList.first().cameraId)
    } else {
      // 无设备，等待插入（通过设备列表变更回调自动激活）
      logger.i { "[onSessionScreenReady] no USB camera detected, waiting for device attach" }
    }

    // 设置 signaling server 的 track provider
    signalingServer.peerConnectionFactory = peerConnectionFactory
    signalingServer.trackProvider = object : SignalingServer.TrackProvider {
      override fun getVideoTrack(): VideoTrack? = _videoTrack.value
      override fun getAudioTrack(): AudioTrack? = null
    }

    // 注入 Web API 控制器（供前端侧边卡片查询/控制）
    DeviceApiController.Companion.sessionManager = this

    startStreamServer()
  }

  override fun switchCamera(cameraId: String) {
    if (cameraId == activeCameraId) {
      logger.i { "[switchCamera] camera $cameraId already active" }
      return
    }
    activateCamera(cameraId)
  }

  override fun getSupportedResolutions(): List<ResolutionInfo> {
    val resolutions = activeSession()?.supportedResolutions() ?: emptyList()
    if (resolutions.isEmpty()) {
      logger.w { "[getSupportedResolutions] no resolutions (camera not opened?)" }
    }
    return resolutions
  }

  override fun switchResolution(width: Int, height: Int, fps: Int) {
    val session = activeSession() ?: run {
      logger.w { "[switchResolution] no active camera" }
      return
    }
    logger.i { "[switchResolution] ${session.cameraId}: ${width}x${height} @ $fps" }
    session.changeResolution(width, height, fps)
  }

  override fun release() {
    // 停止输入中继服务器
    relayServer.stop()

    // 停止串流
    if (isStreamServerRunning) {
      stopStreamServer()
    }

    // 释放所有会话（采集器 + BLE 连接）
    stopBleStateMirror()
    sessions.values.forEach { it.release() }
    sessions.clear()
    activeCameraId = null

    // dispose audio & video tracks
    _videoTrack.value?.dispose()
    _videoTrack.value = null

    bleHelper.release()
    uvcCameraHelper.release()
    videoSource?.dispose()
    videoSource = null

    // dispose surface texture helper
    surfaceTextureHelper?.let {
      try { it.stopListening() } catch (_: Exception) {}
      try { it.dispose() } catch (_: Exception) {}
    }
    surfaceTextureHelper = null
  }

  // ==================== 预览 / 串流开关 ====================

  override fun enablePreview(surface: Surface) {
    logger.i { "[enablePreview] isConnected=${_isConnected.value}, isCapturing=${_isCapturing.value}" }
    lastPreviewSurface = surface
    activeSession()?.let { session ->
      if (!_isCapturing.value) {
        session.resumeCapture()
        _isCapturing.value = true
      }
      session.setPreviewSurface(surface)
    }
    _isPreviewing.value = true
  }

  override fun disablePreview() {
    logger.i { "[disablePreview]" }
    lastPreviewSurface = null
    activeSession()?.let { session ->
      session.setPreviewSurface(null)
      if (_isCapturing.value && !_streamInfo.value.isStreaming) {
        session.pauseCapture()
        _isCapturing.value = false
      }
    }
    _isPreviewing.value = false
  }

  override fun enableStreaming() {
    activeSession()?.let { session ->
      if (!_isCapturing.value) {
        session.resumeCapture()
        _isCapturing.value = true
      }
    }
    _videoTrack.value?.setEnabled(true)
    _streamInfo.value = _streamInfo.value.copy(
      isStreaming = true
    )
  }

  override fun disableStreaming() {
    _videoTrack.value?.setEnabled(false)
    _streamInfo.value = _streamInfo.value.copy(
      isStreaming = false
    )
    if (_isCapturing.value && !_isPreviewing.value) {
      activeSession()?.pauseCapture()
      _isCapturing.value = false
    }
  }

  // ==================== BLE 键鼠控制（语义：与当前活跃摄像头配对） ====================

  override fun startBleScan() {
    bleHelper.startScan()
  }

  override fun stopBleScan() {
    bleHelper.stopScan()
  }

  override fun connectBleDevice(device: BluetoothDevice) {
    val cameraId = activeCameraId ?: run {
      logger.w { "[connectBleDevice] no active camera, ignoring" }
      return
    }
    bleHelper.stopScan()
    // 持久化配对关系并连接
    pairingStore.setPairing(cameraId, device.address, device.name)
    attachBleToSession(sessions[cameraId] ?: return, device)
  }

  override fun connectBleDeviceByAddress(address: String) {
    val device = bleHelper.getRemoteDevice(address) ?: run {
      logger.w { "[connectBleDeviceByAddress] invalid address: $address" }
      return
    }
    connectBleDevice(device)
  }

  override fun disconnectBle() {
    val cameraId = activeCameraId ?: return
    pairingStore.removePairing(cameraId)
    activeSession()?.releaseBle()
    stopBleStateMirror()
  }

  // ==================== 串流服务器 ====================

  override fun startStreamServer() {
    if (isStreamServerRunning) {
      logger.w { "[startStreaming] already streaming" }
      return
    }

    try {
      // 启动 HTTP 服务器
      val httpUrl = WebStreamPageServer.start(context = context, port = 8080)
      logger.i { "[startStreaming] HTTP server started at $httpUrl" }

      // 启动 WebSocket 信令服务器
      startSignalingServer()

      // 启动输入中继服务器 (端口 3001)
      relayServer.start(INPUT_RELAY_SERVER_PORT)
      logger.i { "[startStreaming] Input relay server started" }

      isStreamServerRunning = true
      _streamInfo.value = _streamInfo.value.copy(
        isStreaming = true,
        serverUrl = httpUrl,
        codec = "VP8"  // 默认编码格式
      )
    } catch (e: Exception) {
      logger.e { "[startStreaming] failed: ${e.message}" }
      // 回滚
      try { WebStreamPageServer.stop() } catch (_: Exception) {}
      try { signalingServer.stop() } catch (_: Exception) {}
      throw e
    }
  }

  override fun stopStreamServer() {
    if (!isStreamServerRunning) {
      logger.w { "[stopStreaming] not streaming" }
      return
    }

    try {
      signalingServer.stop()
      logger.i { "[stopStreaming] signaling server stopped" }
    } catch (e: Exception) {
      logger.e { "[stopStreaming] signaling server stop failed: ${e.message}" }
    }

    isStreamServerRunning = false
    _streamInfo.value = _streamInfo.value.copy(
      isStreaming = false,
      serverUrl = "",
      codec = "N/A",
      clientAddresses = emptyList()
    )
  }

  // ==================== 会话管理（核心） ====================

  /**
   * 激活指定摄像头：
   * 1. 释放旧活跃会话的资源（采集器 + BLE 连接，配对记录保留）
   * 2. 向 UvcCameraHelper 申请 UsbControlBlock（异步权限流程）
   * 3. 若有持久化配对记录，自动回连对应 ESP32
   */
  private fun activateCamera(cameraId: String) {
    // 释放旧的活跃会话
    if (activeCameraId != null && activeCameraId != cameraId) {
      deactivateCurrentSession()
    }

    val camera = _cameraList.value.find { it.cameraId == cameraId }
    val session = sessions.getOrPut(cameraId) {
      CameraSession(cameraId, camera?.displayName ?: cameraId)
    }
    session.onCaptureStarted = ::onCaptureStarted
    // 复用 UI 已创建的预览 Surface（切换摄像头时 UI 不会重新回调 surfaceCreated）
    session.pendingPreviewSurface = lastPreviewSurface

    activeCameraId = cameraId
    _currentCamera.value = camera ?: CameraDeviceInfo(cameraId, cameraId, false)

    // 等待中的采集启动显示为 N/A，实际值在 onCaptureStarted 更新
    _streamInfo.value = _streamInfo.value.copy(
      resolution = "N/A",
      resolutionWidth = 0,
      resolutionHeight = 0,
      frameRate = 0
    )
    _isConnected.value = false
    _isCapturing.value = false

    // 申请 USB 权限并打开设备
    uvcCameraHelper.openControlBlock(cameraId, object : UvcCameraHelper.OnControlBlockListener {
      override fun onGranted(id: String, ctrlBlock: USBMonitor.UsbControlBlock) {
        val sth = surfaceTextureHelper
        val source = videoSource
        val current = sessions[id]
        if (id != activeCameraId || current == null || sth == null || source == null) {
          // 申请期间已切换到其他摄像头，释放多余的控制块
          logger.w { "[activateCamera] camera $id no longer active, closing ctrlBlock" }
          try { ctrlBlock.close() } catch (_: Exception) {}
          return
        }
        current.startCapturer(context.applicationContext, ctrlBlock, sth, source)
      }

      override fun onDenied(id: String) {
        logger.e { "[activateCamera] permission denied or device missing for camera $id" }
      }
    })

    // 自动回连该摄像头配对的 ESP32
    autoReconnectPairedBle(session)
  }

  /** 释放当前活跃会话的资源（会话对象保留在表中，处于 dormant） */
  private fun deactivateCurrentSession() {
    val id = activeCameraId ?: return
    val session = sessions[id] ?: return
    logger.i { "[deactivateCurrentSession] releasing session for $id" }
    session.release()
    _isCapturing.value = false
    _isConnected.value = false
    stopBleStateMirror()
  }

  /** 采集启动回调：更新分辨率/帧率和采集状态 */
  private fun onCaptureStarted(width: Int, height: Int, fps: Int) {
    _streamInfo.value = _streamInfo.value.copy(
      resolution = "${width}x${height}",
      resolutionWidth = width,
      resolutionHeight = height,
      frameRate = fps
    )
    _isCapturing.value = true
    _isConnected.value = true
  }

  // ==================== BLE 会话管理 ====================

  /** 为会话绑定并连接 BLE 设备（同时更新对外状态镜像） */
  private fun attachBleToSession(session: CameraSession, device: BluetoothDevice) {
    val connection = bleHelper.createConnection(device)
    connection.onUnexpectedDisconnect = { onBleUnexpectedlyDisconnected(session) }
    session.attachBle(connection)
    if (session.cameraId == activeCameraId) {
      startBleStateMirror(connection, session)
    }
  }

  /** 若该摄像头有持久化配对记录，自动回连 */
  private fun autoReconnectPairedBle(session: CameraSession) {
    val address = pairingStore.getPairedAddress(session.cameraId) ?: return
    val device = bleHelper.getRemoteDevice(address) ?: run {
      logger.w { "[autoReconnectPairedBle] invalid paired address $address for ${session.cameraId}" }
      return
    }
    logger.i { "[autoReconnectPairedBle] reconnecting $address for camera ${session.cameraId}" }
    session.bleReconnectAttempts = 0
    attachBleToSession(session, device)
  }

  /** BLE 意外断开：若仍是活跃会话且有配对记录，有限次自动重连 */
  private fun onBleUnexpectedlyDisconnected(session: CameraSession) {
    if (session.cameraId != activeCameraId) return
    // 清理已死连接，为重连腾出位置（保留配对记录）
    session.releaseBle()

    val address = pairingStore.getPairedAddress(session.cameraId) ?: return

    if (session.bleReconnectAttempts >= BLE_MAX_RECONNECT_ATTEMPTS) {
      logger.w { "[onBleUnexpectedlyDisconnected] max reconnect attempts reached for ${session.cameraId}" }
      return
    }
    session.bleReconnectAttempts++

    sessionManagerScope.launch {
      delay(BLE_RECONNECT_DELAY_MS)
      // 延迟期间可能已切换摄像头或手动重新连接
      if (session.cameraId != activeCameraId || session.bleConnection != null) return@launch
      val device = bleHelper.getRemoteDevice(address) ?: return@launch
      logger.i { "[onBleUnexpectedlyDisconnected] retry ${session.bleReconnectAttempts}/$BLE_MAX_RECONNECT_ATTEMPTS for $address" }
      attachBleToSession(session, device)
    }
  }

  /** 将活跃会话的 BLE 连接状态镜像到对外 StateFlow */
  private fun startBleStateMirror(connection: BleConnection, session: CameraSession) {
    stopBleStateMirror()
    bleStateMirrorJob = sessionManagerScope.launch {
      launch {
        connection.statusText.collect { _bleStatus.value = it }
      }
      launch {
        connection.isReady.collect { ready ->
          if (ready) session.bleReconnectAttempts = 0
          _isBleReady.value = ready
        }
      }
      launch {
        connection.connectedDeviceName.collect { _connectedBleDeviceName.value = it }
      }
    }
  }

  private fun stopBleStateMirror() {
    bleStateMirrorJob?.cancel()
    bleStateMirrorJob = null
    _bleStatus.value = context.getString(R.string.ble_status_disconnected)
    _isBleReady.value = false
    _connectedBleDeviceName.value = null
  }

  // ==================== USB 插拔处理 ====================

  private fun setupUsbListeners(helper: UvcCameraHelper) {
    helper.setOnDeviceListChangedListener { cameraList ->
      _cameraList.value = cameraList
      // 清理已拔出摄像头的 dormant 会话（配对记录保留在 PairingStore）
      sessions.keys.retainAll { id -> cameraList.any { it.cameraId == id } }
      // 如果当前没有活跃摄像头且新插入设备，自动激活第一个
      if (activeCameraId == null && cameraList.isNotEmpty()) {
        activateCamera(cameraList.first().cameraId)
      }
    }
    helper.setOnCameraDetachedListener { cameraId ->
      logger.w { "[onCameraDetached] active camera detached: $cameraId" }
      sessions.remove(cameraId)?.release()
      if (cameraId == activeCameraId) {
        activeCameraId = null
        _currentCamera.value = null
        _isCapturing.value = false
        _isConnected.value = false
        _streamInfo.value = _streamInfo.value.copy(
          resolution = "N/A",
          resolutionWidth = 0,
          resolutionHeight = 0,
          frameRate = 0
        )
        stopBleStateMirror()
        // 自动切换到剩余的第一个摄像头
        val remaining = _cameraList.value.filter { it.cameraId != cameraId }
        if (remaining.isNotEmpty()) {
          activateCamera(remaining.first().cameraId)
        }
      }
    }
  }

  // ==================== 私有辅助 ====================

  /**
   * 启动信令服务器并开始监听客户端连接
   */
  private fun startSignalingServer() {
    val wsUrl = signalingServer.start()
    logger.i { "[startSignalingServer] signaling server started at $wsUrl" }

    // 观察客户端地址变化
    sessionManagerScope.launch {
      signalingServer.clientAddresses.collect { addresses ->
        _streamInfo.value = _streamInfo.value.copy(clientAddresses = addresses)
      }
    }
  }

  private fun buildAudioConstraints(): MediaConstraints {
    val mediaConstraints = MediaConstraints()
    val items = listOf(
      MediaConstraints.KeyValuePair("googEchoCancellation", true.toString()),
      MediaConstraints.KeyValuePair("googAutoGainControl", true.toString()),
      MediaConstraints.KeyValuePair("googHighpassFilter", true.toString()),
      MediaConstraints.KeyValuePair("googNoiseSuppression", true.toString()),
      MediaConstraints.KeyValuePair("googTypingNoiseDetection", true.toString())
    )

    return mediaConstraints.apply {
      with(optional) {
        add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        addAll(items)
      }
    }
  }
}
