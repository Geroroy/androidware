#!/usr/bin/env bash
# Installs the debug APK on the connected device and tails the relevant logcat lines.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
APK="$ROOT/app/build/androidware-debug.apk"
adb wait-for-device
adb shell getprop ro.product.model; adb shell getprop ro.build.version.release; adb shell getprop ro.product.cpu.abilist; adb shell uname -r
adb install -r -g "$APK"
adb shell am start -n dev.androidware.app/.MainActivity
adb logcat -c
adb logcat -v time EngineService:V AndroidRuntime:E '*:S'
