# pg-kvm

**Turn an Android phone into an IP-KVM.**

*(**pg** stands for **poor guys** - for those of us who can't justify a few hundred dollars for a
hardware IP-KVM but still have machines to babysit.)*

**English** | [中文](#中文说明)

## Hardware requirements

That's all the hardware you need - no dedicated KVM switch, no extra server:

- 📷 A **UVC video capture card** (HDMI-to-USB, the cheap kind works fine)
- 🔲 An **ESP32-S3 development board**
- 📱 An **Android phone**

---

## Use case

You have a home server sitting in a corner. Most of the time it just works - until one day it
hangs, drops off the network, or needs a BIOS/UEFI tweak. Now you *must* physically connect a
monitor and keyboard to debug it, and hauling a monitor over (finding one, finding the right cable,
clearing desk space) is a pain. Again.

pg-kvm is for exactly that: leave a UVC capture card plugged into the server's HDMI port and an
ESP32-S3 into a USB port, and the next time it acts up, just grab an android phone and open a browser, see the screen, and
type.

## What is this?

pg-kvm is a low-cost, software-only IP-KVM solution built around an ordinary Android phone:

- A **UVC USB camera** (e.g. an HDMI capture card) captures the target computer's screen.
- An **Android app** streams the video to any browser over **WebRTC**, with the signaling server
  embedded in the app itself - no external server needed.
- Keyboard and mouse events from the browser are relayed over **Bluetooth Low Energy (BLE)** to an
  **ESP32-S3** board, which replays them to the target PC as a wired **USB HID** keyboard and mouse.

The result: you can view and control a remote computer from a browser, as if its monitor, keyboard,
and mouse were plugged in directly - with the target PC requiring zero software installation.

> ⚠️ **Status**: the project is fully working but still being polished. Current end-to-end video
> latency is around **1 second**, which is not yet ideal - optimization is ongoing.

```
                    ┌─────────┐
                    │ Browser │  (you)
                    └────┬────┘
      video (WebRTC) ▲   │   ▼ input (WebSocket)
                    ┌────┴────┐
                    │ Android │
                    │   app   │
                    └──▲───▲──┘
 video (USB-OTG) ▲     │   │     ▼ input (BLE)
               ┌───────┘   └───────┐
           ┌────┴─────┐      ┌─────┴────┐
           │   UVC    │      │ ESP32-S3 │
           │  camera  │      │ (bridge) │
           └────▲─────┘      └─────┬────┘
  video (HDMI)  │                  │ input (USB HID) ▼
           ┌────┴──────────────────┴────┐
           │         Target PC          │
           └─────────────────────────────┘
```

### Highlights

- 📹 **UVC camera capture** via a bundled native `libuvc` - works with most USB capture cards and
  UVC webcams through USB-OTG.
- 📡 **Video streaming over WebRTC**, with the signaling server (WebSocket, port 3000) running
  inside the app.
- ⌨️ 🖱️ **Full remote input**: a second WebSocket server (port 3001) receives keyboard/mouse events
  from the browser and forwards them over BLE to the ESP32-S3.
- 🌐 **Web frontend** (Vue 3 + TypeScript) with i18n support - no client installation required.
- 🔌 **Target PC stays untouched** - input is injected as standard USB HID devices.

## Repository structure

```
.
├── pg-kvm-android/        # The Android app + web frontend
│   ├── app/               #   Main app: WebRTC streaming, signaling & input-relay servers, BLE
│   ├── libausbc/          #   UVC camera capture library (forked/derived from herhansen/libausbc)
│   ├── libuvc/            #   Native libuvc (JNI), with libjpeg-turbo and rapidjson
│   ├── libnative/         #   Additional native helpers
│   ├── frontend/          #   Vue 3 + TS + Vite web viewer/controller
│   └── ...
├── pg-kvm-esp32s3-dummy/  # Placeholder describing the ESP32-S3 firmware (see below)
├── LICENSE                # Apache 2.0
└── README.md
```

### ESP32-S3 firmware

The ESP32-S3 side runs the open-source
[ESPRemoteControl](https://github.com/KoStard/ESPRemoteControl/) firmware by KoStard. pg-kvm's
Android app is **protocol-compatible** with it (BLE GATT, service
`2D2A0001-8A5A-4E76-A2E3-1E57D9A1B001`), and the firmware source is **not** bundled here - please
download, compile, and flash it yourself. See
[pg-kvm-esp32s3-dummy/README.md](pg-kvm-esp32s3-dummy/README.md) for details.

## Getting started

1. **Firmware**: clone [ESPRemoteControl](https://github.com/KoStard/ESPRemoteControl/), build it
   with PlatformIO, and flash it to an ESP32-S3 board (see its README).
2. **Android app**: open `pg-kvm-android/` in Android Studio and run it on your phone. Attach a
   UVC capture card / webcam via USB-OTG.
3. **Pair**: in the app, connect to the `KBBridge-ESP32S3` BLE device.
4. **Control**: open the web page served by the app running on the phone in any browser on the
   same network, and start working on the remote PC.

## Acknowledgements

- **[Stream](https://getstream.io/)** - the Android app is derived from the
  [webrtc-sample-compose](https://github.com/getStream/webrtc-sample-compose) sample
  (Apache 2.0).
- **[KoStard](https://github.com/KoStard/)** - author of
  [ESPRemoteControl](https://github.com/KoStard/ESPRemoteControl/), the ESP32-S3 BLE->USB-HID
  bridge firmware this project interoperates with.
- **[herhansen/libausbc](https://github.com/herhansen/libausbc)** and the upstream **libuvc**
  project - UVC camera capture on Android.
- **[jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera)** - UVC
  camera capture on Android, which the `libuvc` module of this project is based on.
- The **WebRTC**, **Vue**, and **NimBLE-Arduino** communities.

## License

Released under the [Apache 2.0 License](LICENSE).

---

## ⭐ Star this project

If pg-kvm saves you from buying an expensive hardware KVM, please consider giving this repository a
Star - it helps others discover the project and keeps development going. Thank you! 🙏

---

# 中文说明

*(**pg** 即 **poor guys** - 懂的都懂)*

## 硬件要求

所需的全部硬件就这些 - 无需专用 KVM 切换器,无需额外服务器:

- 📷 一只 **UVC 视频采集卡**(HDMI 转 USB,便宜的即可)
- 🔲 一块 **ESP32-S3 开发板**
- 📱 一台 **安卓手机**

## 使用场景

家里角落里躺着一台服务器。大多数时候它安静地跑着 - 直到某天它挂了、断网连不上了,或者需要调
整 BIOS/UEFI。这时候你*必须*搬一台显示器和键盘过去物理连接调试:找显示器、找对线、腾桌面……
每次都很折腾。

pg-kvm 正是为这种场景而生:在服务器的 HDMI 口上常驻一只 UVC 采集卡,USB 口上插一个 ESP32-S3。
下次它再犯病,找一台安卓手机连上,打开浏览器就能看到画面、直接敲键盘。

## 这是什么?

pg-kvm 是一套围绕普通 Android 手机构建的低成本、纯软件 IP-KVM 方案:

- 通过 **UVC USB 摄像头**(如 HDMI 采集卡)采集目标电脑的画面;
- **Android 应用**通过 **WebRTC** 将画面推送到任意浏览器,信令服务器内置于应用本身,无需额外
  部署服务端;
- 浏览器端的键盘、鼠标事件经 **BLE(低功耗蓝牙)** 转发到 **ESP32-S3** 开发板,由它以有线
  **USB HID** 键鼠的形式注入目标电脑。

效果等同于:你可以在浏览器里查看并操控远程电脑,就像显示器和键鼠直接插在上面一样 - 目标电脑
**无需安装任何软件**。

> ⚠️ **当前状态**:项目已可完整使用,但仍在打磨中。目前端到端视频延迟约 **1 秒**,尚不理想,
> 仍在持续优化。

```
                    ┌─────────┐
                    │  浏览器 │  (你)
                    └────┬────┘
      视频 (WebRTC) ▲    │    ▼ 输入 (WebSocket)
                    ┌────┴────┐
                    │ Android │
                    │   应用  │
                    └──▲───▲──┘
 视频 (USB-OTG) ▲      │   │      ▼ 输入 (BLE)
               ┌───────┘   └───────┐
           ┌────┴─────┐      ┌─────┴────┐
           │   UVC    │      │ ESP32-S3 │
           │   摄像头 │      │  (桥接器) │
           └────▲─────┘      └─────┬────┘
  视频 (HDMI)  │                   │ 输入 (USB HID) ▼
           ┌────┴──────────────────┴────┐
           │          目标 PC           │
           └─────────────────────────────┘
```

### 特性

- 📹 基于 native `libuvc` 的 **UVC 摄像头采集**,通过 USB-OTG 兼容大多数采集卡和摄像头;
- 📡 基于 **WebRTC** 的视频推流,信令服务器(WebSocket,端口 3000)内置于应用;
- ⌨️ 🖱️ **完整远程输入**:第二个 WebSocket 服务器(端口 3001)接收浏览器键鼠事件并经 BLE
  转发至 ESP32-S3;
- 🌐 **Web 前端**(Vue 3 + TypeScript),支持多语言,客户端无需安装;
- 🔌 **目标电脑零侵入** - 输入以标准 USB HID 设备注入。

## 仓库结构

```
.
├── pg-kvm-android/        # Android 应用 + Web 前端
│   ├── app/               #   主应用:WebRTC 推流、信令与输入中继服务器、BLE
│   ├── libausbc/          #   UVC 摄像头采集库(衍生自 herhansen/libausbc)
│   ├── libuvc/            #   Native libuvc(JNI),含 libjpeg-turbo 与 rapidjson
│   ├── libnative/         #   其他 native 辅助代码
│   ├── frontend/          #   Vue 3 + TS + Vite 网页查看/控制端
│   └── ...
├── pg-kvm-esp32s3-dummy/  # ESP32-S3 固件说明占位目录(见下)
├── LICENSE                # Apache 2.0
└── README.md
```

### ESP32-S3 固件

ESP32-S3 端运行 KoStard 的开源固件
[ESPRemoteControl](https://github.com/KoStard/ESPRemoteControl/)。本项目的 Android 应用与其
**协议兼容**(BLE GATT,服务 UUID `2D2A0001-8A5A-4E76-A2E3-1E57D9A1B001`),固件源码**并未**
包含在本仓库中,请自行下载、编译并烧录,详见
[pg-kvm-esp32s3-dummy/README.md](pg-kvm-esp32s3-dummy/README.md)。

## 快速上手

1. **固件**:克隆 [ESPRemoteControl](https://github.com/KoStard/ESPRemoteControl/),使用
   PlatformIO 编译并烧录到 ESP32-S3 开发板(详见其 README);
2. **Android 应用**:用 Android Studio 打开 `pg-kvm-android/` 并运行到手机,通过 USB-OTG
   接入 UVC 采集卡或摄像头;
3. **配对**:在应用中连接 `KBBridge-ESP32S3` BLE 设备;
4. **控制**:在同一网络的任意浏览器中打开由运行在手机上的服务器提供的网页,即可开始操作
   远程电脑。

## 致谢

- **[Stream](https://getstream.io/)** - Android 应用衍生自其开源示例
  [webrtc-sample-compose](https://github.com/getStream/webrtc-sample-compose)(Apache 2.0);
- **[KoStard](https://github.com/KoStard/)** -
  [ESPRemoteControl](https://github.com/KoStard/ESPRemoteControl/)(ESP32-S3 BLE->USB-HID 桥接
  固件)的作者;
- **[herhansen/libausbc](https://github.com/herhansen/libausbc)** 与上游 **libuvc** 项目 -
  Android 上的 UVC 摄像头采集;
- **[jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera)** - 本项目
  `libuvc` 模块所基于的 Android UVC 摄像头采集开源项目;
- **WebRTC**、**Vue**、**NimBLE-Arduino** 等开源社区。

## 许可证

以 [Apache 2.0](LICENSE) 许可证发布。

---

## ⭐ 求个 Star

如果 pg-kvm 帮你省下了一台昂贵的硬件 KVM,欢迎给仓库点个 Star - 你的支持能让更多人发现这个
项目,也是持续开发的动力。谢谢!🙏
