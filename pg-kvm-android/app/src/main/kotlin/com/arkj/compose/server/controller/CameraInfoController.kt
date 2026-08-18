package com.arkj.compose.server.controller

import com.yanzhenjie.andserver.annotation.CrossOrigin
import com.yanzhenjie.andserver.annotation.GetMapping
import com.yanzhenjie.andserver.annotation.RestController

/**
 * 摄像头信息 REST API 控制器
 *
 * 提供摄像头状态查询接口，供前端页面调用。
 * 由于 AndServer 通过反射创建 Controller 实例，
 * 使用 companion object 静态持有数据提供者。
 *
 * @author Created by claude on 2026/8/5
 */
@CrossOrigin(origins = ["*"])
@RestController
class CameraInfoController {

  companion object {
    /**
     * 摄像头信息数据提供者
     * 在 DemoFragment 中设置，由 AndServer 控制器调用
     */
    var cameraInfoProvider: (() -> CameraInfo)? = null
  }

  /**
   * GET /api/camera/info
   * 获取当前摄像头状态信息
   */
  @GetMapping("/api/camera/info")
  fun getCameraInfo(): CameraInfo {
    return cameraInfoProvider?.invoke() ?: CameraInfo()
  }

  /**
   * 摄像头信息数据类
   */
  data class CameraInfo(
    val status: String = "unknown",
    val resolution: String = "N/A",
    val frameRate: Int = 0,
    val brightness: Int = 0,
    val serverIp: String = "",
    val serverPort: Int = 0,
    val deviceModel: String = android.os.Build.MODEL,
    val deviceManufacturer: String = android.os.Build.MANUFACTURER
  )
}