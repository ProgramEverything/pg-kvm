# CMakeLists.txt for libuvc module
# Converts the original ndk-build (Android.mk) to CMake
#
# Builds four native libraries:
#   1. libusb100.so  - libusb with Android backend
#   2. libjpeg-turbo1500.so - libjpeg-turbo with SIMD
#   3. libuvc.so     - UVC video capture library
#   4. libUVCCamera.so - JNI wrapper for Android camera access
#
cmake_minimum_required(VERSION 3.10.2)
project("uvccamera")

# ── Build type: default to Debug for debugging support ─────────────────────
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE "Debug" CACHE STRING "Build type (Debug / Release)" FORCE)
    message(STATUS "No build type specified, defaulting to Debug")
endif()

message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "Target ABI: ${ANDROID_ABI}")

# ── Common compile options ─────────────────────────────────────────────────
# Base flags for all targets
set(COMMON_C_FLAGS
    -DANDROID_NDK
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fexceptions
)

# Debug / Release specific flags
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(COMMON_C_FLAGS ${COMMON_C_FLAGS}
        -g
        -O0
        -DUVC_DEBUGGING
        -fno-omit-frame-pointer
    )
    message(STATUS "Debug symbols and logging enabled")
else()
    set(COMMON_C_FLAGS ${COMMON_C_FLAGS}
        -O3
        -fstrict-aliasing
        -fprefetch-loop-arrays
        -DLOG_NDEBUG
    )
endif()

# ── Helper: set common properties on a target ──────────────────────────────
function(set_native_target_properties TARGET)
    target_compile_options(${TARGET} PRIVATE ${COMMON_C_FLAGS})
    # C++ standard for C++ sources
    if(${TARGET} MATCHES "UVCCamera")
        target_compile_features(${TARGET} PRIVATE cxx_std_11)
    endif()
endfunction()

# ═══════════════════════════════════════════════════════════════════════════
# 1. libusb100.so — libusb with Android backend
# ═══════════════════════════════════════════════════════════════════════════
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)

add_library(usb100 SHARED
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/android_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/poll_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/android_netlink.c
)

target_include_directories(usb100 PRIVATE
    ${LIBUSB_ROOT}
    ${LIBUSB_ROOT}/libusb
    ${LIBUSB_ROOT}/libusb/os
    ${LIBUSB_ROOT}/android
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# usb100 needs this PUBLIC include so consumers (uvc, UVCCamera) can find libusb.h
target_include_directories(usb100 PUBLIC
    ${LIBUSB_ROOT}
)

target_compile_definitions(usb100 PRIVATE
    OS_ANDROID
    ACCESS_RAW_DESCRIPTORS
    HAVE_DLFCN_H
    HAVE_GETTIMEOFDAY
    HAVE_INTTYPES_H
    HAVE_MEMORY_H
    HAVE_POLL_H
    HAVE_SIGNAL_H
    HAVE_SYS_STAT_H
    HAVE_SYS_TIME_H
    HAVE_SYS_TYPES_H
    HAVE_UNISTD_H
    HAVE_LINUX_FILTER_H
    HAVE_LINUX_NETLINK_H
    HAVE_ASM_TYPES_H
    HAVE_SYS_SOCKET_H
    ENABLE_LOGGING
    USE_SYSTEM_LOGGING_FACILITY
    THREADS_POSIX
    DEFAULT_VISIBILITY=__attribute__\(\(visibility\(\"default\"\)\)\)
    POLL_NFDS_TYPE=nfds_t
)

target_link_libraries(usb100 PRIVATE
    log
)

set_native_target_properties(usb100)

# ═══════════════════════════════════════════════════════════════════════════
# 2. libjpeg-turbo1500.so — JPEG codec with SIMD acceleration
# ═══════════════════════════════════════════════════════════════════════════
set(JPEG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo-1.5.0)

set(JPEG_COMMON_SOURCES
    ${JPEG_ROOT}/jcapimin.c      ${JPEG_ROOT}/jcapistd.c
    ${JPEG_ROOT}/jccoefct.c      ${JPEG_ROOT}/jccolor.c
    ${JPEG_ROOT}/jcdctmgr.c      ${JPEG_ROOT}/jchuff.c
    ${JPEG_ROOT}/jcinit.c        ${JPEG_ROOT}/jcmainct.c
    ${JPEG_ROOT}/jcmarker.c      ${JPEG_ROOT}/jcmaster.c
    ${JPEG_ROOT}/jcomapi.c       ${JPEG_ROOT}/jcparam.c
    ${JPEG_ROOT}/jcphuff.c       ${JPEG_ROOT}/jcprepct.c
    ${JPEG_ROOT}/jcsample.c      ${JPEG_ROOT}/jctrans.c
    ${JPEG_ROOT}/jdapimin.c      ${JPEG_ROOT}/jdapistd.c
    ${JPEG_ROOT}/jdatadst.c      ${JPEG_ROOT}/jdatasrc.c
    ${JPEG_ROOT}/jdcoefct.c      ${JPEG_ROOT}/jdcolor.c
    ${JPEG_ROOT}/jddctmgr.c      ${JPEG_ROOT}/jdhuff.c
    ${JPEG_ROOT}/jdinput.c       ${JPEG_ROOT}/jdmainct.c
    ${JPEG_ROOT}/jdmarker.c      ${JPEG_ROOT}/jdmaster.c
    ${JPEG_ROOT}/jdmerge.c       ${JPEG_ROOT}/jdphuff.c
    ${JPEG_ROOT}/jdpostct.c      ${JPEG_ROOT}/jdsample.c
    ${JPEG_ROOT}/jdtrans.c       ${JPEG_ROOT}/jerror.c
    ${JPEG_ROOT}/jfdctflt.c      ${JPEG_ROOT}/jfdctfst.c
    ${JPEG_ROOT}/jfdctint.c      ${JPEG_ROOT}/jidctflt.c
    ${JPEG_ROOT}/jidctfst.c      ${JPEG_ROOT}/jidctint.c
    ${JPEG_ROOT}/jidctred.c      ${JPEG_ROOT}/jquant1.c
    ${JPEG_ROOT}/jquant2.c       ${JPEG_ROOT}/jutils.c
    ${JPEG_ROOT}/jmemmgr.c       ${JPEG_ROOT}/jmemnobs.c
    ${JPEG_ROOT}/jaricom.c       ${JPEG_ROOT}/jcarith.c
    ${JPEG_ROOT}/jdarith.c
    ${JPEG_ROOT}/turbojpeg.c     ${JPEG_ROOT}/transupp.c
    ${JPEG_ROOT}/jdatadst-tj.c   ${JPEG_ROOT}/jdatasrc-tj.c
)

# Architecture-specific SIMD sources
set(JPEG_SIMD_SOURCES "")

if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    enable_language(ASM)
    list(APPEND JPEG_SIMD_SOURCES
        ${JPEG_ROOT}/simd/jsimd_arm.c
        ${JPEG_ROOT}/simd/jsimd_arm_neon.S
    )
    set(JPEG_SIZE_T_DEFINE -DSIZEOF_SIZE_T=4)
    set(JPEG_NEON_FLAG -mfpu=neon)

elseif(${ANDROID_ABI} STREQUAL "arm64-v8a")
    enable_language(ASM)
    list(APPEND JPEG_SIMD_SOURCES
        ${JPEG_ROOT}/simd/jsimd_arm64.c
        ${JPEG_ROOT}/simd/jsimd_arm64_neon.S
    )
    set(JPEG_SIZE_T_DEFINE -DSIZEOF_SIZE_T=8)

elseif(${ANDROID_ABI} STREQUAL "x86_64")
    # NASM not available in NDK CMake toolchain; use C fallback for x86_64
    list(APPEND JPEG_SIMD_SOURCES ${JPEG_ROOT}/jsimd_none.c)
    set(JPEG_SIZE_T_DEFINE -DSIZEOF_SIZE_T=8)

elseif(${ANDROID_ABI} STREQUAL "x86")
    # NASM not available in NDK CMake toolchain; use C fallback for x86
    list(APPEND JPEG_SIMD_SOURCES ${JPEG_ROOT}/jsimd_none.c)
    set(JPEG_SIZE_T_DEFINE -DSIZEOF_SIZE_T=4)

else()
    # Fallback: no SIMD
    list(APPEND JPEG_SIMD_SOURCES ${JPEG_ROOT}/jsimd_none.c)
    set(JPEG_SIZE_T_DEFINE -DSIZEOF_SIZE_T=4)
endif()

add_library(jpeg-turbo1500 SHARED
    ${JPEG_COMMON_SOURCES}
    ${JPEG_SIMD_SOURCES}
)

target_include_directories(jpeg-turbo1500 PRIVATE
    ${JPEG_ROOT}
    ${JPEG_ROOT}/include
    ${JPEG_ROOT}/simd
)

target_compile_definitions(jpeg-turbo1500 PRIVATE
    ANDROID_NDK
    ${JPEG_SIZE_T_DEFINE}
)

# Architecture-specific compile flags for jpeg-turbo
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(jpeg-turbo1500 PRIVATE ${JPEG_NEON_FLAG})
    set_source_files_properties(
        ${JPEG_ROOT}/simd/jsimd_arm_neon.S
        PROPERTIES COMPILE_FLAGS "-x assembler-with-cpp"
    )
elseif(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set_source_files_properties(
        ${JPEG_ROOT}/simd/jsimd_arm64_neon.S
        PROPERTIES COMPILE_FLAGS "-x assembler-with-cpp"
    )
endif()

target_link_libraries(jpeg-turbo1500 PRIVATE
    dl
    log
)

set_native_target_properties(jpeg-turbo1500)

# ═══════════════════════════════════════════════════════════════════════════
# 3. libuvc.so — UVC (USB Video Class) library
# ═══════════════════════════════════════════════════════════════════════════
set(LIBUVC_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libuvc)

add_library(uvc SHARED
    ${LIBUVC_ROOT}/src/ctrl.c
    ${LIBUVC_ROOT}/src/device.c
    ${LIBUVC_ROOT}/src/diag.c
    ${LIBUVC_ROOT}/src/frame.c
    ${LIBUVC_ROOT}/src/frame-mjpeg.c
    ${LIBUVC_ROOT}/src/init.c
    ${LIBUVC_ROOT}/src/stream.c
    ${LIBUVC_ROOT}/src/misc.c
)

target_include_directories(uvc PRIVATE
    ${LIBUVC_ROOT}/include
    ${LIBUVC_ROOT}/include/libuvc
    ${JPEG_ROOT}
    ${JPEG_ROOT}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(uvc PRIVATE
    ANDROID_NDK
    LIBUVC_HAS_JPEG
)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(uvc PRIVATE UVC_DEBUGGING)
endif()

target_link_libraries(uvc PRIVATE
    usb100
    jpeg-turbo1500
    log
)

set_native_target_properties(uvc)

# ═══════════════════════════════════════════════════════════════════════════
# 4. libUVCCamera.so — JNI wrapper for Android UVC camera
# ═══════════════════════════════════════════════════════════════════════════
set(UVCCAMERA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/UVCCamera)

add_library(UVCCamera SHARED
    ${UVCCAMERA_ROOT}/_onload.cpp
    ${UVCCAMERA_ROOT}/utilbase.cpp
    ${UVCCAMERA_ROOT}/UVCCamera.cpp
    ${UVCCAMERA_ROOT}/UVCPreview.cpp
    ${UVCCAMERA_ROOT}/UVCButtonCallback.cpp
    ${UVCCAMERA_ROOT}/UVCStatusCallback.cpp
    ${UVCCAMERA_ROOT}/Parameters.cpp
    ${UVCCAMERA_ROOT}/serenegiant_usb_UVCCamera.cpp
)

target_include_directories(UVCCamera PRIVATE
    ${UVCCAMERA_ROOT}
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIBUSB_ROOT}/libusb
    ${LIBUVC_ROOT}/include
    ${LIBUVC_ROOT}/include/libuvc
    ${CMAKE_CURRENT_SOURCE_DIR}/rapidjson/include
)

target_compile_definitions(UVCCamera PRIVATE
    ANDROID_NDK
    ACCESS_RAW_DESCRIPTORS
)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(UVCCamera PRIVATE)
else()
    target_compile_definitions(UVCCamera PRIVATE
        LOG_NDEBUG
    )
endif()

target_link_libraries(UVCCamera PRIVATE
    uvc
    usb100
    dl
    log
    android
)

set_native_target_properties(UVCCamera)
# Suppress pointer-to-int cast warnings (RETURN macro logs pointer as %d in utilbase.h)
target_compile_options(UVCCamera PRIVATE -Wno-pointer-to-int-cast)