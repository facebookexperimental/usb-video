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

#include "UsbAudioStreamer.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <libusb/libusb.h>

#include <format>
#include <memory>
#include "RingBuffer.h"
#include "aaudio_type_conversion.h"

#define ULOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "UsbAudioStreamer", __VA_ARGS__)

#define ULOGI(...) __android_log_print(ANDROID_LOG_INFO, "UsbAudioStreamer", __VA_ARGS__)

#define ULOGW(...) __android_log_print(ANDROID_LOG_WARN, "UsbAudioStreamer", __VA_ARGS__)

#define ULOGE(...) __android_log_print(ANDROID_LOG_ERROR, "UsbAudioStreamer", __VA_ARGS__)

UsbAudioStreamer::~UsbAudioStreamer() {
  if (audioStream_ != nullptr) {
    AAudioStream_close(audioStream_);
    audioStream_ = nullptr;
  }

  state_ = StreamerState::DESTROYING;

  if (deviceHandle_ && claimedInterface_ != -1) {
    auto status = libusb_release_interface(deviceHandle_, claimedInterface_);
    if (status == LIBUSB_SUCCESS) {
      ULOGI("Released claimed audio interface");
    } else {
      ULOGW("Could not release claimed audio interface %s", libusb_error_name(status));
    }
  }

  if (detachedInterface_ != -1) {
    auto status = libusb_attach_kernel_driver(deviceHandle_, detachedInterface_);
    if (status == LIBUSB_SUCCESS) {
      ULOGI("Attached audio interface to kernel driver");
    } else {
      ULOGW("Could not attach audio interface to kernel driver %s", libusb_error_name(status));
    }
  }

  if (deviceHandle_ != nullptr) {
    ULOGI("Free device");
    libusb_close(deviceHandle_);
    deviceHandle_ = nullptr;
  }

  // this will call libusb_free_transfer in destructor
  transfers_.clear();

  if (config_ != nullptr) {
    ULOGI("Free config");
    libusb_free_config_descriptor(config_);
  }

  if (context_ != nullptr) {
    ULOGI("Exit context");
    libusb_exit(context_);
  }

  ringBuffer_ = nullptr;

  ULOGI("UsbAudioStreamer destroyed");
  state_ = StreamerState::DESTROYED;
}

bool UsbAudioStreamer::resolveAudioInterface() {
  if (config_ == nullptr) {
    return false;
  }

  // Find the audio interface
  for (auto i = 0; i < config_->bNumInterfaces; ++i) {
    const auto interface = &config_->interface[i];
    for (auto j = 0; j < interface->num_altsetting; ++j) {
      const auto interfaceDescriptor = &interface->altsetting[j];
      ULOGI(
              "interfaceDescriptor input endpoint at %d %u %u.",
              j,
              interfaceDescriptor->bInterfaceClass,
              interfaceDescriptor->bInterfaceSubClass);
      if (interfaceDescriptor->bInterfaceClass == LIBUSB_CLASS_AUDIO &&
          interfaceDescriptor->bInterfaceSubClass == kInterfaceSubClassStreaming &&
          interfaceDescriptor->bNumEndpoints > 0) {
        for (auto k = 0; k < interfaceDescriptor->bNumEndpoints; k++) {
          ULOGI(
                  "found a streaming sub class interface %u at %u wtih endpoints count %d",
                  interfaceDescriptor->bInterfaceNumber,
                  i,
                  interfaceDescriptor->bNumEndpoints);
          auto const endpoint = &interfaceDescriptor->endpoint[k];
          if ((endpoint->bEndpointAddress & LIBUSB_ENDPOINT_IN) != 0) {
            auto interface_number = interfaceDescriptor->bInterfaceNumber;
            endpointAddress_ = endpoint->bEndpointAddress;
            maxPacketSize_ = endpoint->wMaxPacketSize;
            ULOGI(
                    "Found input endpoint at %u packet size %d. maxPacketSize_: %d",
                    endpoint->bEndpointAddress,
                    endpoint->wMaxPacketSize,
                    maxPacketSize_);
            // if a kernel driver is active, must detach before claiming interfaces
            if (libusb_kernel_driver_active(deviceHandle_, interface_number) == 1) {
              auto detach_call_status =
                      libusb_detach_kernel_driver(deviceHandle_, interfaceDescriptor->bInterfaceNumber);
              if (detach_call_status != LIBUSB_SUCCESS) {
                ULOGE(
                        "libusb_detach_kernel_driver error for interface %d: %s.",
                        interface_number,
                        libusb_error_name(detach_call_status));
                return false;
              }
              detachedInterface_ = interface_number;
            }
            auto claim_interface_status = libusb_claim_interface(deviceHandle_, interface_number);
            if (claim_interface_status != LIBUSB_SUCCESS) {
              ULOGE(
                      "libusb_claim_interface error for interface %d: %s.",
                      interface_number,
                      libusb_error_name(claim_interface_status));
              return false;
            }
            claimedInterface_ = i;
            auto set_alt_setting_status = libusb_set_interface_alt_setting(
                    deviceHandle_,
                    interfaceDescriptor->bInterfaceNumber,
                    interfaceDescriptor->bAlternateSetting);
            if (set_alt_setting_status != LIBUSB_SUCCESS) {
              ULOGE(
                      "libusb_set_interface_alt_setting error for interface %d: %s.",
                      interface_number,
                      libusb_error_name(claim_interface_status));
              return false;
            } else {
              ULOGI("libusb_claim_interface claimed interface %d success", interface_number);
              return true;
            }
          }
        }
      }
    }
  }
  return false;
}

UsbAudioStreamer::UsbAudioStreamer(
        intptr_t deviceFD,
        uint32_t jAudioFormat,
        uint32_t samplingFrequency,
        uint8_t subFrameSize,
        uint8_t channelCount,
        uint32_t jAudioPerfMode,
        uint32_t framesPerBurst)
        : jAudioFormat_(jAudioFormat),
          samplingFrequency_(samplingFrequency),
          subFrameSize_(subFrameSize),
          channelCount_(channelCount),
          framesPerBurst_(framesPerBurst) {
  ULOGI(
          "UsbAudioStreamer::init samplingFrequency_: %d channelCount_: %d framesPerBurst_ %d",
          samplingFrequency_,
          channelCount_,
          framesPerBurst_);
  int errcode = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb setting no discovery option failed %s", libusb_error_name(errcode));
  }

  ULOGI("Initializing UsbContext");
  errcode = libusb_init(&context_);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb_init failed %s", libusb_error_name(errcode));
  } else {
    ULOGD("libusb initialized");
  }

  errcode = libusb_set_option(context_, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_ERROR);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb setting loglevel option failed %s", libusb_error_name(errcode));
    return;
  }

  errcode = libusb_wrap_sys_device(context_, deviceFD, &deviceHandle_);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb_wrap_sys_device failed %s", libusb_error_name(errcode));
    return;
  }

  libusb_device* device = libusb_get_device(deviceHandle_);
  ULOGD("Got device %p with usb speed %d", device, libusb_get_device_speed(device));
  errcode = libusb_get_active_config_descriptor(device, &config_);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb_get_active_config_descriptor failed %s", libusb_error_name(errcode));
    return;
  }

  aaudio_result_t result = AAudio_createStreamBuilder(&audioStreamBuilder_);
  ULOGD("AAudio_createStreamBuilder result %d.", result);
  if (result == AAUDIO_OK && audioStreamBuilder_ != nullptr) {
    AAudioStreamBuilder_setDirection(audioStreamBuilder_, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(audioStreamBuilder_, convertFormat(jAudioFormat_));
    AAudioStreamBuilder_setSampleRate(audioStreamBuilder_, samplingFrequency);
    AAudioStreamBuilder_setChannelCount(audioStreamBuilder_, channelCount);
    AAudioStreamBuilder_setPerformanceMode(audioStreamBuilder_, convertPerfMode(jAudioPerfMode));
    AAudioStreamBuilder_setDataCallback(audioStreamBuilder_, audioPlaybackCallback, this);
    result = AAudioStreamBuilder_openStream(audioStreamBuilder_, &audioStream_);
    ULOGD("AAudioStreamBuilder_openStream result %d.", result);
    framesPerBurst_ = AAudioStream_getFramesPerBurst(audioStream_);
    bufferCapacityInFrames_ = AAudioStream_getBufferCapacityInFrames(audioStream_);
    ULOGD(
            "AAudioStream params: framesPerBurst %d bufferSizeInFrames %d bufferCapacityInFrames = %d",
            AAudioStream_getFramesPerBurst(audioStream_),
            AAudioStream_getBufferSizeInFrames(audioStream_),
            AAudioStream_getBufferCapacityInFrames(audioStream_));
  } else {
    state_ = StreamerState::ERROR;
    return;
  }

  if (resolveAudioInterface()) {
    ULOGE("Resolved audio interface");
  } else {
    state_ = StreamerState::ERROR;
    ULOGE("Could not resolve audio interface");
    return;
  }
  allocateTransferRequests();

  state_ = StreamerState::READY_TO_START;
}

bool UsbAudioStreamer::start() {
  ULOGI("UsbAudioStreamer start called");
  if (state_ != StreamerState::READY_TO_START) {
    ULOGE("Streamer state must by ready to start");
    return false;
  }
  state_ = StreamerState::STARTING;
  streamerStats_.t0_10_s = {};
  streamerStats_.total_bytes = 0;
  streamerStats_.player_cb_counter = 0;
  streamerStats_.usb_cb_counter = 0;
  streamerStats_.event_loops = 0;
  stopUsbAudioCapture_ = 0;

  if(!submitTransferRequests()) {
    ULOGE("Submit transfer requests failed");
    return false;
  }

  if (!startAudioPlayer()) {
    state_ = StreamerState::ERROR;
    ULOGE("start audio player error");
    return false;
  }
  aaudio_stream_state_t inputState = AAUDIO_STREAM_STATE_STARTING;
  aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
  int64_t timeoutNanos = 500LL * 1000000LL;
  auto result = AAudioStream_waitForStateChange(audioStream_, inputState, &nextState, timeoutNanos);
  ULOGD(
          "AAudioStream start: result %d nextState %d started(ref) = %d",
          result,
          nextState,
          AAUDIO_STREAM_STATE_STARTED);
  if (result != AAUDIO_OK) {
    state_ = StreamerState::ERROR;
    ULOGE("start audio player error");
    return false;
  }
  state_ = StreamerState::STARTED;
  return true;
}

bool UsbAudioStreamer::ensureTransferRequests() {
  if (!transfers_.empty()) {
    return false;
  }
  return this->submitTransferRequests();
}


bool UsbAudioStreamer::submitTransferRequests() {
  for (const auto& transferData: transfers_) {
    auto submit_transfer_status = libusb_submit_transfer(transferData->transfer);
    transferData->isSubmitted = submit_transfer_status == LIBUSB_SUCCESS;
  }

  int32_t submittedTransfers = std::count_if(
      transfers_.begin(),
      transfers_.end(),
      [](const std::unique_ptr<TransferUserData>& transferUserData) {
        return transferUserData->isSubmitted;
      });
  ULOGE("submitTransferRequests2 %d", submittedTransfers);
  if (submittedTransfers == 0) {
    state_ = StreamerState::ERROR;
    return false;
  } else {
    return true;
  }
}

void UsbAudioStreamer::allocateTransferRequests() {
  framesPerBurst_ = AAudioStream_getFramesPerBurst(audioStream_);
  bufferCapacityInFrames_ = AAudioStream_getBufferCapacityInFrames(audioStream_);
  ULOGD(
          "AAudioStream params: framesPerBurst %d bufferSizeInFrames %d bufferCapacityInFrames = %d",
          framesPerBurst_,
          AAudioStream_getBufferSizeInFrames(audioStream_),
          bufferCapacityInFrames_);
  auto bytes_per_burst = framesPerBurst_ * subFrameSize_ * channelCount_;
  auto computed_num_packets = (bytes_per_burst + maxPacketSize_ - 1) / maxPacketSize_;
  auto num_packets = std::max(2, computed_num_packets);
  auto buffer_size = maxPacketSize_ * num_packets;
  auto computed_num_transfers = (bufferCapacityInFrames_ + framesPerBurst_ - 1) / framesPerBurst_;
  int32_t num_transfers = std::max(2, computed_num_transfers);
  size_t ring_buffer_capacity = buffer_size * num_transfers / subFrameSize_;
  ULOGI(
          "ISO transfer params. maxPacketSize: %d num packets: %d buffer size: %d num transfers: %d",
          maxPacketSize_,
          num_packets,
          buffer_size,
          num_transfers);
  ULOGI(
          "Audio out params. framesPerBurst: %d bufferCapacityInFrames: %d, ring buffer capacity: %zu",
          framesPerBurst_,
          bufferCapacityInFrames_,
          ring_buffer_capacity);

  if (ringBuffer_->capacity() != ring_buffer_capacity) {
    ringBuffer_ = std::make_unique<RingBufferPcm>(ring_buffer_capacity);
  }

  for (auto i = 0; i < num_transfers; i++) {
    libusb_transfer* transfer = libusb_alloc_transfer(num_packets);
    if (transfer == nullptr) {
      ULOGD("libusb_alloc_transfer index %d failed.", i);
      continue;
    }
    ULOGD(
            "libusb_transfer initial status is %d. %p maxPacketSize_: %d",
            transfer->status,
            this,
            maxPacketSize_);
    transfers_.emplace_back(std::make_unique<TransferUserData>(transfer, this, false));
    TransferUserData* transferUserData = transfers_.back().get();
    libusb_fill_iso_transfer(
            transfer,
            deviceHandle_,
            (unsigned char)endpointAddress_,
            (unsigned char*)malloc(buffer_size),
            buffer_size,
            num_packets,
            transferCallback,
            transferUserData,
            kIsochronousTransferTimeoutMillis);
    transfer->flags = LIBUSB_TRANSFER_SHORT_NOT_OK | LIBUSB_TRANSFER_FREE_BUFFER;
    libusb_set_iso_packet_lengths(transfer, maxPacketSize_);
  }
}

bool UsbAudioStreamer::stop() {
  ULOGI("UsbAudioStreamer stop called");
  state_ = StreamerState::STOPPING;
  uint8_t tries{0};
  while (hasActiveTransfers() && tries++ < 5) {
    std::unique_lock lk(mutex_);
    stateChange_.wait_for(lk, 100ms);
  }
  stopUsbAudioCapture_ = 1;
  if (hasActiveTransfers() || !stopAudioPlayer()) {
    ULOGE("UsbAudioStreamer stop failed. Active Transfers %d", hasActiveTransfers());
    state_ = StreamerState::ERROR;
    return false;
  } else {
    state_ = StreamerState::READY_TO_START;
    return true;
  }
}

uint32_t UsbAudioStreamer::samplesFromByteCount(uint32_t byteCount) const {
  return byteCount / channelCount_ / subFrameSize_;
}

std::string UsbAudioStreamer::statsSummaryString() const {
  std::string audioFormatStr = "";
  if (jAudioFormat_ == 2) { // AudioFormat.ENCODING_PCM_16BIT
    audioFormatStr = "PCM16";
  } else if (jAudioFormat_ == 3) {
    audioFormatStr = "PCM8";
  } else if (jAudioFormat_ == 4) {
    audioFormatStr = "PCM Float";
  }
  return std::format(
          "{} {}Ch. {}", audioFormatStr, channelCount_, streamerStats_.samplingFrequency);
  ;
}

aaudio_data_callback_result_t UsbAudioStreamer::audioPlaybackCallback(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames) {
  UsbAudioStreamer* streamer = reinterpret_cast<UsbAudioStreamer*>(userData);
  auto sizeToRead = streamer->channelCount_ * numFrames;
  auto bytesToRead = streamer->bytesInAudioFrames(numFrames);

  streamer->streamerStats_.event_loops++;
  libusb_handle_events_timeout_completed(
          streamer->context(),
          &streamer->libusbEventsTimeout_,
          const_cast<int*>(&streamer->stopUsbAudioCapture_));
  streamer->streamerStats_.player_cb_counter++;

  auto available = streamer->ringBuffer_->size();

  if (available < sizeToRead) {
    memset(audioData, 0, bytesToRead);
  } else {
    auto movedData = streamer->ringBuffer_->read((uint16_t*)audioData, sizeToRead);
    if (movedData != sizeToRead && streamer->state_ == StreamerState::STARTED) {
      ULOGD(
              "ringBuffer read error %zu sizeToRead %d read data = %d",
              available,
              sizeToRead,
              movedData);
    }
  }
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

bool UsbAudioStreamer::startAudioPlayer() {
  if (AAudioStream_requestStart(audioStream_) != AAUDIO_OK) {
    return false;
  }
  aaudio_stream_state_t inputState = AAUDIO_STREAM_STATE_STARTING;
  aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
  int64_t timeoutNanos = 500LL * 1000000LL;
  AAudioStream_waitForStateChange(audioStream_, inputState, &nextState, timeoutNanos);
  return nextState == AAUDIO_STREAM_STATE_STARTED;
}

bool UsbAudioStreamer::stopAudioPlayer() {
  if (AAudioStream_requestStop(audioStream_) != AAUDIO_OK) {
    return false;
  }
  aaudio_stream_state_t inputState = AAUDIO_STREAM_STATE_STOPPING;
  aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
  AAudioStream_waitForStateChange(audioStream_, inputState, &nextState, 500000000);
  return nextState == AAUDIO_STREAM_STATE_STOPPED;
}

void UsbAudioStreamer::transferCallback(libusb_transfer* transfer) {
  if (transfer == nullptr) {
    ULOGE("transferCallback transfer is null.");
    return;
  }
  TransferUserData* transferUserData = reinterpret_cast<TransferUserData*>(transfer->user_data);

  if (transferUserData == nullptr) {
    ULOGE("transferUserData is null.");
    return;
  }
  transferUserData->isSubmitted = false;
  if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
    ULOGI("LIBUSB_TRANSFER_NO_DEVICE");
    return;
  }

  UsbAudioStreamer* streamer = transferUserData->streamer;
  const StreamerState state = streamer->state_;
  if (state == StreamerState::STOPPING) {
    if (streamer->hasActiveTransfers()) {
      ULOGE("Streamer has active transfers");
    } else {
      ULOGE("Streamer has no active transfers");
      std::unique_lock lk(streamer->mutex_);
      streamer->stateChange_.notify_one();
    }
    return;
  }
  if (state == StreamerState::DESTROYING || state == StreamerState::DESTROYED) {
    ULOGE("Streamer is shutting down");
    return;
  }

  int len = 0;
  for (auto i = 0; i < transfer->num_iso_packets; i++) {
    struct libusb_iso_packet_descriptor* pack = &transfer->iso_packet_desc[i];
    if (pack->status != LIBUSB_TRANSFER_COMPLETED) {
      const time_point<steady_clock> now = steady_clock::now();
      if (now - streamer->callbackErrorLoggedAt_ > 60s) {
        ULOGE("Error (status %d: %s)", pack->status, libusb_error_name(pack->status));
        streamer->callbackErrorLoggedAt_ = now;
      }
      continue;
    }
    const uint8_t* data = libusb_get_iso_packet_buffer_simple(transfer, i);

    auto dataSize = pack->actual_length / 2;
    auto result = streamer->ringBuffer_->write((uint16_t*)data, dataSize);
    if (result != dataSize) {
      ULOGE("Write error result = %d to write = %d", result, pack->actual_length);
    }

    len += pack->actual_length;
  }

  /* update stats */
  UsbAudioStreamerStats& stats = streamer->streamerStats_;

  if (len > 0) {
    stats.recordSamples(streamer->samplesFromByteCount(len));
  }

  const time_point<steady_clock> now = steady_clock::now();
  if (stats.t0_10_s.time_since_epoch().count() == 0) {
    stats.t0_10_s = now;
    stats.total_bytes = 0;
    stats.player_cb_counter = 0;
    stats.usb_cb_counter = 0;
  }
  stats.total_bytes += len;
  stats.usb_cb_counter++;

  duration<float> diff = duration_cast<seconds>(now - stats.t0_10_s);
  if (diff >= 10.0s) {
    ULOGI(
            "Audio callbacks %hu usb callbacks %hu in %hu event loops. Transferred  %d in %.1f secs, speed %.1f bps",
            stats.player_cb_counter,
            stats.usb_cb_counter,
            stats.event_loops,
            stats.total_bytes,
            diff.count(),
            stats.total_bytes / diff.count());
    stats.t0_10_s = now;
    stats.total_bytes = 0;
    stats.player_cb_counter = 0;
    stats.usb_cb_counter = 0;
    stats.event_loops = 0;
  }

  int maxExpectedLen = streamer->maxPacketSize_ * transfer->num_iso_packets;

  if (len > maxExpectedLen) {
    ULOGE("Error: incoming transfer data is more than maxPacketSize * num_iso_packets.");
    ULOGE(
            "Error: incoming transfer data %d is more than maxPacketSize * num_iso_packets. %dx%d=%d",
            len,
            streamer->maxPacketSize_,
            transfer->num_iso_packets,
            maxExpectedLen);
    ULOGE("streamer %p", streamer);
    return;
  }

  auto status = libusb_submit_transfer(transfer);
  if (status == LIBUSB_SUCCESS) {
    transferUserData->isSubmitted = true;
  } else if (status == LIBUSB_ERROR_NO_DEVICE) {
    ULOGE("LOST DEVICE libusb_submit_transfer: %s.", libusb_error_name(status));
  } else {
    ULOGE("libusb_submit_transfer: %s.", libusb_error_name(status));
  }
}

bool UsbAudioStreamer::isPlaying() const {
  return state_ == StreamerState::STARTED;
}
