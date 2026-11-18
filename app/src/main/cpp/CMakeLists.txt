cmake_minimum_required(VERSION 3.22.1)
project(launcher_native LANGUAGES CXX)

add_library(launcher_native SHARED nativebridge.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(
    launcher_native
    \${log-lib}
    \${android-lib}
    \${vulkan-lib}
    \${egl-lib}
    \${glesv2-lib}
)
