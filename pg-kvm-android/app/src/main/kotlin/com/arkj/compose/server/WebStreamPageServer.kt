package com.arkj.compose.server

import android.content.Context
import android.util.Log
import com.yanzhenjie.andserver.AndServer
import com.yanzhenjie.andserver.Server
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

private const val TAG = "CameraHttpServer"

object WebStreamPageServer {
  private var server: Server? = null
  private var serverPort: Int = 8080

  /**
   * 启动 HTTP 服务器
   * @param context Application context
   * @param port 监听端口，默认 8080
   * @return 服务器访问 URL
   */
  fun start(context: Context, port: Int = 8080): String {
    // 防重入：若已有实例在运行，先停掉释放端口
    stop()
    serverPort = port

    server = AndServer.webServer(context)
      .port(port)
      .timeout(10, TimeUnit.SECONDS)
      .listener(object : Server.ServerListener {
        override fun onStarted() {
          // 服务器启动成功
          Log.i(TAG, "HTTP Server started at: http://${getLocalIpAddress()}:$port")
        }

        override fun onStopped() {
          // 服务器停止
          Log.i(TAG, "HTTP Server stopped")
        }

        override fun onException(e: java.lang.Exception?) {
          Log.e(TAG, "HTTP Server exception: ${e?.message}")
        }
      })
      .build()

    server!!.startup()

    return "http://${getLocalIpAddress()}:$port"
  }

  /**
   * 停止 HTTP 服务器
   */
  fun stop() {
    server?.apply {
      if (isRunning) shutdown()
    }
    server = null
  }

  /**
   * 服务器是否正在运行
   */
  fun isRunning(): Boolean = server?.isRunning == true

  /**
   * 获取服务器 URL
   */
  fun getServerUrl(): String? {
    if (!isRunning()) return null
    return "http://${getLocalIpAddress()}:$serverPort"
  }

  /**
   * 获取本机局域网 IPv4 地址
   */
  private fun getLocalIpAddress(): String {
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces()
      while (interfaces.hasMoreElements()) {
        val networkInterface = interfaces.nextElement()
        // 跳过回环和未启用的接口
        if (networkInterface.isLoopback || !networkInterface.isUp) continue
        val addresses = networkInterface.inetAddresses
        while (addresses.hasMoreElements()) {
          val address = addresses.nextElement()
          if (!address.isLoopbackAddress) {
            val hostAddress = address.hostAddress
            // 只返回 IPv4 地址
            if (hostAddress != null && hostAddress.indexOf(':') < 0) {
              return hostAddress
            }
          }
        }
      }
    } catch (_: Exception) {
    }
    return "127.0.0.1"
  }
}