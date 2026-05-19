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

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.util.Log
import java.io.Closeable
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private const val TAG = "UsbMonitor"

class UsbMonitor(
    private val applicationContext: Context,
    appScope: CoroutineScope,
    private val onVideoClose: () -> Unit,
    private val onAudioClose: () -> Unit,
) {

  private val _micMutedState: MutableStateFlow<Boolean>
  private val micMutedState: StateFlow<Boolean>

  private val events: Channel<Unit>

  private val _usbDeviceState: MutableStateFlow<UsbDeviceState?>
  val usbDeviceState: StateFlow<UsbDeviceState?>

  private val closeables = mutableListOf<Closeable>()

  init {
    Log.i(TAG, "Initializing USB Monitor")
    _micMutedState = MutableStateFlow(applicationContext.isMicMuted())
    micMutedState = _micMutedState.asStateFlow()
    events = Channel<Unit>(Channel.CONFLATED)
    _usbDeviceState = MutableStateFlow(buildInitialUvcDeviceState())
    usbDeviceState = _usbDeviceState.asStateFlow()

    appScope.launch {
      merge(events.consumeAsFlow(), micMutedState).conflate().collect { runUvcDeviceStateMachine() }
    }
  }

  fun stop() {
    events.close()
  }

  private suspend fun runUvcDeviceStateMachine() {

    suspend fun assignState(state: UsbDeviceState?) {
      Log.i(TAG, "Assigning state ${state?.loggingDescription()}")
      _usbDeviceState.value = state
      yield()
    }

    val enumeratedDevices: List<UsbDevice> = findEnumeratedUVCDevices()
    val currentState = _usbDeviceState.value
    Log.i(TAG, "Running UVC Device State Machine: ${currentState?.loggingDescription()}")
    val selectedUsbDevice = currentState?.usbDevice
    if (selectedUsbDevice != null && enumeratedDevices.contains(selectedUsbDevice)) {
      if (currentState is SelectedUsbDevice) {
        val updatedDevice =
            enumeratedDevices.first {
              it.isUvcDevice() && it.deviceName == selectedUsbDevice.deviceName
            }
        assignState(usbManager?.nextDeviceState(currentState, updatedDevice))
      } else {
        // no-op
        Log.d(TAG, "No change in selected device state")
      }
    } else {
      if (selectedUsbDevice != null && currentState !is DetachedUsbDevice) {
        assignState(DetachedUsbDevice(selectedUsbDevice))
      }
      assignState(null)
      val nextSelectedDevice = enumeratedDevices.firstOrNull()
      if (nextSelectedDevice != null) {
        assignState(buildUvcDeviceState(nextSelectedDevice))
      }
    }
  }

  val videoStreamingConnection: VideoStreamingConnection?
    get() =
        when (val uvcDeviceState = _usbDeviceState.value) {
          is ConnectedUsbDevice -> uvcDeviceState.videoStreamingConnection
          is StreamingUsbDeviceState -> uvcDeviceState.videoStreamingConnection
          else -> null
        }

  fun refreshMicMutedState() {
    _micMutedState.value = applicationContext.isMicMuted()
  }

  fun onCameraAndRecordPermissionGranted() {
    if (_usbDeviceState.value is SelectedUsbDevice) {
      events.trySend(Unit)
    }
  }

  fun onUsbDeviceAttached(device: UsbDevice, caller: String) {
    if (device.isUvcDevice()) {
      Log.i(TAG, "Attached UVC device ${device.loggingName()}, caller: $caller")
      events.trySend(Unit)
    } else {
      Log.i(TAG, "Attached non-UVC device ${device.loggingName()}, caller: $caller")
    }
  }

  fun onUsbDeviceDetached(device: UsbDevice) {
    if (device.isUvcDevice()) {
      Log.i(TAG, "Detached UVC device ${device.loggingName()}")
      events.trySend(Unit)
    } else {
      Log.i(TAG, "Detached non-UVC device ${device.loggingName()}")
    }
  }

  fun requestUsbPermission(pendingIntentBuilder: () -> PendingIntent) {
    val selectedDeviceState = _usbDeviceState.value as? SelectedUsbDevice ?: return
    val usbManager: UsbManager = this.usbManager ?: return
    val updatedDevice =
        findEnumeratedUVCDevices().firstOrNull() {
          it.deviceName == selectedDeviceState.usbDevice.deviceName
        } ?: selectedDeviceState.usbDevice
    if (usbManager.hasPermission(updatedDevice)) {
      Log.i(TAG, "${updatedDevice.loggingName()} already have permission. Updating state.")
      _usbDeviceState.value = buildConnectedState(updatedDevice)
    } else {
      Log.i(TAG, "Requesting USB permission")
      // Request permission from user
      usbManager.requestPermission(updatedDevice, pendingIntentBuilder())
      _usbDeviceState.value =
          SelectedUsbDevice(updatedDevice, SelectedDeviceStatus.PERMISSION_REQUESTED)
    }
  }

  fun onUsbPermissionResult() {
    val selectedDeviceState = _usbDeviceState.value as? SelectedUsbDevice ?: return
    val usbManager: UsbManager = this.usbManager ?: return
    val updatedDevice =
        findEnumeratedUVCDevices().firstOrNull() {
          it.deviceName == selectedDeviceState.usbDevice.deviceName
        } ?: selectedDeviceState.usbDevice
    if (usbManager.hasPermission(updatedDevice)) {
      Log.i(TAG, "${updatedDevice.loggingName()} granted USB permission.")
      _usbDeviceState.value = buildConnectedState(updatedDevice)
    } else {
      Log.i(TAG, "USB permission denied to ${updatedDevice.loggingName()}")
      // Request permission from user
      _usbDeviceState.value =
          SelectedUsbDevice(updatedDevice, SelectedDeviceStatus.PERMISSION_DENIED)
    }
  }

  fun onVideoFormatNegotiated(selectedVideoFormat: VideoFormat) {
    _usbDeviceState.update {
      if (it is ConnectedUsbDevice) {
        StreamingUsbDeviceState(
            it.usbDevice,
            it.audioStreamingConnection,
            it.videoStreamingConnection,
            selectedVideoFormat,
            StreamingStatus.Start,
        )
      } else {
        it
      }
    }
  }

  fun onStreamingStarted(
      usbDeviceState: StreamingUsbDeviceState,
      audioStreamStatus: Boolean,
      audioStreamMessage: String,
      videoStreamStatus: Boolean,
      videoStreamMessage: String,
  ) {
    _usbDeviceState.update {
      StreamingUsbDeviceState(
          usbDeviceState.usbDevice,
          usbDeviceState.audioStreamingConnection,
          usbDeviceState.videoStreamingConnection,
          usbDeviceState.videoFormat,
          StreamingStatus.Started(
              audioStreamStatus,
              audioStreamMessage,
              videoStreamStatus,
              videoStreamMessage,
          ),
      )
    }
  }

  fun onStreamingStopping() {
    _usbDeviceState.update {
      if (it is StreamingUsbDeviceState && it.streamingStatus is StreamingStatus.Started) {
        it.copy(streamingStatus = StreamingStatus.Stopping)
      } else {
        it
      }
    }
  }

  fun onStreamingStopped() {
    _usbDeviceState.update {
      if (it is StreamingUsbDeviceState) {
        it.copy(streamingStatus = StreamingStatus.Stopped)
      } else {
        it
      }
    }
  }

  fun onRestartStreaming() {
    _usbDeviceState.update {
      if (it is StreamingUsbDeviceState && (it.streamingStatus == StreamingStatus.Stopped)) {
        it.copy(streamingStatus = StreamingStatus.Restart)
      } else {
        it
      }
    }
  }

  private fun Context.isMicMuted(): Boolean {
    val audioManager: AudioManager =
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return audioManager.isMicrophoneMute
  }

  private val usbManager: UsbManager?
    get() = applicationContext.getSystemService(UsbManager::class.java)

  private fun findEnumeratedUVCDevices(): List<UsbDevice> {
    val usbManager: UsbManager = this.usbManager ?: return emptyList()
    return usbManager.deviceList.values.filter { it.isUvcDevice() }
  }

  private fun buildInitialUvcDeviceState(): UsbDeviceState? {
    val uvcDevice = findEnumeratedUVCDevices().firstOrNull()
    if (uvcDevice == null) {
      return null
    }
    return usbManager?.nextDeviceState(null, uvcDevice)
  }

  private fun buildUvcDeviceState(uvcDevice: UsbDevice?): UsbDeviceState? {
    if (uvcDevice == null) {
      return null
    }
    return usbManager?.nextDeviceState(_usbDeviceState.value, uvcDevice)
  }

  private fun UsbManager.nextDeviceState(
      currentState: UsbDeviceState?,
      usbDevice: UsbDevice,
  ): UsbDeviceState {
    if (
        currentState is ConnectedUsbDevice ||
            currentState is DetachedUsbDevice ||
            currentState is StreamingUsbDeviceState
    ) {
      return currentState
    }

    val isMicMuted: Boolean = _micMutedState.value
    return if (isMicMuted) {
      SelectedUsbDevice(usbDevice, SelectedDeviceStatus.MIC_MUTED)
    } else if (hasPermission(usbDevice)) {
      buildConnectedState(usbDevice)
    } else {
      SelectedUsbDevice(usbDevice, SelectedDeviceStatus.PERMISSION_REQUIRED)
    }
  }

  fun buildConnectedState(usbDevice: UsbDevice): UsbDeviceState {
    val connections = usbManager?.connect(usbDevice)
    return if (connections == null) {
      SelectedUsbDevice(usbDevice, SelectedDeviceStatus.FAILED_TO_CONNECT)
    } else {
      val (audioStreamingConnection, videoStreamingConnection) = connections
      ConnectedUsbDevice(usbDevice, audioStreamingConnection, videoStreamingConnection)
    }
  }

  private fun UsbManager.connect(
      usbDevice: UsbDevice
  ): Pair<AudioStreamingConnection, VideoStreamingConnection>? {
    val usbConnectionForAudio: UsbDeviceConnection = openDevice(usbDevice) ?: return null
    Log.i(TAG, "======== Start of USB Descriptor =====")
    usbConnectionForAudio.rawDescriptors
        .joinToString(separator = "") {
          String.format(
              Locale.US,
              "%02x",
              it,
          )
        }
        .chunked(64)
        .forEach { Log.i(TAG, it) }
    Log.i(TAG, "======== End of USB Descriptor =====")

    val audioStreamingConnection =
        AudioStreamingConnection(usbDevice, usbConnectionForAudio, onAudioClose)
    closeables.add(audioStreamingConnection)

    val usbConnectionForVideo: UsbDeviceConnection = openDevice(usbDevice) ?: return null

    val videoStreamingConnection =
        VideoStreamingConnection(usbDevice, usbConnectionForVideo, onVideoClose)
    closeables.add(videoStreamingConnection)

    return audioStreamingConnection to videoStreamingConnection
  }

  fun disconnect() {
    for (closeable in closeables) {
      closeable.close()
    }
    closeables.clear()
  }
}
