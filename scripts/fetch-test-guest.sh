#!/usr/bin/env bash
# Builds the M0 smoke-test guest for the DEVICE: an Ubuntu arm64 kernel (GPL-2.0, downloaded, not redistributed
# by this repo) plus an initramfs whose /init runs a 64-bit and a 32-bit (AArch32) static bionic test binary,
# prints results on the serial console and powers off. Import kernel + initrd in the app, press "Start guest",
# then "Export log": serial.log must contain "=== DONE" and "exit status of /bench-arm32-bionic = 0".
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=env.sh
source "$HERE/env.sh"
OUT="${OUT:-$ROOT/dist/test-guest}"; mkdir -p "$OUT"; cd "$OUT"
KVER="${KVER:-6.8.0-71}"; KABI="${KABI:-${KVER}.$(echo "$KVER" | cut -d- -f2)}"
KDEB="linux-image-${KVER}-generic_${KABI}_arm64.deb"
[ -f "$KDEB" ] || curl -sS -o "$KDEB" "https://ports.ubuntu.com/ubuntu-ports/pool/main/l/linux-signed/$KDEB"
rm -rf kdeb && mkdir kdeb && dpkg-deb -x "$KDEB" kdeb && cp kdeb/boot/vmlinuz-* vmlinuz-arm64
TC="$TOOLCHAIN"; E="$ROOT/experiments/pc-tcg-aarch32"
"$TC/bin/armv7a-linux-androideabi${ANDROID_API}-clang" -O2 -static -o bench-arm32-bionic "$E/bench.c" -lm
"$TC/bin/aarch64-linux-android${ANDROID_API}-clang" -O2 -static -o bench-arm64-bionic "$E/bench.c" -lm
"$TC/bin/aarch64-linux-android${ANDROID_API}-clang" -O2 -static -DBENCH32=\"/bench-arm32-bionic\" -DBENCH64=\"/bench-arm64-bionic\" -o init "$E/init.c"
rm -rf initrd && mkdir -p initrd/proc && cp init initrd/init && cp bench-arm32-bionic bench-arm64-bionic initrd/
(cd initrd && find . | cpio -o -H newc 2>/dev/null | gzip -9 > ../initrd-test.img)
"$TC/bin/llvm-strip" bench-arm32-bionic bench-arm64-bionic init 2>/dev/null || true
ls -la vmlinuz-arm64 initrd-test.img
echo "Copy vmlinuz-arm64 and initrd-test.img to the phone (adb push ... /sdcard/Download/) and import them in the app."
