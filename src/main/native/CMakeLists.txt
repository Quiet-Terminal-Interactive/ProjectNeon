cmake_minimum_required(VERSION 3.15)
project(neon_jni C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

set(JNI_HEADER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../target/native/include")

if(NOT EXISTS "${JNI_HEADER_DIR}")
    message(FATAL_ERROR
        "\n*****************************************************\n"
        "ERROR: JNI headers not found!\n"
        "Expected location: ${JNI_HEADER_DIR}\n"
        "\n"
        "You must run 'mvn clean compile' from the project root first.\n"
        "This generates the required JNI header files.\n"
        "\n"
        "From project root, run:\n"
        "  mvn clean compile\n"
        "\n"
        "Then retry the CMake build.\n"
        "*****************************************************\n")
endif()

if(NOT EXISTS "${JNI_HEADER_DIR}/com_quietterminal_projectneon_jni_NeonClientJNI.h")
    message(FATAL_ERROR
        "\n*****************************************************\n"
        "ERROR: JNI header files are incomplete!\n"
        "Directory exists: ${JNI_HEADER_DIR}\n"
        "But required headers are missing.\n"
        "\n"
        "Run 'mvn clean compile' from project root to regenerate.\n"
        "*****************************************************\n")
endif()

message(STATUS "Found JNI headers in: ${JNI_HEADER_DIR}")
message(STATUS "JNI include dirs: ${JNI_INCLUDE_DIRS}")

add_library(neon_jni SHARED
    neon_jni.c
)

target_include_directories(neon_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${JNI_HEADER_DIR}
)

target_link_libraries(neon_jni ${JNI_LIBRARIES})

if(WIN32)
    set_target_properties(neon_jni PROPERTIES
        OUTPUT_NAME "neon_jni"
        PREFIX ""
        SUFFIX ".dll"
    )
elseif(APPLE)
    set_target_properties(neon_jni PROPERTIES
        OUTPUT_NAME "neon_jni"
        PREFIX "lib"
        SUFFIX ".dylib"
    )
else()
    set_target_properties(neon_jni PROPERTIES
        OUTPUT_NAME "neon_jni"
        PREFIX "lib"
        SUFFIX ".so"
    )
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(neon_jni pthread)
endif()

set_target_properties(neon_jni PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(neon_jni PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Wno-unused-parameter
    )
endif()

if(MSVC)
    target_compile_options(neon_jni PRIVATE
        /W4
        /wd4100
    )
endif()

install(TARGETS neon_jni
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
    ARCHIVE DESTINATION lib
)

install(FILES project_neon.h
    DESTINATION include
)
