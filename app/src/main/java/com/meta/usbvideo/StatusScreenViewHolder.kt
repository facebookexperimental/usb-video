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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Size
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isVisible
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
import com.meta.usbvideo.usb.ConnectedUsbDevice
import com.meta.usbvideo.usb.DetachedUsbDevice
import com.meta.usbvideo.usb.SelectedDeviceStatus
import com.meta.usbvideo.usb.SelectedUsbDevice
import com.meta.usbvideo.usb.StreamingStatus
import com.meta.usbvideo.usb.StreamingUsbDeviceState
import com.meta.usbvideo.usb.UsbDeviceState
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoFormatSelectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class StatusScreenViewHolder(
    private val rootView: View,
    private val streamerViewModel: StreamerViewModel,
) : StreamerScreenViewHolder(rootView) {
  private val cameraPermissionRow: View = rootView.findViewById(R.id.camera_permission_row)
  private val cameraPermissionStatus: ImageView =
      rootView.findViewById(R.id.camera_permissions_status)
  private val cameraPermissionSubtitle: TextView =
      rootView.findViewById<TextView>(R.id.camera_subtitle)
  private val recordAudioPermissionRow: View =
      rootView.findViewById(R.id.record_audio_permission_row)
  private val recordAudioPermissionStatus: ImageView =
      rootView.findViewById(R.id.record_audio_permissions_status)
  private val recordAudioPermissionSubtitle: TextView =
      rootView.findViewById<TextView>(R.id.record_audio_subtitle)
  private val usbPermissionRow: View = rootView.findViewById(R.id.usb_permission_row)
  private val usbDeviceStartIcon: ImageView = rootView.findViewById(R.id.usb_start_icon)
  private val usbDeviceSubtitleTextView: TextView = rootView.findViewById(R.id.usb_subtitle)
  private val usbDeviceStatus: ImageView = rootView.findViewById(R.id.usb_device_permission_status)
  private val startStreamingButton: View = rootView.findViewById(R.id.start_streaming_button)
  private val videoFormatSelectorHeading: TextView =
      rootView.findViewById(R.id.video_format_selector_title)
  private val videoFormatSelectorMessage: TextView =
      rootView.findViewById(R.id.video_format_selector_message)

  private val videoFormatSelector: Spinner =
      rootView.findViewById(R.id.video_format_selector)

  private val videoFrameSizeSelector: Spinner =
      rootView.findViewById(R.id.frame_size_selector)
  private val videoFrameFpsSelector: Spinner= rootView.findViewById(R.id.frame_fps_selector)
  private var formatsSelectionGroups: Map<String, Map<Size, List<Int>>>? = null
  private var job: Job? = null
  private val context: Context
    get() = rootView.context

  init {
    cameraPermissionRow.setOnClickListener {
      when (streamerViewModel.cameraPermissionStateFlow.value) {
        CameraPermissionDenied -> streamerViewModel.requestCameraPermission()
        CameraPermissionGranted -> Unit
        CameraPermissionRequested -> Unit
        CameraPermissionRequired -> streamerViewModel.requestCameraPermission()
      }
    }
    recordAudioPermissionRow.setOnClickListener {
      when (streamerViewModel.recordAudioPermissionStateFlow.value) {
        RecordAudioPermissionDenied -> streamerViewModel.requestRecordAudioPermission()
        RecordAudioPermissionGranted -> Unit
        RecordAudioPermissionRequested -> Unit
        RecordAudioPermissionRequired -> streamerViewModel.requestRecordAudioPermission()
      }
    }

    usbPermissionRow.setOnClickListener { view ->
      if (streamerViewModel.isMicMuted()) {
        openQuickSettings(view)
      } else {
        streamerViewModel.requestUsbPermission(context)
      }
    }


    videoFormatSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
        val newFormat = parent?.getItemAtPosition(pos) as? String ?: return
        val selectionTree = formatsSelectionGroups ?: return
        val sizeListMap = selectionTree[newFormat] ?: return
        @Suppress("UNCHECKED_CAST")
        val sizeValues = videoFrameSizeSelector.tag as? List<Size>
        val currentSize = sizeValues?.getOrNull(videoFrameSizeSelector.selectedItemPosition)
        val selectedSize = if (currentSize != null && sizeListMap.containsKey(currentSize)) currentSize else sizeListMap.keys.first()
        val fpsList = sizeListMap.getValue(selectedSize)
        @Suppress("UNCHECKED_CAST")
        val fpsValues = videoFrameFpsSelector.tag as? List<Int>
        val currentFps = fpsValues?.getOrNull(videoFrameFpsSelector.selectedItemPosition)
        val selectedFps = if (currentFps != null && fpsList.contains(currentFps)) currentFps else fpsList.first()
        streamerViewModel.updateSelectedVideoFormat(newFormat, selectedSize, selectedFps)
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    videoFrameSizeSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
        @Suppress("UNCHECKED_CAST")
        val sizeValues = videoFrameSizeSelector.tag as? List<Size> ?: return
        val newSize = sizeValues.getOrNull(pos) ?: return
        val selectionTree = formatsSelectionGroups ?: return
        val selectedFormat = videoFormatSelector.selectedItem as? String ?: return
        val sizeListMap = selectionTree[selectedFormat] ?: return
        if (!sizeListMap.containsKey(newSize)) return
        val fpsList = sizeListMap.getValue(newSize)
        @Suppress("UNCHECKED_CAST")
        val fpsValues = videoFrameFpsSelector.tag as? List<Int>
        val currentFps = fpsValues?.getOrNull(videoFrameFpsSelector.selectedItemPosition)
        val selectedFps = if (currentFps != null && fpsList.contains(currentFps)) currentFps else fpsList.first()
        streamerViewModel.updateSelectedVideoFormat(selectedFormat, newSize, selectedFps)
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    videoFrameFpsSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
        @Suppress("UNCHECKED_CAST")
        val fpsValues = videoFrameFpsSelector.tag as? List<Int> ?: return
        val newFps = fpsValues.getOrNull(pos) ?: return
        val selectionTree = formatsSelectionGroups ?: return
        val selectedFormat = videoFormatSelector.selectedItem as? String ?: return
        @Suppress("UNCHECKED_CAST")
        val sizeValues = videoFrameSizeSelector.tag as? List<Size> ?: return
        val selectedSize = sizeValues.getOrNull(videoFrameSizeSelector.selectedItemPosition) ?: return
        val sizeListMap = selectionTree[selectedFormat] ?: return
        val fpsList = sizeListMap[selectedSize] ?: return
        if (!fpsList.contains(newFps)) return
        streamerViewModel.updateSelectedVideoFormat(selectedFormat, selectedSize, newFps)
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
    startStreamingButton.setOnClickListener { streamerViewModel.negotiateVideoFormat() }
  }

  private fun openQuickSettings(it: View) {
    context.startActivity(Intent(Settings.ACTION_SETTINGS))
  }

  fun unbindModel() {
    videoFormatSelectorHeading.isVisible = false
    videoFormatSelector.isEnabled = true
    videoFrameSizeSelector.isEnabled = true
    videoFrameFpsSelector.isEnabled = true
    videoFormatSelector.isVisible = false
    videoFrameSizeSelector.isVisible = false
    videoFrameFpsSelector.isVisible = false
    startStreamingButton.isVisible = false
    job?.cancel()
  }

  fun bindModel(
      lifecycleOwner: LifecycleOwner,
      streamerViewModel: StreamerViewModel,
  ) {
    job =
        lifecycleOwner.lifecycleScope.launch {
          lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
              streamerViewModel.videoFormatSelectionStateFlow.collectLatest { selection ->
                val supportedVideoFormats = streamerViewModel.supportedVideoFormats
                if (selection == null || supportedVideoFormats.isEmpty()) {
                  hideVideoFormatSection()
                } else {
                  applyVideoFormatSelectState(supportedVideoFormats, selection)
                }
              }
            }

            launch {
              streamerViewModel.cameraPermissionStateFlow.collectLatest {
                setCameraPermissionState(it)
              }
            }
            launch {
              streamerViewModel.recordAudioPermissionStateFlow.collectLatest {
                setRecordAudioPermissionState(it)
              }
            }
            launch {
              combine(
                      streamerViewModel.cameraPermissionStateFlow,
                      streamerViewModel.recordAudioPermissionStateFlow,
                      usbmon.usbDeviceState,
                  ) { cameraPermission, recordAudioPermission, usbDeviceState ->
                    Triple(cameraPermission, recordAudioPermission, usbDeviceState)
                  }
                  .collectLatest { (cameraPermission, recordAudioPermission, usbDeviceState) ->
                    onUsbDeviceState(cameraPermission, recordAudioPermission, usbDeviceState)
                  }
            }
          }
        }
  }

  private fun applyVideoFormatSelectState(
      supportedVideoFormats: List<VideoFormat>,
      selection: VideoFormatSelectionState,
  ) {
    val selectedVideoFormat = selection.videoFormat

    when (selection) {
      is VideoFormatSelectionState.Open -> {
        videoFormatSelectorHeading.isVisible = true
        videoFormatSelector.isVisible = true
        videoFrameSizeSelector.isVisible = true
        videoFrameFpsSelector.isVisible = true
        startStreamingButton.isVisible = true
        if (selectedVideoFormat.fps < 30) {
          videoFormatSelectorMessage.text =
              context.getString(
                  R.string.video_streaming_low_fps_warn,
                  selectedVideoFormat.toString(),
              )
          videoFormatSelectorMessage.isVisible = true
          videoFormatSelectorMessage.setTextAppearance(R.style.status_subtitle_warn)
        } else {
          videoFormatSelectorMessage.isVisible = false
        }
        videoFormatSelector.isEnabled = true
        videoFrameSizeSelector.isEnabled = true
        videoFrameFpsSelector.isEnabled = true
      }
      is VideoFormatSelectionState.Negotiated -> {
        videoFormatSelectorHeading.isVisible = true
        videoFormatSelector.isVisible = true
        videoFrameSizeSelector.isVisible = true
        videoFrameFpsSelector.isVisible = true
        startStreamingButton.isVisible = false
        videoFormatSelectorMessage.isVisible = false

        videoFormatSelector.isEnabled = false
        videoFrameSizeSelector.isEnabled = false
        videoFrameFpsSelector.isEnabled = false
      }

      is VideoFormatSelectionState.NegotiationError -> {
        videoFormatSelectorHeading.isVisible = true
        videoFormatSelector.isVisible = true
        videoFrameSizeSelector.isVisible = true
        videoFrameFpsSelector.isVisible = true
        startStreamingButton.isVisible = true
        videoFormatSelectorMessage.isVisible = true

        videoFormatSelector.isEnabled = true
        videoFrameSizeSelector.isEnabled = true
        videoFrameFpsSelector.isEnabled = true
        videoFormatSelectorMessage.setTextAppearance(R.style.status_subtitle_warn)
        videoFormatSelectorMessage.text = selection.errorMessage
      }
    }

    val formatSizeAndFpsGroups: Map<String, Map<Size, List<Int>>> =
        supportedVideoFormats
            .groupBy { it.fourccFormat }
            .mapValues { (_, v) -> v.groupBy { it.size }.mapValues { (_, v) -> v.map { it.fps } } }
    formatsSelectionGroups = formatSizeAndFpsGroups
    val fourccFormats = formatSizeAndFpsGroups.keys

    val formatList = fourccFormats.toList()
    videoFormatSelector.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, formatList)
    videoFormatSelector.setSelection(formatList.indexOf(selectedVideoFormat.fourccFormat).coerceAtLeast(0))

    val sizeListMap = formatSizeAndFpsGroups.getValue(selectedVideoFormat.fourccFormat)
    val sizeList = sizeListMap.keys.toList()
    videoFrameSizeSelector.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sizeList.map { "${it.width}x${it.height}" })
    videoFrameSizeSelector.tag = sizeList
    videoFrameSizeSelector.setSelection(sizeList.indexOf(selectedVideoFormat.size).coerceAtLeast(0))

    val fpsList = sizeListMap.getValue(selectedVideoFormat.size)
    videoFrameFpsSelector.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, fpsList.map { "${it} fps" })
    videoFrameFpsSelector.tag = fpsList
    videoFrameFpsSelector.setSelection(fpsList.indexOf(selectedVideoFormat.fps).coerceAtLeast(0))
  }

  private fun hideVideoFormatSection() {
    videoFormatSelectorHeading.isVisible = false
    videoFormatSelector.isVisible = false
    videoFrameSizeSelector.isVisible = false
    videoFrameFpsSelector.isVisible = false
    startStreamingButton.isVisible = false
    videoFormatSelectorMessage.isVisible = false
  }

  private fun setCameraPermissionState(permissionState: CameraPermissionState) {
    when (permissionState) {
      CameraPermissionGranted -> {
        cameraPermissionRow.isClickable = false
        cameraPermissionSubtitle.setTextAppearance(R.style.status_subtitle_text)
        cameraPermissionSubtitle.setText(R.string.camera_permission_explanation)
        cameraPermissionStatus.setImageResource(R.drawable.check_circle_filled_24)
      }
      CameraPermissionDenied -> {
        cameraPermissionRow.isClickable = true
        cameraPermissionSubtitle.setTextAppearance(R.style.status_subtitle_error)
        cameraPermissionSubtitle.setText(R.string.camera_permission_denied)
        cameraPermissionStatus.setImageResource(R.drawable.check_box_circle_filled_24)
      }
      else -> {
        cameraPermissionRow.isClickable = true
        cameraPermissionSubtitle.setTextAppearance(R.style.status_subtitle_text)
        cameraPermissionSubtitle.setText(R.string.camera_permission_explanation)
        cameraPermissionStatus.setImageResource(R.drawable.check_box_circle_filled_24)
      }
    }
  }

  private fun setRecordAudioPermissionState(permissionState: RecordAudioPermissionState) {
    when (permissionState) {
      RecordAudioPermissionGranted -> {
        recordAudioPermissionRow.isClickable = false
        recordAudioPermissionSubtitle.setTextAppearance(R.style.status_subtitle_text)
        recordAudioPermissionSubtitle.setText(R.string.record_audio_explanation)
        recordAudioPermissionStatus.setImageResource(R.drawable.check_circle_filled_24)
      }
      RecordAudioPermissionDenied -> {
        recordAudioPermissionRow.isClickable = true
        recordAudioPermissionSubtitle.setTextAppearance(R.style.status_subtitle_error)
        recordAudioPermissionSubtitle.setText(R.string.record_audio_permission_denied)
        recordAudioPermissionStatus.setImageResource(R.drawable.check_box_circle_filled_24)
      }
      else -> {
        recordAudioPermissionRow.isClickable = true
        recordAudioPermissionSubtitle.setTextAppearance(R.style.status_subtitle_text)
        recordAudioPermissionSubtitle.setText(R.string.record_audio_explanation)
        recordAudioPermissionStatus.setImageResource(R.drawable.check_box_circle_filled_24)
      }
    }
  }

  private fun onUsbDeviceState(
      cameraPermission: CameraPermissionState,
      recordAudioPermission: RecordAudioPermissionState,
      usbDeviceState: UsbDeviceState?,
  ) {
    usbDeviceStartIcon.setImageResource(R.drawable.usb_stick_filled_24)
    usbDeviceSubtitleTextView.setTextAppearance(R.style.status_subtitle_text)
    usbPermissionRow.isClickable = false
    when (usbDeviceState) {
      null -> {
        usbDeviceStatus.setImageResource(R.drawable.check_box_circle_filled_24)
        usbDeviceSubtitleTextView.setText(R.string.uvc_device_not_found)
      }
      is SelectedUsbDevice -> {
        when (usbDeviceState.status) {
          SelectedDeviceStatus.MIC_MUTED -> {
            usbPermissionRow.isClickable = true
            usbDeviceStartIcon.setImageResource(R.drawable.microphone_off_filled_24)
            usbDeviceSubtitleTextView.setText(R.string.unmute_mic_tip)
            usbDeviceSubtitleTextView.setTextAppearance(R.style.status_subtitle_error)
            usbDeviceStatus.setImageResource(R.drawable.check_box_circle_filled_24)
          }
          SelectedDeviceStatus.PERMISSION_REQUIRED -> {
            if (
                cameraPermission == CameraPermissionGranted &&
                    recordAudioPermission == RecordAudioPermissionGranted
            ) {
              usbPermissionRow.isClickable = true
              usbDeviceStatus.setImageResource(R.drawable.check_box_circle_filled_24)
              usbDeviceSubtitleTextView.text =
                  context.getString(
                      R.string.uvc_device_permission_required_for_name_by_manufacturer,
                      usbDeviceState.usbDevice.productName,
                      usbDeviceState.usbDevice.manufacturerName,
                  )
            } else {
              usbDeviceStatus.setImageResource(R.drawable.check_box_circle_filled_24)
              usbDeviceSubtitleTextView.text =
                  context.getString(
                      R.string.uvc_camera_and_record_permission_requirement,
                      usbDeviceState.usbDevice.productName,
                      usbDeviceState.usbDevice.manufacturerName,
                  )
            }
          }
          SelectedDeviceStatus.PERMISSION_REQUESTED -> {
            usbPermissionRow.isClickable = true
            usbDeviceStatus.setImageResource(R.drawable.check_box_circle_filled_24)
            usbDeviceSubtitleTextView.setText(R.string.uvc_device_permission_requested)
          }
          SelectedDeviceStatus.PERMISSION_DENIED -> {
            usbPermissionRow.isClickable = true
            usbDeviceStatus.setImageResource(R.drawable.check_box_circle_filled_24)
            usbDeviceSubtitleTextView.setText(R.string.uvc_device_permission_denied)
          }
          SelectedDeviceStatus.FAILED_TO_CONNECT -> {
            usbDeviceStatus.setImageResource(R.drawable.check_circle_filled_24)
            usbDeviceSubtitleTextView.text = context.getString(R.string.usb_connect_failure)
            usbDeviceSubtitleTextView.setTextAppearance(R.style.status_subtitle_error)
          }
        }
      }

      is ConnectedUsbDevice -> {
        usbDeviceStatus.setImageResource(R.drawable.check_circle_filled_24)
        if (usbDeviceState.videoStreamingConnection.supportedVideoFormats().isEmpty()) {
          usbDeviceSubtitleTextView.text =
              context.getString(R.string.video_streaming_format_not_found)
          usbDeviceSubtitleTextView.setTextAppearance(R.style.status_subtitle_error)
        } else {
          usbDeviceSubtitleTextView.text =
              context.getString(
                  R.string.device_connected_with_name_by_manufacturer,
                  usbDeviceState.usbDevice.productName,
                  usbDeviceState.usbDevice.manufacturerName,
              )
        }
      }

      is StreamingUsbDeviceState -> {
        when (val streamingState = usbDeviceState.streamingStatus) {
          StreamingStatus.Restart,
          StreamingStatus.Start -> {
            usbDeviceStatus.setImageResource(R.drawable.check_circle_filled_24)
            usbDeviceSubtitleTextView.text =
                context.getString(
                    R.string.device_connected_with_name_by_manufacturer,
                    usbDeviceState.usbDevice.productName,
                    usbDeviceState.usbDevice.manufacturerName,
                )
          }
          is StreamingStatus.Started -> {
            usbDeviceStatus.setImageResource(R.drawable.check_circle_filled_24)
            if (!streamingState.audioStreamingSuccess) {
              usbDeviceSubtitleTextView.text =
                  context.getString(
                      R.string.audio_streaming_failure,
                      streamingState.audioStreamingMessage,
                  )
              usbDeviceSubtitleTextView.setTextAppearance(R.style.status_subtitle_error)
            } else if (!streamingState.videoStreamingSuccess) {
              usbDeviceSubtitleTextView.text =
                  context.getString(
                      R.string.video_streaming_failure,
                      streamingState.videoStreamingMessage,
                  )
              usbDeviceSubtitleTextView.setTextAppearance(R.style.status_subtitle_error)
            } else {
              usbDeviceSubtitleTextView.text =
                  context.getString(
                      R.string.device_connected_with_details,
                      usbDeviceState.usbDevice.productName,
                      UsbVideoNativeLibrary.getUsbSpeed(),
                  )
            }
          }
          StreamingStatus.Stopped -> {
            usbDeviceStatus.setImageResource(R.drawable.check_circle_filled_24)
            usbDeviceSubtitleTextView.setText(R.string.uvc_streaming_stopped)
          }
          StreamingStatus.Stopping -> {
            usbDeviceStatus.setImageResource(R.drawable.check_circle_filled_24)
            usbDeviceSubtitleTextView.setText(R.string.uvc_streaming_stopping)
          }
        }
      }
      is DetachedUsbDevice -> {
        usbDeviceStatus.setImageResource(R.drawable.check_box_circle_filled_24)
        usbDeviceSubtitleTextView.setText(R.string.uvc_device_detached)

        videoFormatSelector.adapter = null
        videoFrameSizeSelector.adapter = null
        videoFrameFpsSelector.adapter = null

        videoFormatSelectorHeading.isVisible = false
        videoFormatSelector.isVisible = false
        videoFrameSizeSelector.isVisible = false
        videoFrameFpsSelector.isVisible = false
      }
    }
  }
}
