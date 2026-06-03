#include <GLES2/gl2.h>
#include <android/log.h>
#include "usb_shared.h"
#include <pthread.h>

extern GLuint g_unityTex;

void render_frame()
{
    __android_log_print(ANDROID_LOG_INFO, "USB", "render_frame ENTER");

    if (g_unityTex == 0) {
        __android_log_print(ANDROID_LOG_ERROR, "USB", "g_unityTex == 0");
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, "USB", "binding texture=%u", g_unityTex);

    GLboolean isTex = glIsTexture(g_unityTex);

    __android_log_print(
            ANDROID_LOG_INFO,
            "USB",
            "glIsTexture=%d",
            isTex
    );

    glBindTexture(GL_TEXTURE_2D, g_unityTex);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR)
    {
        __android_log_print(ANDROID_LOG_ERROR, "USB", "GL ERROR AFTER BIND=%x", err);
    }

    // -----------------------------
    // BLINK TEST (VALID 1x1 UPDATE)
    // -----------------------------

    static bool toggle = false;
    toggle = !toggle;

    // MUST match the declared width/height (1x1 safe test)
    unsigned char pixel[4];

    if (toggle) {
        pixel[0] = 255; pixel[1] = 0;   pixel[2] = 0;   pixel[3] = 255; // red
    } else {
        pixel[0] = 0;   pixel[1] = 255; pixel[2] = 0;   pixel[3] = 255; // green
    }

    glTexSubImage2D(
            GL_TEXTURE_2D,
            0,
            0, 0,
            1, 1,                 // IMPORTANT: must match buffer size
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            pixel
    );

    GLenum err2 = glGetError();
    if (err2 != GL_NO_ERROR)
    {
        __android_log_print(ANDROID_LOG_ERROR, "USB", "GL ERROR AFTER UPLOAD=%x", err2);
    }

    glFlush();

    __android_log_print(
            ANDROID_LOG_INFO,
            "USB",
            "thread=%ld toggle=%d",
            pthread_self(),
            toggle
    );
}