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
package com.meta.usbvideo

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface
import com.meta.usbvideo.usb.AudioStreamingConnection
import com.meta.usbvideo.usb.AudioStreamingFormatTypeDescriptor
import com.meta.usbvideo.usb.LibuvcFrameFormat
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoStreamingConnection

enum class UsbSpeed {
  Unknown,
  Low,
  Full,
  High,
  Super,
}

enum class VideoStreamerState {
  INITIAL,
  INITIALIZED,
  CONFIGURED,
  STARTED,
  STOPPED,
  INIT_FAILED,
  CONFIGURE_FAILED,
  START_FAILED,
  STOP_FAILED,
}

@androidx.annotation.Keep
class AudioStatsSummary {
  var jAudioFormat: Int = 0
  var channelCount: Int = 0
  var samplingFrequency: Int = 0

  @androidx.annotation.Keep
  fun update(jAudioFormat: Int, channelCount: Int, samplingFrequency: Int) {
    this.jAudioFormat = jAudioFormat
    this.channelCount = channelCount
    this.samplingFrequency = samplingFrequency
  }

  fun isValid(): Boolean = jAudioFormat >= 0 && channelCount >= 0 && samplingFrequency >= 0
}

@androidx.annotation.Keep
class VideoStatsSummary {
  var captureFrameFormat: LibuvcFrameFormat? = null
  var captureFrameWidth: Int = 0
  var captureFrameHeight: Int = 0
  var fps: Int = 0

  @androidx.annotation.Keep
  fun update(captureFrameFormat: Int, captureFrameWidth: Int, captureFrameHeight: Int, fps: Int) {
    this.captureFrameFormat =
        if (captureFrameFormat < 0) null else LibuvcFrameFormat.values()[captureFrameFormat]
    this.captureFrameWidth = captureFrameWidth
    this.captureFrameHeight = captureFrameHeight
    this.fps = fps
  }

  fun isValid(): Boolean =
      captureFrameFormat != null && captureFrameWidth >= 0 && captureFrameHeight >= 0 && fps >= 0
}

object UsbVideoNativeLibrary {

  fun getUsbSpeed(): UsbSpeed = UsbSpeed.values()[getUsbDeviceSpeed()]

  private external fun getUsbDeviceSpeed(): Int

  fun connectUsbAudioStreaming(
      context: Context,
      audioStreamingConnection: AudioStreamingConnection,
  ): Pair<Boolean, String> {
    if (!audioStreamingConnection.supportsAudioStreaming) {
      return false to context.getString(R.string.no_audio_interface)
    }

    val audioFormat =
        audioStreamingConnection.supportedAudioFormat
            ?: return false to context.getString(R.string.no_audio_format)

    if (!audioStreamingConnection.hasFormatTypeDescriptor) {
      return false to context.getString(R.string.no_audio_format)
    }

    val format: AudioStreamingFormatTypeDescriptor = audioStreamingConnection.formatTypeDescriptor

    val channelCount = format.bNrChannels
    val samplingFrequency =
        format.tSamFreq.firstOrNull()
            ?: return false to context.getString(R.string.no_audio_sampling)
    val subFrameSize = format.bSubFrameSize
    val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val outputFramesPerBuffer =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 0

    val deviceFD = audioStreamingConnection.deviceFD

    return if (
        connectUsbAudioStreamingNative(
            deviceFD,
            audioFormat,
            samplingFrequency,
            subFrameSize,
            channelCount,
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
            outputFramesPerBuffer,
        )
    ) {
      true to context.getString(R.string.success)
    } else {
      false to context.getString(R.string.usb_connect_failure)
    }
  }

  private external fun connectUsbAudioStreamingNative(
      deviceFD: Int,
      jAudioFormat: Int,
      samplingFrequency: Int,
      subFrameSize: Int,
      channelCount: Int,
      jAudioPerfMode: Int,
      outputFramesPerBuffer: Int,
  ): Boolean

  external fun disconnectUsbAudioStreamingNative()

  external fun startUsbAudioStreamingNative()

  external fun stopUsbAudioStreamingNative()

  fun connectUsbVideoStreaming(
      context: Context,
      videoStreamingConnection: VideoStreamingConnection,
      frameFormat: VideoFormat?,
  ): Pair<Boolean, String> {
    val videoFormat = frameFormat ?: return false to context.getString(R.string.no_video_format)
    val deviceFD = videoStreamingConnection.deviceFD

    val configuredVideoStreamingState =
        connectUsbVideoStreamingNative(
            deviceFD,
            videoFormat.width,
            videoFormat.height,
            videoFormat.fps,
            videoFormat.toLibuvcFrameFormat().ordinal,
        )
    return when (VideoStreamerState.values()[configuredVideoStreamingState]) {
      VideoStreamerState.CONFIGURED -> true to context.getString(R.string.success)
      VideoStreamerState.CONFIGURE_FAILED ->
          false to context.getString(R.string.video_format_configure_failure)
      else -> {
        false to context.getString(R.string.usb_connect_failure)
      }
    }
  }

  external fun connectUsbVideoStreamingNative(
      deviceFD: Int,
      width: Int,
      height: Int,
      fps: Int,
      libuvcFrameFormat: Int,
  ): Int

  external fun startUsbVideoStreamingNative(surface: Surface): Boolean

  external fun stopUsbVideoStreamingNative()

  external fun disconnectUsbVideoStreamingNative()

  external fun streamingStatsSummaryString(): String

  external fun updateStreamingStatsSummary(
      audioStatsSummary: AudioStatsSummary,
      videoStatsSummary: VideoStatsSummary,
  )
}
