cmake_minimum_required(VERSION 3.22.1)

project(mathclient LANGUAGES CXX)

add_library(
        mathclient
        SHARED
        math_core.cpp
)

target_compile_features(mathclient PRIVATE cxx_std_20)

target_compile_options(
        mathclient
        PRIVATE
        -Wall
        -Wextra
        -Wpedantic
)

find_library(android-log-lib log)
find_library(android-dl-lib dl)

target_link_libraries(
        mathclient
        PRIVATE
        ${android-log-lib}
        ${android-dl-lib}
)
