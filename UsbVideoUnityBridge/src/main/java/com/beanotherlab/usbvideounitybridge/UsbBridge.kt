package com.beanotherlab.usbvideounitybridge

import android.util.Log

class UsbBridge {

  companion object {

    private var textureId: Int = 0

    init {
      System.loadLibrary("usbvideo")
    }

    @JvmStatic
    fun create(texId: Int) {
      textureId = texId
      Log.d("USB", "Unity texture = $textureId")

      nativeInit(textureId)
      Log.d("USB", "nativeInit() was invoked")
    }

    @JvmStatic
    external fun nativeInit(texId: Int)

    @JvmStatic
    external fun nativeRender()
  }
}