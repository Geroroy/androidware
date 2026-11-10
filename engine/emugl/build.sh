#!/bin/bash
# Build the awgl render server.
#   ./build.sh linux     -> build-linux/aw-gl-renderer   (+ libawgl.a)
#   ./build.sh android   -> build-android/libawgl.so     (arm64-v8a, API 28)
#
# Steps: fetch sources (fetch.sh) -> apply patches/*.patch -> build emugen
# for the build machine and generate the wire-protocol decoders -> CMake.
#
# Environment:
#   ANDROID_NDK   NDK root          (default /opt/android-sdk/ndk-r29)
#   HOST_CXX      build-machine C++ (default c++)
#   JOBS          parallel jobs     (default nproc)
set -euo pipefail

TARGET="${1:-}"
case "$TARGET" in
    linux|android) ;;
    *) echo "usage: $0 <linux|android>" >&2; exit 2 ;;
esac

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"
BUILD="$HERE/build-$TARGET"
GEN="$BUILD/gen"
JOBS="${JOBS:-$(nproc)}"
HOST_CXX="${HOST_CXX:-c++}"
NDK="${ANDROID_NDK:-/opt/android-sdk/ndk-r29}"

# 1. sources
"$HERE/fetch.sh"

# 2. patches (once per fetched tree)
if [ ! -f "$SRC/.aw-patched" ]; then
    for p in "$HERE"/patches/*.patch; do
        echo "build.sh: applying $(basename "$p")"
        patch -d "$SRC" -p1 --forward --no-backup-if-mismatch < "$p"
    done
    touch "$SRC/.aw-patched"
fi

# 3. emugen (host tool) + generated decoders
mkdir -p "$BUILD" "$GEN"
EMUGEN="$BUILD/emugen"
EG="$SRC/host/tools/emugen"
if [ ! -x "$EMUGEN" ] || [ "$SRC/.aw-patched" -nt "$EMUGEN" ]; then
    echo "build.sh: building emugen with $HOST_CXX"
    "$HOST_CXX" -O1 -w -std=gnu++11 -o "$EMUGEN" \
        "$EG/ApiGen.cpp" "$EG/EntryPoint.cpp" "$EG/main.cpp" \
        "$EG/Parser.cpp" "$EG/strUtils.cpp" "$EG/TypeFactory.cpp"
fi
gen_decoder() {  # <input dir> <basename>
    if [ ! -f "$GEN/$2_dec.cpp" ] || [ "$EMUGEN" -nt "$GEN/$2_dec.cpp" ]; then
        echo "build.sh: emugen $2"
        "$EMUGEN" -D "$GEN" -i "$1" "$2"
    fi
}
gen_decoder "$SRC/host/libs/GLESv1_dec" gles1
gen_decoder "$SRC/host/libs/GLESv2_dec" gles2
gen_decoder "$SRC/host/libs/renderControl_dec" renderControl

# 4. configure + build
CMAKE_ARGS=(-S "$HERE" -B "$BUILD/cmake" -DCMAKE_BUILD_TYPE=Release
            -DEMUGL_SRC="$SRC" -DEMUGL_GEN="$GEN")
if [ "$TARGET" = android ]; then
    if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
        echo "build.sh: NDK not found at $NDK (set ANDROID_NDK)" >&2
        exit 1
    fi
    CMAKE_ARGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
                 -DANDROID_ABI=arm64-v8a
                 -DANDROID_PLATFORM=android-28
                 -DANDROID_STL=c++_static)
fi
cmake "${CMAKE_ARGS[@]}"
cmake --build "$BUILD/cmake" -j "$JOBS"

# 5. collect outputs
if [ "$TARGET" = android ]; then
    cp -f "$BUILD/cmake/libawgl.so" "$BUILD/libawgl.so"
    "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
        -o "$BUILD/libawgl.stripped.so" "$BUILD/libawgl.so"
    cp -f "$HERE/aw/awgl.h" "$BUILD/awgl.h"
    echo "build.sh: built $BUILD/libawgl.so (+ libawgl.stripped.so, awgl.h)"
else
    cp -f "$BUILD/cmake/aw-gl-renderer" "$BUILD/aw-gl-renderer"
    cp -f "$BUILD/cmake/libawgl.a" "$BUILD/libawgl.a"
    echo "build.sh: built $BUILD/aw-gl-renderer"
fi
