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

#include <aaudio/AAudio.h>
#include <libusb/libusb.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <vector>

#include <algorithm>
#include <condition_variable>
#include <iterator>
#include <mutex>

#include "RingBuffer.h"

using namespace std::chrono;

enum class StreamerState : int {
  INITIAL,
  READY_TO_START,
  STARTING,
  STARTED,
  STOPPING,
  STOPPED,
  DESTROYING,
  DESTROYED,
  ERROR,
};

struct UsbAudioStreamerStats {
  uint32_t total_bytes{0};
  uint16_t usb_cb_counter{0};
  uint16_t player_cb_counter{0};
  uint16_t event_loops{0};
  steady_clock::time_point t0_10_s{milliseconds{0}};

  uint32_t samplingFrequency = 0;
  uint32_t currentSamplingFrequency = 0;
  steady_clock::time_point t0_1_s{high_resolution_clock::now()};

  void recordSamples(uint32_t samples) {
    currentSamplingFrequency += samples;
    if (duration_cast<seconds>(high_resolution_clock::now() - t0_1_s) >= 1s) {
      t0_1_s = high_resolution_clock::now();
      samplingFrequency = currentSamplingFrequency;
      currentSamplingFrequency = 0;
    }
  }
};

class UsbAudioStreamer;

struct TransferUserData {

  TransferUserData(libusb_transfer* transfer_, UsbAudioStreamer* streamer_, bool isSubmitted_):
  transfer(transfer_),
  streamer(streamer_),
  isSubmitted(isSubmitted_) {
  }
  libusb_transfer* transfer;
  UsbAudioStreamer* streamer;
  bool isSubmitted;

  ~TransferUserData() {

    libusb_free_transfer(transfer);
    streamer = nullptr;
  }
};

class UsbAudioStreamer final {
 public:
  UsbAudioStreamer(const UsbAudioStreamer&) = delete;
  UsbAudioStreamer(UsbAudioStreamer&&) = delete;
  UsbAudioStreamer(
      intptr_t deviceFD,
      uint32_t jAudioFormat,
      uint32_t samplingFrequency,
      uint8_t subFrameSize, // number of bytes per audio sample
      uint8_t channelCount,
      uint32_t jAudioPerfMode,
      uint32_t framesPerBurst);
  ~UsbAudioStreamer();

  UsbAudioStreamer& operator=(const UsbAudioStreamer&) = delete;
  UsbAudioStreamer&& operator=(UsbAudioStreamer&&) = delete;

  libusb_context* context() {
    return context_;
  }

  libusb_device_handle* deviceHandle() {
    return deviceHandle_;
  }

  libusb_config_descriptor* config() {
    return config_;
  }

  int getUsbDeviceSpeed() const {
    if (deviceHandle_ == nullptr) {
      return LIBUSB_SPEED_UNKNOWN;
    }
    libusb_device* device = libusb_get_device(deviceHandle_);
    return device != nullptr ? libusb_get_device_speed(device) : LIBUSB_SPEED_UNKNOWN;
  }

  uint32_t bytesInAudioFrames(int32_t numFrames) const {
    return numFrames * channelCount_ * subFrameSize_;
  }

  bool hasActiveTransfers() const {
    return std::count_if(
               transfers_.begin(),
               transfers_.end(),
               [](const std::unique_ptr<TransferUserData>& transfer) {
                 return transfer->isSubmitted;
               }) > 0;
  }

  bool start();
  bool isPlaying() const;
  bool stop();
  uint32_t samplesFromByteCount(uint32_t bytes) const;
  std::string statsSummaryString() const;
  bool ensureTransferRequests();

 private:
  libusb_context* context_;
  libusb_device_handle* deviceHandle_{};
  libusb_config_descriptor* config_{};
  std::vector<std::unique_ptr<TransferUserData>> transfers_{};
  uint8_t endpointAddress_{};
  uint16_t maxPacketSize_{};
  int detachedInterface_{-1};
  int claimedInterface_{-1};
  uint32_t jAudioFormat_{};
  uint32_t samplingFrequency_{};
  uint8_t subFrameSize_{};
  uint8_t channelCount_{};
  int32_t framesPerBurst_{};
  int32_t bufferCapacityInFrames_{};
  const struct libusb_init_option libusbOptions = {.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY};
  timeval libusbEventsTimeout_{0, 100}; // 100 microseconds
  std::unique_ptr<RingBufferPcm> ringBuffer_{std::make_unique<RingBufferPcm>(3072)};
  std::atomic<StreamerState> state_{StreamerState::INITIAL};
  std::mutex mutex_;
  std::condition_variable stateChange_;

  bool resolveAudioInterface();
  bool startAudioPlayer();
  bool stopAudioPlayer();
  void allocateTransferRequests();
  bool submitTransferRequests();
  static aaudio_data_callback_result_t
  audioPlaybackCallback(AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

  static void transferCallback(libusb_transfer* transfer);

  volatile int stopUsbAudioCapture_{0};

  AAudioStreamBuilder* audioStreamBuilder_{};
  AAudioStream* audioStream_{};
  UsbAudioStreamerStats streamerStats_{};
  steady_clock::time_point callbackErrorLoggedAt_{seconds{0}};

  static constexpr uint32_t kIsochronousTransferTimeoutMillis = 500;
  static constexpr uint8_t kInterfaceSubClassStreaming = 0x02;
};
