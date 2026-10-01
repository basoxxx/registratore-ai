# Libreria nativa whisper_jni per la versione desktop (macOS / Windows / Linux).
# Uso: cmake -S desktop/native -B build-native -DCMAKE_BUILD_TYPE=Release && cmake --build build-native --config Release
cmake_minimum_required(VERSION 3.20)
cmake_policy(SET CMP0091 NEW)
# Su Windows linkiamo il runtime C++ in modo statico: nessuna DLL di Visual C++ da installare.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
project(whisper_jni_desktop C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)

find_package(JNI REQUIRED)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

if (APPLE)
    # GPU dei Mac (Metal) con gli shader incorporati nella libreria
    set(GGML_METAL ON CACHE BOOL "" FORCE)
    set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
elseif (CMAKE_SYSTEM_PROCESSOR MATCHES "AMD64|x86_64|amd64")
    # PC degli ultimi ~10 anni: istruzioni AVX2/FMA per una trascrizione molto più veloce
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
endif ()

add_subdirectory(${REPO_ROOT}/third_party/whisper.cpp whisper_build)

add_library(whisper_jni SHARED ${REPO_ROOT}/app/src/main/cpp/whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(whisper_jni PRIVATE whisper)
