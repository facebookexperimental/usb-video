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

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.Locale

private const val TAG = "UsbMonitor"
object UsbMonitor {

  private val AV_DEVICE_USB_CLASSES: IntArray =
    intArrayOf(
      UsbConstants.USB_CLASS_VIDEO,
      UsbConstants.USB_CLASS_AUDIO,
    )

  private val closeables = mutableListOf<Closeable>()

  private lateinit var application: Application
  private lateinit var usbDeviceStateInternal: MutableStateFlow<UsbDeviceState>
  lateinit var usbDeviceStateFlow: StateFlow<UsbDeviceState>

  fun init(application: Application) {
    this.application = application
    usbDeviceStateInternal = MutableStateFlow(initialUsbDeviceState())
    usbDeviceStateFlow = usbDeviceStateInternal.asStateFlow()
  }

  fun connect(usbDevice: UsbDevice) {
    getUsbManager()?.prepareDevice(usbDevice)?.let { setState(it) }
  }
  fun findUvcDevice(): UsbDevice? {
    val usbManager: UsbManager = getUsbManager() ?: return null
    val devicesMap: Map<String, UsbDevice> = usbManager.deviceList ?: return null
    return devicesMap.values.firstOrNull { isUvcDevice(it) }
  }
  val usbDeviceState: UsbDeviceState
    get() = usbDeviceStateInternal.value
  private fun addCloseable(closeable: Closeable) {
    closeables.add(closeable)
  }

  fun getUsbManager(): UsbManager? {
    return application.getSystemService(Context.USB_SERVICE) as UsbManager?
  }

  private fun initialUsbDeviceState(): UsbDeviceState {
    val usbManager: UsbManager = getUsbManager() ?: return UsbDeviceState.NotFound
    val usbDevice =
      usbManager.deviceList.values.firstOrNull { isUvcDevice(it) }
        ?: return UsbDeviceState.NotFound
    if (!usbManager.hasPermission(usbDevice)) {
      return UsbDeviceState.PermissionRequired(usbDevice)
    }
    return usbManager.prepareDevice(usbDevice) ?: return UsbDeviceState.PermissionGranted(usbDevice)
  }

  fun UsbManager.prepareDevice(usbDevice: UsbDevice): UsbDeviceState.Connected? {
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

    val audioStreamingConnection = AudioStreamingConnection(usbDevice, usbConnectionForAudio)
    addCloseable(audioStreamingConnection)

    val usbConnectionForVideo: UsbDeviceConnection = openDevice(usbDevice) ?: return null

    val videoStreamingConnection =
      VideoStreamingConnection(
        usbDevice,
        usbConnectionForVideo,
      )
    addCloseable(videoStreamingConnection)

    return UsbDeviceState.Connected(
      usbDevice,
      audioStreamingConnection,
      videoStreamingConnection,
    )
  }

  private fun isUvcDevice(device: UsbDevice): Boolean {
    return device.deviceClass in AV_DEVICE_USB_CLASSES ||
        isMiscDeviceWithInterfaceInAnyDeviceClass(device, AV_DEVICE_USB_CLASSES)
  }

  private fun isMiscDeviceWithInterfaceInAnyDeviceClass(
    device: UsbDevice,
    deviceClasses: IntArray
  ): Boolean {
    return device.deviceClass == UsbConstants.USB_CLASS_MISC &&
        (0 until device.interfaceCount).any {
          device.getInterface(it).interfaceClass in deviceClasses
        }
  }
  fun setState(state: UsbDeviceState) {
    usbDeviceStateInternal.value = state
  }

  fun disconnect() {
    for (closeable in closeables) {
      closeable.close()
    }
    closeables.clear()
  }
}
