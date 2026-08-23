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
import android.view.Surface
import com.arkj.compose.models.CameraDeviceInfo
import com.arkj.compose.models.ResolutionInfo
import com.arkj.compose.models.StreamInfo
import com.arkj.compose.stream.SignalingServer
import com.arkj.compose.stream.input.BleHelper
import com.arkj.compose.stream.peer.StreamPeerConnectionFactory
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.VideoTrack
import java.util.UUID

/** 串流相关的三个后台服务 */
enum class StreamService { HTTP, SIGNALING, RELAY }

/** 三个服务的监听端口配置 */
data class ServerPorts(
  val httpPort: Int,
  val signalingPort: Int,
  val relayPort: Int
)

/** 服务启动/重启结果事件（供 UI 弹 Toast） */
data class ServerStartEvent(
  /** 启动或重启失败的服务，空列表表示全部成功 */
  val failures: List<StreamService>,
  /** true 表示这是一次重启（成功时也提示） */
  val isRestart: Boolean
)

interface WebRtcSessionManager {

  val signalingServer: SignalingServer

  val peerConnectionFactory: StreamPeerConnectionFactory

  val videoTrack: StateFlow<VideoTrack?>

  val connectedClient: StateFlow<List<UUID>>

  /** 设备上所有可用摄像头列表 */
  val cameraList: StateFlow<List<CameraDeviceInfo>>

  /** 当前选中的摄像头 */
  val currentCamera: StateFlow<CameraDeviceInfo?>

  /** 串流状态信息 */
  val streamInfo: StateFlow<StreamInfo>

  /** 采集循环是否正在运行（preview 线程） */
  val isCapturing: StateFlow<Boolean>

  /** 摄像头是否已连接（USB 连接已建立） */
  val isConnected: StateFlow<Boolean>

  /** 预览 Surface 是否正在显示画面 */
  val isPreviewing: StateFlow<Boolean>

  // ===== BLE 键鼠控制 =====

  /** BLE 连接状态文本 */
  val bleStatus: StateFlow<String>

  /** BLE 是否已就绪（已连接且可发送指令） */
  val isBleReady: StateFlow<Boolean>

  /** BLE 是否正在扫描设备 */
  val isBleScanning: StateFlow<Boolean>

  /** 扫描到的 BLE 设备列表 */
  val scannedBleDevices: StateFlow<List<BleHelper.ScannedDevice>>

  /** 已连接的 BLE 设备名称，未连接时为 null */
  val connectedBleDeviceName: StateFlow<String?>

  /** 已连接的输入客户端数量 */
  val inputClientCount: StateFlow<Int>

  //  val remoteVideoTrackFlow: SharedFlow<VideoTrack>

  fun onSessionScreenReady()

  /** 切换到指定摄像头，如果正在串流会先断开所有客户端 */
  fun switchCamera(cameraId: String)

  /** 当前摄像头支持的分辨率列表（摄像头未打开时为空） */
  fun getSupportedResolutions(): List<ResolutionInfo>

  /** 切换当前摄像头的采集分辨率 */
  fun switchResolution(width: Int, height: Int, fps: Int)

  /** 启动串流服务 (HTTP 服务器 + WebSocket 信令服务器 + 输入中继服务器)；失败通过 [serverStartEvents] 报告 */
  fun startStreamServer()

  /** 停止串流服务 */
  fun stopStreamServer()

  /** 重启全部三个服务；失败通过 [serverStartEvents] 报告 */
  fun restartStreamServer()

  /** 当前端口配置 */
  fun getServerPorts(): ServerPorts

  /**
   * 更新端口配置（持久化）。若服务正在运行会自动用新端口重启，
   * 失败通过 [serverStartEvents] 报告。
   */
  fun updateServerPorts(httpPort: Int, signalingPort: Int, relayPort: Int)

  /** 服务启动/重启结果事件流（供 UI 弹 Toast） */
  val serverStartEvents: SharedFlow<ServerStartEvent>

  /** 开启预览（根据当前 isCapturing/isConnected 状态自动处理重连/重启） */
  fun enablePreview(surface: Surface)

  /** 关闭预览 */
  fun disablePreview()

  fun enableStreaming()

  fun disableStreaming()

  // ===== BLE 键鼠控制 =====

  /** 开始扫描 BLE 设备 */
  fun startBleScan()

  /** 停止扫描 BLE 设备 */
  fun stopBleScan()

  /** 连接到指定的 BLE 设备 */
  fun connectBleDevice(device: BluetoothDevice)

  /** 通过 BLE 地址连接设备（供 Web API 调用，与当前活跃摄像头配对） */
  fun connectBleDeviceByAddress(address: String)

  /** 断开当前 BLE 连接 */
  fun disconnectBle()

  fun release()
}