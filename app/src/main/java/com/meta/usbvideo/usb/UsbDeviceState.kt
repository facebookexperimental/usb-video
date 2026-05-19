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
  val usbDevice: UsbDevice

  fun loggingDescription(): String
}

enum class SelectedDeviceStatus {
  MIC_MUTED,
  PERMISSION_REQUIRED,
  PERMISSION_REQUESTED,
  PERMISSION_DENIED,
  FAILED_TO_CONNECT,
}

data class SelectedUsbDevice(override val usbDevice: UsbDevice, val status: SelectedDeviceStatus) :
    UsbDeviceState {

  override fun loggingDescription(): String {
    return "Selected(usbDevice=${usbDevice.loggingName()}, status=$status)"
  }
}

class ConnectedUsbDevice(
    override val usbDevice: UsbDevice,
    val audioStreamingConnection: AudioStreamingConnection,
    val videoStreamingConnection: VideoStreamingConnection,
) : UsbDeviceState {
  override fun loggingDescription(): String {
    return "Connected(usbDevice=${usbDevice.loggingName()})"
  }
}

sealed interface StreamingStatus {
  data object Start : StreamingStatus

  class Started(
      val audioStreamingSuccess: Boolean,
      val audioStreamingMessage: String,
      val videoStreamingSuccess: Boolean,
      val videoStreamingMessage: String,
  ) : StreamingStatus

  data object Stopping : StreamingStatus

  data object Stopped : StreamingStatus

  data object Restart : StreamingStatus
}

class StreamingUsbDeviceState(
    override val usbDevice: UsbDevice,
    val audioStreamingConnection: AudioStreamingConnection,
    val videoStreamingConnection: VideoStreamingConnection,
    val videoFormat: VideoFormat,
    val streamingStatus: StreamingStatus,
) : UsbDeviceState {

  fun copy(streamingStatus: StreamingStatus): StreamingUsbDeviceState {
    return StreamingUsbDeviceState(
        usbDevice,
        audioStreamingConnection,
        videoStreamingConnection,
        videoFormat,
        streamingStatus,
    )
  }

  override fun loggingDescription(): String {
    return "Streaming(usbDevice=${usbDevice.loggingName()}, videoFormat=$videoFormat streamingState=$streamingStatus)"
  }
}

data class DetachedUsbDevice(
    override val usbDevice: UsbDevice,
) : UsbDeviceState {
  override fun loggingDescription(): String {
    return "Detached(usbDevice=${usbDevice.loggingName()})"
  }
}
