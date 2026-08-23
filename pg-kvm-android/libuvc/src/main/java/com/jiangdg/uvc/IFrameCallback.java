/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.jiangdg.uvc;

import java.nio.ByteBuffer;

/**
 * Callback interface for UVCCamera class
 * If you need frame data as ByteBuffer, you can use this callback interface with UVCCamera#setFrameCallback
 */
public interface IFrameCallback {
	/**
	 * This method is called from native library via JNI on the same thread as UVCCamera#startCapture.
	 * You can use both UVCCamera#startCapture and #setFrameCallback
	 * but it is better to use either for better performance.
	 * You can also pass pixel format type to UVCCamera#setFrameCallback for this method.
	 * Some frames may drops if this method takes a time.
	 * When you use some color format like NV21, this library never execute color space conversion,
	 * just execute pixel format conversion. If you want to get same result as on screen, please try to
	 * consider to get images via texture(SurfaceTexture) and read pixel buffer from it using OpenGL|ES2/3
	 * instead of using IFrameCallback(this way is much efficient in most case than using IFrameCallback).
	 * @param frame this is direct ByteBuffer from JNI layer and you should handle it's byte order and limitation.
	 */
	public void onFrame(ByteBuffer frame);

	/**
	 * native 层实际调用的是本重载。
	 *
	 * frame 底层内存归 native 帧池所有：帧已"借出"，native 在本方法返回后
	 * 也不会复用/释放这块内存，直到调用方通过 UVCCamera.nativeReleaseFrame(framePtr)
	 * 归还帧池。因此可以零拷贝地把 frame 包装后交给异步消费者，消费完成后
	 * （或提前放弃时）必须调用 nativeReleaseFrame(framePtr)。
	 *
	 * 默认实现保持旧行为：同步处理完立即归还帧，此时 frame 仅在本方法
	 * 返回前有效，实现方应在本方法内完成数据拷贝。
	 *
	 * @param frame 底层为 native 帧池内存的 direct ByteBuffer
	 * @param framePtr 帧句柄，用于归还帧池
	 */
	public default void onFrame(ByteBuffer frame, long framePtr) {
		try {
			onFrame(frame);
		} finally {
			UVCCamera.nativeReleaseFrame(framePtr);
		}
	}
}
