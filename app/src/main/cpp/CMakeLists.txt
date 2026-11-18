cmake_minimum_required(VERSION 3.22.1)
project(launcher_native LANGUAGES C CXX)

add_library(
    launcher_native
    SHARED
    nativebridge.cpp
    x11connector_epoll.c
)

# PCGL owns the native X11 transport inside this APK.
target_link_libraries(
    launcher_native
    log
    android
    vulkan
    EGL
    GLESv2
)
