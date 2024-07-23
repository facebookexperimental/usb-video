/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.meta.usbvideo.usb

import android.hardware.usb.UsbDevice

/**
 * ADT to describe the state of a USB device. This is used by the <code>UvcDevice</code> object to
 * provide reactive api to observe the state machine and perform state transitions for streaming
 * video and audio from the device.
 */
sealed interface UsbDeviceState {
  object NotFound : UsbDeviceState

  class Attached(val usbDevice: UsbDevice) : UsbDeviceState

  class Detached(val usbDevice: UsbDevice) : UsbDeviceState

  class PermissionRequired(val usbDevice: UsbDevice) : UsbDeviceState

  class PermissionRequested(val usbDevice: UsbDevice) : UsbDeviceState

  class PermissionGranted(val usbDevice: UsbDevice) : UsbDeviceState

  class PermissionDenied(val usbDevice: UsbDevice) : UsbDeviceState

  class Connected(
      val usbDevice: UsbDevice,
      val audioStreamingConnection: AudioStreamingConnection,
      val videoStreamingConnection: VideoStreamingConnection,
  ) : UsbDeviceState

  class Streaming(
      val usbDevice: UsbDevice,
      val audioStreamingConnection: AudioStreamingConnection,
      val audioStreamingSuccess: Boolean,
      val audioStreamingMessage: String,
      val videoStreamingConnection: VideoStreamingConnection,
      val videoStreamingSuccess: Boolean,
      val videoStreamingMessage: String,
  ) : UsbDeviceState

  class StreamingRestart(
    val usbDevice: UsbDevice,
    val audioStreamingConnection: AudioStreamingConnection,
    val videoStreamingConnection: VideoStreamingConnection,
  ) : UsbDeviceState

  class StreamingStop(
    val usbDevice: UsbDevice,
    val audioStreamingConnection: AudioStreamingConnection,
    val videoStreamingConnection: VideoStreamingConnection,
  ) : UsbDeviceState
  class StreamingStopped(
      val usbDevice: UsbDevice,
      val audioStreamingConnection: AudioStreamingConnection,
      val videoStreamingConnection: VideoStreamingConnection,
  ) : UsbDeviceState
}
