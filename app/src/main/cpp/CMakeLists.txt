cmake_minimum_required(VERSION 3.18.1)
project("faceid_jni")

add_library(faceid_jni SHARED faceid_jni.cpp)
target_include_directories(faceid_jni PRIVATE .)

find_library(android-lib android)

target_link_libraries(faceid_jni
    ${CMAKE_SOURCE_DIR}/lib/${ANDROID_ABI}/libfaceid.so
    nativewindow
    ${android-lib}
    log)
