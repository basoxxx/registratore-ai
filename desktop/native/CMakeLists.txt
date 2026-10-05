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

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Tutte le librerie finiscono in out/ (anche con i generatori multi-configurazione di Visual Studio)
set(OUT_DIR ${CMAKE_BINARY_DIR}/out)
foreach (cfg "" _RELEASE _DEBUG _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${cfg} ${OUT_DIR})
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY${cfg} ${OUT_DIR})
endforeach ()

if (APPLE)
    # Mac Apple Silicon: libreria unica, GPU Metal (shader incorporati) e CPU con dotprod/fp16
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_METAL ON CACHE BOOL "" FORCE)
    set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
else ()
    # Windows / Linux: backend CPU in più varianti (SSE4.2, AVX, AVX2, AVX-512, ...);
    # all'avvio viene caricata la più veloce supportata dal processore.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(CMAKE_BUILD_RPATH "$ORIGIN")
    set(CMAKE_INSTALL_RPATH "$ORIGIN")
endif ()

add_subdirectory(${REPO_ROOT}/third_party/whisper.cpp whisper_build)

# llama.cpp b5755 (riassunto): stesso ggml di whisper.cpp 1.7.6, condiviso.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
add_subdirectory(${REPO_ROOT}/third_party/llama.cpp llama_build)

add_library(whisper_jni SHARED
    ${REPO_ROOT}/app/src/main/cpp/whisper_jni.cpp
    ${REPO_ROOT}/app/src/main/cpp/llama_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${JNI_INCLUDE_DIRS} ${REPO_ROOT}/third_party/llama.cpp/include)
target_link_libraries(whisper_jni PRIVATE whisper llama ggml)
