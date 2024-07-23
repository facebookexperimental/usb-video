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

#include <assert.h>
#include <limits.h>
#include <stdint.h>
#include <algorithm>
#include <memory>

template <typename T>
class RingBuffer {
 public:
  RingBuffer(const RingBuffer&) = delete;
  RingBuffer& operator=(const RingBuffer&) = delete;
  RingBuffer(int capacity) : capacity_(capacity), readPos_(0), writePos_(0) {
    buffer_ = std::unique_ptr<T[]>(new T[capacity_]);
    memset(buffer_.get(), 0, sizeof(T) * capacity_);
  }

  int32_t capacity() const {
    return capacity_;
  }

  size_t size() {
    return (writePos_ + capacity_ - readPos_) % capacity_;
  }

  int write(T* data, int len) {
    if (data == nullptr || len <= 0) {
      return 0;
    }

    // If we are adding data larger than the capacity
    // then just take the last capacity_ from the input data.
    if (len > capacity_) {
      data = &data[len - capacity_];
      len = capacity_;
    }

    int size = (writePos_ + capacity_ - readPos_) % capacity_;
    int start = writePos_;
    int end = (writePos_ + len) % capacity_;
    if (start < end) {
      memcpy(&buffer_[start], data, len * sizeof(T));
    } else {
      int first_slice = capacity_ - start;
      memcpy(&buffer_[start], data, first_slice * sizeof(T));
      memcpy(&buffer_[0], &data[first_slice], (len - first_slice) * sizeof(T));
    }

    writePos_ = end;
    if (size + len > capacity_) {
      readPos_ = (readPos_ + size + len) % capacity_;
    }
    return len;
  }

  int read(T* data, int len) {
    int available = (writePos_ + capacity_ - readPos_) % capacity_;
    if (available == 0 || data == nullptr || len <= 0) {
      return 0;
    }

    int to_copy = std::min(len, available);

    int start = readPos_;
    int end = (readPos_ + to_copy) % capacity_;

    if (start < end) {
      memcpy(data, &buffer_[start], to_copy * sizeof(T));
    } else {
      int first_slice = capacity_ - start;
      memcpy(data, &buffer_[start], first_slice * sizeof(T));
      memcpy(&data[first_slice], &buffer_[0], (to_copy - first_slice) * sizeof(T));
    }

    readPos_ = (readPos_ + to_copy) % capacity_;
    return to_copy;
  }

 private:
  int capacity_;
  uint32_t readPos_;
  uint32_t writePos_;
  std::unique_ptr<T[]> buffer_;
};

typedef RingBuffer<uint16_t> RingBufferPcm;
