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
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.meta.usbvideo.permission.CameraPermissionDenied
import com.meta.usbvideo.permission.CameraPermissionGranted
import com.meta.usbvideo.permission.CameraPermissionRequested
import com.meta.usbvideo.permission.CameraPermissionRequired
import com.meta.usbvideo.permission.CameraPermissionState
import com.meta.usbvideo.permission.RecordAudioPermissionDenied
import com.meta.usbvideo.permission.RecordAudioPermissionGranted
import com.meta.usbvideo.permission.RecordAudioPermissionRequested
import com.meta.usbvideo.permission.RecordAudioPermissionRequired
import com.meta.usbvideo.permission.RecordAudioPermissionState
import com.meta.usbvideo.usb.UsbDeviceState
import com.meta.usbvideo.usb.UsbMonitor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class StatusScreenViewHolder(
    private val rootView: View,
    private val streamerViewModel: StreamerViewModel,
) : StreamerScreenViewHolder(rootView) {
  private val appPermissionsStatus: TextView
  private val usbDeviceStatus: TextView
  private val audioStreamingStatus: TextView
  private val videoStreamingStatus: TextView

  private val context: Context
    get() = rootView.context

  init {
    appPermissionsStatus = rootView.findViewById(R.id.app_permissions_status)
    usbDeviceStatus = rootView.findViewById(R.id.usb_device_status)
    audioStreamingStatus = rootView.findViewById(R.id.audio_streaming_status)
    videoStreamingStatus = rootView.findViewById(R.id.video_streaming_status)
  }

  fun observeViewModel(
      lifecycleOwner: LifecycleOwner,
      streamerViewModel: StreamerViewModel,
  ) {
    lifecycleOwner.lifecycleScope.launch {
      lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          combine(
                  streamerViewModel.cameraPermissionStateFlow,
                  streamerViewModel.recordAudioPermissionStateFlow,
              ) {
                  cameraPermissionState: CameraPermissionState,
                  recordAudioPermissionState: RecordAudioPermissionState ->
                when {
                  cameraPermissionState is CameraPermissionGranted &&
                      recordAudioPermissionState is RecordAudioPermissionGranted ->
                      context.getString(R.string.app_permissions_granted)
                  cameraPermissionState is CameraPermissionRequired ||
                      cameraPermissionState is CameraPermissionRequested ||
                      recordAudioPermissionState is RecordAudioPermissionRequired ||
                      recordAudioPermissionState is RecordAudioPermissionRequested ->
                      context.getString(R.string.app_permissions_explanation)
                  cameraPermissionState is CameraPermissionDenied &&
                      recordAudioPermissionState is RecordAudioPermissionDenied ->
                      context.getString(R.string.app_permissions_denied)
                  cameraPermissionState is CameraPermissionDenied ->
                      context.getString(R.string.camera_permission_denied)
                  recordAudioPermissionState is RecordAudioPermissionDenied ->
                      context.getString(R.string.record_audio_permission_denied)
                  else -> {
                    context.getString(R.string.app_permissions_explanation)
                  }
                }
              }
              .collectLatest { appPermissionsStatus.text = it }
        }
        launch { UsbMonitor.usbDeviceStateFlow.collectLatest { setUsbDeviceState(it) } }
      }
    }
  }

  fun setUsbDeviceState(uvcDeviceFlow: UsbDeviceState) {
    when (uvcDeviceFlow) {
      UsbDeviceState.NotFound -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_not_found)
        videoStreamingStatus.text = ""
        audioStreamingStatus.text = ""
      }
      is UsbDeviceState.Attached -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_attached)
        videoStreamingStatus.text = ""
        audioStreamingStatus.text = ""
      }
      is UsbDeviceState.Detached -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_detached)
        videoStreamingStatus.text = ""
        audioStreamingStatus.text = ""
      }
      is UsbDeviceState.PermissionRequired -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_permission_required)
        videoStreamingStatus.text = ""
        audioStreamingStatus.text = ""
      }
      is UsbDeviceState.PermissionRequested -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_permission_requested)
        videoStreamingStatus.text = ""
        audioStreamingStatus.text = ""
      }
      is UsbDeviceState.PermissionGranted -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_permission_granted)
        videoStreamingStatus.text = ""
        audioStreamingStatus.text = ""
      }
      is UsbDeviceState.PermissionDenied -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_permission_denied)
        videoStreamingStatus.text = ""
        audioStreamingStatus.text = ""
      }
      is UsbDeviceState.Connected -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_permission_granted)
        videoStreamingStatus.text = context.getString(R.string.video_streaming_wait_state)
        audioStreamingStatus.text = context.getString(R.string.audio_streaming_wait_state)
      }
      is UsbDeviceState.Streaming -> {
        usbDeviceStatus.text = context.getString(R.string.uvc_device_permission_granted)
        if (uvcDeviceFlow.audioStreamingSuccess) {
          val usbDevice = uvcDeviceFlow.usbDevice
          usbDeviceStatus.text =
              context.getString(
                  R.string.device_connected_with_details,
                  usbDevice.productName,
                  UsbVideoNativeLibrary.getUsbSpeed(),
              )
          audioStreamingStatus.text = context.getString(R.string.audio_streaming_success)
        } else {
          audioStreamingStatus.text =
              context.getString(
                  R.string.audio_streaming_failure,
                  uvcDeviceFlow.audioStreamingMessage,
              )
        }
        if (uvcDeviceFlow.videoStreamingSuccess) {
          videoStreamingStatus.text = context.getString(R.string.video_streaming_success)
        } else {
          videoStreamingStatus.text =
              context.getString(
                  R.string.video_streaming_failure,
                  uvcDeviceFlow.videoStreamingMessage,
              )
        }
      }
      is UsbDeviceState.StreamingRestart -> {
        audioStreamingStatus.text = context.getString(R.string.audio_streaming_wait_state)
        videoStreamingStatus.text = context.getString(R.string.video_streaming_wait_state)
      }
      is UsbDeviceState.StreamingStop -> {
        audioStreamingStatus.text = context.getString(R.string.audio_streaming_stopped)
        videoStreamingStatus.text = context.getString(R.string.video_streaming_stopped)
      }
      is UsbDeviceState.StreamingStopped -> {
        audioStreamingStatus.text = context.getString(R.string.audio_streaming_stopped)
        videoStreamingStatus.text = context.getString(R.string.video_streaming_stopped)
      }
    }
  }
}
