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

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <memory.h>
#include <string>

#include "UsbAudioStreamer.h"
#include "UsbVideoStreamer.h"
#include "clog.h"

static JavaVM* javaVM_ = nullptr;
static jmethodID updateAudioStatsMethodID = nullptr;
static jmethodID updateVideotatsMethodID = nullptr;

static std::unique_ptr<UsbAudioStreamer> streamer_{};
static std::unique_ptr<UsbVideoStreamer> uvcStreamer_{};

using ANativeWindowOwner = std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)>;
static ANativeWindowOwner previewWindow_ = ANativeWindowOwner(nullptr, &ANativeWindow_release);

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void* reserved) {
  javaVM_ = jvm;
  JNIEnv* env;
  if (JNI_OK != jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4)) {
    CLOGE("Get JNIEnv failed");
    return JNI_ERR;
  }
  jclass audioStatsSummaryClass = env->FindClass("com/meta/usbvideo/AudioStatsSummary");
  if (audioStatsSummaryClass != nullptr) {
    updateAudioStatsMethodID = env->GetMethodID(audioStatsSummaryClass, "update", "(III)V");
  }
  jclass videoStatsSummaryClass = env->FindClass("com/meta/usbvideo/VideoStatsSummary");
  if (videoStatsSummaryClass != nullptr) {
    updateVideotatsMethodID = env->GetMethodID(videoStatsSummaryClass, "update", "(IIII)V");
  }
  CLOGI("JNI_OnLoad success!");
  return JNI_VERSION_1_4;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* jvm, void* reserved) {
  if (jvm) {
    jvm->DestroyJavaVM();
  }
  javaVM_ = nullptr;
  CLOGI("JNI_OnUnload success!");
}

JNIEXPORT jint JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_getUsbDeviceSpeed(JNIEnv* env, jobject self) {
  if (streamer_ != nullptr) {
    return streamer_->getUsbDeviceSpeed();
  }
  return 0; /* LIBUSB_SPEED_UNKNOWN */
}

JNIEXPORT jint JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_connectUsbVideoStreamingNative(
    JNIEnv* env,
    jobject /*self*/,
    jint deviceFd,
    jint width,
    jint height,
    jint fps,
    jint libuvcFrameFormat) {
  if (uvcStreamer_ == nullptr) {
    uvcStreamer_ = std::make_unique<UsbVideoStreamer>((intptr_t)deviceFd);
  } else if (uvcStreamer_->getDeviceFD() != deviceFd) {
    CLOGW("Unexpected uvcStreamer_ state!");
    uvcStreamer_->stop();
    uvcStreamer_ = nullptr;
    previewWindow_.reset(nullptr);
    uvcStreamer_ = std::make_unique<UsbVideoStreamer>((intptr_t)deviceFd);
  }
  auto state =
      uvcStreamer_->configure(width, height, fps, static_cast<uvc_frame_format>(libuvcFrameFormat));
  return static_cast<jint>(state);
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_startUsbVideoStreamingNative(
    JNIEnv* env,
    jobject /*self*/,
    jobject jSurface) {
  if (uvcStreamer_ != nullptr) {
    previewWindow_.reset(ANativeWindow_fromSurface(env, jSurface));
    return uvcStreamer_->start(previewWindow_.get());
  }
  return false;
}

JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_stopUsbVideoStreamingNative(
    JNIEnv* env,
    jobject self) {
  if (uvcStreamer_ != nullptr) {
    uvcStreamer_->stop();
  }
}

JNIEXPORT void JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_disconnectUsbVideoStreamingNative(
    JNIEnv* env,
    jobject self) {
  uvcStreamer_ = nullptr;
  previewWindow_.reset(nullptr);
}

JNIEXPORT jstring JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_streamingStatsSummaryString(
    JNIEnv* env,
    jobject self) {
  std::string result;
  if (streamer_ != nullptr) {
    result += streamer_->statsSummaryString();
    result += "\n";
  }
  if (uvcStreamer_ != nullptr) {
    result += uvcStreamer_->statsSummaryString();
  }
  return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_updateStreamingStatsSummary(
    JNIEnv* env,
    jobject /*self*/,
    jobject audioStatsSummary,
    jobject videoStatsSummary) {
  if (audioStatsSummary != nullptr && updateAudioStatsMethodID != nullptr) {
    if (streamer_ != nullptr) {
      auto stats = streamer_->statsSummary();
      env->CallVoidMethod(
          audioStatsSummary,
          updateAudioStatsMethodID,
          stats.jAudioFormat,
          stats.channelCount,
          stats.samplingFrequency);
    } else {
      env->CallVoidMethod(audioStatsSummary, updateAudioStatsMethodID, -1, -1, -1);
    }
  }

  if (videoStatsSummary != nullptr && updateVideotatsMethodID != nullptr) {
    if (uvcStreamer_ != nullptr) {
      auto stats = uvcStreamer_->statsSummary();
      env->CallVoidMethod(
          videoStatsSummary,
          updateVideotatsMethodID,
          stats.captureFrameFormat,
          stats.captureFrameWidth,
          stats.captureFrameHeight,
          stats.fps);
    } else {
      env->CallVoidMethod(videoStatsSummary, updateVideotatsMethodID, -1, -1, -1, -1);
    }
  }
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_connectUsbAudioStreamingNative(
    JNIEnv* env,
    jobject tis,
    jint deviceFd,
    jint jAudioFormat,
    jint samplingFrequency,
    jint subFrameSize,
    jint channelCount,
    jint jAudioPerfMode,
    jint outputFramesPerBuffer) {
  if (streamer_ != nullptr) {
    return true;
  }
  streamer_ = std::make_unique<UsbAudioStreamer>(
      (intptr_t)deviceFd,
      jAudioFormat,
      samplingFrequency,
      subFrameSize,
      channelCount,
      jAudioPerfMode,
      outputFramesPerBuffer);
  return streamer_ != nullptr;
}

JNIEXPORT void JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_disconnectUsbAudioStreamingNative(
    JNIEnv* env,
    jobject self) {
  if (streamer_ != nullptr) {
    streamer_ = nullptr;
  }
}
JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_startUsbAudioStreamingNative(
    JNIEnv* env,
    jobject self) {
  if (streamer_ != nullptr) {
    streamer_->start();
  }
}

JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_stopUsbAudioStreamingNative(
    JNIEnv* env,
    jobject self) {
  if (streamer_ != nullptr) {
    streamer_->stop();
  }
}

} // extern "C"
