cmake_minimum_required(VERSION 3.22.1)
project(launcher_native LANGUAGES CXX)

add_library(launcher_native SHARED nativebridge.cpp)

find_library(log-lib log)
find_library(vulkan-lib vulkan)

target_link_libraries(launcher_native ${log-lib} ${vulkan-lib})
