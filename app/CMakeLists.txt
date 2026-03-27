cmake_minimum_required(VERSION 3.22.1)
project(voicelistener LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_USE_SYSTEM_GGML OFF CACHE BOOL "" FORCE)
if (ANDROID)
    # Android builds are cross-compiled, so host-native tuning emits invalid flags like -mcpu=native.
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
else()
    set(GGML_NATIVE ON CACHE BOOL "" FORCE)
endif()
set(GGML_LTO ON CACHE BOOL "" FORCE)

set(WHISPER_CPP_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp")

if (EXISTS "${WHISPER_CPP_SOURCE_DIR}/CMakeLists.txt")
    add_subdirectory("${WHISPER_CPP_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/whisper.cpp")
    set(WHISPER_CPP_INCLUDE_DIR "${WHISPER_CPP_SOURCE_DIR}/include")
    set(WHISPER_CPP_GGML_INCLUDE_DIR "${WHISPER_CPP_SOURCE_DIR}/ggml/include")
else()
    FetchContent_Declare(
        whispercpp
        GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
        GIT_TAG v1.8.4
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(whispercpp)
    set(WHISPER_CPP_INCLUDE_DIR "${whispercpp_SOURCE_DIR}/include")
    set(WHISPER_CPP_GGML_INCLUDE_DIR "${whispercpp_SOURCE_DIR}/ggml/include")
endif()

add_library(
    whisper_jni
    SHARED
    src/main/cpp/whisper_jni.cpp
)

target_include_directories(
    whisper_jni
    PRIVATE
    "${WHISPER_CPP_INCLUDE_DIR}"
    "${WHISPER_CPP_GGML_INCLUDE_DIR}"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    whisper_jni
    PRIVATE
    whisper
    ${log-lib}
    ${android-lib}
)

target_compile_definitions(
    whisper_jni
    PRIVATE
    WHISPER_USE_CPU=1
    WHISPER_JNI_STREAM_CHUNK_SAMPLES=1024
    WHISPER_JNI_STREAM_RESERVE_SAMPLES=4096
)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(
        whisper_jni
        PRIVATE
        -O3
        -ffast-math
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -march=armv8.2-a+dotprod+fp16
    )
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(
        whisper_jni
        PRIVATE
        -O3
        -ffast-math
        -mfpu=neon
    )
else()
    target_compile_options(
        whisper_jni
        PRIVATE
        -O3
        -ffast-math
    )
endif()
