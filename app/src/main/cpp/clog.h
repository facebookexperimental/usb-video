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

#ifndef USB_VIDEO_CLOG_H
#define USB_VIDEO_CLOG_H

#include <android/log.h>

#define CLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "UsbVideo", __VA_ARGS__)
#define CLOGI(...) __android_log_print(ANDROID_LOG_INFO, "UsbVideo", __VA_ARGS__)

#define CLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "UsbVideo", __VA_ARGS__)
#define CLOGW(...) __android_log_print(ANDROID_LOG_WARN, "UsbVideo", __VA_ARGS__)

#endif // USB_VIDEO_CLOG_H
