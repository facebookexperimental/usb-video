#include <jni.h>
#include <android/log.h>
#include <GLES2/gl2.h>
#include "usb_shared.h"

GLuint g_unityTex = 0;   // ONLY HERE (single definition)

extern "C"
JNIEXPORT void JNICALL
Java_com_beanotherlab_usbvideounitybridge_UsbBridge_nativeInit(
        JNIEnv* env,
        jobject thiz,
        jint texId)
{
    g_unityTex = (GLuint)texId;

    __android_log_print(
            ANDROID_LOG_INFO,
            "USB",
            "Unity texture bound: %d",
            g_unityTex
    );

    // init camera / uvc pipeline here
}