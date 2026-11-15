cmake_minimum_required(VERSION 3.22.1)
project(icharge_llama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is vendored by CI (git clone --branch <pinned tag> into this folder).
# Gradle only reaches this file when those sources exist, so a plain checkout
# still builds the app without the AI engine.
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

include(ExternalProject)

# ggml has no runtime CPU dispatch when linked statically: it only uses the
# instructions it was compiled for. So llama.cpp is built twice into two JNI
# libraries and LlamaNative loads the fast one only when /proc/cpuinfo reports
# the features (dotprod + fp16 + i8mm: Snapdragon 8 Gen 1 and newer, the Fold 8).
#
#   icharge_llama        baseline — every arm64 phone
#   icharge_llama_fast   armv8.2-a+dotprod+fp16+i8mm
function(add_llama_jni lib_name arm_arch)
    set(out ${CMAKE_CURRENT_BINARY_DIR}/${lib_name}-deps)
    set(static_libs
        ${out}/lib/libllama.a
        ${out}/lib/libggml.a
        ${out}/lib/libggml-cpu.a
        ${out}/lib/libggml-base.a)

    set(android_args "")
    foreach(var CMAKE_TOOLCHAIN_FILE CMAKE_MAKE_PROGRAM ANDROID_ABI ANDROID_PLATFORM ANDROID_STL ANDROID_NDK)
        if(DEFINED ${var} AND NOT "${${var}}" STREQUAL "")
            list(APPEND android_args "-D${var}=${${var}}")
        endif()
    endforeach()

    ExternalProject_Add(${lib_name}_deps
        SOURCE_DIR ${LLAMA_DIR}
        BINARY_DIR ${out}/build
        CMAKE_ARGS
            ${android_args}
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON
            -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${out}/lib
            -DBUILD_SHARED_LIBS=OFF
            -DLLAMA_BUILD_COMMON=OFF
            -DLLAMA_BUILD_TESTS=OFF
            -DLLAMA_BUILD_TOOLS=OFF
            -DLLAMA_BUILD_EXAMPLES=OFF
            -DLLAMA_BUILD_SERVER=OFF
            -DLLAMA_CURL=OFF
            -DLLAMA_ALL_WARNINGS=OFF
            -DGGML_OPENMP=OFF
            -DGGML_NATIVE=OFF
            -DGGML_LLAMAFILE=OFF
            -DGGML_CPU_ARM_ARCH=${arm_arch}
        BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target llama
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS ${static_libs}
    )

    add_library(${lib_name} SHARED llama_jni.cpp)
    add_dependencies(${lib_name} ${lib_name}_deps)
    target_include_directories(${lib_name} PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include
    )
    target_link_libraries(${lib_name}
        -Wl,--start-group ${static_libs} -Wl,--end-group
        android
        log
    )
endfunction()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_llama_jni(icharge_llama "armv8-a")
    add_llama_jni(icharge_llama_fast "armv8.2-a+dotprod+fp16+i8mm")
else()
    add_llama_jni(icharge_llama "")
endif()
