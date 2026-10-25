cmake_minimum_required(VERSION 3.22.1)
project(SoinuBiderNative LANGUAGES C CXX)
find_package(oboe REQUIRED CONFIG)
add_library(soinubider SHARED SBRealtime.c SBAudioAndroid.cpp)
set_target_properties(soinubider PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED YES C_EXTENSIONS NO
    CXX_STANDARD 17 CXX_STANDARD_REQUIRED YES)
target_compile_options(soinubider PRIVATE -Wall -Wextra)
target_link_libraries(soinubider PRIVATE oboe::oboe m atomic)
