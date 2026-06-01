package com.beanotherlab.usbvideounitybridge

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface

class UsbBridge {

  init {
    System.loadLibrary("usbvideo")
  }

  // FIX: Move this OUT of the companion object block to match your exact C++ JNI symbol signature!
  external fun nativeSetSurface(surface: Surface)

  companion object {
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var nativeTextureId: Int = -1

    // Retain a static reference to the root class instance to call the method
    private var bridgeInstance: UsbBridge? = null

    @JvmStatic
    fun createSurfaceTexture(textureId: Int) {
      if (textureId == 0) return

      nativeTextureId = textureId
      bridgeInstance = UsbBridge()

      // Completely omit the GLES20 parameter logic to bypass the thread context warning
      surfaceTexture = SurfaceTexture(nativeTextureId)
      surface = Surface(surfaceTexture)

      Log.d("USB_BRIDGE", "SurfaceTexture created from Unity ID: $nativeTextureId")

      surface?.let {
        bridgeInstance?.nativeSetSurface(it)
      }
    }

    @JvmStatic
    fun updateTexture() {
      surfaceTexture?.let {
        synchronized(it) {
          it.updateTexImage()
        }
      }
    }
  }
}
