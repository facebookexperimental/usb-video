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
package com.meta.usbvideo.eventloop

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Implements event loop thread for dispatching work such as native calls off main UI thread. The
 * class provides an API for posting Runnable objects to run on the event thread on
 * first-come-first-serve basis or execute later.
 *
 * The event loop thread internally uses Android <code>android.os.Looper</code> and
 * <code>android.os.Handler</code>.
 */
object EventLooper {

  private val TAG = "EventLooper"
  private val lock = CountDownLatch(1)
  private lateinit var looper: Looper
  private lateinit var handler: Handler

  init {
    thread(name = "EventLooper") {
      Looper.prepare()
      looper = requireNotNull(Looper.myLooper())
      handler = Handler(looper)
      lock.countDown()
      Looper.loop()
    }
    Log.i(TAG, "EventLooper initialized")
  }

  fun getLooper(): Looper {
    lock.await()
    return looper
  }

  fun getHandler(): Handler {
    lock.await()
    return handler
  }

  fun post(runnable: Runnable) {
    lock.await()
    handler.post(runnable)
  }

  suspend fun <T> call(callable: Callable<T>): T {
    return suspendCoroutine { cont ->
      post {
        @Suppress("CatchGeneralException")
        try {
          val t: T = callable.call()
          cont.resume(t)
        } catch (e: Exception) {
          cont.resumeWithException(e)
        }
      }
    }
  }

  fun postDelayed(runnable: Runnable, delayMillis: Long) {
    lock.await()
    handler.postDelayed(runnable, delayMillis)
  }
}
