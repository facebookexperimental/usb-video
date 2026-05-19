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

using namespace std::chrono;

struct VideoStatsSummary {
  uvc_frame_format captureFrameFormat;
  int32_t captureFrameWidth;
  int32_t captureFrameHeight;
  int32_t fps;
};

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

enum class VideoStreamerState : uint8_t {
  INITIAL,
  INITIALIZED,
  CONFIGURED,
  STARTED,
  STOPPED,
  INIT_FAILED,
  CONFIGURE_FAILED,
  START_FAILED,
  STOP_FAILED,
};

class UsbVideoStreamer final {
 public:
  static void captureFrameCallback(uvc_frame_t* frame, void* user_data);
  explicit UsbVideoStreamer(intptr_t deviceFD);
  ~UsbVideoStreamer();
  VideoStreamerState
  configure(int32_t width, int32_t height, int32_t fps, uvc_frame_format uvcFrameFormat);
  bool start(ANativeWindow* previewWindow);
  bool stop();
  [[nodiscard]] VideoStreamerState getState() const {
    return state_;
  }
  [[nodiscard]] intptr_t getDeviceFD() const {
    return deviceFD_;
  }
  std::string statsSummaryString() const;
  [[nodiscard]] VideoStatsSummary statsSummary() const;

 private:
  void printFrameFormats() const;

  uvc_context_t* uvcContext_{};
  uvc_device_handle_t* deviceHandle_{};
  uvc_stream_ctrl_t streamCtrl_{};
  uvc_stream_handle_t* streamHandle_{nullptr};

  ANativeWindow* previewWindow_{};

  VideoStreamerState state_{VideoStreamerState::INITIAL};
  intptr_t deviceFD_;

  int32_t captureFrameWidth_{};
  int32_t captureFrameHeight_{};
  int32_t captureFrameFps_{};
  uvc_frame_format captureFrameFormat_{};

  UsbVideoStreamerStats stats_{};
};
