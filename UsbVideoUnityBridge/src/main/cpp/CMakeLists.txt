cmake_minimum_required(VERSION 3.22.1)

project(usbvideounitybridge)

add_library(
        usbvideo
        SHARED
        usbvideo_jni.cpp
        usb_render.cpp
        unity_plugin.cpp
)

find_library(
        log-lib
        log
)

find_library(
        android-lib
        android
)

find_library(
        GLESv2-lib
        GLESv2
)

target_link_libraries(
        usbvideo
        ${log-lib}
        ${android-lib}
        ${GLESv2-lib}
)