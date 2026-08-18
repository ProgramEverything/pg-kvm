package com.arkj.video;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import com.jiangdg.ausbc.utils.CameraUtils;
import com.jiangdg.usb.USBMonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.arkj.compose.models.CameraDeviceInfo;

/**
 * UVC（USB Video Class）摄像头全局 Helper（唯一实例）。
 *
 * 职责：持有 USBMonitor 等全局资源，做设备枚举、插拔监听和 USB 权限申请。
 * 不再创建/持有 VideoCapturer —— 每个摄像头的采集（UsbControlBlock/UVCCamera）
 * 由上层 CameraSession 中的 UvcCameraVideoCapture 承载。
 *
 * USB 权限是异步的（系统弹窗），openControlBlock 可能在权限获取后才回调 onGranted。
 */
public class UvcCameraHelper {

  private static final String TAG = "UvcCameraHelper";

  private final Context context;
  private final USBMonitor usbMonitor;
  private final Map<String, UsbDevice> deviceMap = new HashMap<>();

  // 异步权限流程中的待处理请求：cameraId -> listener
  private final Map<String, OnControlBlockListener> pendingListeners = new HashMap<>();

  // 设备列表变更回调
  private OnDeviceListChangedListener deviceListChangedListener;

  // 活跃摄像头被拔出的回调（由 SessionManager 释放对应会话）
  private OnCameraDetachedListener cameraDetachedListener;

  /** 设备列表变更回调 */
  public interface OnDeviceListChangedListener {
    void onDeviceListChanged(List<CameraDeviceInfo> cameraList);
  }

  /** 活跃摄像头被物理拔出的回调 */
  public interface OnCameraDetachedListener {
    void onCameraDetached(String cameraId);
  }

  /** 申请 UsbControlBlock 的结果回调（异步，权限弹窗后触发） */
  public interface OnControlBlockListener {
    void onGranted(String cameraId, USBMonitor.UsbControlBlock ctrlBlock);
    void onDenied(String cameraId);
  }

  public UvcCameraHelper(Context context) {
    this.context = context.getApplicationContext();

    this.usbMonitor = new USBMonitor(this.context, new USBMonitor.OnDeviceConnectListener() {
      @Override
      public void onAttach(UsbDevice device) {
        if (!isUvcDevice(device)) return;
        Log.i(TAG, "onAttach: " + device.getDeviceName());
        refreshDeviceList();
      }

      @Override
      public void onDetach(UsbDevice device) {
        if (!isUvcDevice(device)) return;
        Log.i(TAG, "onDetach: " + device.getDeviceName());
        String cameraId = device.getDeviceName();
        // 如有未完成的权限申请，直接拒绝
        OnControlBlockListener pending = pendingListeners.remove(cameraId);
        if (pending != null) {
          pending.onDenied(cameraId);
        }
        // 通知上层该摄像头已拔出
        if (cameraDetachedListener != null) {
          cameraDetachedListener.onCameraDetached(cameraId);
        }
        refreshDeviceList();
      }

      @Override
      public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
        if (!isUvcDevice(device)) return;
        Log.i(TAG, "onConnect: " + device.getDeviceName());

        String cameraId = device.getDeviceName();
        OnControlBlockListener pending = pendingListeners.remove(cameraId);
        if (pending != null) {
          pending.onGranted(cameraId, ctrlBlock);
        }
      }

      @Override
      public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        Log.i(TAG, "onDisconnect: " + device.getDeviceName());
      }

      @Override
      public void onCancel(UsbDevice device) {
        Log.w(TAG, "onCancel (permission denied): " + (device != null ? device.getDeviceName() : "null"));
        if (device != null) {
          OnControlBlockListener pending = pendingListeners.remove(device.getDeviceName());
          if (pending != null) {
            pending.onDenied(device.getDeviceName());
          }
        }
      }
    });

    try {
      usbMonitor.register();
      Log.i(TAG, "USBMonitor registered");
    } catch (Exception e) {
      Log.e(TAG, "USBMonitor register failed", e);
    }
  }

  // ==================== 设备枚举 ====================

  /**
   * 获取已连接的 UVC 摄像头列表（同时刷新内部 deviceMap）
   */
  public List<CameraDeviceInfo> getCameraList() {
    List<CameraDeviceInfo> list = new ArrayList<>();
    try {
      List<UsbDevice> devices = usbMonitor.getDeviceList();
      deviceMap.clear();
      for (UsbDevice device : devices) {
        if (isUvcDevice(device)) {
          String cameraId = device.getDeviceName();
          deviceMap.put(cameraId, device);
          String displayName = buildDisplayName(device);
          list.add(new CameraDeviceInfo(cameraId, displayName, false));
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "getCameraList error", e);
    }
    return list;
  }

  // ==================== 打开设备（权限中介） ====================

  /**
   * 为指定摄像头申请 UsbControlBlock。
   * 已有权限时同步回调 onGranted；否则异步走系统权限弹窗，结果通过回调通知。
   * 同一 cameraId 的重复请求会覆盖前一个 listener。
   */
  public void openControlBlock(String cameraId, OnControlBlockListener listener) {
    Log.i(TAG, "openControlBlock: " + cameraId);

    if (cameraId == null) {
      Log.e(TAG, "openControlBlock: cameraId is null");
      return;
    }

    UsbDevice device = deviceMap.get(cameraId);
    if (device == null) {
      // 刷新设备列表再试
      getCameraList();
      device = deviceMap.get(cameraId);
    }

    if (device == null) {
      Log.e(TAG, "Device not found for cameraId: " + cameraId);
      listener.onDenied(cameraId);
      return;
    }

    if (usbMonitor.hasPermission(device)) {
      // 已有权限，直接打开
      try {
        USBMonitor.UsbControlBlock ctrlBlock = usbMonitor.openDevice(device);
        listener.onGranted(cameraId, ctrlBlock);
      } catch (SecurityException e) {
        Log.e(TAG, "openDevice failed despite hasPermission=true", e);
        requestPermission(cameraId, device, listener);
      }
    } else {
      requestPermission(cameraId, device, listener);
    }
  }

  // ==================== 生命周期 ====================

  /**
   * 释放所有全局资源
   */
  public void release() {
    for (Map.Entry<String, OnControlBlockListener> e : pendingListeners.entrySet()) {
      try {
        e.getValue().onDenied(e.getKey());
      } catch (Exception ex) {
        Log.w(TAG, "notify denied on release failed", ex);
      }
    }
    pendingListeners.clear();
    try {
      usbMonitor.unregister();
    } catch (Exception e) {
      Log.e(TAG, "USBMonitor unregister failed", e);
    }
    usbMonitor.destroy();
    deviceMap.clear();
  }

  /**
   * 设置设备列表变更回调
   */
  public void setOnDeviceListChangedListener(OnDeviceListChangedListener listener) {
    this.deviceListChangedListener = listener;
  }

  /**
   * 设置摄像头拔出回调
   */
  public void setOnCameraDetachedListener(OnCameraDetachedListener listener) {
    this.cameraDetachedListener = listener;
  }

  // ==================== 内部实现 ====================

  private void requestPermission(String cameraId, UsbDevice device, OnControlBlockListener listener) {
    pendingListeners.put(cameraId, listener);
    Log.i(TAG, "Requesting USB permission for: " + device.getDeviceName());
    usbMonitor.requestPermission(device);
  }

  private void refreshDeviceList() {
    List<CameraDeviceInfo> list = getCameraList();
    if (deviceListChangedListener != null) {
      deviceListChangedListener.onDeviceListChanged(list);
    }
  }

  private boolean isUvcDevice(UsbDevice device) {
    if (device == null) return false;
    return CameraUtils.INSTANCE.isUsbCamera(device) || CameraUtils.INSTANCE.isFilterDevice(context, device);
  }

  private String buildDisplayName(UsbDevice device) {
    // 尝试获取设备信息
    try {
      USBMonitor.UsbDeviceInfo info = USBMonitor.getDeviceInfo(context, device);
      if (info != null && info.product != null && !info.product.isEmpty()) {
        return info.product + " (USB)";
      }
    } catch (Exception e) {
      // fallback
    }
    return "USB 摄像头 (" + device.getProductId() + ")";
  }
}
