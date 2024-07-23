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
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoStreamingConnection

enum class UsbSpeed {
  Unknown,
  Low,
  Full,
  High,
  Super,
}

object UsbVideoNativeLibrary {

  fun getUsbSpeed(): UsbSpeed = UsbSpeed.values()[getUsbDeviceSpeed()]

  private external fun getUsbDeviceSpeed(): Int

  fun connectUsbAudioStreaming(
      context: Context,
      audioStreamingConnection: AudioStreamingConnection,
  ): Pair<Boolean, String> {
    if (!audioStreamingConnection.supportsAudioStreaming) {
      return false to "No Audio Streaming Interface"
    }

    val audioFormat =
        audioStreamingConnection.supportedAudioFormat ?: return false to "No Supported Audio Format"

    if (!audioStreamingConnection.hasFormatTypeDescriptor) {
      return false to "No Audio Streaming Format Descriptor"
    }

    val format: AudioStreamingFormatTypeDescriptor = audioStreamingConnection.formatTypeDescriptor

    val channelCount = format.bNrChannels
    val samplingFrequency = format.tSamFreq.firstOrNull() ?: return false to "No Sample Rate"
    val subFrameSize = format.bSubFrameSize
    val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val outputFramesPerBuffer =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 0

    val deviceFD = audioStreamingConnection.deviceFD

    return if (connectUsbAudioStreamingNative(
        deviceFD,
        audioFormat,
        samplingFrequency,
        subFrameSize,
        channelCount,
        AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
        outputFramesPerBuffer,
    )) {
      true to "Success"
    } else {
      false to "Native audio player failure. Check logs for errors."
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
      videoStreamingConnection: VideoStreamingConnection,
      surface: Surface,
      frameFormat: VideoFormat?,
  ): Pair<Boolean, String> {
    val videoFormat = frameFormat ?: return false to "No supported video format"
    val deviceFD = videoStreamingConnection.deviceFD
    return if (connectUsbVideoStreamingNative(
        deviceFD,
        videoFormat.width,
        videoFormat.height,
        videoFormat.fps,
        videoFormat.toLibuvcFrameFormat().ordinal,
        surface,
    )) {
      true to "Success"
    } else {
      false to "Native video player failure. Check logs for errors."
    }
  }

  external fun connectUsbVideoStreamingNative(
    deviceFD: Int,
    width: Int,
    height: Int,
    fps: Int,
    libuvcFrameFormat: Int,
    surface: Surface,
  ): Boolean
  external fun startUsbVideoStreamingNative(): Boolean
  external fun stopUsbVideoStreamingNative()
  external fun disconnectUsbVideoStreamingNative()

  external fun streamingStatsSummaryString(): String
}
