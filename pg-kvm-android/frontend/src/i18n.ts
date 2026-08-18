import { ref } from "vue";

/**
 * 极简 i18n：无需第三方依赖。
 * 语言优先级：localStorage 记忆的选择 > 浏览器语言 > 中文。
 */

export type Locale = "zh" | "en";

const messages = {
  zh: {
    // 连接状态
    status_not_connected: "未连接",
    status_connecting_signaling: "正在连接信令服务器...",
    status_signaling_connected: "信令已连接，创建 WebRTC 连接...",
    status_signaling_failed: "信令连接失败！请确认服务端已启动",
    status_signaling_closed: "信令已断开",
    status_webrtc_connecting: "WebRTC 连接中...",
    status_connected_receiving: "已连接，正在接收视频流",
    status_failed: "连接失败",
    status_disconnected: "连接断开",
    status_playing: "正在播放视频流",
    status_offer_sent: "已发送 offer，等待 answer...",
    status_answer_received: "已收到 answer，等待 ICE 连接...",
    status_closed: "已断开",
    // 输入捕获状态
    input_not_started: "输入捕获未启动",
    input_connected: "输入捕获已连接",
    input_disconnected: "输入捕获已断开",
    input_failed: "输入中继连接失败",
    // 侧边卡片
    camera_title: "📷 摄像头",
    no_camera: "未检测到摄像头",
    ble_title: "⌨️ 蓝牙键鼠",
    ble_not_connected: "未连接",
    ble_connect: "连接设备",
    ble_disconnect: "断开",
    input_clients: "输入客户端: {n}",
    device_info_title: "ℹ️ 设备信息",
    resolution: "分辨率",
    frame_rate: "帧率",
    stream_info_title: "📡 串流信息",
    codec: "编码格式",
    server_address: "服务器地址",
    connected_clients: "已连接客户端",
    // 控制区
    start_stream: "开始拉流",
    stop_stream: "停止拉流",
    connecting: "连接中...",
    stream_label: "串流：",
    // 视频区
    waiting_stream: "等待视频流...",
    video_waiting_hint: "请确保服务端已启动，\n然后点击「开始拉流」按钮",
    fullscreen_hint: "进入全屏后可用键鼠控制",
    fullscreen: "全屏",
    exit_fullscreen: "退出全屏",
    // 分辨率对话框
    select_resolution: "选择分辨率",
    resolution_list_error: "无法获取分辨率列表，请确保摄像头已连接并开始采集",
    cancel: "取消",
    // 蓝牙对话框
    select_ble_device: "选择蓝牙设备",
    scanning: "扫描中...",
    rescan: "重新扫描",
    ble_scanning_hint: "正在扫描蓝牙设备...",
    ble_no_devices: "未发现蓝牙设备，请确保 ESP32 已上电",
  },
  en: {
    // Connection status
    status_not_connected: "Not connected",
    status_connecting_signaling: "Connecting to signaling server...",
    status_signaling_connected: "Signaling connected, creating WebRTC...",
    status_signaling_failed: "Signaling failed! Make sure the server is running",
    status_signaling_closed: "Signaling disconnected",
    status_webrtc_connecting: "WebRTC connecting...",
    status_connected_receiving: "Connected, receiving stream",
    status_failed: "Connection failed",
    status_disconnected: "Disconnected",
    status_playing: "Playing stream",
    status_offer_sent: "Offer sent, waiting for answer...",
    status_answer_received: "Answer received, waiting for ICE...",
    status_closed: "Closed",
    // Input capture status
    input_not_started: "Input capture not started",
    input_connected: "Input capture connected",
    input_disconnected: "Input capture disconnected",
    input_failed: "Input relay connection failed",
    // Sidebar card
    camera_title: "📷 Camera",
    no_camera: "No camera detected",
    ble_title: "⌨️ BLE Keyboard/Mouse",
    ble_not_connected: "Not connected",
    ble_connect: "Connect",
    ble_disconnect: "Disconnect",
    input_clients: "Inputs: {n}",
    device_info_title: "ℹ️ Device Info",
    resolution: "Resolution",
    frame_rate: "Frame Rate",
    stream_info_title: "📡 Stream Info",
    codec: "Codec",
    server_address: "Server",
    connected_clients: "Clients",
    // Controls
    start_stream: "Start Stream",
    stop_stream: "Stop Stream",
    connecting: "Connecting...",
    stream_label: "Stream:",
    // Video area
    waiting_stream: "Waiting for stream...",
    video_waiting_hint: "Make sure the server is running,\nthen click \"Start Stream\"",
    fullscreen_hint: "Enter fullscreen to control with keyboard & mouse",
    fullscreen: "Fullscreen",
    exit_fullscreen: "Exit fullscreen",
    // Resolution dialog
    select_resolution: "Select Resolution",
    resolution_list_error: "Failed to load resolutions; make sure a camera is connected and capturing",
    cancel: "Cancel",
    // BLE dialog
    select_ble_device: "Select Device",
    scanning: "Scanning...",
    rescan: "Rescan",
    ble_scanning_hint: "Scanning for devices...",
    ble_no_devices: "No devices found; make sure the ESP32 is powered on",
  },
} as const;

export type MessageKey = keyof typeof messages.zh;

function detectInitialLocale(): Locale {
  const saved = localStorage.getItem("locale");
  if (saved === "zh" || saved === "en") return saved;
  return navigator.language.toLowerCase().startsWith("zh") ? "zh" : "en";
}

export const locale = ref<Locale>(detectInitialLocale());

export function setLocale(l: Locale) {
  locale.value = l;
  localStorage.setItem("locale", l);
  document.documentElement.lang = l === "zh" ? "zh-CN" : "en";
}

export function toggleLocale() {
  setLocale(locale.value === "zh" ? "en" : "zh");
}

/** 取当前语言文案；params 中的 {name} 占位符会被替换 */
export function t(key: MessageKey, params?: Record<string, string | number>): string {
  let text: string =
    messages[locale.value][key] ?? messages.zh[key] ?? key;
  if (params) {
    for (const [name, value] of Object.entries(params)) {
      text = text.replace(`{${name}}`, String(value));
    }
  }
  return text;
}
