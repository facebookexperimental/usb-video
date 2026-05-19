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
package com.meta.usbvideo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView

sealed class StreamerScreenViewHolder(rootView: View) : RecyclerView.ViewHolder(rootView)

class ConnectCaptureCardViewHolder(rootView: View) : StreamerScreenViewHolder(rootView)

class StreamerScreensAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val streamerViewModel: StreamerViewModel,
    var screens: List<StreamerScreen>,
) : RecyclerView.Adapter<StreamerScreenViewHolder>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamerScreenViewHolder {
    return when (StreamerScreen.values()[viewType]) {
      StreamerScreen.ConnectCaptureCardCTA ->
          ConnectCaptureCardViewHolder(
              parent.inflate(R.layout.connect_capture_card),
          )
      StreamerScreen.Status ->
          StatusScreenViewHolder(
              parent.inflate(R.layout.status_screen),
              streamerViewModel,
          )
      StreamerScreen.Streaming ->
          StreamingViewHolder(
              parent.inflate(R.layout.streaming_screen),
              streamerViewModel,
          )
    }
  }

  fun ViewGroup.inflate(@LayoutRes layoutRes: Int): View =
      LayoutInflater.from(context).inflate(layoutRes, this, false)

  override fun onBindViewHolder(holder: StreamerScreenViewHolder, position: Int) {
    when (holder) {
      is ConnectCaptureCardViewHolder -> Unit
      is StatusScreenViewHolder -> holder.bindModel(lifecycleOwner, streamerViewModel)
      is StreamingViewHolder -> holder.bindModel()
    }
  }

  override fun onViewRecycled(holder: StreamerScreenViewHolder) {
    when (holder) {
      is ConnectCaptureCardViewHolder -> Unit
      is StatusScreenViewHolder -> holder.unbindModel()
      is StreamingViewHolder -> Unit
    }
  }

  override fun getItemCount(): Int {
    return screens.size
  }

  override fun getItemId(position: Int): Long {
    return screens.get(position).ordinal.toLong()
  }

  override fun getItemViewType(position: Int): Int {
    return screens[position].ordinal
  }
}
