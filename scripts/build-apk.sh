#!/usr/bin/env bash
# Builds app/build/androidware-debug.apk WITHOUT the Android Gradle Plugin (Google Maven is not needed).
# Pipeline: aapt2 compile/link -> javac -> d8 -> zip (dex + lib/arm64-v8a + assets) -> zipalign -> apksigner (debug key).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=env.sh
source "$HERE/env.sh"
APP="$ROOT/app"; OUT="$APP/build"; rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/classes" "$OUT/apk"

for t in aapt2 d8 zipalign apksigner; do [ -x "$BUILD_TOOLS/$t" ] || { echo "missing $BUILD_TOOLS/$t"; exit 1; }; done
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR"; exit 1; }

echo "[1/6] aapt2 compile"
"$BUILD_TOOLS/aapt2" compile --dir "$APP/src/main/res" -o "$OUT/res/res.zip"
echo "[2/6] aapt2 link"
"$BUILD_TOOLS/aapt2" link -o "$OUT/apk/base.apk" -I "$ANDROID_JAR" --manifest "$APP/src/main/AndroidManifest.xml" \
   --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 35 "$OUT/res/res.zip"
echo "[3/6] javac"
find "$APP/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 1.8 -target 1.8 -encoding UTF-8 -nowarn -Xlint:none -bootclasspath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar" -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.err" || { grep -v "JAVA_TOOL_OPTIONS" "$OUT/javac.err"; echo "javac failed"; exit 1; }
grep -v "JAVA_TOOL_OPTIONS\|^warning: \[options\]" "$OUT/javac.err" || true
echo "[4/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BUILD_TOOLS/d8" --release --min-api 29 --lib "$ANDROID_JAR" --output "$OUT/apk" @"$OUT/classes.txt" 2> "$OUT/d8.err" || { grep -v "JAVA_TOOL_OPTIONS" "$OUT/d8.err"; echo "d8 failed"; exit 1; }
[ -f "$OUT/apk/classes.dex" ] || { echo "d8 produced no classes.dex"; exit 1; }
echo "[5/6] package native engine + assets"
cp "$OUT/apk/base.apk" "$OUT/apk/unaligned.apk"
pushd "$OUT/apk" >/dev/null
zip -q -j unaligned.apk classes.dex
popd >/dev/null
if [ -d "$APP/jniLibs/arm64-v8a" ] && [ -n "$(ls -A "$APP/jniLibs/arm64-v8a")" ]; then
  pushd "$APP/jniLibs" >/dev/null
  # Native libs are stored uncompressed and page-aligned so the loader/exec path can map them directly.
  zip -q -r -0 "$OUT/apk/unaligned.apk" arm64-v8a -i 'arm64-v8a/*.so' --prefix >/dev/null 2>&1 || true
  popd >/dev/null
  # 'zip' cannot write the lib/ prefix directly; rewrite entry names with python.
  python3 - "$OUT/apk/unaligned.apk" "$APP/jniLibs" <<'PY'
import sys, zipfile, os
apk, libdir = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(tmp, "w") as zout:
    for item in zin.infolist():
        if item.filename.startswith("arm64-v8a/"):
            continue
        zout.writestr(item, zin.read(item.filename))
    for abi in sorted(os.listdir(libdir)):
        d = os.path.join(libdir, abi)
        if not os.path.isdir(d): continue
        for f in sorted(os.listdir(d)):
            if f.endswith(".so"):
                zi = zipfile.ZipInfo("lib/%s/%s" % (abi, f)); zi.compress_type = zipfile.ZIP_STORED
                zi.external_attr = 0o100755 << 16
                with open(os.path.join(d, f), "rb") as fh: zout.writestr(zi, fh.read())
os.replace(tmp, apk)
PY
else
  echo "  (no native engine in app/jniLibs/arm64-v8a - APK will run but cannot start a guest)"
fi
if [ -d "$APP/src/main/assets" ]; then
  pushd "$APP/src/main" >/dev/null; zip -q -r "$OUT/apk/unaligned.apk" assets; popd >/dev/null
fi
echo "[6/6] zipalign + sign"
"$BUILD_TOOLS/zipalign" -p -f 4 "$OUT/apk/unaligned.apk" "$OUT/apk/aligned.apk"
KS="$ROOT/.debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
     -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
   --out "$OUT/androidware-debug.apk" "$OUT/apk/aligned.apk" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/androidware-debug.apk" 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -3
ls -la "$OUT/androidware-debug.apk"
echo "unzip -l:"; unzip -l "$OUT/androidware-debug.apk" | grep -E "lib/|classes.dex|assets/qemu-share" | head -20
