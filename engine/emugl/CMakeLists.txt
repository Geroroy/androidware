# Builds the emugl host renderer (emu-2.0-release) against the system
# EGL/GLES as:
#   - Linux:   aw-gl-renderer (executable) + libawgl.a
#   - Android: libawgl.so (shared, C API in aw/awgl.h)
# Driven by build.sh, which fetches/patches the sources and runs emugen
# (the decoder generator, a build-machine tool) before invoking CMake.
cmake_minimum_required(VERSION 3.10)
project(awgl CXX)

set(EMUGL_SRC "${CMAKE_CURRENT_SOURCE_DIR}/src" CACHE PATH "patched emugl tree")
set(EMUGL_GEN "" CACHE PATH "directory with emugen output (gles1/gles2/renderControl decoders)")
if(NOT EXISTS "${EMUGL_GEN}/gles1_dec.cpp")
    message(FATAL_ERROR "EMUGL_GEN (${EMUGL_GEN}) has no generated decoders; use build.sh")
endif()

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS ON)            # -std=gnu++11, like the 2016 build
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(H "${EMUGL_SRC}/host")
set(S "${EMUGL_SRC}/shared")
set(R "${H}/libs/libOpenglRender")

set(EMUGL_SOURCES
    # shared/emugl/common (libemugl_common)
    ${S}/emugl/common/crash_reporter.cpp
    ${S}/emugl/common/id_to_object_map.cpp
    ${S}/emugl/common/lazy_instance.cpp
    ${S}/emugl/common/logging.cpp
    ${S}/emugl/common/message_channel.cpp
    ${S}/emugl/common/pod_vector.cpp
    ${S}/emugl/common/shared_library.cpp
    ${S}/emugl/common/smart_ptr.cpp
    ${S}/emugl/common/sockets.cpp
    ${S}/emugl/common/thread_store.cpp
    ${S}/emugl/common/thread_pthread.cpp
    # shared/OpenglCodecCommon
    ${S}/OpenglCodecCommon/glUtils.cpp
    ${S}/OpenglCodecCommon/ChecksumCalculator.cpp
    ${S}/OpenglCodecCommon/ChecksumCalculatorThreadInfo.cpp
    # decoders (hand-written part + emugen output)
    ${H}/libs/GLESv1_dec/GLESv1Decoder.cpp
    ${H}/libs/GLESv2_dec/GLESv2Decoder.cpp
    ${EMUGL_GEN}/gles1_dec.cpp
    ${EMUGL_GEN}/gles1_server_context.cpp
    ${EMUGL_GEN}/gles2_dec.cpp
    ${EMUGL_GEN}/gles2_server_context.cpp
    ${EMUGL_GEN}/renderControl_dec.cpp
    ${EMUGL_GEN}/renderControl_server_context.cpp
    # host/libs/libOpenglRender, minus render_api.cpp (replaced by
    # aw/awgl.cpp), NativeSubWindow_* (replaced by aw/NativeSubWindow_*) and
    # the Windows-only files.
    ${R}/ColorBuffer.cpp
    ${R}/FbConfig.cpp
    ${R}/FrameBuffer.cpp
    ${R}/ReadBuffer.cpp
    ${R}/RenderContext.cpp
    ${R}/RenderControl.cpp
    ${R}/RenderServer.cpp
    ${R}/RenderThread.cpp
    ${R}/RenderThreadInfo.cpp
    ${R}/RenderWindow.cpp
    ${R}/SocketStream.cpp
    ${R}/TcpStream.cpp
    ${R}/TextureDraw.cpp
    ${R}/TextureResize.cpp
    ${R}/TimeUtils.cpp
    ${R}/UnixStream.cpp
    ${R}/WindowSurface.cpp
)
# host/libs/libOpenGLESDispatch/*.cpp is replaced by aw/dispatch.cpp and
# host/libs/Translator is not built at all (only one of its headers,
# GLcommon/GLutils.h, is included by ColorBuffer.cpp).

set(AW_SOURCES
    aw/awgl.cpp
    aw/dispatch.cpp
    aw/glstrings.cpp
)
if(ANDROID)
    list(APPEND AW_SOURCES aw/NativeSubWindow_android.cpp)
    list(APPEND AW_SOURCES aw/jni.cpp)   # JNI entry points for the app (GlRenderer.java)
else()
    list(APPEND AW_SOURCES aw/NativeSubWindow_headless.cpp)
endif()

set(AWGL_INCLUDES
    ${CMAKE_CURRENT_SOURCE_DIR}/aw/include   # must come first: overrides EGL/eglplatform.h
    ${CMAKE_CURRENT_SOURCE_DIR}/aw
    ${H}/include
    ${H}/include/libOpenglRender
    ${S}
    ${S}/OpenglCodecCommon
    ${H}/libs/Translator/include
    ${H}/libs/GLESv1_dec
    ${H}/libs/GLESv2_dec
    ${H}/libs/renderControl_dec
    ${R}
    ${EMUGL_GEN}
)

# The 2016 code base is noisy with current compilers; real errors are fixed
# in patches/, warnings are silenced for the upstream sources only.
set(EMUGL_WARN_FLAGS -w)
set(AW_WARN_FLAGS -Wall -Wextra)

add_library(emugl_objs OBJECT ${EMUGL_SOURCES})
target_include_directories(emugl_objs PRIVATE ${AWGL_INCLUDES})
target_compile_options(emugl_objs PRIVATE ${EMUGL_WARN_FLAGS} -fno-strict-aliasing)

add_library(aw_objs OBJECT ${AW_SOURCES})
target_include_directories(aw_objs PRIVATE ${AWGL_INCLUDES})
target_compile_options(aw_objs PRIVATE ${AW_WARN_FLAGS})

if(ANDROID)
    add_library(awgl SHARED $<TARGET_OBJECTS:emugl_objs> $<TARGET_OBJECTS:aw_objs>)
    # libEGL / libGLESv1_CM / libGLESv2 are dlopen()ed at run time on purpose
    # (see aw/dispatch.cpp), so they are not link-time dependencies.
    target_link_libraries(awgl PRIVATE log android dl
        -Wl,-z,max-page-size=16384
        -Wl,--exclude-libs,ALL          # keep the static libc++ private
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/aw/awgl.map
        -Wl,--no-undefined)
    set_target_properties(awgl PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/aw/awgl.map)
else()
    find_package(Threads REQUIRED)
    add_library(awgl STATIC $<TARGET_OBJECTS:emugl_objs> $<TARGET_OBJECTS:aw_objs>)
    add_executable(aw-gl-renderer aw/main.cpp)
    target_compile_options(aw-gl-renderer PRIVATE ${AW_WARN_FLAGS})
    target_link_libraries(aw-gl-renderer PRIVATE awgl Threads::Threads ${CMAKE_DL_LIBS})
endif()
