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
package com.meta.usbvideo.permission

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager

fun Activity.getPermissionStatus(permission: String): PermissionStatus {
  val isGranted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
  return if (isGranted) {
    PermissionGranted
  } else if (shouldShowRequestPermissionRationale(permission)) {
    PermissionDenied
  } else {
    PermissionRequired
  }
}

fun Activity.getCameraPermissionState(): CameraPermissionState {
  return getPermissionStatus(Manifest.permission.CAMERA).toCameraState()
}

fun Activity.getRecordAudioPermissionState(): RecordAudioPermissionState {
  return getPermissionStatus(Manifest.permission.RECORD_AUDIO).toRecordAudioState()
}

fun PermissionStatus.toCameraState(): CameraPermissionState {
  return when (this) {
    PermissionGranted -> CameraPermissionGranted
    PermissionRequired -> CameraPermissionRequired
    PermissionDenied -> CameraPermissionDenied
  }
}

fun PermissionStatus.toRecordAudioState(): RecordAudioPermissionState {
  return when (this) {
    PermissionGranted -> RecordAudioPermissionGranted
    PermissionRequired -> RecordAudioPermissionRequired
    PermissionDenied -> RecordAudioPermissionDenied
  }
}
