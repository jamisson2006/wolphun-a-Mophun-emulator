cmake_minimum_required(VERSION 3.10.2)
project(wolphunsf)

add_library(wolphunsf SHARED soundfont_jni.cpp)

find_library(log-lib log)
target_link_libraries(wolphunsf ${log-lib})
