#!/usr/bin/env bash
# Cross-builds the guest engine (QEMU aarch64-softmmu, headless + VNC + QMP) for Android arm64 and
# installs it into app/jniLibs/arm64-v8a/libqemu-system-aarch64.so plus app/src/main/assets/qemu-share.
#
# Inputs (sources are NOT vendored; see docs/dependencies.md for versions, hashes and licenses):
#   $SRC/qemu            QEMU v11.1.3 git checkout (git clone --branch v11.1.3 https://github.com/qemu/qemu)
#   $SRC/subprojects/*   dtc, keycodemapdb, berkeley-softfloat-3, berkeley-testfloat-3 at the revisions in qemu/subprojects/*.wrap
#   $SRC/pixman-0.42.2, $SRC/glib-2.80.0, $SRC/libffi-3.4.6, $SRC/pcre2-10.42  (Ubuntu noble source tarballs)
#   $SRC/proxy-libintl   (git clone --branch 0.4 https://github.com/frida/proxy-libintl)
# Host tools: NDK r29, meson >= 1.5, ninja, autoconf/automake/libtool (+libltdl-dev), pkg-config, python3.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=env.sh
source "$HERE/env.sh"
: "${SRC:=$ROOT/engine/src}"; : "${WORK:=$ROOT/engine/work}"; : "${JOBS:=$(nproc)}"
PREFIX="$WORK/prefix"; mkdir -p "$PREFIX" "$WORK"
API="$ANDROID_API"; TC="$TOOLCHAIN"
export PATH="$WORK/xbin:$PATH"

step() { echo; echo "=== $*"; }

step "toolchain wrappers"
mkdir -p "$WORK/xbin"; cd "$WORK/xbin"
for t in gcc cc clang; do printf '#!/bin/sh\nexec %s/bin/aarch64-linux-android%s-clang "$@"\n' "$TC" "$API" > aarch64-linux-android-$t; done
for t in g++ c++ clang++; do printf '#!/bin/sh\nexec %s/bin/aarch64-linux-android%s-clang++ "$@"\n' "$TC" "$API" > aarch64-linux-android-$t; done
for t in ar nm ranlib strip objcopy readelf objdump strings; do ln -sf "$TC/bin/llvm-$t" aarch64-linux-android-$t; done
ln -sf "$TC/bin/ld.lld" aarch64-linux-android-ld
printf '#!/bin/sh\nexport PKG_CONFIG_LIBDIR=%s/lib/pkgconfig\nexport PKG_CONFIG_PATH=\nexec pkg-config "$@"\n' "$PREFIX" > aarch64-linux-android-pkg-config
chmod +x ./*
CROSS="$WORK/cross-aarch64-android.ini"
sed "s|@PREFIX@|$PREFIX|g; s|@TOOLCHAIN@|$TC|g; s|android28-clang|android${API}-clang|g" "$ROOT/engine/build/cross-aarch64-android.ini.template" > "$CROSS"

step "pixman (static, NEON asm off: clang's integrated assembler rejects the GAS macros)"
cd "$SRC/pixman-0.42.2" && rm -rf build-android
meson setup build-android --cross-file "$CROSS" --prefix "$PREFIX" --default-library static --buildtype release \
  -Dlibpng=disabled -Dgtk=disabled -Dtests=disabled -Dopenmp=disabled -Dgnuplot=false -Da64-neon=disabled
ninja -C build-android install

step "libffi (static)"
cd "$SRC/libffi-3.4.6"
[ -x configure ] || autoreconf -fiv
CC="$TC/bin/aarch64-linux-android${API}-clang" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip" CFLAGS="-O2 -fPIC" \
  ./configure --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared --enable-static --disable-docs --disable-multi-os-directory
make -j"$JOBS" && make install

step "pcre2 (static, 8-bit, no JIT)"
cd "$SRC/pcre2-10.42"
CC="$TC/bin/aarch64-linux-android${API}-clang" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip" CFLAGS="-O2 -fPIC" \
  ./configure --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared --enable-static --disable-pcre2grep-jit \
  --enable-pcre2-8 --disable-pcre2-16 --disable-pcre2-32 --enable-unicode --enable-jit=no
make -j"$JOBS" && make install

step "proxy-libintl (bionic has no libintl)"
cd "$SRC/proxy-libintl" && rm -rf build-android
meson setup build-android --cross-file "$CROSS" --prefix "$PREFIX" --default-library static --buildtype release
ninja -C build-android install

step "glib 2.80 (static) with Android patches"
cd "$SRC/glib-2.80.0"
if [ ! -f .androidware-patched ]; then
  patch -p1 < "$ROOT/engine/build/glib-2.80.0-android-meson.patch"
  for p in "$ROOT"/engine/build/glib-termux-patches/*.patch; do sed 's|@TERMUX_PREFIX@|/data/local/tmp/androidware|g' "$p" | patch -p1; done
  sed -i 's|chosen_search_path = "/bin:/usr/bin:.";|chosen_search_path = "/system/bin:.";|; s|argv_buffer\[0\] = (char \*) "/bin/sh";|argv_buffer[0] = (char *) "/system/bin/sh";|' glib/gspawn.c
  touch .androidware-patched
fi
rm -rf build-android
meson setup build-android --cross-file "$CROSS" --prefix "$PREFIX" --default-library static --buildtype release \
  -Dtests=false -Dintrospection=disabled -Dselinux=disabled -Dxattr=false -Dlibmount=disabled -Dman-pages=disabled \
  -Ddocumentation=false -Dsysprof=disabled -Dlibelf=disabled -Dnls=disabled -Dglib_debug=disabled
ninja -C build-android install

step "QEMU v11.1.3 with Termux Android patches"
cd "$SRC/qemu"
if [ ! -f .androidware-patched ]; then
  for p in "$ROOT"/engine/qemu/patches/0*.patch; do sed 's|@TERMUX_PREFIX@|/data/local/tmp/androidware|g' "$p" | patch -p1; done
  for d in dtc keycodemapdb berkeley-softfloat-3 berkeley-testfloat-3; do rm -rf "subprojects/$d"; cp -r "$SRC/subprojects/$d" "subprojects/$d"; done
  # wraps with patch_directory= expect meson to overlay subprojects/packagefiles/<name>; we do it by hand (offline)
  for d in berkeley-softfloat-3 berkeley-testfloat-3; do cp -r "subprojects/packagefiles/$d/." "subprojects/$d/"; done
  # bionic has no librt; QEMU's meson insists on finding it when shm_open() is not in libc (Termux patch 0017 supplies shm_open)
  "$TC/bin/llvm-ar" rcs "$PREFIX/lib/librt.a"
  touch .androidware-patched
fi
rm -rf build && mkdir build && cd build
../configure --cross-prefix=aarch64-linux-android- --host-cc=gcc --cpu=aarch64 --prefix=/data/local/tmp/androidware/usr \
  --target-list=aarch64-softmmu --enable-tcg --disable-kvm --disable-xen --disable-docs --disable-werror \
  --enable-vnc --disable-vnc-sasl --disable-vnc-jpeg --disable-png --disable-sdl --disable-gtk --disable-opengl --disable-virglrenderer \
  --disable-gnutls --disable-nettle --disable-gcrypt --disable-libssh --disable-curl --disable-guest-agent --disable-tools \
  --enable-fdt=internal --disable-slirp --disable-vhost-user --disable-vhost-net --disable-vhost-kernel --disable-vhost-vdpa \
  --disable-linux-aio --disable-linux-io-uring --disable-seccomp --disable-libudev --disable-selinux --disable-virtfs --disable-spice \
  --disable-usb-redir --disable-libusb --disable-attr --disable-cap-ng --disable-numa --disable-rbd --disable-smartcard \
  --disable-lzo --disable-snappy --disable-bzip2 --disable-zstd --disable-brlapi --disable-plugins --disable-debug-info --disable-rust \
  --disable-pa --disable-alsa --disable-oss --disable-jack --disable-pipewire --disable-sndio --disable-coreaudio --disable-dsound --audio-drv-list= \
  --disable-libdw --disable-l2tpv3 --disable-vde --disable-netmap --disable-af-xdp --disable-keyring --disable-libpmem --disable-libdaxctl \
  --disable-multiprocess --disable-vfio-user-server --disable-strip \
  --extra-cflags="-O2 -fPIC -I$PREFIX/include" --extra-ldflags="-L$PREFIX/lib -llog -Wl,-z,max-page-size=16384"
ninja -j"$JOBS" qemu-system-aarch64

step "install into app"
OUTLIB="$ROOT/app/jniLibs/arm64-v8a"; mkdir -p "$OUTLIB"
"$TC/bin/llvm-strip" -o "$OUTLIB/libqemu-system-aarch64.so" qemu-system-aarch64
file "$OUTLIB/libqemu-system-aarch64.so"
ASSETS="$ROOT/app/src/main/assets/qemu-share"; rm -rf "$ASSETS"; mkdir -p "$ASSETS/keymaps"
cp pc-bios/keymaps/* "$ASSETS/keymaps/" 2>/dev/null || cp -r "$SRC/qemu/build/pc-bios/keymaps/." "$ASSETS/keymaps/"
ls -la "$OUTLIB" "$ASSETS/keymaps" | head
echo "engine build complete"
