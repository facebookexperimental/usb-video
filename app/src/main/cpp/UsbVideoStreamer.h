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

#pragma once

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <libusb/libusb.h>
#include <libuvc/libuvc.h>

#include <chrono>
#include <cstdint>
#include <memory>
#include <vector>

using namespace std::chrono;

struct UsbVideoStreamerStats {
  u_int64_t total_bytes = 0;
  uint16_t usb_cb_counter = 0;
  uint16_t frames = 0;
  steady_clock::time_point lastFpsUpdate{0s};
  uint8_t fps = 0; // memoize value of current FPS when second rolls over
  uint8_t currentFps = 0;
  steady_clock::time_point t0{high_resolution_clock::now()};

  steady_clock::time_point captureRenderClock_{high_resolution_clock::now()};
  nanoseconds capture_{0ns};
  nanoseconds render_{0ns};

  void recordCapture() {
    auto now = high_resolution_clock::now();
    capture_ += (now - captureRenderClock_);
    captureRenderClock_ = now;
  }

  void recordRender() {
    auto now = high_resolution_clock::now();
    render_ += (now - captureRenderClock_);
    captureRenderClock_ = now;
  }

  void recordFrame() {
    currentFps++;
    auto now = high_resolution_clock::now();
    if (now - t0 >= 1s) {
      t0 = now;
      fps = currentFps;
      currentFps = 0;
    }
  }
};

struct CaptureFrameCallbackData {
  ANativeWindow* preview_window;
  UsbVideoStreamerStats stats;
};

class UsbVideoStreamer final {
 public:
  static void captureFrameCallback(uvc_frame_t* frame, void* user_data);
  UsbVideoStreamer(
      intptr_t deviceFD,
      int32_t width,
      int32_t height,
      int32_t fps,
      uvc_frame_format uvcFrameFormat);
  ~UsbVideoStreamer();
  bool configureOutput(ANativeWindow* previewWindow);
  bool start();
  bool stop();
  bool isRunning() const;
  std::string statsSummaryString() const;

 private:
  uvc_context_t* uvcContext_{};
  uvc_device_handle_t* deviceHandle_{};
  uvc_stream_ctrl_t streamCtrl_{};
  bool isStreamControlNegotiated_{false};
  uvc_stream_handle_t *streamHandle_{nullptr};

  ANativeWindow* previewWindow_{};

  intptr_t deviceFD_;
  int32_t width_;
  int32_t height_;
  int32_t fps_;
  uvc_frame_format uvcFrameFormat_;

  int32_t captureFrameWidth_{};
  int32_t captureFrameHeight_{};
  int32_t captureFrameFps_{};
  uvc_frame_format captureFrameFormat_{};

  UsbVideoStreamerStats stats_{};
};
