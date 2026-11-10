#!/bin/bash
# Fetch the pristine Android Emulator "emugl" sources (emu-2.0-release,
# distrib/android-emugl, Apache-2.0) into engine/emugl/src.
# The tree is NOT modified here; build.sh applies patches/*.patch on top.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"
URL="https://android.googlesource.com/platform/external/qemu/+archive/refs/heads/emu-2.0-release/distrib/android-emugl.tar.gz"
# Optional local mirror of the same archive, already extracted (speeds up CI).
LOCAL="${AWGL_EMUGL_LOCAL:-/home/user/emugl/emu-2.0-release}"

if [ "${1:-}" = "--force" ]; then rm -rf "$SRC"; fi
if [ -f "$SRC/.aw-fetched" ]; then
    echo "fetch.sh: $SRC already present (use --force to refetch)"
    exit 0
fi
rm -rf "$SRC"; mkdir -p "$SRC"
if [ -f "$LOCAL/host/libs/libOpenglRender/FrameBuffer.cpp" ]; then
    echo "fetch.sh: copying from $LOCAL"
    cp -a "$LOCAL/." "$SRC/"
else
    echo "fetch.sh: downloading $URL"
    curl -fL --retry 3 -o "$SRC/.emugl.tar.gz" "$URL"
    tar -xzf "$SRC/.emugl.tar.gz" -C "$SRC"
    rm -f "$SRC/.emugl.tar.gz"
fi
test -f "$SRC/host/libs/libOpenglRender/FrameBuffer.cpp"
rm -f "$SRC/.aw-patched"
touch "$SRC/.aw-fetched"
echo "fetch.sh: done -> $SRC"
