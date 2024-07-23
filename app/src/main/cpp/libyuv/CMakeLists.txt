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

project(libyuv)

set(target_name libyuv)

set(SOURCES
        libyuv/source/compare.cc
        libyuv/source/compare_common.cc
        libyuv/source/compare_gcc.cc
        libyuv/source/compare_mmi.cc
        libyuv/source/compare_msa.cc
        libyuv/source/compare_neon.cc
        libyuv/source/compare_neon64.cc
        libyuv/source/compare_win.cc
        libyuv/source/convert.cc
        libyuv/source/convert_argb.cc
        libyuv/source/convert_from.cc
        libyuv/source/convert_from_argb.cc
        libyuv/source/convert_to_argb.cc
        libyuv/source/convert_to_i420.cc
        libyuv/source/cpu_id.cc
        libyuv/source/planar_functions.cc
        libyuv/source/rotate.cc
        libyuv/source/rotate_any.cc
        libyuv/source/rotate_argb.cc
        libyuv/source/rotate_common.cc
        libyuv/source/rotate_gcc.cc
        libyuv/source/rotate_mmi.cc
        libyuv/source/rotate_msa.cc
        libyuv/source/rotate_neon.cc
        libyuv/source/rotate_neon64.cc
        libyuv/source/rotate_win.cc
        libyuv/source/row_any.cc
        libyuv/source/row_common.cc
        libyuv/source/row_gcc.cc
        libyuv/source/row_mmi.cc
        libyuv/source/row_msa.cc
        libyuv/source/row_neon.cc
        libyuv/source/row_neon64.cc
        libyuv/source/row_win.cc
        libyuv/source/scale.cc
        libyuv/source/scale_any.cc
        libyuv/source/scale_argb.cc
        libyuv/source/scale_common.cc
        libyuv/source/scale_gcc.cc
        libyuv/source/scale_mmi.cc
        libyuv/source/scale_msa.cc
        libyuv/source/scale_neon.cc
        libyuv/source/scale_neon64.cc
        libyuv/source/scale_uv.cc
        libyuv/source/scale_win.cc
        libyuv/source/video_common.cc
        )


set(PUBLIC_HEADERS
        libyuv/include/libyuv.h
        )

set(PRIVATE_HEADERS
        libyuv/include/libyuv.h
        libyuv/include/libyuv/basic_types.h
        libyuv/include/libyuv/compare.h
        libyuv/include/libyuv/compare_row.h
        libyuv/include/libyuv/convert.h
        libyuv/include/libyuv/convert_argb.h
        libyuv/include/libyuv/convert_from.h
        libyuv/include/libyuv/convert_from_argb.h
        libyuv/include/libyuv/cpu_id.h
        libyuv/include/libyuv/macros_msa.h
        libyuv/include/libyuv/mjpeg_decoder.h
        libyuv/include/libyuv/planar_functions.h
        libyuv/include/libyuv/rotate.h
        libyuv/include/libyuv/rotate_argb.h
        libyuv/include/libyuv/rotate_row.h
        libyuv/include/libyuv/row.h
        libyuv/include/libyuv/scale.h
        libyuv/include/libyuv/scale_argb.h
        libyuv/include/libyuv/scale_row.h
        libyuv/include/libyuv/scale_uv.h
        libyuv/include/libyuv/version.h
        libyuv/include/libyuv/video_common.h
        )


add_library(${target_name} ${PUBLIC_HEADERS} ${PRIVATE_HEADERS} ${SOURCES})
target_include_directories(${target_name} PUBLIC libyuv/include)


