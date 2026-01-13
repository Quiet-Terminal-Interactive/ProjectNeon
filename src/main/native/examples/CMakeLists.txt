cmake_minimum_required(VERSION 3.15)
project(neon_jni_examples C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

include_directories(${JNI_INCLUDE_DIRS})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/..)
include_directories(${CMAKE_SOURCE_DIR}/target/native/include)

add_executable(client_example
    client_example.c
)

add_executable(host_example
    host_example.c
)

target_link_libraries(client_example neon_jni ${JNI_LIBRARIES})
target_link_libraries(host_example neon_jni ${JNI_LIBRARIES})

if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(client_example pthread)
    target_link_libraries(host_example pthread)
endif()

if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(client_example PRIVATE -Wall -Wextra)
    target_compile_options(host_example PRIVATE -Wall -Wextra)
endif()

install(TARGETS client_example host_example
    RUNTIME DESTINATION bin/examples
)
