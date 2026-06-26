cmake_minimum_required(VERSION 3.22.1)
project(math_flarial_parity LANGUAGES C CXX)

add_library(flarialclient SHARED parity_client.cpp)

target_compile_features(flarialclient PRIVATE cxx_std_20)
target_compile_options(flarialclient PRIVATE -fvisibility=hidden)
target_link_libraries(flarialclient PRIVATE android log)
