cmake_minimum_required(VERSION 3.18)
project("hardware_buffer_reader")

add_library(hardware_buffer_reader SHARED
    hardware_buffer_reader.cpp
)

target_link_libraries(hardware_buffer_reader
    android
    nativewindow
    log
)
