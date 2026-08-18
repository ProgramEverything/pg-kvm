package com.arkj.compose.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限申请工具类，统一管理所有运行时权限的检查和请求。
 *
 * 使用 Activity.requestPermissions() 而非 Compose launcher。
 */
object PermissionUtils {

    /** 摄像头 + 录音 */
    val CAMERA_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

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
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    /**
     * 检查 onRequestPermissionsResult 的回调结果是否全部授权。
     */
    fun allGranted(grantResults: IntArray): Boolean {
        return grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }
}