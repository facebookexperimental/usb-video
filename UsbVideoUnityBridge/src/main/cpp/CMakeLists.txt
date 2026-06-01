cmake_minimum_required(VERSION 3.22.1)

project(usbvideounitybridge)

add_library (
        usbvideo
        SHARED
        usbvideo_jni.cpp
)

find_library (
        log-lib
        log
)

target_link_libraries (
        usbvideo
        ${log-lib}
        android
)