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

package com.meta.usbvideo.animation

import android.view.View
import androidx.viewpager2.widget.ViewPager2

private const val MIN_SCALE = 0.85f
private const val MIN_ALPHA = 0.5f

/**
 * View pager animation to transition between status and streamer screens.
 * Adapted from android developer guide at
 * https://developer.android.com/develop/ui/views/animations/screen-slide-2
 */
class ZoomOutPageTransformer : ViewPager2.PageTransformer {

  override fun transformPage(view: View, position: Float) {
    view.zoomOutFor(position)
  }

  fun View.zoomOutFor(position: Float) {
    val pageWidth = width
    val pageHeight = height
    when {
      position < -1 -> { // [-Infinity,-1)
        // This page is way off-screen to the left.
        alpha = 0f
      }
      position <= 1 -> { // [-1,1]
        // Modify the default slide transition to shrink the page as well.
        val scaleFactor = Math.max(MIN_SCALE, 1 - Math.abs(position))
        val vertMargin = pageHeight * (1 - scaleFactor) / 2
        val horzMargin = pageWidth * (1 - scaleFactor) / 2
        translationX =
            if (position < 0) {
              horzMargin - vertMargin / 2
            } else {
              horzMargin + vertMargin / 2
            }

        // Scale the page down (between MIN_SCALE and 1).
        scaleX = scaleFactor
        scaleY = scaleFactor

        // Fade the page relative to its size.
        alpha = (MIN_ALPHA + (((scaleFactor - MIN_SCALE) / (1 - MIN_SCALE)) * (1 - MIN_ALPHA)))
      }
      else -> { // (1,+Infinity]
        // This page is way off-screen to the right.
        alpha = 0f
      }
    }
  }
}
