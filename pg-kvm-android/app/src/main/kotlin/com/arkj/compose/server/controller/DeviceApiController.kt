package com.arkj.compose.server.controller

import com.arkj.compose.stream.sessions.WebRtcSessionManager
import com.yanzhenjie.andserver.annotation.CrossOrigin
import com.yanzhenjie.andserver.annotation.GetMapping
import com.yanzhenjie.andserver.annotation.PostMapping
import com.yanzhenjie.andserver.annotation.RequestParam
import com.yanzhenjie.andserver.annotation.RestController
import org.json.JSONArray
import org.json.JSONObject
import kotlin.collections.forEach

/**
 * 设备状态与控制 REST API 控制器，供 Web 前端侧边卡片调用。
 *
 * 与 app UI 功能一致：摄像头切换、蓝牙连接、设备信息、串流信息。
 *
 * 由于 AndServer 通过反射创建 Controller 实例，
 * 使用 companion object 静态持有 SessionManager（在 onSessionScreenReady 中注入）。
 *
 * 返回值为手工构造的 JSON 字符串（项目未注册 MessageConverter，
 * 直接返回对象会退化为 toString()）。
 */
@CrossOrigin(origins = ["*"])
@RestController
class DeviceApiController {

  companion object {
    /** 由 WebRtcSessionManagerImpl 在会话初始化时注入 */
    var sessionManager: WebRtcSessionManager? = null
  }

  // ==================== 查询 ====================

  /**
   * GET /api/status
   * 聚合状态：摄像头列表、当前摄像头、串流信息、BLE 状态。前端轮询此接口刷新侧边卡片。
   */
  @GetMapping("/api/status", produces = ["application/json"])
  fun getStatus(): String {
    val sm = sessionManager ?: return error("session not ready").toString()

    val cameras = JSONArray().apply {
      sm.cameraList.value.forEach { camera ->
        put(JSONObject().apply {
          put("id", camera.cameraId)
          put("name", camera.displayName)
          put("current", camera.cameraId == sm.currentCamera.value?.cameraId)
        })
      }
    }

    val stream = sm.streamInfo.value
    val streamJson = JSONObject().apply {
      put("resolution", stream.resolution)
      put("resolutionWidth", stream.resolutionWidth)
      put("resolutionHeight", stream.resolutionHeight)
      put("frameRate", stream.frameRate)
      put("codec", stream.codec)
      put("isStreaming", stream.isStreaming)
      put("serverUrl", stream.serverUrl)
      put("clientAddresses", JSONArray(stream.clientAddresses))
    }

    val ble = JSONObject().apply {
      put("status", sm.bleStatus.value)
      put("isReady", sm.isBleReady.value)
      put("isScanning", sm.isBleScanning.value)
      val connectedName = sm.connectedBleDeviceName.value
      put("connectedDeviceName", connectedName ?: JSONObject.NULL)
      put("inputClientCount", sm.inputClientCount.value)
    }

    return JSONObject().apply {
      put("cameras", cameras)
      val currentName = sm.currentCamera.value?.displayName
      put("currentCameraName", currentName ?: JSONObject.NULL)
      put("stream", streamJson)
      put("ble", ble)
    }.toString()
  }

  /**
   * GET /api/resolutions
   * 当前摄像头支持的分辨率列表。
   */
  @GetMapping("/api/resolutions", produces = ["application/json"])
  fun getResolutions(): String {
    val sm = sessionManager ?: return error("session not ready").toString()
    return JSONObject().apply {
      put("resolutions", JSONArray().apply {
        sm.getSupportedResolutions().forEach { r ->
          put(JSONObject().apply {
            put("width", r.width)
            put("height", r.height)
            put("fps", r.fps)
          })
        }
      })
    }.toString()
  }

  /**
   * GET /api/ble/devices
   * BLE 扫描结果（配合 /api/ble/scan/start 轮询）。
   */
  @GetMapping("/api/ble/devices", produces = ["application/json"])
  fun getBleDevices(): String {
    val sm = sessionManager ?: return error("session not ready").toString()
    return JSONObject().apply {
      put("isScanning", sm.isBleScanning.value)
      put("devices", JSONArray().apply {
        sm.scannedBleDevices.value.forEach { d ->
          put(JSONObject().apply {
            put("name", d.name)
            put("address", d.address)
            put("rssi", d.rssi)
          })
        }
      })
    }.toString()
  }

  // ==================== 控制 ====================

  /**
   * POST /api/camera/switch?cameraId=xxx
   * 切换摄像头。
   */
  @PostMapping("/api/camera/switch", produces = ["application/json"])
  fun switchCamera(@RequestParam("cameraId") cameraId: String): String {
    val sm = sessionManager ?: return error("session not ready").toString()
    return try {
      sm.switchCamera(cameraId)
      ok()
    } catch (e: Exception) {
      error(e.message ?: "switch failed").toString()
    }
  }

  /**
   * POST /api/resolution/switch?width=1280&height=720&fps=30
   * 切换当前摄像头采集分辨率。
   */
  @PostMapping("/api/resolution/switch", produces = ["application/json"])
  fun switchResolution(
    @RequestParam("width") width: Int,
    @RequestParam("height") height: Int,
    @RequestParam("fps", defaultValue = "30") fps: Int
  ): String {
    val sm = sessionManager ?: return error("session not ready").toString()
    return try {
      sm.switchResolution(width, height, fps)
      ok()
    } catch (e: Exception) {
      error(e.message ?: "switch failed").toString()
    }
  }

  /**
   * POST /api/ble/scan/start
   * 开始 BLE 扫描。
   */
  @PostMapping("/api/ble/scan/start", produces = ["application/json"])
  fun startBleScan(): String {
    val sm = sessionManager ?: return error("session not ready").toString()
    sm.startBleScan()
    return ok()
  }

  /**
   * POST /api/ble/scan/stop
   * 停止 BLE 扫描。
   */
  @PostMapping("/api/ble/scan/stop", produces = ["application/json"])
  fun stopBleScan(): String {
    val sm = sessionManager ?: return error("session not ready").toString()
    sm.stopBleScan()
    return ok()
  }

  /**
   * POST /api/ble/connect?address=xx:xx:xx:xx:xx:xx
   * 连接 BLE 设备（与当前活跃摄像头配对）。
   */
  @PostMapping("/api/ble/connect", produces = ["application/json"])
  fun connectBle(@RequestParam("address") address: String): String {
    val sm = sessionManager ?: return error("session not ready").toString()
    sm.connectBleDeviceByAddress(address)
    return ok()
  }

  /**
   * POST /api/ble/disconnect
   * 断开 BLE 连接并清除当前摄像头的配对记录。
   */
  @PostMapping("/api/ble/disconnect", produces = ["application/json"])
  fun disconnectBle(): String {
    val sm = sessionManager ?: return error("session not ready").toString()
    sm.disconnectBle()
    return ok()
  }

  // ==================== 响应构造 ====================

  private fun ok(): String = JSONObject().put("ok", true).toString()

  private fun error(message: String): JSONObject = JSONObject().apply {
    put("ok", false)
    put("message", message)
  }
}
