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
import android.util.Log
import android.hardware.usb.UsbDeviceConnection
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.impl.annotations.MockK
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests [VideoStreamingConnection] */
class VideoStreamingConnectionTests {
  @MockK(relaxed = true) private lateinit var usbDevice: UsbDevice
  @MockK(relaxed = true) private lateinit var usbDeviceConnection: UsbDeviceConnection

  @Before
  fun setUp() {
    MockKAnnotations.init(this)
    mockkStatic(Log::class)
    every { Log.v(any(), any()) } returns 0
    every { Log.d(any(), any()) } returns 0
    every { Log.i(any(), any()) } returns 0
    every { Log.e(any(), any()) } returns 0
  }

  @Test
  fun `4K 30Hz U3 MS2130 Generic device YUY2 60fps descriptor test`() {
    videoFormatAndFrameTester(MS2130Generic_YUY2_60FPS, "YUY2", 1920, 1080, 60)
  }

  @Test
  fun `Cam Link 4K YUY2 60fps descriptor test`() {
    videoFormatAndFrameTester(CamLink4K_YUY2_60FPS, "YUY2", 1920, 1080, 60)
  }

  @Test
  fun `Cam Link 4K NV12 24fps descriptor test`() {
    videoFormatAndFrameTester(CamLink4K_N12_24FPS, "NV12", 3840, 2160, 24)
  }

  @Test
  fun `Hagibis YUY2 60fps descriptor test`() {
    videoFormatAndFrameTester(Hagibis, "YUY2", 1920, 1080, 60)
  }

  @Test
  fun `T174445785 YUY2 60fps Cam Link descriptor test`() {
    videoFormatAndFrameTester(CamLink, "YUY2", 1920, 1080, 59)
  }

  private fun videoFormatAndFrameTester(
    usbDescriptor: String,
    fourccFormat: String,
    width: Int,
    height: Int,
    fps: Int,
  ) {
    every { usbDeviceConnection.rawDescriptors } returns
        usbDescriptor
          .filter { it.isDigit() || it.isLetter() }
          .chunked(2)
          .map { it.toInt(16).toByte() }
          .toByteArray()

    val videoStreamingConnection = VideoStreamingConnection(usbDevice, usbDeviceConnection)
    val videoFormat: VideoFormat? = videoStreamingConnection.findBestVideoFormat(width, height)
    assertNotNull(videoFormat)
    assertEquals(expected = width, videoFormat.width)
    assertEquals(expected = height, videoFormat.height)
    assertEquals(expected = fps, videoFormat.fps)
    assertEquals(expected = fourccFormat, videoFormat.fourccFormat)
  }
}
