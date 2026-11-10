#!/usr/bin/env bash
# Downloads Google's official Android 7.0 (API 24) ARM emulator system image
# (system-images;android-24;default;armeabi-v7a, 32-bit ARMv7 userspace + Linux 3.10 "ranchu" kernel)
# and prepares a run directory for PC testing with experiments/android-guest/boot.py.
# Use of the image is governed by the Android SDK License Agreement; it is never committed or bundled.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${OUT:=$ROOT/dist/android-guest}"
URL=https://dl.google.com/android/repository/sys-img/android/armeabi-v7a-24_r07.zip
SHA1=3454546b4eed2d6c3dd06d47757d6da9f4176033
mkdir -p "$OUT" && cd "$OUT"
[ -f armeabi-v7a-24_r07.zip ] || curl -fL -o armeabi-v7a-24_r07.zip "$URL"
echo "$SHA1  armeabi-v7a-24_r07.zip" | sha1sum -c -
unzip -o -j -q armeabi-v7a-24_r07.zip 'armeabi-v7a/kernel-ranchu' 'armeabi-v7a/ramdisk.img' 'armeabi-v7a/system.img' 'armeabi-v7a/userdata.img'
mv -f userdata.img userdata.pristine.img
cp userdata.pristine.img userdata.img
gzip -dc "$ROOT/app/src/main/assets/androidguest/cache.img.gz" > cache.img
ls -la "$OUT"
