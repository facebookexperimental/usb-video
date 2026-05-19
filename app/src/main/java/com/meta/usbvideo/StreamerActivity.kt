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

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.meta.usbvideo.animation.ZoomOutPageTransformer
import com.meta.usbvideo.permission.getCameraPermissionState
import com.meta.usbvideo.permission.getRecordAudioPermissionState
import com.meta.usbvideo.usb.ConnectedUsbDevice
import com.meta.usbvideo.usb.DetachedUsbDevice
import com.meta.usbvideo.usb.SelectedUsbDevice
import com.meta.usbvideo.usb.StreamingStatus
import com.meta.usbvideo.usb.StreamingUsbDeviceState
import kotlinx.coroutines.launch

private const val TAG = "StreamerActivity"

enum class StreamerScreen {
  ConnectCaptureCardCTA,
  Status,
  Streaming,
}

class StreamerActivity : ComponentActivity() {

  private lateinit var viewPager: ViewPager2
  private lateinit var screensAdapter: StreamerScreensAdapter

  private val streamerViewModel: StreamerViewModel by viewModels {
    StreamerViewModelFactory(getCameraPermissionState(), getRecordAudioPermissionState())
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    streamerViewModel.prepareCameraPermissionLaunchers(this)
    streamerViewModel.prepareUsbBroadcastReceivers(this)
    setContentView(R.layout.activity_streamer)
    viewPager = findViewById(R.id.view_pager)
    viewPager.offscreenPageLimit = 1
    viewPager.setPageTransformer(ZoomOutPageTransformer())
    screensAdapter =
        StreamerScreensAdapter(
            this,
            streamerViewModel,
            buildInitialScreens(),
        )
    viewPager.adapter = screensAdapter
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        usbmon.usbDeviceState.collect {
          when (it) {
            null -> presentConnectCaptureCardCTA()
            is DetachedUsbDevice -> presentConnectCaptureCardCTA()
            is ConnectedUsbDevice -> presentStatusScreen()
            is SelectedUsbDevice -> presentStatusScreen()
            is StreamingUsbDeviceState -> {
              when (it.streamingStatus) {
                StreamingStatus.Start,
                StreamingStatus.Restart -> {
                  Log.i(TAG, "doOnCreate: presentStreamingScreen ${it.streamingStatus}")
                  presentStreamingScreen()
                  streamerViewModel.startStreaming(it)
                }
                is StreamingStatus.Started -> Unit
                is StreamingStatus.Stopping -> Unit
                StreamingStatus.Stopped -> {
                  streamerViewModel.restartStreaming()
                }
              }
            }
          }
        }
      }
    }

    // for testing splash screen
    val splashScreenDurationMs: Int = intent.getIntExtra("_splash_screen_millis", 0)
    if (splashScreenDurationMs > 0) {
      viewPager.viewTreeObserver.addOnPreDrawListener(
          object : ViewTreeObserver.OnPreDrawListener {
            // wait for the splash screen duration to pass before showing the app content
            override fun onPreDraw(): Boolean =
                SystemClock.uptimeMillis() - Process.getStartUptimeMillis() > splashScreenDurationMs
          }
      )
    }
  }

  private fun presentConnectCaptureCardCTA() {
    val oldScreens: List<StreamerScreen> = screensAdapter.screens
    val newScreens: List<StreamerScreen> = listOf(StreamerScreen.ConnectCaptureCardCTA)
    if (oldScreens != newScreens) {
      screensAdapter.screens = newScreens
      if (oldScreens.isNotEmpty()) {
        screensAdapter.notifyItemRangeRemoved(0, oldScreens.size)
      }
      screensAdapter.notifyItemInserted(0)
      viewPager.setCurrentItem(0, true)
    }
  }

  private fun presentStatusScreen() {
    val oldScreens: List<StreamerScreen> = screensAdapter.screens
    val newScreens: List<StreamerScreen> = listOf(StreamerScreen.Status)
    if (oldScreens != newScreens) {
      screensAdapter.screens = newScreens
      if (oldScreens.isNotEmpty()) {
        screensAdapter.notifyItemRangeRemoved(0, oldScreens.size)
      }
      screensAdapter.notifyItemInserted(0)
      viewPager.setCurrentItem(0, true)
    }
  }

  private fun presentStreamingScreen() {
    val oldScreens: List<StreamerScreen> = screensAdapter.screens
    if (!screensAdapter.screens.contains(StreamerScreen.Streaming)) {
      screensAdapter.screens =
          listOf(
              StreamerScreen.Status,
              StreamerScreen.Streaming,
          )
      if (oldScreens.firstOrNull() == StreamerScreen.Status) {
        screensAdapter.notifyItemInserted(1)
        viewPager.setCurrentItem(1, true)
      } else {
        screensAdapter.notifyItemRangeRemoved(0, oldScreens.size)
        screensAdapter.notifyItemRangeInserted(0, 2)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Log.i(TAG, "onNewIntent: ${intent.action}")
    if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
      val usbDevice: UsbDevice =
          IntentCompat.getParcelableExtra(
              intent,
              UsbManager.EXTRA_DEVICE,
              UsbDevice::class.java,
          ) ?: return
      usbmon.onUsbDeviceAttached(usbDevice, "onNewIntent")
    }
  }

  override fun onStop() {
    super.onStop()
    streamerViewModel.stopStreaming()
  }

  private fun buildInitialScreens(): List<StreamerScreen> {
    return when (usbmon.usbDeviceState.value) {
      is ConnectedUsbDevice -> listOf(StreamerScreen.Status)
      is DetachedUsbDevice -> listOf(StreamerScreen.ConnectCaptureCardCTA)
      is SelectedUsbDevice -> listOf(StreamerScreen.Status)
      is StreamingUsbDeviceState -> {
        Log.e(TAG, "Initial state as streaming is not expected.")
        listOf(StreamerScreen.Status)
      }
      null -> listOf(StreamerScreen.ConnectCaptureCardCTA)
    }
  }
}
