<script setup lang="ts">
/**
 * WebRTC 画面串流 Demo - 客户端（主叫端 / 拉流端）
 *
 * 使用浏览器原生 WebRTC API，不依赖任何第三方 WebRTC 库。
 * 通过 WebSocket 与服务端交换信令，接收视频流并渲染到 <video> 元素。
 */
import { ref, computed, onMounted, onUnmounted } from "vue";
import { t, toggleLocale } from "./i18n";

// ============ 状态 ============
const statusText = ref(t("status_not_connected"));
const connectionState = ref("new");
const videoRef = ref<HTMLVideoElement | null>(null);
const videoContainerRef = ref<HTMLElement | null>(null);
const remoteStream = ref<MediaStream | null>(null);
const isFullscreen = ref(false);

let pc: RTCPeerConnection | null = null;
let ws: WebSocket | null = null;
let pendingCandidates: RTCIceCandidateInit[] = [];

// ============ 键鼠捕获状态 ============
const isCapturingInput = ref(false);
const inputStatusText = ref(t("input_not_started"));
let inputWs: WebSocket | null = null;
let modifierState = { ctrl: false, shift: false, alt: false, meta: false };
let pointerLocked = ref(false);

// ============ 配置 ============
const WS_URL = `ws://${window.location.hostname}:3000`;

const isConnected = computed(() => connectionState.value === "connected");

// ============ 开始连接 ============
async function startConnection() {
  statusText.value = t("status_connecting_signaling");

  // ---------- 1. 连接 WebSocket 信令服务器 ----------
  ws = new WebSocket(WS_URL);

  // 拉流开始后自动开启键鼠捕获
  startInputCapture();

  ws.onopen = async () => {
    statusText.value = t("status_signaling_connected");
    await setupPeerConnection();
    await sendOffer();
  };

  ws.onmessage = async (event) => {
    const msg = JSON.parse(event.data);
    await handleSignalingMessage(msg);
  };

  ws.onerror = () => {
    statusText.value = t("status_signaling_failed");
  };

  ws.onclose = () => {
    statusText.value = t("status_signaling_closed");
  };
}

// ---------- 2. 创建 PeerConnection ----------
async function setupPeerConnection() {
  pc = new RTCPeerConnection({
    iceServers: [
      { urls: "stun:stun.l.google.com:19302" },
    ],
  });

  // 监听连接状态
  pc.onconnectionstatechange = () => {
    if (!pc) return;
    connectionState.value = pc.connectionState;
    switch (pc.connectionState) {
      case "connecting":
        statusText.value = t("status_webrtc_connecting");
        break;
      case "connected":
        statusText.value = t("status_connected_receiving");
        logConnectionDetails(pc);
        break;
      case "failed":
        statusText.value = t("status_failed");
        break;
      case "disconnected":
        statusText.value = t("status_disconnected");
        break;
    }
  };

  // 监听 ICE 候选
  pc.onicecandidate = (event) => {
    if (event.candidate && ws && ws.readyState === WebSocket.OPEN) {
      ws.send(
        JSON.stringify({
          type: "ice-candidate",
          candidate: event.candidate,
        })
      );
    }
  };

  // 监听远端视频轨道
  pc.ontrack = (event) => {
    console.log("[客户端] ✅ ontrack 触发, track.kind:", event.track.kind);
    console.log("[客户端] track.readyState:", event.track.readyState);
    console.log("[客户端] streams count:", event.streams.length);
    let stream: MediaStream;
    if (event.streams[0]) {
      stream = event.streams[0];
    } else {
      // Unified Plan: 轨道可能不带 stream 到达，手动创建
      stream = new MediaStream();
      stream.addTrack(event.track);
    }
    remoteStream.value = stream;
    statusText.value = t("status_playing");
    console.log("[客户端] 已绑定 remoteStream 到 video 元素");
    // 检查 stream 中的 track 信息
    const tracks = stream.getVideoTracks();
    console.log("[客户端] stream 中 video tracks:", tracks.length);
    tracks.forEach((t, i) => {
      console.log(`[客户端]   track[${i}]: ${t.kind}, readyState=${t.readyState}, enabled=${t.enabled}`);
    });
  };
}

// ---------- 打印连接详情（本端/对端 IP 和端口）+ 媒体统计 ----------
async function logConnectionDetails(pc: RTCPeerConnection) {
  try {
    const stats = await pc.getStats();
    let selectedPair: any = null;
    const candidates: Record<string, any> = {};
    const inboundRtp: any[] = [];
    const codecs: Record<string, any> = {};

    stats.forEach((report) => {
      if (report.type === "candidate-pair" && report.state === "succeeded") {
        selectedPair = report;
      }
      if (report.type === "local-candidate" || report.type === "remote-candidate") {
        candidates[report.id] = report;
      }
      if (report.type === "inbound-rtp" && report.kind === "video") {
        inboundRtp.push(report);
      }
      if (report.type === "codec") {
        codecs[report.id] = report;
      }
    });

    // IP 和端口
    if (selectedPair) {
      const local = candidates[selectedPair.localCandidateId];
      const remote = candidates[selectedPair.remoteCandidateId];
      if (local) {
        console.log(
          `[客户端] 本端: ${local.ip ?? local.address}:${local.port} (${local.candidateType})`
        );
      }
      if (remote) {
        console.log(
          `[客户端] 对端: ${remote.ip ?? remote.address}:${remote.port} (${remote.candidateType})`
        );
      }
    } else {
      console.warn("[客户端] ⚠️ 未找到 succeeded candidate-pair");
    }

    // 入站 RTP 统计
    if (inboundRtp.length > 0) {
      inboundRtp.forEach((rtp) => {
        console.log(
          `[客户端] 入站视频: ${rtp.packetsReceived ?? 0} 包, ` +
          `${rtp.bytesReceived ?? 0} 字节, ` +
          `codec=${codecs[rtp.codecId]?.mimeType ?? "?"}, ` +
          `framesDecoded=${rtp.framesDecoded ?? 0}, ` +
          `firCount=${rtp.firCount ?? 0}, pliCount=${rtp.pliCount ?? 0}`
        );
      });
    } else {
      console.warn("[客户端] ⚠️ 未见入站 RTP 统计 — 可能没有收到媒体数据");
    }
  } catch (e) {
    console.log("[客户端] 无法获取连接详情:", e);
  }
}

// ---------- 3. 创建并发送 Offer ----------
async function sendOffer() {
  if (!pc || !ws) return;

  // 添加接收视频的 transceiver（主动方需要）
  pc.addTransceiver("video", { direction: "recvonly" });

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);

  console.log("[客户端] 发送 offer SDP:");
  console.log(pc.localDescription?.sdp);
  ws.send(
    JSON.stringify({
      type: "offer",
      sdp: pc.localDescription,
    })
  );
  statusText.value = t("status_offer_sent");
}

// ---------- 4. 处理信令消息 ----------
async function handleSignalingMessage(msg: {
  type: string;
  sdp?: RTCSessionDescriptionInit;
  candidate?: RTCIceCandidateInit;
}) {
  if (!pc) return;

  switch (msg.type) {
    case "answer": {
      if (msg.sdp) {
        console.log("[客户端] 收到 answer SDP:");
        console.log(msg.sdp.sdp);
        await pc.setRemoteDescription(
          new RTCSessionDescription(msg.sdp)
        );
        statusText.value = t("status_answer_received");

        // 远程描述设置完成后，处理之前缓存的 ICE 候选
        for (const c of pendingCandidates) {
          try {
            await pc.addIceCandidate(new RTCIceCandidate(c));
          } catch (e) {
            console.error("[客户端] 添加缓存 ICE 候选失败:", e);
          }
        }
        pendingCandidates = [];
      }
      break;
    }

    case "ice-candidate": {
      if (msg.candidate) {
        // 如果远程描述尚未设置，将 ICE 候选加入缓存队列
        if (!pc.remoteDescription) {
          pendingCandidates.push(msg.candidate);
          return;
        }
        try {
          await pc.addIceCandidate(
            new RTCIceCandidate(msg.candidate)
          );
        } catch (e) {
          console.error("[客户端] 添加 ICE 候选失败:", e);
        }
      }
      break;
    }
  }
}

// ---------- 停止连接 ----------
function stopConnection() {
  if (pc) {
    pc.close();
    pc = null;
  }
  if (ws) {
    ws.close();
    ws = null;
  }
  remoteStream.value = null;
  connectionState.value = "closed";
  statusText.value = t("status_closed");
  // 停止输入捕获
  stopInputCapture();
}

// ============ 键鼠输入捕获 ============

function startInputCapture() {
  const INPUT_WS_URL = `ws://${window.location.hostname}:3001`;
  inputWs = new WebSocket(INPUT_WS_URL);

  inputWs.onopen = () => {
    isCapturingInput.value = true;
    inputStatusText.value = t("input_connected");
    console.log("[输入] 已连接到输入中继服务器");
  };

  inputWs.onclose = () => {
    isCapturingInput.value = false;
    inputStatusText.value = t("input_disconnected");
    // 释放所有按键
    modifierState = { ctrl: false, shift: false, alt: false, meta: false };
  };

  inputWs.onerror = () => {
    inputStatusText.value = t("input_failed");
    isCapturingInput.value = false;
  };
}

function stopInputCapture() {
  if (inputWs) {
    inputWs.close();
    inputWs = null;
  }
  isCapturingInput.value = false;
  pointerLocked.value = false;
  inputStatusText.value = t("input_not_started");
}

function sendInputMessage(msg: Record<string, unknown>) {
  if (inputWs && inputWs.readyState === WebSocket.OPEN) {
    inputWs.send(JSON.stringify(msg));
  }
}

// ---------- 键盘事件处理 ----------

function onKeyDown(e: KeyboardEvent) {
  if (!isCapturingInput.value || !isFullscreen.value) return; // 仅全屏时捕获键鼠
  e.preventDefault();

  // 跟踪修饰键状态
  if (e.key === "Control") modifierState.ctrl = true;
  if (e.key === "Shift") modifierState.shift = true;
  if (e.key === "Alt") modifierState.alt = true;
  if (e.key === "Meta") modifierState.meta = true;

  // 忽略纯修饰键（由 key-down 消息处理）
  if (["Control", "Shift", "Alt", "Meta"].includes(e.key)) {
    sendInputMessage({
      type: "key-down",
      key: e.key,
      code: e.code,
      ctrl: e.ctrlKey,
      shift: e.shiftKey,
      alt: e.altKey,
      meta: e.metaKey,
    });
    return;
  }

  sendInputMessage({
    type: "key-down",
    key: e.key,
    code: e.code,
    keyCode: e.keyCode,
    ctrl: e.ctrlKey,
    shift: e.shiftKey,
    alt: e.altKey,
    meta: e.metaKey,
  });
}

function onKeyUp(e: KeyboardEvent) {
  if (!isCapturingInput.value || !isFullscreen.value) return; // 仅全屏时捕获键鼠
  e.preventDefault();

  if (e.key === "Control") modifierState.ctrl = false;
  if (e.key === "Shift") modifierState.shift = false;
  if (e.key === "Alt") modifierState.alt = false;
  if (e.key === "Meta") modifierState.meta = false;

  sendInputMessage({
    type: "key-up",
    key: e.key,
    code: e.code,
    keyCode: e.keyCode,
  });
}

// ---------- 鼠标事件处理 ----------

let lastMouseX = 0;
let lastMouseY = 0;

function onMouseMove(e: MouseEvent) {
  if (!isCapturingInput.value || !isFullscreen.value) return; // 仅全屏时捕获键鼠

  if (pointerLocked.value && document.pointerLockElement) {
    // 使用 Pointer Lock API 的相对移动
    const dx = Math.round(e.movementX);
    const dy = Math.round(e.movementY);
    if (dx !== 0 || dy !== 0) {
      sendInputMessage({ type: "mouse-move", dx, dy });
    }
  } else {
    // 非 Pointer Lock 模式：使用绝对位置计算相对移动
    if (lastMouseX !== 0 && lastMouseY !== 0) {
      const dx = Math.round(e.clientX - lastMouseX);
      const dy = Math.round(e.clientY - lastMouseY);
      if (dx !== 0 || dy !== 0) {
        sendInputMessage({ type: "mouse-move", dx, dy });
      }
    }
    lastMouseX = e.clientX;
    lastMouseY = e.clientY;
  }
}

function onMouseDown(e: MouseEvent) {
  if (!isCapturingInput.value || !isFullscreen.value) return; // 仅全屏时捕获键鼠
  e.preventDefault();

  const button = e.button === 0 ? "left" : e.button === 1 ? "middle" : "right";
  sendInputMessage({ type: "mouse-down", button });
}

function onMouseUp(e: MouseEvent) {
  if (!isCapturingInput.value || !isFullscreen.value) return; // 仅全屏时捕获键鼠
  e.preventDefault();

  const button = e.button === 0 ? "left" : e.button === 1 ? "middle" : "right";
  sendInputMessage({ type: "mouse-up", button });
}

function onWheel(e: WheelEvent) {
  if (!isCapturingInput.value || !isFullscreen.value) return; // 仅全屏时捕获键鼠
  e.preventDefault();

  const dx = Math.round(e.deltaX / 10);
  const dy = Math.round(e.deltaY / 10);
  sendInputMessage({ type: "mouse-scroll", dx, dy });
}

// 监听 Pointer Lock 状态变化
function onPointerLockChange() {
  pointerLocked.value = document.pointerLockElement !== null;
}

// ---------- 全屏控制 ----------
function toggleFullscreen() {
  const el = videoContainerRef.value;
  if (!el) return;
  if (document.fullscreenElement) {
    document.exitFullscreen();
  } else {
    el.requestFullscreen?.();
    // 进入全屏后锁定鼠标指针（失败不影响全屏）
    tryLockPointer();
  }
}

function tryLockPointer() {
  try {
    const result = videoContainerRef.value?.requestPointerLock?.() as unknown;
    // 较新浏览器返回 Promise，吞掉拒绝（如未获得用户手势授权）
    if (result && typeof (result as Promise<void>).catch === "function") {
      (result as Promise<void>).catch(() => {});
    }
  } catch {
    // 忽略
  }
}

function onFullscreenChange() {
  const wasFullscreen = isFullscreen.value;
  isFullscreen.value = document.fullscreenElement !== null;
  // 全屏切换瞬间丢失的鼠标锁：进入全屏时兜底重锁
  if (!wasFullscreen && isFullscreen.value && !document.pointerLockElement) {
    tryLockPointer();
  }
}

// ============ 设备状态侧边卡片 ============

interface CameraItem {
  id: string;
  name: string;
  current: boolean;
}

interface DeviceStatus {
  cameras: CameraItem[];
  currentCameraName: string | null;
  stream: {
    resolution: string;
    resolutionWidth: number;
    resolutionHeight: number;
    frameRate: number;
    codec: string;
    isStreaming: boolean;
    serverUrl: string;
    clientAddresses: string[];
  };
  ble: {
    status: string;
    isReady: boolean;
    isScanning: boolean;
    connectedDeviceName: string | null;
    inputClientCount: number;
  };
}

interface BleDeviceItem {
  name: string;
  address: string;
  rssi: number;
}

const deviceStatus = ref<DeviceStatus | null>(null);
let statusTimer: ReturnType<typeof setInterval> | null = null;

async function fetchStatus() {
  try {
    const resp = await fetch("/api/status");
    const data = await resp.json();
    deviceStatus.value = data;
  } catch {
    // 服务端未就绪时静默忽略，下轮重试
  }
}

async function postApi(path: string) {
  try {
    await fetch(path, { method: "POST" });
  } catch {
    // 忽略网络错误
  }
  await fetchStatus();
}

// ---------- 摄像头切换 ----------

function switchCamera(cameraId: string) {
  postApi(`/api/camera/switch?cameraId=${encodeURIComponent(cameraId)}`);
}

// ---------- 分辨率切换 ----------

const showResolutionDialog = ref(false);
const resolutionOptions = ref<{ width: number; height: number; fps: number }[]>([]);

async function openResolutionDialog() {
  try {
    const resp = await fetch("/api/resolutions");
    const data = await resp.json();
    resolutionOptions.value = data.resolutions ?? [];
  } catch {
    resolutionOptions.value = [];
  }
  showResolutionDialog.value = true;
}

function switchResolution(item: { width: number; height: number; fps: number }) {
  showResolutionDialog.value = false;
  postApi(`/api/resolution/switch?width=${item.width}&height=${item.height}&fps=${item.fps}`);
}

// ---------- 蓝牙连接 ----------

const showBleDialog = ref(false);
const bleDevices = ref<BleDeviceItem[]>([]);
const bleDialogScanning = ref(false);

async function fetchBleDevices() {
  try {
    const resp = await fetch("/api/ble/devices");
    const data = await resp.json();
    bleDevices.value = data.devices ?? [];
    bleDialogScanning.value = !!data.isScanning;
  } catch {
    // 忽略
  }
}

async function openBleDialog() {
  await postApi("/api/ble/scan/start");
  showBleDialog.value = true;
  await fetchBleDevices();
}

async function rescanBle() {
  await postApi("/api/ble/scan/start");
  await fetchBleDevices();
}

function connectBle(address: string) {
  showBleDialog.value = false;
  postApi(`/api/ble/connect?address=${encodeURIComponent(address)}`);
}

function disconnectBle() {
  postApi("/api/ble/disconnect");
}

// ---------- 卡片辅助 ----------

const bleDotClass = computed(() => {
  if (deviceStatus.value?.ble.isReady) return "on";
  const status = deviceStatus.value?.ble.status ?? "";
  const busyMarkers = ["扫描中", "连接中", "发现服务", "Scanning", "Connecting", "Discovering"];
  if (busyMarkers.some((m) => status.includes(m))) {
    return "busy";
  }
  return "off";
});

function isCurrentResolution(item: { width: number; height: number }): boolean {
  const stream = deviceStatus.value?.stream;
  return !!stream && stream.resolutionWidth === item.width && stream.resolutionHeight === item.height;
}

function rssiClass(rssi: number): string {
  if (rssi > -50) return "good";
  if (rssi > -70) return "mid";
  return "bad";
}

// 键盘/鼠标事件挂载/卸载
function attachInputListeners() {
  document.addEventListener("keydown", onKeyDown);
  document.addEventListener("keyup", onKeyUp);
  document.addEventListener("pointerlockchange", onPointerLockChange);
  document.addEventListener("fullscreenchange", onFullscreenChange);
}

function detachInputListeners() {
  document.removeEventListener("keydown", onKeyDown);
  document.removeEventListener("keyup", onKeyUp);
  document.removeEventListener("pointerlockchange", onPointerLockChange);
  document.removeEventListener("fullscreenchange", onFullscreenChange);
}

// ---------- 当 remoteStream 变化时绑定到 video 元素 ----------
function onVideoReady() {
  // Vue 的 watchEffect 会在 remoteStream 变化时触发
  // 这里通过 :srcObject 绑定，Vue 自动处理
}

// ============ 生命周期 ============
onMounted(() => {
  // 页面加载后自动连接
  // 如需手动连接，可注释掉此行，改为按钮触发
  // startConnection();
  attachInputListeners();

  // 轮询设备状态，刷新侧边卡片
  fetchStatus();
  statusTimer = setInterval(() => {
    fetchStatus();
    if (showBleDialog.value) {
      fetchBleDevices();
    }
  }, 2000);
});

onUnmounted(() => {
  stopConnection();
  detachInputListeners();
  if (statusTimer) {
    clearInterval(statusTimer);
    statusTimer = null;
  }
});
</script>

<template>
  <div class="page-layout">
    <!-- ===== 左侧：设备状态卡片 ===== -->
    <aside class="sidebar-card">
      <!-- 摄像头切换 -->
      <section class="card-section">
        <h3 class="section-title">{{ t("camera_title") }}</h3>
        <div v-if="deviceStatus && deviceStatus.cameras.length > 0" class="camera-list">
          <button
            v-for="camera in deviceStatus.cameras"
            :key="camera.id"
            class="camera-item"
            :class="{ active: camera.current }"
            @click="!camera.current && switchCamera(camera.id)"
          >
            {{ camera.name }}
          </button>
        </div>
        <p v-else class="empty-text">{{ t("no_camera") }}</p>
      </section>

      <!-- 蓝牙连接 -->
      <section class="card-section">
        <h3 class="section-title">{{ t("ble_title") }}</h3>
        <div class="ble-row">
          <span class="ble-dot" :class="bleDotClass"></span>
          <div class="ble-text">
            <span class="ble-name">
              {{ deviceStatus?.ble.isReady && deviceStatus.ble.connectedDeviceName
                ? deviceStatus.ble.connectedDeviceName
                : deviceStatus?.ble.status ?? t("ble_not_connected") }}
            </span>
            <span v-if="(deviceStatus?.ble.inputClientCount ?? 0) > 0" class="ble-sub">
              {{ t("input_clients", { n: deviceStatus?.ble.inputClientCount ?? 0 }) }}
            </span>
          </div>
          <button
            v-if="deviceStatus?.ble.isReady"
            class="mini-btn danger"
            @click="disconnectBle"
          >
            {{ t("ble_disconnect") }}
          </button>
          <button v-else class="mini-btn" @click="openBleDialog">{{ t("ble_connect") }}</button>
        </div>
      </section>

      <!-- 设备信息 -->
      <section class="card-section">
        <h3 class="section-title">{{ t("device_info_title") }}</h3>
        <div class="info-row clickable" @click="openResolutionDialog">
          <span class="info-label">{{ t("resolution") }}</span>
          <span class="info-value accent">
            {{ deviceStatus?.stream.resolution ?? "N/A" }} ▾
          </span>
        </div>
        <div class="info-row">
          <span class="info-label">{{ t("frame_rate") }}</span>
          <span class="info-value">{{ deviceStatus?.stream.frameRate ?? 0 }} fps</span>
        </div>
      </section>

      <!-- 串流信息 -->
      <section v-if="deviceStatus?.stream.isStreaming" class="card-section">
        <h3 class="section-title">{{ t("stream_info_title") }}</h3>
        <div class="info-row">
          <span class="info-label">{{ t("codec") }}</span>
          <span class="info-value">{{ deviceStatus.stream.codec }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">{{ t("server_address") }}</span>
          <span class="info-value breakable">{{ deviceStatus.stream.serverUrl }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">{{ t("connected_clients") }}</span>
          <span class="info-value">{{ deviceStatus.stream.clientAddresses.length }}</span>
        </div>
        <ul v-if="deviceStatus.stream.clientAddresses.length" class="client-list">
          <li v-for="addr in deviceStatus.stream.clientAddresses" :key="addr">{{ addr }}</li>
        </ul>
      </section>
    </aside>

    <!-- ===== 右侧：串流主区域 ===== -->
    <main class="app-container">
      <!-- 控制按钮：拉流开/关合并 -->
      <div class="controls">
      <button
        class="toggle-btn"
        :class="{ stop: isConnected }"
        :disabled="connectionState === 'connecting'"
        @click="isConnected ? stopConnection() : startConnection()"
      >
        {{ connectionState === "connecting" ? t("connecting") : isConnected ? t("stop_stream") : t("start_stream") }}
      </button>
      <!-- 语言切换：中/EN -->
      <button class="toggle-btn lang-btn" @click="toggleLocale">中/EN</button>
    </div>

    <!-- 状态显示 -->
    <div class="status-bar">
      <span class="status-label">{{ t("stream_label") }}</span>
      <span
        class="status-value"
        :class="{
          connected: connectionState === 'connected',
          connecting: connectionState === 'connecting',
          failed: connectionState === 'failed',
        }"
      >
        {{ statusText }}
      </span>
      <span v-if="isCapturingInput" class="status-divider">|</span>
      <span
        v-if="isCapturingInput"
        class="status-value connected"
      >
        {{ inputStatusText }}
      </span>
    </div>

    <!-- 视频播放器 -->
    <div
      ref="videoContainerRef"
      class="video-container"
      :class="{ 'input-active': isCapturingInput && isFullscreen }"
      @mousemove="onMouseMove"
      @mousedown="onMouseDown"
      @mouseup="onMouseUp"
      @wheel="onWheel"
      @contextmenu.prevent
    >
      <video
        ref="videoRef"
        :srcObject="remoteStream"
        autoplay
        playsinline
        muted
        class="video-player"
      ></video>
      <div v-if="!remoteStream" class="video-placeholder">
        <p>{{ t("waiting_stream") }}</p>
        <p class="hint">{{ t("video_waiting_hint") }}</p>
      </div>
      <!-- 非全屏提示：进入全屏后才可用键鼠控制 -->
      <div v-if="remoteStream && !isFullscreen" class="fullscreen-hint">
        {{ t("fullscreen_hint") }}
      </div>
      <!-- 全屏按钮 -->
      <button
        v-if="remoteStream"
        class="fullscreen-btn"
        :title="isFullscreen ? t('exit_fullscreen') : t('fullscreen')"
        @click="toggleFullscreen"
      >
        ⛶
      </button>
    </div>
    </main>
  </div>

  <!-- ===== 分辨率选择对话框 ===== -->
  <div v-if="showResolutionDialog" class="modal-overlay" @click.self="showResolutionDialog = false">
    <div class="modal-card">
      <h3 class="modal-title">{{ t("select_resolution") }}</h3>
      <div class="modal-body">
        <p v-if="resolutionOptions.length === 0" class="empty-text">
          {{ t("resolution_list_error") }}
        </p>
        <button
          v-for="item in resolutionOptions"
          :key="`${item.width}x${item.height}`"
          class="camera-item"
          :class="{ active: isCurrentResolution(item) }"
          @click="!isCurrentResolution(item) && switchResolution(item)"
        >
          {{ item.width }} x {{ item.height }}
          <small>{{ item.fps }} fps</small>
        </button>
      </div>
      <div class="modal-footer">
        <button class="mini-btn" @click="showResolutionDialog = false">{{ t("cancel") }}</button>
      </div>
    </div>
  </div>

  <!-- ===== 蓝牙设备选择对话框 ===== -->
  <div v-if="showBleDialog" class="modal-overlay" @click.self="showBleDialog = false">
    <div class="modal-card">
      <div class="modal-title-row">
        <h3 class="modal-title">{{ t("select_ble_device") }}</h3>
        <button class="mini-btn" @click="rescanBle">
          {{ bleDialogScanning ? t("scanning") : t("rescan") }}
        </button>
      </div>
      <div class="modal-body">
        <p v-if="bleDevices.length === 0" class="empty-text">
          {{ bleDialogScanning ? t("ble_scanning_hint") : t("ble_no_devices") }}
        </p>
        <button
          v-for="device in bleDevices"
          :key="device.address"
          class="ble-device-item"
          @click="connectBle(device.address)"
        >
          <span class="ble-device-name">{{ device.name }}</span>
          <span class="ble-device-rssi" :class="rssiClass(device.rssi)">{{ device.rssi }} dBm</span>
        </button>
      </div>
      <div class="modal-footer">
        <button class="mini-btn" @click="showBleDialog = false">{{ t("cancel") }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ===== 页面布局：左侧状态卡片 + 右侧串流区 ===== */
.page-layout {
  display: flex;
  gap: 1.5rem;
  align-items: flex-start;
  max-width: 1200px;
  margin: 0 auto;
  padding: 1.5rem;
}

.app-container {
  flex: 1;
  min-width: 0;
  text-align: center;
}

/* 窄屏时上下堆叠 */
@media (max-width: 900px) {
  .page-layout {
    flex-direction: column;
  }

  .sidebar-card {
    width: 100%;
  }
}

/* ===== 左侧设备状态卡片 ===== */
.sidebar-card {
  width: 260px;
  flex-shrink: 0;
  background: #1a1a1a;
  border: 1px solid #333;
  border-radius: 10px;
  padding: 1rem;
  text-align: left;
}

.card-section {
  padding: 0.6rem 0;
}

.card-section + .card-section {
  border-top: 1px solid #2a2a2a;
}

.section-title {
  margin: 0 0 0.5rem;
  font-size: 0.9rem;
  font-weight: 600;
  color: #ddd;
}

.empty-text {
  margin: 0.25rem 0;
  font-size: 0.8rem;
  color: #666;
  text-align: center;
}

/* 摄像头列表 */
.camera-list {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.camera-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.5rem 0.7rem;
  font-size: 0.85rem;
  border: 1px solid #444;
  border-radius: 6px;
  background: #242424;
  color: #ddd;
  cursor: pointer;
  transition: all 0.15s;
}

.camera-item:hover {
  border-color: #646cff;
}

.camera-item.active {
  background: rgba(100, 108, 255, 0.15);
  border-color: #646cff;
  color: #fff;
  font-weight: 600;
}

.camera-item small {
  color: #888;
  font-size: 0.75rem;
}

/* 蓝牙行 */
.ble-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.ble-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.ble-dot.on {
  background: #4caf50;
}

.ble-dot.busy {
  background: #ff9800;
}

.ble-dot.off {
  background: #666;
}

.ble-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.ble-name {
  font-size: 0.82rem;
  color: #ddd;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ble-sub {
  font-size: 0.72rem;
  color: #888;
}

.mini-btn {
  padding: 0.3rem 0.7rem;
  font-size: 0.78rem;
  border: 1px solid #646cff;
  border-radius: 5px;
  background: #646cff;
  color: #fff;
  cursor: pointer;
  transition: all 0.15s;
}

.mini-btn:hover {
  background: #7a81ff;
}

.mini-btn.danger {
  background: #f44336;
  border-color: #f44336;
}

.mini-btn.danger:hover {
  background: #f55545;
}

/* 信息行 */
.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.25rem 0;
  font-size: 0.82rem;
}

.info-row.clickable {
  cursor: pointer;
  border-radius: 5px;
  padding: 0.25rem 0.35rem;
  margin: 0 -0.35rem;
}

.info-row.clickable:hover {
  background: #242424;
}

.info-label {
  color: #888;
}

.info-value {
  color: #ddd;
  font-weight: 500;
}

.info-value.accent {
  color: #646cff;
}

.info-value.breakable {
  word-break: break-all;
  text-align: right;
}

.client-list {
  margin: 0.3rem 0 0;
  padding-left: 1.1rem;
  font-size: 0.72rem;
  color: #777;
}

.client-list li {
  margin: 0.1rem 0;
}

/* ===== 模态对话框 ===== */
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.6);
}

.modal-card {
  width: min(360px, 90vw);
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  background: #1e1e1e;
  border: 1px solid #3a3a3a;
  border-radius: 10px;
  padding: 1rem;
}

.modal-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  color: #eee;
}

.modal-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  padding: 0.4rem 0;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 0.6rem;
  border-top: 1px solid #2a2a2a;
}

/* 蓝牙设备列表项 */
.ble-device-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.55rem 0.7rem;
  border: 1px solid #444;
  border-radius: 6px;
  background: #242424;
  cursor: pointer;
  transition: border-color 0.15s;
  text-align: left;
}

.ble-device-item:hover {
  border-color: #646cff;
}

.ble-device-name {
  font-size: 0.85rem;
  color: #ddd;
}

.ble-device-rssi {
  font-size: 0.75rem;
}

.ble-device-rssi.good {
  color: #4caf50;
}

.ble-device-rssi.mid {
  color: #ff9800;
}

.ble-device-rssi.bad {
  color: #f44336;
}

.controls {
  display: flex;
  gap: 1rem;
  justify-content: center;
  margin-bottom: 1.5rem;
}

.controls button {
  padding: 0.75rem 1.5rem;
  font-size: 1rem;
  border: 1px solid #555;
  border-radius: 6px;
  background: #2a2a2a;
  color: #fff;
  cursor: pointer;
  transition: all 0.2s;
}

.controls button:hover:not(:disabled) {
  background: #3a3a3a;
  border-color: #646cff;
}

.controls button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.status-bar {
  margin-bottom: 1.5rem;
  font-size: 1rem;
}

.status-value.connected {
  color: #4caf50;
}
.status-value.connecting {
  color: #ff9800;
}
.status-value.failed {
  color: #f44336;
}

.video-container {
  width: 100%;
  max-width: 800px;
  margin: 0 auto 1.5rem;
  background: #111;
  border-radius: 8px;
  overflow: hidden;
  aspect-ratio: 4 / 3;
  position: relative;
}

/* 全屏时铺满视口 */
.video-container:fullscreen {
  max-width: none;
  width: 100vw;
  height: 100vh;
  aspect-ratio: auto;
  border-radius: 0;
}

/* 全屏按钮 */
.fullscreen-btn {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  width: 2.2rem;
  height: 2.2rem;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.1rem;
  line-height: 1;
  border: none;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  cursor: pointer;
  transition: background 0.2s;
  z-index: 10;
}

.fullscreen-btn:hover {
  background: rgba(0, 0, 0, 0.75);
}

.video-player {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.video-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #666;
}

.video-placeholder p {
  margin: 0.25rem;
}

.hint {
  font-size: 0.85rem;
  color: #555;
  margin-top: 0.5rem !important;
  /* i18n 文案中的 \n 换行生效 */
  white-space: pre-line;
}

.hint code {
  background: #222;
  padding: 0.15rem 0.4rem;
  border-radius: 3px;
  font-size: 0.85rem;
}

/* 拉流开/关合并按钮 */
.controls button.toggle-btn {
  padding: 0.75rem 2.5rem;
  background: #4caf50;
  border-color: #4caf50;
}

/* 语言切换按钮（次按钮样式） */
.lang-btn {
  padding: 0.75rem 1rem !important;
  background: #607d8b !important;
  border-color: #607d8b !important;
  font-size: 0.85rem;
}

.controls button.toggle-btn:hover:not(:disabled) {
  background: #5cbf60;
  border-color: #5cbf60;
}

.controls button.toggle-btn.stop {
  background: #f44336;
  border-color: #f44336;
}

.controls button.toggle-btn.stop:hover:not(:disabled) {
  background: #f55545;
  border-color: #f55545;
}

/* 键鼠捕获样式 */
.controls button.active {
  background: #4caf50;
  border-color: #4caf50;
  color: #fff;
}

.status-divider {
  margin: 0 0.5rem;
  color: #555;
}

.video-container.input-active {
  border: 2px solid #4caf50;
  cursor: none;
}

/* 非全屏时的键鼠控制提示（不阻挡交互） */
.fullscreen-hint {
  position: absolute;
  top: 0.5rem;
  left: 50%;
  transform: translateX(-50%);
  padding: 0.3rem 0.8rem;
  border-radius: 1rem;
  background: rgba(0, 0, 0, 0.55);
  color: rgba(255, 255, 255, 0.9);
  font-size: 0.8rem;
  pointer-events: none;
  z-index: 10;
}
</style>