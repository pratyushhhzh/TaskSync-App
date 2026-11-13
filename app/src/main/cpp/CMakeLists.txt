cmake_minimum_required(VERSION 3.22.1)
project(tasksync_sentencepiece LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# SentencePiece v0.2.2 and its pinned Abseil source are bundled locally.
set(SPM_ABSL_PROVIDER "module" CACHE STRING "" FORCE)
set(SPM_PROTOBUF_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(SPM_NLCODEC_BPE OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)

# Exclude upstream command-line tools and tests from the default app build.
add_subdirectory(third_party/sentencepiece sentencepiece-build EXCLUDE_FROM_ALL)

add_library(tasksync_sentencepiece SHARED sentencepiece_jni.cpp)
target_include_directories(tasksync_sentencepiece PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/third_party/sentencepiece"
    "${CMAKE_CURRENT_LIST_DIR}/third_party/sentencepiece/src"
    "${CMAKE_CURRENT_BINARY_DIR}/sentencepiece-build")
target_link_libraries(tasksync_sentencepiece PRIVATE sentencepiece-static log)

