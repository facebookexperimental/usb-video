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

#ifndef USB_VIDEO_AAUDIO_TYPE_CONVERSION_H
#define USB_VIDEO_AAUDIO_TYPE_CONVERSION_H
#include <aaudio/AAudio.h>

inline aaudio_format_t convertFormat(int jAudioFormat) {
  switch (jAudioFormat) {
    case 4:
      return AAUDIO_FORMAT_PCM_FLOAT;
    case 2:
    default:
      return AAUDIO_FORMAT_PCM_I16;
  }
}
inline aaudio_performance_mode_t convertPerfMode(int jPerfMode) {
  switch (jPerfMode) {
    case 0:
      return AAUDIO_PERFORMANCE_MODE_NONE;
    case 1:
      return AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    case 2:
      return AAUDIO_PERFORMANCE_MODE_POWER_SAVING;
    default:
      return AAUDIO_PERFORMANCE_MODE_NONE;
  }
}
#endif // USB_VIDEO_AAUDIO_TYPE_CONVERSION_H
