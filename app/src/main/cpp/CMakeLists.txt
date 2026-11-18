cmake_minimum_required(VERSION 3.22.1)
project(launcher_native LANGUAGES CXX)

add_library(launcher_native SHARED nativebridge.cpp)

# These are Android NDK platform libraries. Link by their platform names so
# NDK's toolchain resolves their system stubs for the ARM64 target.
target_link_libraries(
    launcher_native
    log
    android
    vulkan
    EGL
    GLESv2
)
