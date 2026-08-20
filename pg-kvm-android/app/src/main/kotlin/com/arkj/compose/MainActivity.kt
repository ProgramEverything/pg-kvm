package com.arkj.compose

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arkj.compose.models.CameraDeviceInfo
import com.arkj.compose.models.ResolutionInfo
import com.arkj.compose.models.StreamInfo
import com.arkj.compose.stream.SignalingServer
import com.arkj.compose.stream.input.BleHelper
import com.arkj.compose.stream.peer.StreamPeerConnectionFactory
import com.arkj.compose.stream.sessions.LocalWebRtcSessionManager
import com.arkj.compose.stream.sessions.WebRtcSessionManager
import com.arkj.compose.stream.sessions.WebRtcSessionManagerImpl
import com.arkj.compose.ui.theme.Primary
import com.arkj.compose.ui.theme.WebrtcSampleComposeTheme
import com.arkj.compose.utils.PermissionUtils
import io.getstream.webrtc.sample.compose.R

class MainActivity : ComponentActivity() {

  private lateinit var sessionManager: WebRtcSessionManager

  companion object {
    const val BLE_REQUEST_CODE = 1001
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    PermissionUtils.init(this)
    PermissionUtils.requestCameraPermission(this)

    sessionManager = WebRtcSessionManagerImpl(
      context = this,
      signalingServer = SignalingServer(),
      peerConnectionFactory = StreamPeerConnectionFactory(this)
    )

    setContent {
      CompositionLocalProvider(LocalWebRtcSessionManager provides sessionManager) {
        WebrtcSampleComposeTheme {
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
          ) {
            StreamingScreen()
          }
        }
      }
    }
  }

  @Deprecated("Use Activity Result API instead")
  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == BLE_REQUEST_CODE && PermissionUtils.allGranted(grantResults)) {
      // 通知 sessionManager 触发 BLE 扫描
      sessionManager.startBleScan()
    }
  }

  override fun onDestroy() {
    super.onDestroy()

    sessionManager.release()
  }
}

@Composable
fun StreamingScreen() {
  val sessionManager = LocalWebRtcSessionManager.current
  val scope = rememberCoroutineScope()

  val isCameraPermissionGranted by PermissionUtils.isCameraPermissionGranted.collectAsState()
  LaunchedEffect(isCameraPermissionGranted) {
    if (isCameraPermissionGranted) {
      sessionManager.onSessionScreenReady()
    }
  }

  val cameraList by sessionManager.cameraList.collectAsState()
  val currentCamera by sessionManager.currentCamera.collectAsState()
  val streamInfo by sessionManager.streamInfo.collectAsState()
  val isCapturing by sessionManager.isCapturing.collectAsState()
  val isConnected by sessionManager.isConnected.collectAsState()
  val isPreviewing by sessionManager.isPreviewing.collectAsState()

  // BLE state
  val bleStatus by sessionManager.bleStatus.collectAsState()
  val isBleReady by sessionManager.isBleReady.collectAsState()
  val isBleScanning by sessionManager.isBleScanning.collectAsState()
  val scannedBleDevices by sessionManager.scannedBleDevices.collectAsState()
  val connectedBleDeviceName by sessionManager.connectedBleDeviceName.collectAsState()
  val inputClientCount by sessionManager.inputClientCount.collectAsState()

  // BLE dialog state
  var showBleDialog by remember { mutableStateOf(false) }

  // 分辨率选择状态
  var showResolutionDialog by remember { mutableStateOf(false) }
  var resolutionOptions by remember { mutableStateOf<List<ResolutionInfo>>(emptyList()) }

  // 当扫描开始后自动打开设备选择对话框
  LaunchedEffect(isBleScanning) {
    if (isBleScanning) {
      showBleDialog = true
    }
  }

  // 仅存储 Surface 引用，不驱动 UI 状态
  var previewSurface by remember { mutableStateOf<Surface?>(null) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
  ) {
    // ===== Header =====
    CameraHeader(
      cameraList = cameraList,
      currentCamera = currentCamera,
      onCameraSelected = { cameraId ->
        sessionManager.switchCamera(cameraId)
      }
    )

    Spacer(modifier = Modifier.height(4.dp))

    // ===== BLE 连接面板 =====
    BleConnectionPanel(
      bleStatus = bleStatus,
      isBleReady = isBleReady,
      connectedDeviceName = connectedBleDeviceName,
      inputClientCount = inputClientCount,
      onConnectClick = {
        sessionManager.startBleScan()
        showBleDialog = true
      },
      onDisconnectClick = {
        sessionManager.disconnectBle()
      }
    )

    Spacer(modifier = Modifier.height(4.dp))

    // ===== BLE 设备扫描对话框 =====
    if (showBleDialog) {
      BleDeviceDialog(
        isScanning = isBleScanning,
        devices = scannedBleDevices,
        onScanClick = { sessionManager.startBleScan() },
        onDeviceClick = { device ->
          sessionManager.connectBleDevice(device)
          showBleDialog = false
        },
        onDismiss = {
          sessionManager.stopBleScan()
          showBleDialog = false
        }
      )
    }

    // ===== Preview: UVC SurfaceView 显式预览 + 叠加按钮 =====
    // 高度按当前摄像头分辨率等比例缩放（宽度始终填满）；分辨率未知时按 16:9
    val previewAspectRatio = if (
      streamInfo.resolutionWidth > 0 && streamInfo.resolutionHeight > 0
    ) {
      streamInfo.resolutionWidth.toFloat() / streamInfo.resolutionHeight.toFloat()
    } else {
      16f / 9f
    }
    Box(
      modifier = Modifier
        .padding(horizontal = 16.dp)
        .fillMaxWidth()
        .aspectRatio(previewAspectRatio),
      contentAlignment = Alignment.Center
    ) {
      if (cameraList.isNotEmpty()) {
        AndroidView(
          factory = { context ->
            SurfaceView(context).apply {
              holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                  previewSurface = holder.surface
                  if (isPreviewing) {
                    sessionManager.enablePreview(holder.surface)
                  }
                }
                override fun surfaceChanged(
                  holder: SurfaceHolder,
                  format: Int,
                  width: Int,
                  height: Int
                ) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                  previewSurface = null
                  sessionManager.disablePreview()
                }
              })
            }
          },
          modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
        )
      } else {
        // 无 USB 摄像头时的占位
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Text(
            text = "📷",
            fontSize = 48.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.no_usb_camera_hint),
            color = Color.Gray,
            fontSize = 14.sp
          )
        }
      }

      // 预览开关按钮 — 右下角叠放
      if (cameraList.isNotEmpty()) {
        Button(
          onClick = {
            if (isPreviewing) {
              sessionManager.disablePreview()
            } else {
              val surface = previewSurface
              if (surface != null) {
                sessionManager.enablePreview(surface)
              }
            }
          },
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(12.dp),
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.buttonColors(
            backgroundColor = if (isPreviewing) Primary else Color(0xFF4CAF50)
          )
        ) {
          Text(
            text = if (isPreviewing) stringResource(R.string.pause_preview) else stringResource(R.string.resume_preview),
            color = Color.White,
            fontSize = 14.sp
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // ===== Stream Info Panel =====
    StreamInfoPanel(
      streamInfo = streamInfo,
      onResolutionClick = {
        resolutionOptions = sessionManager.getSupportedResolutions()
        showResolutionDialog = true
      }
    )

    // ===== 分辨率选择对话框 =====
    if (showResolutionDialog) {
      ResolutionDialog(
        resolutions = resolutionOptions,
        currentKey = if (streamInfo.resolutionWidth > 0) "${streamInfo.resolutionWidth}x${streamInfo.resolutionHeight}" else null,
        onResolutionSelected = { info ->
          sessionManager.switchResolution(info.width, info.height, info.fps)
          showResolutionDialog = false
        },
        onDismiss = { showResolutionDialog = false }
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // ===== 服务器开关按钮（单个切换） =====
    Button(
      onClick = {
        if (streamInfo.isStreaming) {
          sessionManager.disableStreaming()
        } else {
          sessionManager.enableStreaming()
        }
      },
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .height(48.dp),
      shape = RoundedCornerShape(8.dp),
      colors = ButtonDefaults.buttonColors(
        backgroundColor = if (streamInfo.isStreaming) Primary else Color(0xFF4CAF50)
      )
    ) {
      Text(
        text = if (streamInfo.isStreaming) stringResource(R.string.pause_stream) else stringResource(
          R.string.resume_stream),
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
  }
}

/**
 * 顶部 Header，包含摄像头选择下拉按钮
 */
@Composable
fun CameraHeader(
  cameraList: List<CameraDeviceInfo>,
  currentCamera: CameraDeviceInfo?,
  onCameraSelected: (String) -> Unit
) {
  var expanded by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Primary)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Start
  ) {
    // 摄像头选择按钮
    Box {
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .background(Color.White.copy(alpha = 0.2f))
          .clickable { expanded = true }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "📷",
          fontSize = 18.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = currentCamera?.displayName ?: stringResource(R.string.select_camera),
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = "▼",
          color = Color.White,
          fontSize = 12.sp
        )
      }

      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
      ) {
        if (cameraList.isEmpty()) {
          DropdownMenuItem(
            onClick = { expanded = false },
            enabled = false
          ) {
            Text(stringResource(R.string.no_camera_detected))
          }
        } else {
          cameraList.forEach { camera ->
            DropdownMenuItem(
              onClick = {
                expanded = false
                onCameraSelected(camera.cameraId)
              }
            ) {
              Text(
                text = camera.displayName,
                fontWeight = if (camera == currentCamera) FontWeight.Bold else FontWeight.Normal,
                color = if (camera == currentCamera) Primary else Color.Black
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 串流信息面板
 *
 * @param onResolutionClick 点击分辨率数值时触发（弹出分辨率选择框）
 */
@Composable
fun StreamInfoPanel(
  streamInfo: StreamInfo,
  onResolutionClick: () -> Unit = {}
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    shape = RoundedCornerShape(12.dp),
    elevation = 2.dp,
    backgroundColor = MaterialTheme.colors.surface
  ) {
    Column(
      modifier = Modifier.padding(16.dp)
    ) {
      Text(
        text = stringResource(R.string.device_info),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.onSurface
      )

      Spacer(modifier = Modifier.height(8.dp))

      // 分辨率行：点击弹出分辨率选择框
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .clickable { onResolutionClick() }
          .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.resolution),
          fontSize = 14.sp,
          color = Color.Gray
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = streamInfo.resolution,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Primary
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = stringResource(R.string.switch_label),
            fontSize = 12.sp,
            color = Primary
          )
        }
      }

      InfoRow(label = stringResource(R.string.frame_rate), value = "${streamInfo.frameRate} fps")

      if (streamInfo.isStreaming) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.stream_info),
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colors.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))

        InfoRow(label = stringResource(R.string.codec), value = streamInfo.codec)
        InfoRow(label = stringResource(R.string.server_address), value = streamInfo.serverUrl)

        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.connected_clients, streamInfo.clientAddresses.size),
          fontSize = 14.sp,
          color = MaterialTheme.colors.onSurface
        )
        if (streamInfo.clientAddresses.isNotEmpty()) {
          streamInfo.clientAddresses.forEach { address ->
            Text(
              text = "  • $address",
              fontSize = 13.sp,
              color = Color.Gray,
              modifier = Modifier.padding(start = 8.dp, top = 2.dp)
            )
          }
        }
      }
    }
  }
}

/**
 * 信息行
 */
@Composable
private fun InfoRow(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(
      text = label,
      fontSize = 14.sp,
      color = Color.Gray
    )
    Text(
      text = value,
      fontSize = 14.sp,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colors.onSurface
    )
  }
}

/**
 * 分辨率选择对话框。
 * 列出当前摄像头支持的所有分辨率，点击切换。
 */
@Composable
fun ResolutionDialog(
  resolutions: List<ResolutionInfo>,
  currentKey: String?,
  onResolutionSelected: (ResolutionInfo) -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.select_resolution),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      if (resolutions.isEmpty()) {
        Text(
          text = stringResource(R.string.resolution_list_error),
          fontSize = 14.sp,
          color = Color.Gray
        )
      } else {
        LazyColumn(
          modifier = Modifier.height(320.dp)
        ) {
          items(resolutions) { resolution ->
              val isCurrent = resolution.key == currentKey
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp)
                  .clickable {
                    if (!isCurrent) {
                      onResolutionSelected(resolution)
                    } else {
                      onDismiss()
                    }
                  },
                shape = RoundedCornerShape(8.dp),
                elevation = 1.dp,
                backgroundColor = if (isCurrent) Primary.copy(alpha = 0.12f) else MaterialTheme.colors.surface
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Text(
                    text = "${resolution.width} x ${resolution.height}",
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) Primary else MaterialTheme.colors.onSurface
                  )
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                      text = "${resolution.fps} fps",
                      fontSize = 12.sp,
                      color = Color.Gray
                    )
                    if (isCurrent) {
                      Spacer(modifier = Modifier.width(8.dp))
                      Text(
                        text = stringResource(R.string.current),
                        fontSize = 12.sp,
                        color = Primary
                      )
                    }
                  }
                }
              }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel), color = Color.Gray)
      }
    }
  )
}

// ===== BLE UI Components =====

/**
 * 蓝牙连接状态面板，显示在预览卡片上方。
 */
@Composable
fun BleConnectionPanel(
  bleStatus: String,
  isBleReady: Boolean,
  connectedDeviceName: String?,
  inputClientCount: Int,
  onConnectClick: () -> Unit,
  onDisconnectClick: () -> Unit
) {
  val context = LocalContext.current
  val activity = context as ComponentActivity
  // 状态灯与文案比较需使用当前语言的资源值（状态文本由 BleConnection 本地化生成）
  val scanningText = stringResource(R.string.ble_scanning)
  val connectingText = stringResource(R.string.ble_status_connecting)

  var isWaitingBTPermission by remember{ mutableStateOf(false) }
  val isBTPermissionGranted by PermissionUtils.isBTPermissionGranted.collectAsState()
  LaunchedEffect(isBTPermissionGranted) {
    if (isBTPermissionGranted && isWaitingBTPermission) {
      onConnectClick()
    }
  }

  // 点击"连接设备"时先检查蓝牙权限
  fun onConnectButtonClick() {
    if (PermissionUtils.arePermissionsGranted(context, PermissionUtils.BLE_PERMISSIONS)) {
      onConnectClick()
    } else {
      PermissionUtils.requestBTPermission(activity)
      isWaitingBTPermission = true
    }
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    shape = RoundedCornerShape(12.dp),
    elevation = 2.dp,
    backgroundColor = MaterialTheme.colors.surface
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      // 左侧：状态指示灯 + 文字
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        // 状态指示灯
        Box(
          modifier = Modifier
            .width(10.dp)
            .height(10.dp)
            .clip(CircleShape)
            .background(
              when {
                isBleReady -> Color(0xFF4CAF50) // 绿色：已连接
                bleStatus == scanningText || bleStatus == connectingText -> Color(0xFFFF9800) // 黄色：连接中
                else -> Color(0xFF9E9E9E) // 灰色：未连接
              }
            )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Text(
            text = if (isBleReady && connectedDeviceName != null) {
              "BLE: $connectedDeviceName"
            } else {
              "BLE: $bleStatus"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface
          )
          if (inputClientCount > 0) {
            Text(
              text = stringResource(R.string.ble_input_clients, inputClientCount),
              fontSize = 12.sp,
              color = Color.Gray
            )
          }
        }
      }

      // 右侧：连接/断开按钮
      if (isBleReady) {
        Button(
          onClick = onDisconnectClick,
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(0xFFE53935)
          )
        ) {
          Text(
            text = stringResource(R.string.ble_disconnect),
            color = Color.White,
            fontSize = 13.sp
          )
        }
      } else {
        Button(
          onClick = { onConnectButtonClick() },
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.buttonColors(
            backgroundColor = Primary
          )
        ) {
          Text(
            text = stringResource(R.string.ble_connect),
            color = Color.White,
            fontSize = 13.sp
          )
        }
      }
    }
  }
}

/**
 * BLE 设备扫描对话框。
 * 显示扫描到的设备列表，用户点击设备进行连接。
 */
@Composable
fun BleDeviceDialog(
  isScanning: Boolean,
  devices: List<BleHelper.ScannedDevice>,
  onScanClick: () -> Unit,
  onDeviceClick: (BluetoothDevice) -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.ble_select_device),
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        TextButton(onClick = onScanClick) {
          Text(
            text = if (isScanning) stringResource(R.string.ble_scanning) else stringResource(R.string.ble_rescan),
            color = Primary,
            fontSize = 13.sp
          )
        }
      }
    },
    text = {
      Column {
        if (isScanning && devices.isEmpty()) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            CircularProgressIndicator(
              modifier = Modifier
                .width(32.dp)
                .height(32.dp),
              color = Primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.ble_scanning_hint),
              fontSize = 14.sp,
              color = Color.Gray
            )
          }
        } else if (devices.isEmpty()) {
          Text(
            text = stringResource(R.string.ble_no_devices),
            fontSize = 14.sp,
            color = Color.Gray
          )
        } else {
          LazyColumn(
            modifier = Modifier.height(300.dp)
          ) {
            items(devices) { device ->
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp)
                  .clickable { onDeviceClick(device.device) },
                shape = RoundedCornerShape(8.dp),
                elevation = 1.dp
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Column {
                    Text(
                      text = device.name,
                      fontSize = 14.sp,
                      fontWeight = FontWeight.Medium,
                      color = MaterialTheme.colors.onSurface
                    )
                    Text(
                      text = device.address,
                      fontSize = 12.sp,
                      color = Color.Gray
                    )
                  }
                  // RSSI 信号强度指示
                  Text(
                    text = "${device.rssi} dBm",
                    fontSize = 12.sp,
                    color = when {
                      device.rssi > -50 -> Color(0xFF4CAF50)
                      device.rssi > -70 -> Color(0xFFFF9800)
                      else -> Color(0xFFE53935)
                    }
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel), color = Color.Gray)
      }
    }
  )
}


