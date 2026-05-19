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

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout.LayoutParams
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.meta.usbvideo.ui.VideoContainerView
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoFormatSelectionState
import com.meta.usbvideo.usb.loggingName

private const val TAG = "StreamingViewHolder"

class StreamingViewHolder(
    private val rootView: View,
    private val streamerViewModel: StreamerViewModel,
) : StreamerScreenViewHolder(rootView) {

  private var overlayMode: OverlayMode = OverlayMode.INITIAL_STATS
  val streamingStats: TextView = rootView.findViewById(R.id.streaming_stats)
  val videoFrame: VideoContainerView = rootView.findViewById(R.id.video_container)
  private val videoTextureView: TextureView = TextureView(videoFrame.context)
  private var lastUpdatedAt = 0L
  private var stateTransitionAt = 0L
  private var checkUSBSpeed = false

  init {
    videoTextureView.surfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
          override fun onSurfaceTextureAvailable(
              surfaceTexture: SurfaceTexture,
              width: Int,
              height: Int,
          ) {
            Log.d(
                TAG,
                "onSurfaceTextureAvailable() called with: surface = $surfaceTexture, width = $width, height = $height",
            )
            streamerViewModel.surfaceTextureAvailable(surfaceTexture, width, height)
          }

          override fun onSurfaceTextureSizeChanged(
              surface: SurfaceTexture,
              width: Int,
              height: Int,
          ) {
            videoFrame.invalidate()
            Log.d(
                TAG,
                "onSurfaceTextureSizeChanged() called with: surface = $surface, width = $width, height = $height",
            )
          }

          override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            Log.d(TAG, "onSurfaceTextureDestroyed() called with: surface = $surfaceTexture")
            streamerViewModel.surfaceTextureDestroyed(surfaceTexture)
            return true
          }

          override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
            if (overlayMode == OverlayMode.NONE) {
              return
            }
            if (checkUSBSpeed) {
              showDegradedUsbSpeedIfSlow()
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastUpdatedAt > 999) {
              val streamingStatsSummaryText =
                  if (overlayMode == OverlayMode.TOGGLE_TIP) {
                    rootView.context.getText(R.string.streaming_stats_toggle_tip)
                  } else {
                    streamerViewModel.getStreamingStatsSummaryString()
                  }
              streamingStats.text = streamingStatsSummaryText
              streamingStats.isVisible = streamingStatsSummaryText.isNotEmpty()
              lastUpdatedAt = now
            }

            if (stateTransitionAt == 0L) {
              stateTransitionAt = now
            } else {
              when (overlayMode) {
                OverlayMode.INITIAL_STATS -> {
                  if (now - stateTransitionAt > 10_000) {
                    updateOverlayMode(overlayMode.next())
                  }
                }
                OverlayMode.TOGGLE_TIP -> {
                  if (now - stateTransitionAt > 5_000) {
                    updateOverlayMode(overlayMode.next())
                  }
                }
                else -> Unit
              }
            }
          }
        }

    videoFrame.addVideoTextureView(
        videoTextureView,
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
    )
    rootView.setOnClickListener {
      if (overlayMode == OverlayMode.STREAMING_STATS) {
        updateOverlayMode(OverlayMode.NONE)
      } else {
        updateOverlayMode(overlayMode.next())
      }
    }
  }

  private fun showDegradedUsbSpeedIfSlow() {
    val usbSpeed: UsbSpeed = UsbVideoNativeLibrary.getUsbSpeed()
    if (usbSpeed == UsbSpeed.Unknown) {
      return
    }
    if (usbSpeed != UsbSpeed.Super) {
      val label: String =
          when (usbSpeed) {
            UsbSpeed.Low -> rootView.resources.getString(R.string.usb_speed_low_speed)
            UsbSpeed.Full -> rootView.resources.getString(R.string.usb_speed_full_speed)
            UsbSpeed.High -> rootView.resources.getString(R.string.usb_speed_high_speed)
            else -> error("Unreachable usb speed $usbSpeed")
          }
      Toast.makeText(
              rootView.context,
              rootView.resources.getString(R.string.expect_degraded_performance, label),
              Toast.LENGTH_LONG,
          )
          .show()
    }
    checkUSBSpeed = false
  }

  fun bindModel() {
    val videoFormatSelectionState = streamerViewModel.videoFormatSelectionStateFlow.value
    if (videoFormatSelectionState is VideoFormatSelectionState.Negotiated) {
      val videoFormat: VideoFormat = videoFormatSelectionState.videoFormat
      videoTextureView.layoutParams =
          LayoutParams(
              videoFormat.width,
              videoFormat.height,
              Gravity.CENTER,
          )
    } else {
      Log.e(
          TAG,
          "Binding failure for ${videoFormatSelectionState?.loggingName()}. Expected negotiated state.",
      )
    }
    videoFrame.requestLayout()
    checkUSBSpeed = true
  }

  private enum class OverlayMode {
    INITIAL_STATS,
    TOGGLE_TIP,
    NONE,
    STREAMING_STATS,
  }

  inline fun <reified T : Enum<T>> T.next(): T {
    val values = enumValues<T>()
    val nextOrdinal = (ordinal + 1) % values.size
    return values[nextOrdinal]
  }

  private fun updateOverlayMode(overlayMode: OverlayMode) {
    this.overlayMode = overlayMode
    if (overlayMode == OverlayMode.NONE) {
      streamingStats.isVisible = false
    }
    stateTransitionAt = SystemClock.uptimeMillis()
  }
}
