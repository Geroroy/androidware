#!/bin/bash
# Maintainer helper: regenerate patches/0001-*.patch from the edited tree in
# src/ against a pristine copy (default: the local mirror used by fetch.sh).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PRISTINE="${1:-/home/user/emugl/emu-2.0-release}"
OUT="$HERE/patches/0001-awgl-standalone-renderer.patch"
cd "$HERE"
{
    cat <<'HDR'
awgl: minimal changes to emugl (emu-2.0-release, distrib/android-emugl) so it
can run as a standalone render server on top of the system EGL/GLES.
See engine/emugl/README.md for the rationale of each hunk.
Apply with: patch -d src -p1 < this-file

HDR
    diff -ruN -x .aw-fetched -x .aw-patched "$PRISTINE" src \
        | sed -E -e "s#^--- $PRISTINE/([^\t]*).*#--- a/\1#" \
                 -e "s#^\+\+\+ src/([^\t]*).*#+++ b/\1#" \
                 -e '/^diff -ruN/d' || true
} > "$OUT"
echo "wrote $OUT"
