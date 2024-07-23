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
import android.util.Log
import android.util.Size
import com.meta.usbvideo.UsbVideoNativeLibrary
import com.meta.usbvideo.eventloop.EventLooper
import java.io.Closeable
import java.nio.ByteBuffer

private const val TAG = "VideoStreamingConnection"

private const val USB_CLASS_VIDEO = 0x0E // 14

private const val USB_IAD_FUNCTION_CLASS_VIDEO = 0x0E
private const val USB_IAD_FUNCTION_SUBCLASS_VIDEO = 0x03

// A.2. Video Interface Subclass Codes
private const val USB_SUBCLASS_VIDEO_STREAMING: Int = 0x02

// A.6. Video Class-Specific VS Interface Descriptor Subtypes
private const val UVC_VS_FORMAT_UNCOMPRESSED: Int = 0x04
private const val UVC_VS_FRAME_UNCOMPRESSED: Int = 0x05
private const val UVC_VS_FORMAT_MJPEG: Int = 0x06
private const val UVC_VS_FRAME_MJPEG: Int = 0x07

private val SUPPORTED_VIDEO_FOURCC_FORMATS: Array<String> = arrayOf("YUY2", "NV12", "MJPG")

private fun aspectRatio(width: Int, height: Int): Pair<Int, Int> {
  val divisor = gcd(width, height)
  return width / divisor to height / divisor
}

private fun gcd(big: Int, small: Int): Int = if (small == 0) big else gcd(small, big % small)

class VideoStreamingConnection(
    private val usbDevice: UsbDevice,
    private val usbDeviceConnection: UsbDeviceConnection,
) : Closeable {
  val deviceFD: Int = usbDeviceConnection.fileDescriptor
  lateinit var iadDescriptor: IADDescriptor
  lateinit var interfaceDescriptor: InterfaceDescriptor
  lateinit var endpointDescriptor: EndpointDescriptor

  val videoFormats: List<VideoFormat>

  init {
    @Suppress("CatchGeneralException")
    videoFormats =
        try {
          parseRawDescriptors(usbDeviceConnection.rawDescriptors)
        } catch (e: Exception) {
          Log.e(TAG, "Error in parsing USB descriptors for video streaming", e)
          emptyList<VideoFormat>()
        }
    Log.i(TAG, "---- Supported video formats and frame sizes ----")
    videoFormats.forEach { Log.i(TAG, it.toString()) }
  }

  private fun parseRawDescriptors(rawDescriptors: ByteArray): List<VideoFormat> {
    Log.i(TAG, "Parsing usb descriptors of ${usbDevice.productName} for video streaming")
    val formatsBuilder = mutableListOf<VideoFormat>()
    var fourccFormat: String? = null
    for (descriptor in UsbDescriptorParser(rawDescriptors).descriptors()) {
      when {
        !::iadDescriptor.isInitialized ->
            if (descriptor.isIADDescriptorWithVideoStreamingFunction()) {
              Log.i(TAG, "Found IAD descriptor with video streaming function")
              iadDescriptor = IADDescriptor(descriptor.buffer)
            }
        !::interfaceDescriptor.isInitialized &&
            descriptor.isInterfaceDescriptorAtLeastOneEndpoint() &&
            descriptor.isInterfaceDescriptorWithVideoStreaming() -> {
          Log.i(TAG, "Found device interface with video streaming and endpoint > 0")
          interfaceDescriptor = InterfaceDescriptor(descriptor.buffer)
        }
        descriptor.isVSUncompressedFormatTypeDescriptor() -> {
          val uncompressedFormatDescriptor = VSUncompressedFormatDescriptor(descriptor.buffer)
          fourccFormat = uncompressedFormatDescriptor.fourccFormat
        }
        descriptor.isMJPEGVideoFormatDescriptor() -> {
          val mjpegFormatDescriptor = VSMjpegFormatDescriptor(descriptor.buffer)
          fourccFormat = mjpegFormatDescriptor.fourccFormat
        }
        descriptor.isVSFrameDescriptor() -> {
          if (fourccFormat != null) {
            val vsFrameDescriptor = VSFrameDescriptor(descriptor.buffer)
            formatsBuilder.add(
                VideoFormat(
                    fourccFormat,
                    vsFrameDescriptor.width(),
                    vsFrameDescriptor.height(),
                    vsFrameDescriptor.fps(),
                ))
          } else {
            Log.e(TAG, "Found Frame Type Descriptor without a prior format descriptor")
          }
        }
        ::interfaceDescriptor.isInitialized && !::endpointDescriptor.isInitialized ->
            if (descriptor.isEndpointDescriptorWithDirIN()) {
              Log.i(TAG, "Found device interface endpoint")
              endpointDescriptor = EndpointDescriptor(descriptor.buffer)
            }
        ::iadDescriptor.isInitialized && descriptor.isIADDescriptor() -> {
          return formatsBuilder.toList()
        }
      }
    }
    return formatsBuilder.toList()
  }

  override fun close() {
    Log.e(TAG, "close: disconnectUsbAudioStreamingNative", )
    EventLooper.post {
      Log.d("VideoStreamingDescriptor", "stopUsbVideoStreamingNative")
      UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
      UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
      Log.i("VideoStreamingDescriptor", "Closing video streaming descriptor")
      usbDeviceConnection.close()
    }
  }

  fun findBestVideoFormat(size: Size): VideoFormat? = findBestVideoFormat(size.width, size.height)

  fun findBestVideoFormat(width: Int, height: Int): VideoFormat? {
    if (videoFormats.isEmpty()) {
      return null
    }

    return videoFormatFor(width, height).also {
      Log.i(TAG, "Resolved video format for ${width}x${height} screen: $it")
    }
  }

  private fun videoFormatFor(width: Int, height: Int): VideoFormat? {
    // match size at 60fps
    matchExactSize(width, height, 60)?.let {
      return it
    }

    // match size at any fps
    matchExactSize(width, height)?.let {
      return it
    }

    // look for aspect ratio match
    matchAspectRatio(width, height)?.let {
      return it
    }

    // look for a closest aspect ratio size match
    matchClosestAspectRatio(width, height)?.let {
      return it
    }

    // look for a closest area size match
    return matchClosetArea(width, height)
  }

  fun matchExactSize(width: Int, height: Int, fps: Int = 0): VideoFormat? {
    return videoFormats.find {
      SUPPORTED_VIDEO_FOURCC_FORMATS.contains(it.fourccFormat) &&
          width == it.width &&
          height == it.height &&
          (fps == 0 || fps == it.fps)
    }
  }

  fun matchAspectRatio(width: Int, height: Int): VideoFormat? {
    val supportedFormats =
        videoFormats.filter { SUPPORTED_VIDEO_FOURCC_FORMATS.contains(it.fourccFormat) }
    val aspectRatio = aspectRatio(width, height)
    val area = width * height
    val byAspectRatio: Map<Pair<Int, Int>, List<VideoFormat>> =
        supportedFormats.groupBy { it.aspectRatio }

    return byAspectRatio.get(aspectRatio)?.let { formats: List<VideoFormat> ->
      val (betterHalf, lesserHalf) = formats.partition { it.area >= area }
      betterHalf.minByOrNull { it.area } ?: lesserHalf.maxBy { it.area }
    }
  }

  fun matchClosestAspectRatio(width: Int, height: Int): VideoFormat? {
    val supportedFormats =
        videoFormats.filter {
          SUPPORTED_VIDEO_FOURCC_FORMATS.contains(it.fourccFormat) &&
              (it.width >= width || it.height >= height)
        }
    val aspectRatio = width.toFloat() / height.toFloat()
    val (smaller, bigger) = supportedFormats.partition { it.aspectRatioFloat > aspectRatio }
    return bigger.minByOrNull { it.aspectRatioFloat } ?: smaller.maxByOrNull { it.aspectRatioFloat }
  }

  fun matchClosetArea(width: Int, height: Int): VideoFormat? {
    val supportedFormats =
        videoFormats.filter { SUPPORTED_VIDEO_FOURCC_FORMATS.contains(it.fourccFormat) }
    val area = width * height
    val (smallerHalf, biggerHalf) = supportedFormats.partition { it.area <= area }
    return smallerHalf.maxByOrNull { it.area } ?: biggerHalf.minByOrNull { it.area }
  }

  fun matchExact(formatToMatch: String, width: Int, height: Int, fps: Int): VideoFormat? {
    return videoFormats.find {
      it.fourccFormat == formatToMatch && it.width == width && it.height == height && it.fps == fps
    }
  }

  fun matchFormat(formatToMatch: String): VideoFormat? {
    return videoFormats.find { it.fourccFormat == formatToMatch }
  }
}

fun Descriptor.isIADDescriptorWithVideoStreamingFunction(): Boolean {
  return bDescriptorType == USB_DT_IAD &&
      buffer.getBInt(offset + 4) == USB_IAD_FUNCTION_CLASS_VIDEO &&
      buffer.getBInt(offset + 5) == USB_IAD_FUNCTION_SUBCLASS_VIDEO
}

fun Descriptor.isIADDescriptor(): Boolean {
  return bDescriptorType == USB_DT_IAD
}

fun Descriptor.isInterfaceDescriptorWithVideoStreaming(): Boolean {
  return bDescriptorType == USB_DT_DEVICE_INTERFACE &&
      buffer.getBInt(offset + 5) == USB_CLASS_VIDEO &&
      buffer.getBInt(offset + 6) == USB_SUBCLASS_VIDEO_STREAMING
}

fun Descriptor.isVSUncompressedFormatTypeDescriptor(): Boolean {
  return bDescriptorType == USB_DT_CLASSSPECIFIC_INTERFACE &&
      buffer.getBInt(offset + 2) == UVC_VS_FORMAT_UNCOMPRESSED
}

fun Descriptor.isMJPEGVideoFormatDescriptor(): Boolean {
  return bDescriptorType == USB_DT_CLASSSPECIFIC_INTERFACE &&
      buffer.getBInt(offset + 2) == UVC_VS_FORMAT_MJPEG
}

fun Descriptor.isVSFrameDescriptor(): Boolean {
  return bDescriptorType == USB_DT_CLASSSPECIFIC_INTERFACE &&
      buffer.getBInt(offset + 2) in intArrayOf(UVC_VS_FRAME_UNCOMPRESSED, UVC_VS_FRAME_MJPEG)
}

fun Descriptor.isVideoStreamingEndpoint(): Boolean {
  return bDescriptorType == USB_DT_CLASSSPECIFIC_INTERFACE &&
      buffer.getBInt(offset + 2) == UVC_VS_FORMAT_UNCOMPRESSED
}

data class VideoFormat(
    val fourccFormat: String,
    val width: Int,
    val height: Int,
    val fps: Int,
) {
  override fun toString(): String = "$fourccFormat ${width}x$height @$fps"

  fun label(): String = "$fourccFormat ${width}x$height @$fps"

  val aspectRatio: Pair<Int, Int> = aspectRatio(width, height)
  val aspectRatioFloat: Float = width.toFloat() / height.toFloat()
  val area: Int = width * height

  fun toLibuvcFrameFormat(): LibuvcFrameFormat {
    return when (fourccFormat) {
      "YUY2" -> LibuvcFrameFormat.UVC_FRAME_FORMAT_YUYV
      "MJPG" -> LibuvcFrameFormat.UVC_FRAME_FORMAT_MJPEG
      "NV12" -> LibuvcFrameFormat.UVC_FRAME_FORMAT_NV12
      else -> throw IllegalArgumentException("Unsupported fourcc format $fourccFormat")
    }
  }
}

/**
 * <pre>
 *        ------- VS Uncompressed Format Type Descriptor --------
 * bLength                  : 0x1B (27 bytes)
 * bDescriptorType          : 0x24 (Video Streaming Interface)
 * bDescriptorSubtype       : 0x04 (Uncompressed Format Type)
 * bFormatIndex             : 0x02 (2)
 * bNumFrameDescriptors     : 0x0B (11 Frame Descriptors)
 * guidFormat               : {32595559-0000-0010-8000-00AA00389B71} (YUY2)
 * bBitsPerPixel            : 0x10 (16 bits per pixel)
 * bDefaultFrameIndex       : 0x01 (Index 1)
 * bAspectRatioX            : 0x00
 * bAspectRatioY            : 0x00
 * bmInterlaceFlags         : 0x00
 *  D0 IL stream or variable: 0 (no)
 *  D1 Fields per frame     : 0 (2 fields)
 *  D2 Field 1 first        : 0 (no)
 *  D3 Reserved             : 0
 *  D4..5 Field pattern     : 0 (Field 1 only)
 *  D6..7 Display Mode      : 0 (Bob only)
 * bCopyProtect             : 0x00 (No restrictions)
 * Data (HexDump)           : 1B 24 04 02 0B 59 55 59 32 00 00 10 00 80 00 00   .$...YUY2.......
 *                            AA 00 38 9B 71 10 01 00 00 00 00                  ..8.q......
 * </pre>
 */
class VSUncompressedFormatDescriptor(pack: ByteBuffer) {
  val bLength: Int = pack.getBInt()
  val bDescriptorType: Int = pack.getBInt()
  val bDescriptorSubtype: Int = pack.getBInt()
  val bFormatIndex: Int = pack.getBInt()
  val bNumFrameDescriptors: Int = pack.getBInt()

  // the descriptor contains 16 bytes guidFormat but we only read first 4 bytes which is
  // fourccFormat and skip 12
  val fourccFormat: String =
      String(byteArrayOf(pack.get(), pack.get(), pack.get(), pack.get())).also {
        pack.position(pack.position() + 12)
      }

  val bBitsPerPixel: Int = pack.getBInt()
  val bDefaultFrameIndex: Int = pack.getBInt()
  val bAspectRatioX: Int = pack.getBInt()
  val bAspectRatioY: Int = pack.getBInt()
  val bmInterlaceFlags: Int = pack.getBInt()
  val bCopyProtect: Int = pack.getBInt()
}

/**
 * MJPEG Video Format Descriptor
 * <pre>
 *         ----- Video Streaming MJPEG Format Type Descriptor ----
 * bLength                  : 0x0B (11 bytes)
 * bDescriptorType          : 0x24 (Video Streaming Interface)
 * bDescriptorSubtype       : 0x06 (Format MJPEG)
 * bFormatIndex             : 0x01 (1)
 * bNumFrameDescriptors     : 0x0B (11)
 * bmFlags                  : 0x01 (Sample size is fixed)
 * bDefaultFrameIndex       : 0x01 (1)
 * bAspectRatioX            : 0x00
 * bAspectRatioY            : 0x00
 * bmInterlaceFlags         : 0x00
 *  D0 IL stream or variable: 0 (no)
 *  D1 Fields per frame     : 0 (2 fields)
 *  D2 Field 1 first        : 0 (no)
 *  D3 Reserved             : 0
 *  D4..5 Field pattern     : 0 (Field 1 only)
 *  D6..7 Display Mode      : 0 (Bob only)
 * bCopyProtect             : 0x00 (No restrictions)
 * Data (HexDump)           : 0B 24 06 01 0B 01 01 00 00 00 00                  .$.........
 * </pre
 */
class VSMjpegFormatDescriptor(pack: ByteBuffer) {
  val bLength: Int = pack.getBInt()
  val bDescriptorType: Int = pack.getBInt()
  val bDescriptorSubtype: Int = pack.getBInt()
  val bFormatIndex: Int = pack.getBInt()
  val bNumFrameDescriptors: Int = pack.getBInt()
  val bmFlags: Int = pack.getBInt()
  val bDefaultFrameIndex: Int = pack.getBInt()
  val bAspectRatioX: Int = pack.getBInt()
  val bAspectRatioY: Int = pack.getBInt()
  val bmInterlaceFlags: Int = pack.getBInt()
  val bCopyProtect: Int = pack.getBInt()

  val fourccFormat: String = "MJPG"
}

/**
 * VS Uncompressed Format Type Descriptor. For fps, we are only employing dwDefaultFrameInterval.
 * So, we are also using this descriptor type for parsing MPEG frame type descriptor.
 * <pre>
 *         -------- VS Uncompressed Frame Type Descriptor --------
 * ---> This is the Default (optimum) Frame index
 * bLength                  : 0x1E (30 bytes)
 * bDescriptorType          : 0x24 (Video Streaming Interface)
 * bDescriptorSubtype       : 0x05 (Uncompressed Frame Type)
 * bFrameIndex              : 0x01
 * bmCapabilities           : 0x01
 * wWidth                   : 0x0780 (1920)
 * wHeight                  : 0x0438 (1080)
 * dwMinBitRate             : 0x58FD4000 (1492992000 bps -> 186.624 MB/s)
 * dwMaxBitRate             : 0x58FD4000 (1492992000 bps -> 186.624 MB/s)
 * dwMaxVideoFrameBufferSize: 0x002F7600 (3110400 bytes)
 * dwDefaultFrameInterval   : 0x00028B0A (16.6666 ms -> 60.0000 fps)
 * bFrameIntervalType       : 0x01 (1 discrete frame interval supported)
 * adwFrameInterval[1]      : 0x00028B0A (16.6666 ms -> 60.0000 fps)
 * Data (HexDump)           : 1E 24 05 01 01 80 07 38 04 00 40 FD 58 00 40 FD   .$.....8..@.X.@.
 *                            58 00 76 2F 00 0A 8B 02 00 01 0A 8B 02 00         X.v/..........
 * </pre>
 */
class VSFrameDescriptor(pack: ByteBuffer) {
  val bLength: Int = pack.getBInt()
  val bDescriptorType: Int = pack.getBInt()
  val bDescriptorSubtype: Int = pack.getBInt()
  val bFrameIndex: Int = pack.getBInt()
  val bmCapabilities: Int = pack.getBInt()
  val wWidth: Int = pack.getWInt()
  val wHeight: Int = pack.getWInt()
  val dwMinBitRate: Int = pack.getInt()
  val dwMaxBitRate: Int = pack.getInt()
  val dwMaxVideoFrameBufferSize: Int = pack.getInt()
  val dwDefaultFrameInterval: Int = pack.getInt()
  val bFrameIntervalType: Int = pack.getBInt()

  fun width(): Int = wWidth

  fun height(): Int = wHeight

  fun fps(): Int = 10_000_000 / dwDefaultFrameInterval

  fun isExactMatch(width: Int, height: Int, fps: Int): Boolean {
    return width == width() && height == height() && fps == fps()
  }

  fun isMinimumSizeAndFpsMatch(width: Int, height: Int, fps: Int): Boolean {
    return width <= width() && height <= height() && fps <= fps()
  }
}

/** Must be kept in-sync with https://fburl.com/code/kzplsk2y. */
enum class LibuvcFrameFormat {
  /** Any supported format */
  UVC_FRAME_FORMAT_ANY,
  UVC_FRAME_FORMAT_UNCOMPRESSED,
  UVC_FRAME_FORMAT_COMPRESSED,

  /**
   * YUYV/YUV2/YUV422: YUV encoding with one luminance value per pixel and one UV (chrominance) pair
   * for every two pixels.
   */
  UVC_FRAME_FORMAT_YUYV,
  UVC_FRAME_FORMAT_UYVY,

  /** 24-bit RGB */
  UVC_FRAME_FORMAT_RGB,
  UVC_FRAME_FORMAT_BGR,

  /** Motion-JPEG (or JPEG) encoded images */
  UVC_FRAME_FORMAT_MJPEG,
  UVC_FRAME_FORMAT_H264,

  /** Greyscale images */
  UVC_FRAME_FORMAT_GRAY8,
  UVC_FRAME_FORMAT_GRAY16,

  /* Raw colour mosaic images */
  UVC_FRAME_FORMAT_BY8,
  UVC_FRAME_FORMAT_BA81,
  UVC_FRAME_FORMAT_SGRBG8,
  UVC_FRAME_FORMAT_SGBRG8,
  UVC_FRAME_FORMAT_SRGGB8,
  UVC_FRAME_FORMAT_SBGGR8,

  /** YUV420: NV12 */
  UVC_FRAME_FORMAT_NV12,

  /** YUV: P010 */
  UVC_FRAME_FORMAT_P010,

  /** Number of formats understood */
  UVC_FRAME_FORMAT_COUNT,
}
