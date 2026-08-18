package com.arkj.compose.stream.input

import android.content.Context
import android.util.Log

/**
 * 摄像头与 ESP32 BLE 设备配对关系的持久化存储。
 *
 * key = 摄像头 ID（USB deviceName），value = BLE 设备地址 + 名称。
 * 重启 app 或重新插拔摄像头后，按 cameraId 查询配对记录并自动回连。
 */
class BlePairingStore(context: Context) {
    companion object {
        private const val TAG = "BlePairingStore"
        private const val PREFS_NAME = "ble_camera_pairing"
        private const val KEY_ADDRESS_SUFFIX = ".address"
        private const val KEY_NAME_SUFFIX = ".name"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 查询摄像头配对的 BLE 设备地址，未配对返回 null */
    fun getPairedAddress(cameraId: String): String? =
        prefs.getString(cameraId + KEY_ADDRESS_SUFFIX, null)

    /** 查询摄像头配对的 BLE 设备名称，未配对返回 null */
    fun getPairedName(cameraId: String): String? =
        prefs.getString(cameraId + KEY_NAME_SUFFIX, null)

    /** 记录配对关系 */
    fun setPairing(cameraId: String, bleAddress: String, bleName: String?) {
        prefs.edit()
            .putString(cameraId + KEY_ADDRESS_SUFFIX, bleAddress)
            .putString(cameraId + KEY_NAME_SUFFIX, bleName)
            .apply()
        Log.i(TAG, "Pairing saved: camera=$cameraId -> ble=$bleAddress ($bleName)")
    }

    /** 清除配对关系 */
    fun removePairing(cameraId: String) {
        prefs.edit()
            .remove(cameraId + KEY_ADDRESS_SUFFIX)
            .remove(cameraId + KEY_NAME_SUFFIX)
            .apply()
        Log.i(TAG, "Pairing removed: camera=$cameraId")
    }
}
