#!/usr/bin/env bash
# Builds build/gles1test.apk (armeabi-v7a only) with the NDK + build-tools from scripts/env.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; source "$HERE/../../../scripts/env.sh"
OUT="$HERE/build"; rm -rf "$OUT"; mkdir -p "$OUT/lib/armeabi-v7a"
GLUE="$ANDROID_NDK_ROOT/sources/android/native_app_glue"
"$TOOLCHAIN/bin/armv7a-linux-androideabi21-clang" -O2 -fPIC -shared -I"$GLUE" -o "$OUT/lib/armeabi-v7a/libgles1test.so" \
  "$HERE/main.c" "$GLUE/android_native_app_glue.c" -u ANativeActivity_onCreate -landroid -llog -lEGL -lGLESv1_CM
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" --min-sdk-version 21 --target-sdk-version 24
(cd "$OUT" && cp base.apk unaligned.apk && zip -q -r unaligned.apk lib)
"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
KS="$HERE/../../../.debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "$OUT/gles1test.apk" "$OUT/aligned.apk"
ls -la "$OUT/gles1test.apk"
