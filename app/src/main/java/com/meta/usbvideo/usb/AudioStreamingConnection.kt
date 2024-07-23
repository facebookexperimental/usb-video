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
import android.hardware.usb.UsbDeviceConnection
import android.media.AudioFormat
import android.util.Log
import com.meta.usbvideo.UsbVideoNativeLibrary
import com.meta.usbvideo.eventloop.EventLooper
import java.io.Closeable
import java.lang.Exception
import java.nio.ByteBuffer

private const val USB_CLASS_AUDIO = 0x01

private const val USB_SUBCLASS_AUDIO_STREAMING = 0x02

private const val UAC_FORMAT_TYPE_I_PCM = 0x1
private const val UAC_FORMAT_TYPE_I_IEEE_FLOAT = 0x3

private const val UAC_AS_GENERAL = 0x1
private const val UAC_AS_FORMAT = 0x2

private const val TAG = "AudioStreamingConnection"

/** Owner of UsbDeviceConnection for streaming audio */
class AudioStreamingConnection(
    private val usbDevice: UsbDevice,
    private val usbDeviceConnection: UsbDeviceConnection,
) : Closeable {
  val deviceFD: Int = usbDeviceConnection.fileDescriptor

  lateinit var interfaceDescriptor: InterfaceDescriptor
  lateinit var generalDescriptor: AudioStreamingGeneralDescriptor
  lateinit var formatTypeDescriptor: AudioStreamingFormatTypeDescriptor
  lateinit var endpointDescriptor: EndpointDescriptor

  init {
    Log.i(TAG, "Parsing usb descriptors of ${usbDevice.productName} for audio streaming")
    @Suppress("CatchGeneralException")
    try {
      if (parseRawDescriptors(usbDeviceConnection.rawDescriptors)) {
        Log.i(
            TAG,
            """
            wFormatTag ${generalDescriptor.wFormatTag} 
            bNrChannels: ${formatTypeDescriptor.bNrChannels} 
            bSubFrameSize: ${formatTypeDescriptor.bSubFrameSize} 
            bBitResolution: ${formatTypeDescriptor.bBitResolution} 
            tSamFreq: ${formatTypeDescriptor.tSamFreq.joinToString(", ")} 
          """
                .trimIndent())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in parsing USB descriptors for audio streaming", e)
    }
  }

  val supportsAudioStreaming: Boolean = ::endpointDescriptor.isInitialized

  val hasSupportedAudioFormat: Boolean =
      ::generalDescriptor.isInitialized && generalDescriptor.isSupportedFormat()

  val hasFormatTypeDescriptor: Boolean = ::formatTypeDescriptor.isInitialized

  val hasInterfaceDescriptor: Boolean = ::interfaceDescriptor.isInitialized

  val supportedAudioFormat: Int? =
      if (::generalDescriptor.isInitialized && generalDescriptor.isSupportedFormat()) {
        generalDescriptor.toAudioFormat()
      } else {
        null
      }

  private fun parseRawDescriptors(rawDescriptors: ByteArray): Boolean {
    for (descriptor in UsbDescriptorParser(rawDescriptors).descriptors()) {
      when {
        !::interfaceDescriptor.isInitialized ->
            if (descriptor.isInterfaceDescriptorAtLeastOneEndpoint() &&
                descriptor.isInterfaceDescriptorWithAudioStreaming()) {
              Log.i(TAG, "Found device interface with audio streaming and endpoint > 0")
              interfaceDescriptor = InterfaceDescriptor(descriptor.buffer)
            }
        !::generalDescriptor.isInitialized ->
            if (descriptor.isAudioStreamingInterfaceDescriptor()) {
              Log.i(TAG, "Found Audio streaming general interface AS_GENERAL")
              generalDescriptor = AudioStreamingGeneralDescriptor(descriptor.buffer)
            }
        !::formatTypeDescriptor.isInitialized ->
            if (descriptor.isAudioStreamingFormatTypeDescriptor()) {
              Log.i(TAG, "Found Audio streaming format interface AS_FORMAT")
              formatTypeDescriptor = AudioStreamingFormatTypeDescriptor(descriptor.buffer)
            }
        !::endpointDescriptor.isInitialized ->
            if (descriptor.isEndpointDescriptorWithDirIN()) {
              Log.i(TAG, "Found device interface endpoint")
              endpointDescriptor = EndpointDescriptor(descriptor.buffer)
              return true
            }
      }
    }
    return false
  }

  override fun close() {
    Log.e(TAG, "close: disconnectUsbAudioStreamingNative", )
    EventLooper.post { UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative() }
    usbDeviceConnection.close()
  }
}

fun Descriptor.isIADDescriptorWithAudioStreamingFunction(): Boolean {
  return bDescriptorType == USB_DT_IAD &&
      buffer.getBInt(offset + 4) == USB_CLASS_AUDIO &&
      buffer.getBInt(offset + 5) == USB_SUBCLASS_AUDIO_STREAMING
}

fun Descriptor.isInterfaceDescriptorWithAudioStreaming(): Boolean {
  return bDescriptorType == USB_DT_DEVICE_INTERFACE &&
      buffer.getBInt(offset + 5) == USB_CLASS_AUDIO &&
      buffer.getBInt(offset + 6) == USB_SUBCLASS_AUDIO_STREAMING
}

fun Descriptor.isAudioStreamingInterfaceDescriptor(): Boolean {
  return bDescriptorType == USB_DT_CLASSSPECIFIC_INTERFACE &&
      buffer.getBInt(offset + 2) == UAC_AS_GENERAL // AS_GENERAL
}

fun Descriptor.isAudioStreamingFormatTypeDescriptor(): Boolean {
  return bDescriptorType == USB_DT_CLASSSPECIFIC_INTERFACE &&
      buffer.getBInt(offset + 2) == UAC_AS_FORMAT // Format Type
}

/**
 * Audio Streaming Interface Descriptor (AS_GENERAL)
 * <pre>
 *         -------- Audio Streaming Interface Descriptor ---------
 * bLength                  : 0x07 (7 bytes)
 * bDescriptorType          : 0x24 (Audio Interface Descriptor)
 * bDescriptorSubtype       : 0x01 (AS_GENERAL)
 * bTerminalLink            : 0x02 (Terminal ID 2)
 * bDelay                   : 0x01 (1 frame)
 * wFormatTag               : 0x0001 (PCM)
 * Data (HexDump)           : 07 24 01 02 01 01 00                              .$.....
 * </pre>
 */
class AudioStreamingGeneralDescriptor(pack: ByteBuffer) {
  val bLength: Int = pack.getBInt()
  val bDescriptorType: Int = pack.getBInt()
  val bDescriptorSubtype: Int = pack.getBInt()
  val bTerminalLink: Int = pack.getBInt()
  val bDelay: Int = pack.getBInt()
  val wFormatTag: Int = pack.getWInt()

  fun isSupportedFormat(): Boolean =
      (wFormatTag == UAC_FORMAT_TYPE_I_PCM || wFormatTag == UAC_FORMAT_TYPE_I_IEEE_FLOAT)

  fun toAudioFormat(): Int =
      when (wFormatTag) {
        UAC_FORMAT_TYPE_I_PCM -> AudioFormat.ENCODING_PCM_16BIT
        UAC_FORMAT_TYPE_I_IEEE_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        else -> error("Unsupported audio format $wFormatTag")
      }
}

/**
 * Audio Streaming Format Type Descriptor. Example
 * <pre>
 *         ------- Audio Streaming Format Type Descriptor --------
 * bLength                  : 0x0B (11 bytes)
 * bDescriptorType          : 0x24 (Audio Interface Descriptor)
 * bDescriptorSubtype       : 0x02 (Format Type)
 * bFormatType              : 0x01 (FORMAT_TYPE_I)
 * bNrChannels              : 0x02 (2 channels)
 * bSubframeSize            : 0x02 (2 bytes per subframe)
 * bBitResolution           : 0x10 (16 bits per sample)
 * bSamFreqType             : 0x01 (supports 1 sample frequence)
 * tSamFreq[1]              : 0x0BB80 (48000 Hz)
 * Data (HexDump)           : 0B 24 02 01 02 02 10 01 80 BB 00                  .$.........
 * </pre>
 */
class AudioStreamingFormatTypeDescriptor(pack: ByteBuffer) {
  val bLength: Int = pack.getBInt()
  val bDescriptorType: Int = pack.getBInt()
  val bDescriptorSubtype: Int = pack.getBInt()
  val bFormatType: Int = pack.getBInt()
  val bNrChannels: Int = pack.getBInt()
  val bSubFrameSize: Int = pack.getBInt() // bytes per audio subFrame
  val bBitResolution: Int = pack.getBInt() // bits per sample
  val bSamFreqType: Int = pack.getBInt()
  val tSamFreq: IntArray =
      if (bSamFreqType == 0) {
        intArrayOf(pack.getTInt(), pack.getTInt())
      } else {
        IntArray(bSamFreqType) { pack.getTInt() }
      }
}
