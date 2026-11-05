#!/usr/bin/env bash
# Shared tool locations. Override with environment variables.
# ANDROID_SDK_ROOT must contain: build-tools/<ver> (aapt2, d8, zipalign, apksigner), platforms/android-<api>/android.jar
: "${ANDROID_SDK_ROOT:=/opt/android-sdk}"
: "${ANDROID_NDK_ROOT:=${ANDROID_SDK_ROOT}/ndk-r29}"
: "${BUILD_TOOLS:=${ANDROID_SDK_ROOT}/build-tools-35.0.0}"
: "${ANDROID_JAR:=${ANDROID_SDK_ROOT}/android-35/android.jar}"
: "${ANDROID_API:=28}"          # min API the native engine is compiled against
: "${JAVA_HOME:=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
export ANDROID_SDK_ROOT ANDROID_NDK_ROOT BUILD_TOOLS ANDROID_JAR ANDROID_API JAVA_HOME
TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
export TOOLCHAIN
