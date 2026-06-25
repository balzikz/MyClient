cmake_minimum_required(VERSION 3.22.1)

project(mathclient LANGUAGES C CXX)

add_library(
        mathclient
        SHARED
        math_core.cpp
        egl_lab.cpp
        render_state_lab.cpp
        fast_guard_lab.cpp
)

add_library(
        mathlinker
        SHARED
        linker_load_lab.c
        minecraft_load_lab.c
        minecraft_symbol_probe.c
)

target_compile_features(mathclient PRIVATE cxx_std_20)
target_compile_features(mathlinker PRIVATE c_std_17)

target_compile_options(
        mathclient
        PRIVATE
        -Wall
        -Wextra
        -Wpedantic
)

target_compile_options(
        mathlinker
        PRIVATE
        -Wall
        -Wextra
        -Wpedantic
)

find_library(android-log-lib log)
find_library(android-dl-lib dl)
find_library(android-gles3-lib GLESv3)
find_library(android-egl-lib EGL)

target_link_libraries(
        mathclient
        PRIVATE
        ${android-log-lib}
        ${android-dl-lib}
        ${android-gles3-lib}
        ${android-egl-lib}
)

target_link_libraries(
        mathlinker
        PRIVATE
        ${android-log-lib}
        ${android-dl-lib}
)
