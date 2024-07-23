# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

cmake_minimum_required(VERSION 3.22.1)

project(libusb)

set(TARGET usb)

set(PUBLIC_HEADERS
        libusb-1.0.27/libusb/libusb.h
        libusb-1.0.27/libusb/libusbi.h
        libusb-1.0.27/libusb/version.h
        )

set(PRIVATE_HEADERS
        libusb-1.0.27/android/config.h
        libusb-1.0.27/libusb/libusb.h
        libusb-1.0.27/libusb/libusbi.h
        libusb-1.0.27/libusb/version.h
        libusb-1.0.27/libusb/version_nano.h
        libusb-1.0.27/libusb/os/darwin_usb.h
        libusb-1.0.27/libusb/os/events_posix.h
        libusb-1.0.27/libusb/os/events_windows.h
        libusb-1.0.27/libusb/os/haiku_usb.h
        libusb-1.0.27/libusb/os/haiku_usb_raw.h
        libusb-1.0.27/libusb/os/linux_usbfs.h
        libusb-1.0.27/libusb/os/sunos_usb.h
        libusb-1.0.27/libusb/os/threads_posix.h
        libusb-1.0.27/libusb/os/threads_windows.h
        libusb-1.0.27/libusb/os/windows_common.h
        libusb-1.0.27/libusb/os/windows_usbdk.h
        libusb-1.0.27/libusb/os/windows_winusb.h
        )

set(SRCS
        libusb-1.0.27/libusb/core.c
        libusb-1.0.27/libusb/descriptor.c
        libusb-1.0.27/libusb/hotplug.c
        libusb-1.0.27/libusb/io.c
        libusb-1.0.27/libusb/sync.c
        libusb-1.0.27/libusb/strerror.c
        libusb-1.0.27/libusb/os/linux_usbfs.c
        libusb-1.0.27/libusb/os/events_posix.c
        libusb-1.0.27/libusb/os/threads_posix.c
        libusb-1.0.27/libusb/os/linux_netlink.c
        )

include_directories(
        libusb-1.0.27/android
        libusb-1.0.27/libusb
        libusb-1.0.27/libusb/os
)

add_library(${TARGET} SHARED ${PUBLIC_HEADERS} ${PRIVATE_HEADERS} ${SRCS})
target_include_directories(${TARGET} PUBLIC libusb-1.0.27)
target_include_directories(${TARGET} PUBLIC libusb-1.0.27/libusb)

target_link_libraries(${TARGET}
        android
        log
        )

