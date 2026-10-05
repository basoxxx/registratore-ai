cmake_minimum_required(VERSION 3.22)
project(registratore_whisper)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# whisper.cpp e ggml come librerie condivise, con il backend CPU compilato in più varianti
# (ARMv8.0, ARMv8.2 dotprod, ARMv8.2 dotprod+fp16, ARMv8.6 i8mm): all'avvio ggml carica
# quella più veloce supportata dal processore del telefono (vedi initBackends nel JNI).
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build)

# llama.cpp (riassunto con IA locale): versione b5755, con ggml identico a whisper.cpp 1.7.6,
# quindi riusa lo stesso ggml (e le stesse varianti CPU) già configurato sopra.
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} llama_build)

add_library(whisper_jni SHARED whisper_jni.cpp llama_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include ${LLAMA_DIR}/include)
target_link_libraries(whisper_jni whisper llama ggml log android)
target_compile_options(whisper_jni PRIVATE -O3)
