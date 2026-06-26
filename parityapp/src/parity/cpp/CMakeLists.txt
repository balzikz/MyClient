cmake_minimum_required(VERSION 3.22.1)
project(math_flarial_parity LANGUAGES C CXX)

add_library(shin SHARED parity_shin.cpp)
add_library(flarialclient SHARED parity_client.cpp)

foreach(target shin flarialclient)
    target_compile_features(${target} PRIVATE cxx_std_20)
    target_compile_options(${target} PRIVATE -fvisibility=hidden)
    target_link_libraries(${target} PRIVATE android log dl)
endforeach()
