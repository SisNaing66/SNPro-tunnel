cmake_minimum_required(VERSION 3.22.1)

project("warpvpn")

add_library(
    warpvpn
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    warpvpn
    ${log-lib}
)
