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

#include "UsbVideoStreamer.h"

#include <android/bitmap.h>
#include <android/data_space.h>
#if __ANDROID_MIN_SDK_VERSION__ >= 30
#include <android/imagedecoder.h>
#endif
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <jni.h>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libyuv.h>
#include <libyuv/convert_argb.h>
#include <libyuv/convert_from_argb.h>
#include <libyuv/planar_functions.h>

#include <chrono>
#include <format>

#include <memory.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <cstring>

#define ULOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "UsbVideoStreamer", __VA_ARGS__)

#define ULOGI(...) __android_log_print(ANDROID_LOG_INFO, "UsbVideoStreamer", __VA_ARGS__)
#define ULOGW(...) __android_log_print(ANDROID_LOG_WARN, "UsbVideoStreamer", __VA_ARGS__)

#define ULOGE(...) __android_log_print(ANDROID_LOG_ERROR, "UsbVideoStreamer", __VA_ARGS__)

UsbVideoStreamer::UsbVideoStreamer(
    intptr_t deviceFD,
    int32_t width,
    int32_t height,
    int32_t fps,
    uvc_frame_format uvcFrameFormat)
    : deviceFD_(deviceFD),
      width_(width),
      height_(height),
      fps_(fps),
      uvcFrameFormat_(uvcFrameFormat) {
  if (libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY) != LIBUSB_SUCCESS) {
    ULOGE("libusb setting no discovery option failed");
  }

  // Initialize a UVC service context. Libuvc will set up its own libusb context.
  uvc_error_t res = uvc_init(&uvcContext_, nullptr);
  if (res != UVC_SUCCESS) {
    ULOGE("uvc_init failed %s", uvc_strerror(res));
    return;
  }
  ULOGE("UVC initialized");

  if ((uvc_wrap(deviceFD, uvcContext_, &deviceHandle_) != UVC_SUCCESS) ||
      (deviceHandle_ == nullptr)) {
    ULOGE("uvc_wrap error");
    return;
  }

  res = uvc_get_stream_ctrl_format_size(
      deviceHandle_,
      &streamCtrl_, /* result stored in ctrl */
      uvcFrameFormat_,
      width,
      height,
      fps);
  if (res == UVC_SUCCESS) {
    captureFrameWidth_ = width;
    captureFrameHeight_ = height;
    captureFrameFps_ = fps;
    captureFrameFormat_ = uvcFrameFormat_;
    isStreamControlNegotiated_ = true;
    ULOGI(
        "uvc_get_stream_ctrl_format_size found for %dx%d@%dfps format: %d",
        width,
        height,
        fps,
        uvcFrameFormat_);
  } else {
    isStreamControlNegotiated_ = false;
    ULOGE(
        "uvc_get_stream_ctrl_format_size for %d %dx%d@%dfps failed %s",
        uvcFrameFormat_,
        width,
        height,
        fps,
        uvc_strerror(res));
  }
}

bool UsbVideoStreamer::configureOutput(ANativeWindow* previewWindow) {
  if (!isStreamControlNegotiated_) {
    return false;
  }
  if (previewWindow_ == nullptr) {
    previewWindow_ = previewWindow;
  }
  uvc_error_t ret = uvc_stream_open_ctrl(deviceHandle_, &streamHandle_, &streamCtrl_);
  return ret == UVC_SUCCESS;
}

bool UsbVideoStreamer::start() {
  if (streamHandle_ == nullptr) {
    return false;
  }
  uvc_error_t ret = uvc_stream_start(streamHandle_, captureFrameCallback, this, 0);
  ULOGE("uvc_stream_start %d", ret);
  return ret == UVC_SUCCESS;
}

bool UsbVideoStreamer::stop() {
  if (streamHandle_ == nullptr) {
    return false;
  }
  return uvc_stream_stop(streamHandle_) == UVC_SUCCESS;
}

static std::string fourccFormatFromUvcFrameFormat(uvc_frame_format frameFormat) {
  switch (frameFormat) {
    case UVC_FRAME_FORMAT_YUYV:
      return "YUYV";
    case UVC_FRAME_FORMAT_UYVY:
      return "UYVY";
    case UVC_FRAME_FORMAT_MJPEG:
      return "MJPG";
    case UVC_FRAME_FORMAT_H264:
      return "H264";
    case UVC_FRAME_FORMAT_NV12:
      return "NV12";
    default:
      return "";
  }
  return "";
}

static bool isValidMjpegFrame(uvc_frame_t* frame) {
  // See https://en.wikipedia.org/wiki/JPEG_File_Interchange_Format
  if (frame->data_bytes < 6 || frame->data == nullptr) {
    ULOGE("Invalid MJPEG frame size %zu ptr %p", frame->data_bytes, frame->data);
    return false;
  }
  u_int8_t soi1 = *(u_int8_t*)frame->data;
  u_int8_t soi2 = *((u_int8_t*)frame->data + 1);
  // JPEG frame start of image (SOI) is 0xff 0xd8.
  if (soi1 != 0xff || soi2 != 0xd8) {
    ULOGE("Invalid MJPEG frame SOI. size: %zu SOI: %x%x", frame->data_bytes, soi1, soi2);
    return false;
  }
  return true;
}

std::string UsbVideoStreamer::statsSummaryString() const {
  return std::format(
      "{} {}x{} @{} fps",
      fourccFormatFromUvcFrameFormat(captureFrameFormat_),
      captureFrameWidth_,
      captureFrameHeight_,
      stats_.fps);
  ;
}

UsbVideoStreamer::~UsbVideoStreamer() {
  if (deviceHandle_ != nullptr) {
    ULOGI("Close device handle");
    uvc_close(deviceHandle_);
    deviceHandle_ = nullptr;
  }

  if (uvcContext_ != nullptr) {
    ULOGI("Exit UVC Context");
    uvc_exit(uvcContext_);
    uvcContext_ = nullptr;
  }

  ULOGI("UsbVideoStreamer destroyed");
}

/* This callback function runs once per frame. */
void UsbVideoStreamer::captureFrameCallback(uvc_frame_t* frame, void* user_data) {
  size_t expectedSize;
  switch (frame->frame_format) {
    case UVC_FRAME_FORMAT_NV12:
      expectedSize = frame->width * frame->height + frame->width * frame->height / 2;
      if (frame->data_bytes != expectedSize) {
        ULOGE(
            "Invalid NV12 frame size %zu vs expected %zu for %dx%d, step %zu frame",
            frame->data_bytes,
            expectedSize,
            frame->width,
            frame->height,
            frame->step);
        return;
      }
      break;
    case UVC_FRAME_FORMAT_YUYV:
      expectedSize = frame->width * frame->height * 2;
      if (frame->data_bytes != expectedSize) {
        ULOGE(
            "Invalid YUYV frame size %zu vs expected %zu for %dx%d, step %zu frame",
            frame->data_bytes,
            expectedSize,
            frame->width,
            frame->height,
            frame->step);
        return;
      }
      break;
    case UVC_FRAME_FORMAT_MJPEG:
      if (!isValidMjpegFrame(frame)) {
        return;
      }
      break;
    default:
      break;
  }

  UsbVideoStreamer* self = (UsbVideoStreamer*)user_data;
  ANativeWindow* preview_window = self->previewWindow_;
  UsbVideoStreamerStats& stats = self->stats_;
  bool first_call = stats.lastFpsUpdate.time_since_epoch().count() == 0;
  if (first_call) {
    prctl(PR_SET_NAME, "usb_video_capture");
    ULOGI("__ANDROID_MIN_SDK_VERSION__ %d", __ANDROID_MIN_SDK_VERSION__);
    ULOGI(
        "Capture frame format: %d data bytes: %zu step: %zd %dx%d",
        frame->frame_format,
        frame->data_bytes,
        frame->step,
        frame->width,
        frame->height);
    stats.captureRenderClock_ = high_resolution_clock::now();
  } else {
    stats.recordCapture();
  }

  ANativeWindow_Buffer buffer;
  auto status = ANativeWindow_lock(preview_window, &buffer, nullptr);
  if (status != 0) {
    ULOGE("ANativeWindow_lock failed with error %d", status);
    return;
  }

  if (first_call) {
    ULOGE(
        "Display buffer format: %d  stride: %d %dx%d",
        buffer.format,
        buffer.stride,
        buffer.width,
        buffer.height);
  }

  if (frame->frame_format == UVC_FRAME_FORMAT_NV12) {
    int32_t hardware_buffer_format = buffer.format;
    if (hardware_buffer_format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
      uint8_t* src_y = (uint8_t*)frame->data;
      uint8_t* dest_rgb = (uint8_t*)buffer.bits;
      libyuv::NV21ToARGB(
          src_y,
          frame->step,
          (src_y + frame->width * frame->height),
          frame->step,
          dest_rgb,
          buffer.stride * 4,
          buffer.width,
          buffer.height);
    } else if (hardware_buffer_format == AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM) {
      uint8_t* src_y = (uint8_t*)frame->data;
      uint8_t* dest_rgb = (uint8_t*)buffer.bits;
      libyuv::NV21ToRGB24(
          src_y,
          frame->step,
          (src_y + frame->width * frame->height),
          frame->step,
          dest_rgb,
          buffer.stride * 3,
          buffer.width,
          buffer.height);
    } else {
      ULOGE("Unsupported hardware format for UVC_FRAME_FORMAT_NV12 %d", hardware_buffer_format);
    }
  } else if (frame->frame_format == UVC_FRAME_FORMAT_YUYV) {
    uint8_t* src_yuy2 = (uint8_t*)frame->data;
    uint8_t* dest_rgba = (uint8_t*)buffer.bits;
    libyuv::YUY2ToARGB(
        src_yuy2, frame->step, dest_rgba, 4 * buffer.stride, buffer.width, buffer.height);
    libyuv::ABGRToARGB(
        dest_rgba, 4 * buffer.stride, dest_rgba, 4 * buffer.stride, buffer.width, buffer.height);
  } else if (frame->frame_format == UVC_FRAME_FORMAT_MJPEG) {
#if __ANDROID_MIN_SDK_VERSION__ >= 30
    AImageDecoder* decoder;
    int result = AImageDecoder_createFromBuffer(frame->data, frame->data_bytes, &decoder);
    if (result == ANDROID_IMAGE_DECODER_SUCCESS) {
      size_t stride = buffer.stride * 4;
      size_t size = buffer.height * stride;
      result = AImageDecoder_decodeImage(decoder, buffer.bits, stride, size);
      if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        const AImageDecoderHeaderInfo* info = AImageDecoder_getHeaderInfo(decoder);
        ULOGE(
            "MJPG decoding error %d frame size %zu %dx%d, step %zu, decoded image header: %d X %d stride %zu mime-type %s",
            result,
            frame->data_bytes,
            frame->width,
            frame->height,
            frame->step,
            AImageDecoderHeaderInfo_getWidth(info),
            AImageDecoderHeaderInfo_getHeight(info),
            stride,
            AImageDecoderHeaderInfo_getMimeType(info));
        memset(buffer.bits, 0, size);
      }
      AImageDecoder_delete(decoder);
    } else {
      ULOGE(
          "MJPG AImageDecoder_createFromBuffer error %d frame size %zu %dx%d, step %zu",
          result,
          frame->data_bytes,
          frame->width,
          frame->height,
          frame->step);
    }
#else
    std::unique_ptr<uvc_frame_t, decltype(&uvc_free_frame)> rgb_frame_ptr(
        uvc_allocate_frame(frame->width * frame->height * 3), &uvc_free_frame);
    uvc_frame_t* rgb_frame = rgb_frame_ptr.get();
    uvc_mjpeg2rgb(frame, rgb_frame);
    uint8_t* src_rgb = (uint8_t*)rgb_frame->data;
    uint8_t* dest_rgba = (uint8_t*)buffer.bits;
    int32_t hardware_buffer_format = buffer.format;
    if (hardware_buffer_format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
      libyuv::RGB24ToARGB(
          src_rgb, rgb_frame->step, dest_rgba, buffer.stride * 4, buffer.width, buffer.height);
    } else {
      ULOGE("Unsupported hardware_buffer_format  %d for MJPEG frame", hardware_buffer_format);
    }
#endif
  } else {
    ULOGE("Unsupported  frame->frame_format %d", frame->frame_format);
  }
  ANativeWindow_unlockAndPost(preview_window);

  stats.recordRender();
  stats.recordFrame();
  stats.frames++;
  auto frame_count = stats.frames;
  auto now = steady_clock::now();
  if (first_call) {
    stats.lastFpsUpdate = now;
  }
  duration<float> diff = duration_cast<seconds>(now - stats.lastFpsUpdate);
  if (diff >= 10.0s) {
    auto fps = frame_count / diff.count();
    duration<double, seconds::period> captureDuration(stats.capture_);
    duration<double, seconds::period> renderDuration(stats.render_);
    auto capturePlusRender = captureDuration.count() + renderDuration.count();
    ULOGI(
        "Captured %dx%d %u frames in %.1f secs. fps: %.1f. Capture time: %.2f (%.0f%%) Render Time: %.2f (%.0f%%)",
        frame->width,
        frame->height,
        frame_count,
        diff.count(),
        fps,
        captureDuration.count(),
        captureDuration.count() * 100 / capturePlusRender,
        renderDuration.count(),
        renderDuration.count() * 100 / capturePlusRender);
    stats.lastFpsUpdate = now;
    stats.frames = 0;
    stats.capture_ = 0ns;
    stats.render_ = 0ns;
  }
}
