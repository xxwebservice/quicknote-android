cmake_minimum_required(VERSION 3.10)
project(whisper_jni C CXX)

set(CMAKE_C_STANDARD   11)
set(CMAKE_CXX_STANDARD 17)

# ── whisper.cpp source dir (set via gradle or env) ──────────────────────
if(NOT WHISPER_CPP_DIR)
    message(FATAL_ERROR "WHISPER_CPP_DIR is not set. Point it at the whisper.cpp source root.")
endif()

# ── Collect ggml source files ────────────────────────────────────────────
file(GLOB GGML_SOURCES
    "${WHISPER_CPP_DIR}/ggml/src/ggml.c"
    "${WHISPER_CPP_DIR}/ggml/src/ggml-alloc.c"
    "${WHISPER_CPP_DIR}/ggml/src/ggml-backend.c"
    "${WHISPER_CPP_DIR}/ggml/src/ggml-backend-reg.cpp"
    "${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/*.c"
    "${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/*.cpp"
    "${WHISPER_CPP_DIR}/ggml/src/ggml-threading.cpp"
    "${WHISPER_CPP_DIR}/ggml/src/ggml-quants.c"
    "${WHISPER_CPP_DIR}/ggml/src/ggml-opt.cpp"
)

# ── Build shared library ────────────────────────────────────────────────
add_library(whisper_jni SHARED
    ${GGML_SOURCES}
    ${WHISPER_CPP_DIR}/src/whisper.cpp
    jni.c
)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/ggml/include
    ${WHISPER_CPP_DIR}/ggml/src
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu
    ${WHISPER_CPP_DIR}/src
)

# ── Compile flags ────────────────────────────────────────────────────────
# Common flags
target_compile_options(whisper_jni PRIVATE -O3 -flto -pthread -DNDEBUG)

# ARM64-specific: enable fp16 NEON
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(whisper_jni PRIVATE
        -march=armv8.2-a+fp16
        -DGGML_USE_FP16
    )
endif()

# whisper.cpp defines
target_compile_definitions(whisper_jni PRIVATE
    GGML_USE_CPU
)

# ── Link ─────────────────────────────────────────────────────────────────
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_libraries(whisper_jni
    ${LOG_LIB}
    ${ANDROID_LIB}
    -flto
)
