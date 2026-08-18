package com.arkj.video.capture;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.jiangdg.usb.USBMonitor;
import com.jiangdg.uvc.IFrameCallback;
import com.jiangdg.uvc.UVCCamera;
import com.jiangdg.utils.Size;

import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 非 GLES 方案的 VideoCapturer：使用 UVCCamera 的 setPreviewDisplay + setFrameCallback
 * 来采集 USB 摄像头画面，转为 I420 后送入 WebRTC 管线。
 *
 * setPreviewDisplay 用于显式本地预览（低延迟），setFrameCallback 获取 NV21 buffer
 * 转换为 I420 后通过 capturerObserver.onFrameCaptured 送入 WebRTC。
 *
 * 不使用 SurfaceTextureHelper / OpenGL。
 */
public class UvcCameraVideoCapture implements VideoCapturer {

  private static final String TAG = "UvcCameraVideoCapture";

  private Context appContext;
  private CapturerObserver capturerObserver;

  private USBMonitor.UsbControlBlock ctrlBlock;
  private Surface previewSurface;

  private UVCCamera uvcCamera;
  private HandlerThread cameraThread;
  private Handler cameraHandler;

  private volatile boolean isRunning;
  private int width;
  private int height;
  private int fps;
  private int rotation;

  // ==================== VideoCapturer 接口 ====================

  @Override
  public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext,
                         CapturerObserver capturerObserver) {
    this.appContext = applicationContext;
    this.capturerObserver = capturerObserver;
    // 非 GLES 方案，忽略 SurfaceTextureHelper
  }

  @Override
  public void startCapture(int width, int height, int fps) {
    this.width = width;
    this.height = height;
    this.fps = fps;

    if (ctrlBlock == null) {
      Log.e(TAG, "startCapture: UsbControlBlock is null, cannot open camera");
      capturerObserver.onCapturerStarted(false);
      return;
    }

    isRunning = true;

    // UVCCamera 需要在有 Looper 的线程上操作
    cameraThread = new HandlerThread("uvc-camera-" + System.currentTimeMillis());
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());

    cameraHandler.post(() -> {
      try {
        if (uvcCamera != null) {
          // 重启场景：stopCapture() 后再次 startCapture()，复用已有的 UVCCamera
          restartPreviewInternal();
        } else {
          openCameraInternal();
        }
        capturerObserver.onCapturerStarted(true);
        Log.i(TAG, "startCapture success: " + width + "x" + height + " @ " + fps + "fps");
      } catch (Exception e) {
        Log.e(TAG, "startCapture failed", e);
        isRunning = false;
        capturerObserver.onCapturerStarted(false);
      }
    });
  }

  /**
   * 停止预览循环线程，保留 UVCCamera 对象（不释放原生资源）。
   * 之后可以重新调用 startCapture() 恢复采集，或调用 dispose() 完全释放。
   */
  @Override
  public void stopCapture() throws InterruptedException {
    Log.i(TAG, "stopCapture");
    isRunning = false;

    if (cameraHandler != null) {
      cameraHandler.post(() -> {
        if (uvcCamera != null) {
          try {
            uvcCamera.stopPreview();
          } catch (Exception e) {
            Log.e(TAG, "stopPreview error", e);
          }
        }
      });
    }

    if (cameraThread != null) {
      cameraThread.quitSafely();
      cameraThread.join(2000);
      cameraThread = null;
      cameraHandler = null;
    }

    capturerObserver.onCapturerStopped();
  }

  @Override
  public void changeCaptureFormat(int width, int height, int fps) {
    Log.i(TAG, "changeCaptureFormat: " + width + "x" + height + " @ " + fps);
    this.width = width;
    this.height = height;
    this.fps = fps;
    // 如需动态切换分辨率，需要 stopPreview → setPreviewSize → startPreview 重新开始
    // 当前实现仅记录参数，实际切换需由上层调用 stopCapture + startCapture
  }

  /**
   * 完全释放 UVCCamera 原生资源。调用前应确保已调用 stopCapture()。
   * 如果 stopCapture 还没调用（异常路径），会先兜底停止。
   */
  @Override
  public void dispose() {
    Log.i(TAG, "dispose");
    isRunning = false;

    // 如果 stopCapture 还没调用，先停止预览线程
    if (cameraThread != null) {
      try {
        stopCapture();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // 释放 UVCCamera 原生资源
    if (uvcCamera != null) {
      try {
        uvcCamera.destroy();
      } catch (Exception e) {
        Log.e(TAG, "destroy error", e);
      }
      uvcCamera = null;
    }

    capturerObserver = null;
    appContext = null;
    ctrlBlock = null;
    previewSurface = null;
  }

  @Override
  public boolean isScreencast() {
    return false;
  }

  // ==================== 公开方法 ====================

  /**
   * 设置 UsbControlBlock（由 UvcCameraHelper 在获取权限后设置）
   */
  public void setUsbControlBlock(USBMonitor.UsbControlBlock ctrlBlock) {
    this.ctrlBlock = ctrlBlock;
  }

  /**
   * 设置预览 Surface（用于 setPreviewDisplay 显式预览）
   * 可在 startCapture 之前或之后调用。传 null 可移除预览。
   */
  public void setPreviewSurface(Surface surface) {
    this.previewSurface = surface;
    if (isRunning && uvcCamera != null) {
      cameraHandler.post(() -> {
        if (uvcCamera != null) {
          try {
            uvcCamera.setPreviewDisplay(surface);
          } catch (Exception e) {
            Log.e(TAG, "setPreviewSurface live update failed", e);
          }
        }
      });
    }
  }

  /**
   * 设置视频帧旋转角度（0, 90, 180, 270）
   */
  public void setRotation(int rotation) {
    this.rotation = rotation;
  }

  /**
   * 获取当前摄像头支持的分辨率列表（需在 startCapture 成功后调用）
   */
  public List<Size> getSupportedResolutions() {
    if (uvcCamera == null) return new ArrayList<>();
    try {
      return uvcCamera.getSupportedSizeList();
    } catch (Exception e) {
      Log.e(TAG, "getSupportedResolutions error", e);
      return new ArrayList<>();
    }
  }

  // ==================== 内部实现 ====================

  private void openCameraInternal() {
    // 1. 创建并打开 UVCCamera
    uvcCamera = new UVCCamera();
    uvcCamera.open(ctrlBlock);

    // 2. 设置预览尺寸，先尝试 MJPEG，失败回退 YUYV
    try {
      uvcCamera.setPreviewSize(width, height,
          UVCCamera.DEFAULT_PREVIEW_MIN_FPS, UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
          UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.DEFAULT_BANDWIDTH);
    } catch (Exception e) {
      Log.w(TAG, "setPreviewSize with MJPEG failed, trying YUYV: " + e.getMessage());
      try {
        uvcCamera.setPreviewSize(width, height,
            UVCCamera.DEFAULT_PREVIEW_MIN_FPS, UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
            UVCCamera.FRAME_FORMAT_YUYV, UVCCamera.DEFAULT_BANDWIDTH);
      } catch (Exception e2) {
        Log.e(TAG, "setPreviewSize with YUYV also failed", e2);
        uvcCamera.destroy();
        uvcCamera = null;
        throw new RuntimeException("Failed to set preview size", e2);
      }
    }

    // 3. 设置预览显示（显式预览）
    if (previewSurface != null) {
      uvcCamera.setPreviewDisplay(previewSurface);
    }

    // 4. 设置帧回调 —— PIXEL_FORMAT_YUV420SP(=4) 在本 fork 实际产 NV21
    uvcCamera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);

    // 5. 自动对焦/白平衡
    uvcCamera.setAutoFocus(true);
    uvcCamera.setAutoWhiteBlance(true);

    // 6. 启动预览
    uvcCamera.startPreview();
    uvcCamera.updateCameraParams();
  }

  /**
   * 重启预览（stopCapture 后复用已有 UVCCamera，不重新 open）
   * 会调用 setPreviewSize 以应用可能的分辨率变更
   */
  private void restartPreviewInternal() {
    // 1. 设置预览尺寸（分辨率变更时必需），先尝试 MJPEG，失败回退 YUYV
    try {
      uvcCamera.setPreviewSize(width, height,
          UVCCamera.DEFAULT_PREVIEW_MIN_FPS, UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
          UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.DEFAULT_BANDWIDTH);
    } catch (Exception e) {
      Log.w(TAG, "restartPreviewInternal MJPEG failed, trying YUYV: " + e.getMessage());
      try {
        uvcCamera.setPreviewSize(width, height,
            UVCCamera.DEFAULT_PREVIEW_MIN_FPS, UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
            UVCCamera.FRAME_FORMAT_YUYV, UVCCamera.DEFAULT_BANDWIDTH);
      } catch (Exception e2) {
        Log.e(TAG, "restartPreviewInternal YUYV also failed", e2);
        throw new RuntimeException("Failed to set preview size on restart", e2);
      }
    }
    // 2. 恢复预览显示
    if (previewSurface != null) {
      uvcCamera.setPreviewDisplay(previewSurface);
    }
    // 3. 恢复帧回调
    uvcCamera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);
    // 4. 启动预览
    uvcCamera.startPreview();
    uvcCamera.updateCameraParams();
  }

  // ==================== 帧回调：NV21 → I420 → WebRTC ====================

  private final IFrameCallback frameCallback = new IFrameCallback() {
    @Override
    public void onFrame(ByteBuffer frame) {
      if (!isRunning || capturerObserver == null) {
        return;
      }

      // 校验帧大小
      final int w = width;
      final int h = height;
      final int expectedSize = w * h * 3 / 2;
      if (frame == null || frame.remaining() < expectedSize) {
        return;
      }

      frame.position(0);

      // 分配 I420 buffer
      JavaI420Buffer i420 = JavaI420Buffer.allocate(w, h);

      try {
        // Y 平面：直接拷贝 w*h 字节
        ByteBuffer dstY = i420.getDataY();
        int strideY = i420.getStrideY();
        ByteBuffer dstU = i420.getDataU();
        int strideU = i420.getStrideU();
        ByteBuffer dstV = i420.getDataV();
        int strideV = i420.getStrideV();

        // 拷贝 Y 平面（逐行，考虑 stride）
        copyPlane(frame, dstY, w, h, w, strideY);

        // NV21 chroma 在 Y 平面之后：VU 交错，每对 (V, U) 对应 2x2 像素块
        int chromaOffset = w * h;
        int uvWidth = w / 2;
        int uvHeight = h / 2;

        // 拆分 NV21 的 VU 交错到 I420 的 U 和 V 平面
        byte[] chromaRow = new byte[w]; // 一整行 chroma 的缓冲区
        for (int row = 0; row < uvHeight; row++) {
          int chromaRowStart = chromaOffset + row * w; // NV21 chroma 行起始
          frame.position(chromaRowStart);

          // 读一行 chroma
          for (int col = 0; col < w; col += 2) {
            int idx = col / 2;
            byte v = frame.get(); // V
            byte u = frame.get(); // U
            // 写入 I420 U/V 平面
            int uvRowOffset = row * strideU + idx;
            if (uvRowOffset < dstU.capacity()) {
              dstU.put(uvRowOffset, u);
            }
            if (uvRowOffset < dstV.capacity()) {
              dstV.put(uvRowOffset, v);
            }
          }
        }

        // 封装 VideoFrame 送入 WebRTC
        long timestampNs = System.nanoTime();
        VideoFrame videoFrame = new VideoFrame(i420, rotation, timestampNs);
        capturerObserver.onFrameCaptured(videoFrame);
      } catch (Exception e) {
        Log.e(TAG, "onFrame error", e);
      } finally {
        i420.release();
      }
    }
  };

  /**
   * 逐行拷贝平面数据，处理 stride
   */
  private static void copyPlane(ByteBuffer src, ByteBuffer dst, int width, int height,
                                int srcStride, int dstStride) {
    byte[] row = new byte[width];
    for (int i = 0; i < height; i++) {
      src.position(i * srcStride);
      src.get(row, 0, width);
      dst.position(i * dstStride);
      dst.put(row, 0, width);
    }
    dst.rewind();
  }
}