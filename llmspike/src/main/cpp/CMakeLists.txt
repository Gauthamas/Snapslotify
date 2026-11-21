# LLM timing spike: llama.cpp (GGUF, CPU) as a static library inside one JNI .so, arm64-v8a only.
cmake_minimum_required(VERSION 3.22.1)
project(llmspike CXX C)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/llama.cpp)

# CPU only, no network, no tools, no tests.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
# Helio G35: 8x Cortex-A53 (ARMv8.0-A, no dot product, no fp16 arithmetic)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)

# Try harder: the image encoder library (mtmd) on its own, without the CLI tools
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(llmspike SHARED llmspike.cpp)
target_link_libraries(llmspike llama mtmd log android)
