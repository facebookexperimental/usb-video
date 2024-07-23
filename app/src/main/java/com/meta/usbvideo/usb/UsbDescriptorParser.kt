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

import android.hardware.usb.UsbDeviceConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val USB_DT_DEVICE: Int = 0x01 // 1
const val USB_DT_DEVICE_CONFIG: Int = 0x02 // 2
const val USB_DT_STRING: Int = 0x03 // 3
const val USB_DT_DEVICE_INTERFACE: Int = 0x04 // 4
const val USB_DT_DEVICE_ENDPOINT: Int = 0x05 // 5

const val USB_DT_IAD: Int = 0x0B // 11
const val USB_DT_CLASSSPECIFIC_INTERFACE: Int = 0x24 // 36

const val USB_ENDPOINT_DIR_IN: Int = 0x80 // 128

const val USB_REQUEST_GET_DESCRIPTOR = 0x06 // Get the specified descriptor

private const val REQUEST_BUFFER_SIZE = 255
private const val REQUEST_TIMEOUT_MS = 1000

/**
 * USB device descriptor parser. The parser generates a sequence of Descriptor objects lazily for
 * efficiently parsing descriptors.
 *
 * The class also provides types for common USB descriptors and extension methods for reading USB
 * data types and making requets to the USB device.
 */
class UsbDescriptorParser(private val rawDescriptors: ByteArray) {
  fun descriptors(): Sequence<Descriptor> =
      sequence<Descriptor> {
        val pack: ByteBuffer = ByteBuffer.wrap(rawDescriptors).order(ByteOrder.LITTLE_ENDIAN)
        while (pack.hasRemaining()) {
          val pos: Int = pack.position()
          val len: Int = pack.getBInt(pos)
          val type: Int = pack.getBInt(pos + 1)
          yield(
              Descriptor(
                  pos,
                  len,
                  type,
                  ByteBuffer.wrap(rawDescriptors, pos, len).order(ByteOrder.LITTLE_ENDIAN),
              ),
          )
          pack.position(pos + len)
        }
      }
}

/** A generic USB descriptor with a <code>ByteBuffer</code> in little-endian byte order */
class Descriptor(
    val offset: Int,
    val bLength: Int,
    val bDescriptorType: Int,
    val buffer: ByteBuffer,
) {
  fun isIADDescriptor(): Boolean {
    return bDescriptorType == USB_DT_IAD
  }

  fun isInterfaceDescriptorAtLeastOneEndpoint(): Boolean {
    return bDescriptorType == USB_DT_DEVICE_INTERFACE && buffer.getBInt(offset + 4) > 0
  }

  fun isEndpointDescriptorWithDirIN(): Boolean {
    return bDescriptorType == USB_DT_DEVICE_ENDPOINT &&
        buffer.getBInt(offset + 2) and USB_ENDPOINT_DIR_IN == USB_ENDPOINT_DIR_IN
  }
}

/**
 * USB interface association descriptor (IAD) allows the device to group interfaces that belong to a
 * function. Example:
 * <pre>
 *         ------------------- IAD Descriptor --------------------
 * bLength                  : 0x08 (8 bytes)
 * bDescriptorType          : 0x0B (Interface Association Descriptor)
 * bFirstInterface          : 0x03 (Interface 3)
 * bInterfaceCount          : 0x02 (2 Interfaces)
 * bFunctionClass           : 0x01 (Audio)
 * bFunctionSubClass        : 0x02 (Audio Streaming)
 * bFunctionProtocol        : 0x00
 * iFunction                : 0x06 (String Descriptor 6)
 *  Language 0x0409         : "Cam Link 4K"
 * Data (HexDump)           : 08 0B 03 02 01 02 00 06                           ........
 * </pre>
 */
class IADDescriptor(pack: ByteBuffer) {
  val bLength: Int = pack.getBInt()
  val bDescriptorType: Int = pack.getBInt()
  val bFirstInterface: Int = pack.getBInt()
  val bInterfaceCount: Int = pack.getBInt()
  val bFunctionClass: Int = pack.getBInt()
  val bFunctionSubClass: Int = pack.getBInt()
  val bFunctionProtocol: Int = pack.getBInt()
  val iFunction: Int = pack.getBInt()

  fun getStringDescriptors(usbDeviceConnection: UsbDeviceConnection): Sequence<String> =
      usbDeviceConnection.getStringDescriptors(iFunction)

  fun hasAudioStreamingFunction(): Boolean {
    return bFunctionClass == 0x1 && bFunctionSubClass == 0x02
  }
}

/**
 * USB Interface descriptor. Example:
 * <pre>
 *         ---------------- Interface Descriptor -----------------
 * bLength                  : 0x09 (9 bytes)
 * bDescriptorType          : 0x04 (Interface Descriptor)
 * bInterfaceNumber         : 0x04 (Interface 4)
 * bAlternateSetting        : 0x00
 * bNumEndpoints            : 0x00 (Default Control Pipe only)
 * bInterfaceClass          : 0x01 (Audio)
 * bInterfaceSubClass       : 0x02 (Audio Streaming)
 * bInterfaceProtocol       : 0x00
 * iInterface               : 0x06 (String Descriptor 6)
 *  Language 0x0409         : "Cam Link 4K"
 * Data (HexDump)           : 09 04 04 00 00 01 02 00 06                        .........
 * </pre>
 */
class InterfaceDescriptor(pack: ByteBuffer) {
  val bLength: Int = pack.getBInt()
  val bDescriptorType: Int = pack.getBInt()
  val bInterfaceNumber: Int = pack.getBInt()
  val bAlternateSetting: Int = pack.getBInt()
  val bNumEndpoints: Int = pack.getBInt()
  val bInterfaceClass: Int = pack.getBInt()
  val bInterfaceSubClass: Int = pack.getBInt()
}

/**
 * USB Enpoint descriptor. Example:
 * <pre>
 *         ----------------- Endpoint Descriptor -----------------
 * bLength                  : 0x09 (9 bytes)
 * bDescriptorType          : 0x05 (Endpoint Descriptor)
 * bEndpointAddress         : 0x81 (Direction=IN EndpointID=1)
 * bmAttributes             : 0x05 (TransferType=Isochronous  SyncType=Asynchronous  EndpointType=Data)
 * wMaxPacketSize           : 0x00C0
 * bInterval                : 0x04 (4 ms)
 * bRefresh                 : 0x00
 * bSynchAddress            : 0x00
 * Data (HexDump)           : 09 05 81 05 C0 00 04 00 00                        .........
 * </pre>
 */
class EndpointDescriptor(pack: ByteBuffer) {
  val bLength: Int = pack.getBInt()
  val bDescriptorType: Int = pack.getBInt()
  val bEndpointAddress: Int = pack.getBInt()
  val bmAttributes: Int = pack.getBInt()
  val wMaxPacketSize: Int = pack.getWInt()
  val bInterval: Int = pack.getBInt()
  val bRefresh: Int = if (pack.hasRemaining()) pack.getBInt() else 0
  val bSynchAddress: Int = if (pack.hasRemaining()) pack.getBInt() else 0
}

fun UsbDeviceConnection.getStringDescriptors(index: Int): Sequence<String> {
  // all the languages
  val bufferSize = REQUEST_BUFFER_SIZE
  val buffer = ByteArray(bufferSize)
  val descriptorZeroLen =
      controlTransfer(
          USB_ENDPOINT_DIR_IN,
          USB_REQUEST_GET_DESCRIPTOR,
          (USB_DT_STRING shl 8) or 0,
          0,
          buffer,
          bufferSize,
          REQUEST_TIMEOUT_MS,
      )
  return sequence<Int> {
        val buffer = ByteBuffer.wrap(buffer, 0, descriptorZeroLen).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
          yield(buffer.getWInt())
        }
      }
      .map { languageID ->
        val stringDescriptorLen =
            controlTransfer(
                USB_ENDPOINT_DIR_IN,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_DT_STRING shl 8) or index,
                languageID,
                buffer,
                bufferSize,
                REQUEST_TIMEOUT_MS,
            )
        ByteBuffer.wrap(buffer, 0, stringDescriptorLen)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asCharBuffer()
            .toString()
      }
}

// Returns an int from three bytes in little-endian byte order
inline fun ByteBuffer.getTInt(): Int = getBInt() or (getBInt() shl 8) or (getBInt() shl 16)

// Returns an int from three bytes in little-endian byte order
inline fun ByteBuffer.getTInt(offset: Int): Int =
    getBInt(offset) or (getBInt(offset + 1) shl 8) or (getBInt(offset + 2) shl 16)

inline fun ByteBuffer.getWInt(): Int = getShort().toInt() and 0xffff

inline fun ByteBuffer.getWInt(offset: Int): Int = getShort(offset).toInt() and 0xff

inline fun ByteBuffer.getBInt(): Int = get().toInt() and 0xff

inline fun ByteBuffer.getBInt(offset: Int): Int = get(offset).toInt() and 0xff
