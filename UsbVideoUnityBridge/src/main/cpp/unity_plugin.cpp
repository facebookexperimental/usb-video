#include <android/log.h>
#include "usb_shared.h"

extern void render_frame();

extern "C"
void OnRenderEvent(int eventId);

static void RenderEvent(int eventId)
{
    __android_log_print(ANDROID_LOG_INFO, "USB", "RenderEvent CALLED event=%d", eventId);

    if (eventId == 1)
    {
        render_frame();
    }
}

extern "C"
void* GetRenderEventFunc()
{
    return (void*)RenderEvent;
}