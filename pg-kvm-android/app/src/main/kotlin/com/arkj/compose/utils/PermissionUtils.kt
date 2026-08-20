package com.arkj.compose.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import io.getstream.webrtc.sample.compose.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 权限申请工具类，统一管理所有运行时权限的检查和请求。
 *
 * 使用 Activity.requestPermissions() 而非 Compose launcher。
 */
object PermissionUtils {

    private val _isCameraPermissionGranted = MutableStateFlow(false)
    val isCameraPermissionGranted = _isCameraPermissionGranted.asStateFlow()

    /** 摄像头 */
    val CAMERA_PERMISSIONS = Manifest.permission.CAMERA

    private val _isBTPermissionGranted = MutableStateFlow(false)
    val isBTPermissionGranted = _isBTPermissionGranted.asStateFlow()

  var requestCameraPermissionLauncher: ActivityResultLauncher<String>? = null
  var requestBTPermissionLauncher: ActivityResultLauncher<Array<String>>? = null

    /** 蓝牙权限（根据 SDK 版本自动选择） */
    val BLE_PERMISSIONS: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    fun init(context: Context) {
      _isCameraPermissionGranted.value = arePermissionsGranted(context, Array(1){CAMERA_PERMISSIONS})
      _isBTPermissionGranted.value = arePermissionsGranted(context, Array(BLE_PERMISSIONS.size){BLE_PERMISSIONS[it]})
      requestCameraPermissionLauncher = (context as ComponentActivity).registerForActivityResult(ActivityResultContracts.RequestPermission(),
        object : ActivityResultCallback<Boolean> {
          override fun onActivityResult(isGranted: Boolean) {
            _isCameraPermissionGranted.value = isGranted
          }
        })
      requestBTPermissionLauncher = (context as ComponentActivity).registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions(),
        object : ActivityResultCallback<Map<String, Boolean>> {
          override fun onActivityResult(result: Map<String, Boolean>) {
            _isBTPermissionGranted.value = result.values.all { it }
          }
        })
    }

    /**
     * 检查给定权限是否已全部授予。
     */
    fun arePermissionsGranted(context: Context, permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 统一权限请求方法，调用 Activity.requestPermissions()。
     *
     * @param activity 宿主 Activity
     * @param permissions 需要请求的权限列表
     * @param requestCode 请求码，用于 onRequestPermissionsResult 中区分
     */
    fun requestCameraPermission(activity: ComponentActivity) {
        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
          Handler(Looper.getMainLooper()).post {
            Toast.makeText(activity, activity.getString(R.string.need_camera_permission), Toast.LENGTH_SHORT).show()
          }
          return
        }
        requestCameraPermissionLauncher?.launch(Manifest.permission.CAMERA)
    }

  fun requestBTPermission(activity: ComponentActivity) {
    var shouldShowRationale = false
    for (permission in BLE_PERMISSIONS) {
      if (activity.shouldShowRequestPermissionRationale(permission)) {
        shouldShowRationale = true
        break
      }
    }
    if (shouldShowRationale) {
      Handler(Looper.getMainLooper()).post {
        Toast.makeText(activity, activity.getString(R.string.need_ble_permission), Toast.LENGTH_SHORT).show()
      }
      return
    }
    requestBTPermissionLauncher?.launch(Array(BLE_PERMISSIONS.size){BLE_PERMISSIONS[it]})
  }

    /**
     * 检查 onRequestPermissionsResult 的回调结果是否全部授权。
     */
    fun allGranted(grantResults: IntArray): Boolean {
        return grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }
}