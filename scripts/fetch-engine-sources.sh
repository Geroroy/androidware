#!/usr/bin/env bash
# Fetches the engine sources into engine/src (nothing is vendored in git). Needs network access to
# github.com and archive.ubuntu.com (deb-src). Pinned versions are listed in docs/dependencies.md.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${SRC:=$ROOT/engine/src}"; mkdir -p "$SRC/subprojects"; cd "$SRC"
[ -d qemu ] || git clone --depth 1 --branch v11.1.3 https://github.com/qemu/qemu.git qemu
fetch_rev() { local name=$1 rev=$2; [ -d "subprojects/$name" ] && return; mkdir -p "subprojects/$name"; ( cd "subprojects/$name" && git init -q && git fetch -q --depth 1 "https://github.com/qemu/$name.git" "$rev" && git checkout -q FETCH_HEAD ); }
fetch_rev dtc b6910bec11614980a21e46fbccc35934b671bd81
fetch_rev keycodemapdb f5772a62ec52591ff6870b7e8ef32482371f22c6
fetch_rev berkeley-softfloat-3 b64af41c3276f97f0e181920400ee056b9c88037
fetch_rev berkeley-testfloat-3 e7af9751d9f9fd3b47911f51a5cfd08af256a9ab
[ -d proxy-libintl ] || git clone --depth 1 --branch 0.4 https://github.com/frida/proxy-libintl.git
# Ubuntu noble source tarballs (apt-get source needs deb-src enabled); alternatively download the .orig tarballs manually.
mkdir -p debsrc && cd debsrc
apt-get source --download-only pixman glib2.0 libffi pcre2 || echo "apt-get source failed; place pixman_0.42.2.orig.tar.gz, glib2.0_2.80.0.orig.tar.xz, libffi_3.4.6.orig.tar.gz, pcre2_10.42.orig.tar.gz here"
cd ..
for t in debsrc/pixman_0.42.2.orig.tar.gz debsrc/glib2.0_2.80.0.orig.tar.xz debsrc/libffi_3.4.6.orig.tar.gz debsrc/pcre2_10.42.orig.tar.gz; do tar xf "$t"; done
ls
