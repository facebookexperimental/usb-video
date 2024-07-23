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

project(libuvc
  VERSION 0.0.7
)

set(target_name libuvc)

option(ENABLE_UVC_DEBUGGING "Enable UVC debugging" OFF)
option(ENABLE_UVC_DEBUGGING "LIBUVC_HAS_JPEG" OFF)

set(libuvc_DESCRIPTION "A cross-platform library for USB video devices")
set(libuvc_URL "https://github.com/libuvc/libuvc")

set(SOURCES 
  libuvc-master/src/ctrl.c
  libuvc-master/src/ctrl-gen.c
  libuvc-master/src/device.c
  libuvc-master/src/diag.c
  libuvc-master/src/frame.c
  libuvc-master/src/init.c
  libuvc-master/src/stream.c
  libuvc-master/src/misc.c
)

configure_file(libuvc-master/include/libuvc/libuvc_config.h.in
        libuvc-master/include/libuvc/libuvc_config.h
  @ONLY
)

set(PUBLIC_HEADERS
        libuvc-master/include/libuvc/libuvc.h
        libuvc-master/include/libuvc/libuvc_config.h
        libuvc-master/include/libuvc/libuvc_internal.h
        )

set(PRIVATE_HEADERS
        libuvc-master/include/libuvc/libuvc.h
        libuvc-master/include/libuvc/libuvc_internal.h
        libuvc-master/include/libuvc/libuvc_config.h
        libuvc-master/include/utlist.h
        )

add_library(${target_name} ${PUBLIC_HEADERS} ${PRIVATE_HEADERS} ${SOURCES})
target_include_directories(${target_name} PUBLIC libuvc-master/include "${CMAKE_CURRENT_BINARY_DIR}/libuvc-master/include")

target_link_libraries(${target_name} usb log)
