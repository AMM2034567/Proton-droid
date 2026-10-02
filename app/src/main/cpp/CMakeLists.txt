cmake_minimum_required(VERSION 3.22.1)

project("proton_bridge")

add_library(${CMAKE_PROJECT_NAME} SHARED
    proton_bridge.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    ${log-lib}
)
