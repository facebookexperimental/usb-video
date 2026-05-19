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

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import com.meta.usbvideo.eventloop.EventLooper
import com.meta.usbvideo.permission.CameraPermissionDenied
import com.meta.usbvideo.permission.CameraPermissionGranted
import com.meta.usbvideo.permission.CameraPermissionRequested
import com.meta.usbvideo.permission.CameraPermissionState
import com.meta.usbvideo.permission.RecordAudioPermissionDenied
import com.meta.usbvideo.permission.RecordAudioPermissionGranted
import com.meta.usbvideo.permission.RecordAudioPermissionRequested
import com.meta.usbvideo.permission.RecordAudioPermissionState
import com.meta.usbvideo.usb.ConnectedUsbDevice
import com.meta.usbvideo.usb.DetachedUsbDevice
import com.meta.usbvideo.usb.LibuvcFrameFormat
import com.meta.usbvideo.usb.SelectedUsbDevice
import com.meta.usbvideo.usb.StreamingStatus
import com.meta.usbvideo.usb.StreamingUsbDeviceState
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoFormatSelectionState
import com.meta.usbvideo.usb.isUvcDevice
import com.meta.usbvideo.usb.loggingName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "StreamerViewModel"
private const val ACTION_USB_PERMISSION: String = "com.meta.usbvideo.USB_PERMISSION"

/** Reactively monitors the state of USB AVC device and implements state transitions methods */
class StreamerViewModel(
    private val application: Application,
    cameraPermission: CameraPermissionState,
    recordAudioPermission: RecordAudioPermissionState,
) : AndroidViewModel(application) {

  private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
  private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>
  private val _videoFormatSelectionStateFlow: MutableStateFlow<VideoFormatSelectionState?> =
      MutableStateFlow(null)

  val videoFormatSelectionStateFlow: StateFlow<VideoFormatSelectionState?> =
      _videoFormatSelectionStateFlow.asStateFlow()

  private val cameraPermissionInternalState: MutableStateFlow<CameraPermissionState> =
      MutableStateFlow(cameraPermission)
  val cameraPermissionStateFlow: StateFlow<CameraPermissionState> =
      cameraPermissionInternalState.asStateFlow()

  private val recordAudioPermissionInternalState: MutableStateFlow<RecordAudioPermissionState> =
      MutableStateFlow(recordAudioPermission)
  val recordAudioPermissionStateFlow: StateFlow<RecordAudioPermissionState> =
      recordAudioPermissionInternalState.asStateFlow()

  private val audioStatsSummary = AudioStatsSummary()
  private val videoStatsSummary = VideoStatsSummary()

  init {
    viewModelScope.launch {
      usbmon.usbDeviceState.collectLatest { deviceState ->
        when (deviceState) {
          is SelectedUsbDevice -> Unit
          is ConnectedUsbDevice -> {
            val videoStreamingConnection = deviceState.videoStreamingConnection
            val supportedFormats = videoStreamingConnection.supportedVideoFormats()
            _videoFormatSelectionStateFlow.value =
                if (supportedFormats.isEmpty()) {
                  null
                } else {
                  VideoFormatSelectionState.Open(
                      videoStreamingConnection.findBestVideoFormat(1920, 1080)
                          ?: supportedFormats.first(),
                  )
                }
          }
          is StreamingUsbDeviceState -> Unit
          null -> Unit
          is DetachedUsbDevice -> {
            EventLooper.call {
              UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
              UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
              UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative()
              UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
              usbmon.disconnect()
            }
            _videoFormatSelectionStateFlow.value = null
          }
        }
      }
    }

    // Ideally, this should be internal to usbmon but usbmon is below the stack and can't access
    // observe the Camera and Record Audio permission states. So, this coroutine observes these
    // states here and triggers to run the USB device state machine when both permissions are
    // granted.
    viewModelScope.launch {
      combine(cameraPermissionStateFlow, recordAudioPermissionStateFlow) {
              cameraPermission,
              recordAudioPermission ->
            cameraPermission to recordAudioPermission
          }
          .collect { (cameraPermission, recordAudioPermission) ->
            if (
                cameraPermission == CameraPermissionGranted &&
                    recordAudioPermission == RecordAudioPermissionGranted
            ) {
              usbmon.onCameraAndRecordPermissionGranted()
            }
          }
    }
  }

  val supportedVideoFormats: List<VideoFormat>
    get() = (usbmon.videoStreamingConnection?.supportedVideoFormats() ?: emptyList())

  fun isMicMuted(): Boolean {
    val audioManager: AudioManager =
        application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
    return audioManager.isMicrophoneMute
  }

  // system protected broadcasts
  private val microphoneMutedReceiver: BroadcastReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          Log.i(TAG, "microphoneMutedReceiver onReceive:")
          usbmon.refreshMicMutedState()
        }
      }

  fun updateSelectedVideoFormat(fourccFormat: String, size: Size, fps: Int) {
    val videoFormat = VideoFormat(fourccFormat, size.width, size.height, fps)
    _videoFormatSelectionStateFlow.value = VideoFormatSelectionState.Open(videoFormat)
  }

  private fun updateNegotiatedVideoFormat(
      videoFormat: VideoFormat,
      success: Boolean,
      message: String,
  ) {
    _videoFormatSelectionStateFlow.value =
        if (success) {
          VideoFormatSelectionState.Negotiated(videoFormat)
        } else {
          VideoFormatSelectionState.NegotiationError(videoFormat, message)
        }
  }

  fun prepareCameraPermissionLaunchers(activity: ComponentActivity) {
    cameraPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
          updateCameraPermissionState(
              if (it) {
                CameraPermissionGranted
              } else {
                CameraPermissionDenied
              }
          )
        }
    recordAudioPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
          updateRecordAudioPermissionState(
              if (it) {
                RecordAudioPermissionGranted
              } else {
                RecordAudioPermissionDenied
              }
          )
        }
    activity.lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_DESTROY) {
            recordAudioPermissionLauncher.unregister()
            cameraPermissionLauncher.unregister()
          }
        }
    )
  }

  // system protected broadcasts
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun prepareUsbBroadcastReceivers(activity: ComponentActivity) {
    activity.registerReceiver(
        microphoneMutedReceiver,
        IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED),
    )
    activity.registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
    activity.registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    activity.registerReceiver(
        usbPermissionReceiver,
        IntentFilter(ACTION_USB_PERMISSION),
        Context.RECEIVER_NOT_EXPORTED,
    )
    activity.lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_DESTROY) {
            activity.unregisterReceiver(usbPermissionReceiver)
            activity.unregisterReceiver(usbReceiver)
            activity.unregisterReceiver(microphoneMutedReceiver)
          }
        }
    )
  }

  private val videoSurfaceStateFlow = MutableStateFlow<Surface?>(null)

  fun surfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
    videoSurfaceStateFlow.value = Surface(surfaceTexture)
  }

  fun surfaceTextureDestroyed(surfaceTexture: SurfaceTexture) {
    Log.i(TAG, "surfaceTextureDestroyed")
    videoSurfaceStateFlow.value?.release()
    videoSurfaceStateFlow.value = null
  }

  private suspend fun genSurface(): Surface {
    return videoSurfaceStateFlow.filterNotNull().first()
  }

  fun negotiateVideoFormat(): Boolean {
    val usbDeviceState = usbmon.usbDeviceState.value
    val videoFormatSelectionState = _videoFormatSelectionStateFlow.value
    if (videoFormatSelectionState == null || usbDeviceState !is ConnectedUsbDevice) {
      return false
    }
    val selectedVideoFormat = videoFormatSelectionState.videoFormat
    viewModelScope.launch {
      val (success, message) =
          EventLooper.call {
            UsbVideoNativeLibrary.connectUsbVideoStreaming(
                application,
                usbDeviceState.videoStreamingConnection,
                selectedVideoFormat,
            )
          }
      updateNegotiatedVideoFormat(selectedVideoFormat, success, message)
      if (success) {
        usbmon.onVideoFormatNegotiated(selectedVideoFormat)
      }
    }
    return true
  }

  suspend fun startStreaming(usbDeviceState: StreamingUsbDeviceState) {
    Log.i(TAG, "Start streaming. Video format: ${usbDeviceState.videoFormat}")
    val videoStreamingSurface = genSurface()
    val (audioStreamStatus, audioStreamMessage) =
        EventLooper.call {
          UsbVideoNativeLibrary.connectUsbAudioStreaming(
                  application.applicationContext,
                  usbDeviceState.audioStreamingConnection,
              )
              .also { UsbVideoNativeLibrary.startUsbAudioStreamingNative() }
        }
    Log.i(TAG, "startUsbAudioStreaming $audioStreamStatus, $audioStreamMessage")
    val (videoStreamStatus, videoStreamMessage) =
        if (usbDeviceState.streamingStatus == StreamingStatus.Restart) {
          EventLooper.call {
            UsbVideoNativeLibrary.connectUsbVideoStreaming(
                    application.applicationContext,
                    usbDeviceState.videoStreamingConnection,
                    usbDeviceState.videoFormat,
                )
                .also { UsbVideoNativeLibrary.startUsbVideoStreamingNative(videoStreamingSurface) }
          }
        } else {
          EventLooper.call {
            if (UsbVideoNativeLibrary.startUsbVideoStreamingNative(videoStreamingSurface)) {
              Pair(true, application.getString(R.string.usb_video_streaming_started))
            } else {
              Pair(false, application.getString(R.string.video_streaming_failed_start))
            }
          }
        }
    Log.i(TAG, "startUsbVideoStreaming $videoStreamStatus, $videoStreamMessage")
    usbmon.onStreamingStarted(
        usbDeviceState,
        audioStreamStatus,
        audioStreamMessage,
        videoStreamStatus,
        videoStreamMessage,
    )
  }

  fun stopStreaming() {
    val uvcDeviceState = usbmon.usbDeviceState.value as? StreamingUsbDeviceState ?: return
    if (uvcDeviceState.streamingStatus is StreamingStatus.Started) {
      Log.i(TAG, "Stopping streaming")
      usbmon.onStreamingStopping()
      EventLooper.post {
        UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
        UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative()
        UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
        UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
        Log.i(TAG, "Stopped streaming")
        usbmon.onStreamingStopped()
      }
    }
  }

  fun restartStreaming() {
    usbmon.onRestartStreaming()
  }

  fun getStreamingStatsSummaryString(): String {
    val usbDeviceState = usbmon.usbDeviceState.value
    return if (usbDeviceState is StreamingUsbDeviceState) {
      val productName = usbDeviceState.usbDevice.productName
      val usbSpeed: String? =
          when (UsbVideoNativeLibrary.getUsbSpeed()) {
            UsbSpeed.Unknown -> null
            UsbSpeed.Low -> application.getString(R.string.usb_speed_low_speed)
            UsbSpeed.Full -> application.getString(R.string.usb_speed_full_speed)
            UsbSpeed.High -> application.getString(R.string.usb_speed_high_speed)
            UsbSpeed.Super -> application.getString(R.string.usb_speed_super_speed)
          }
      UsbVideoNativeLibrary.updateStreamingStatsSummary(audioStatsSummary, videoStatsSummary)
      val line1 = arrayOf(productName, usbSpeed).filterNotNull().joinToString(" \u2022 ")
      val line2 = audioStatsSummary.statsSummaryLine()
      val line3 = videoStatsSummary.statsSummaryLine()
      arrayOf(line1, line2, line3).filterNotNull().joinToString("\n")
    } else {
      ""
    }
  }

  private fun AudioStatsSummary.statsSummaryLine(): String? {
    if (!isValid()) {
      return null
    }
    val audioFormatStringRes =
        when (jAudioFormat) {
          AudioFormat.ENCODING_PCM_16BIT -> R.string.audio_format_pcm_16
          AudioFormat.ENCODING_PCM_8BIT -> R.string.audio_format_pcm_8
          AudioFormat.ENCODING_PCM_FLOAT -> R.string.audio_format_pcm_float
          else -> R.string.audio_format_unknown
        }

    return application.getString(
        R.string.audio_streaming_short_summary_line,
        application.getString(audioFormatStringRes),
        channelCount,
        samplingFrequency,
    )
  }

  private fun VideoStatsSummary.statsSummaryLine(): String? {
    if (!isValid()) {
      return null
    }
    val videoFormatStringRes =
        when (captureFrameFormat) {
          LibuvcFrameFormat.UVC_FRAME_FORMAT_YUYV -> R.string.video_fourcc_format_YUYV
          LibuvcFrameFormat.UVC_FRAME_FORMAT_UYVY -> R.string.video_fourcc_format_UYVY
          LibuvcFrameFormat.UVC_FRAME_FORMAT_MJPEG -> R.string.video_fourcc_format_MJPG
          LibuvcFrameFormat.UVC_FRAME_FORMAT_NV12 -> R.string.video_fourcc_format_NV12
          else -> R.string.video_format_unknown
        }

    return application.getString(
        R.string.video_streaming_short_summary_line,
        application.getString(videoFormatStringRes),
        captureFrameWidth,
        captureFrameHeight,
        fps,
    )
  }

  fun requestCameraPermission() {
    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    updateCameraPermissionState(CameraPermissionRequested)
  }

  fun requestRecordAudioPermission() {
    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    updateRecordAudioPermissionState(RecordAudioPermissionRequested)
  }

  fun requestUsbPermission(context: Context) {
    usbmon.requestUsbPermission {
      PendingIntent.getBroadcast(
          context,
          0,
          Intent(ACTION_USB_PERMISSION),
          PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }

  private fun updateCameraPermissionState(cameraPermission: CameraPermissionState) {
    Log.i(TAG, "updateCameraPermissionState to $cameraPermission")
    cameraPermissionInternalState.value = cameraPermission
  }

  private fun updateRecordAudioPermissionState(recordAudioPermission: RecordAudioPermissionState) {
    Log.i(TAG, "recordAudioPermission set to $recordAudioPermission")
    recordAudioPermissionInternalState.value = recordAudioPermission
  }

  private val usbPermissionReceiver: BroadcastReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (intent.action != ACTION_USB_PERMISSION) {
            return
          }
          Log.i(TAG, "Received broadcast for $ACTION_USB_PERMISSION")
          usbmon.onUsbPermissionResult()
        }
      }

  private val usbReceiver: BroadcastReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          val action = intent.action
          val device =
              when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                  IntentCompat.getParcelableExtra(
                      intent,
                      UsbManager.EXTRA_DEVICE,
                      UsbDevice::class.java,
                  )
                }
                else -> null
              }

          if (device == null || !device.isUvcDevice()) {
            Log.i(TAG, "Received Broadcast $action for device ${device?.loggingName()}")
            return
          }
          when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
              usbmon.onUsbDeviceAttached(device, "BroadcastReceiver")
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
              usbmon.onUsbDeviceDetached(device)
            }
          }
        }
      }
}
