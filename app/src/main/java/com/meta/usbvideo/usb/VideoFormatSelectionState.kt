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
package com.meta.usbvideo.usb

sealed interface VideoFormatSelectionState {
  val videoFormat: VideoFormat

  data class Open(override val videoFormat: VideoFormat) : VideoFormatSelectionState

  data class NegotiationError(
      override val videoFormat: VideoFormat,
      val errorMessage: String,
  ) : VideoFormatSelectionState

  data class Negotiated(override val videoFormat: VideoFormat) : VideoFormatSelectionState
}

fun VideoFormatSelectionState.loggingName(): String =
    when (this) {
      is VideoFormatSelectionState.Negotiated -> "NegotiatedVideoFormatSelection $videoFormat"
      is VideoFormatSelectionState.NegotiationError ->
          "NegotiationErrorVideoFormatSelection $videoFormat -> $errorMessage"
      is VideoFormatSelectionState.Open -> "OpenVideoFormatSelection $videoFormat"
    }
