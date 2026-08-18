package com.arkj.compose.models

/**
 * 摄像头设备信息
 *
 * @param cameraId Camera2Enumerator 使用的设备名称 (如 "0", "1")
 * @param displayName 人类可读的显示名称 (如 "前置摄像头", "后置摄像头")
 * @param isFrontFacing 是否为前置摄像头
 */
data class CameraDeviceInfo(
  val cameraId: String,
  val displayName: String,
  val isFrontFacing: Boolean
)

/**
 * 摄像头支持的分辨率选项
 *
 * @param width 宽（像素）
 * @param height 高（像素）
 * @param fps 帧率
 */
data class ResolutionInfo(
  val width: Int,
  val height: Int,
  val fps: Int
) {
  val key: String get() = "${width}x${height}"
}

/**
 * 串流状态信息
 *
 * @param resolution 当前分辨率 (如 "720x480")
 * @param resolutionWidth 当前分辨率宽（0 表示未知，用于列表匹配当前项）
 * @param resolutionHeight 当前分辨率高
 * @param frameRate 当前帧率
 * @param codec 编码格式 (如 "VP8", "H264")
 * @param clientAddresses 已连接客户端地址列表
 * @param isStreaming 是否正在串流
 * @param serverUrl 服务器访问地址
 */
data class StreamInfo(
  val resolution: String = "N/A",
  val resolutionWidth: Int = 0,
  val resolutionHeight: Int = 0,
  val frameRate: Int = 0,
  val codec: String = "N/A",
  val clientAddresses: List<String> = emptyList(),
  val isStreaming: Boolean = false,
  val serverUrl: String = ""
)