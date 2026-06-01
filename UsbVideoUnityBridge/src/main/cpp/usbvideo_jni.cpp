#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>

extern "C"
JNIEXPORT void JNICALL
Java_com_beanotherlab_usbvideounitybridge_UsbBridge_nativeSetSurface(JNIEnv *env, jobject thiz, jobject surface) {

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);

    if (window)
    {
        __android_log_print(ANDROID_LOG_INFO,"USB_BRIDGE","ANativeWindow acquired");
    }
    else
    {
    __android_log_print(ANDROID_LOG_ERROR,"USB_BRIDGE","FAILED to acquire ANativeWindow");
    }

}