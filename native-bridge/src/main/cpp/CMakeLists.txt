cmake_minimum_required(VERSION 3.22.1)
project("cloneforge_core")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Mandatory 16KB ELF segment alignment for Android 15/16 (API 35/36) devices
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_package(shadowhook REQUIRED CONFIG)
find_package(lsplant REQUIRED CONFIG)

add_library(
    cloneforge_core
    SHARED
    io_redirect.cpp
    lsplant_bridge.cpp
)

target_link_libraries(
    cloneforge_core
    shadowhook::shadowhook
    lsplant::lsplant
    android
    log
)
