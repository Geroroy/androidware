# dist

- `androidware-debug-m0.apk` — 이 세션(2026-11-05)에서 생성한 debug 서명 APK. 포함물: 앱 dex, `lib/arm64-v8a/libqemu-system-aarch64.so`(QEMU 11.1.3 aarch64-softmmu, TCG/VNC/QMP/slirp, 스트립), `lib/arm64-v8a/libqemu-arm.so`(QEMU arm-linux-user), `assets/selftest/bench-*-bionic`.
- 서명: 자체 생성 debug 키(`.debug.keystore`, 저장소 미포함). 재빌드하면 키가 바뀌므로 재설치 시 `adb uninstall dev.androidware.app` 후 설치한다.
- 설치: `adb install -r dist/androidware-debug-m0.apk`
- 기기 실행 결과는 **미검증**이다. `docs/device-test-procedure.md`를 따라 측정한다.
- GPL-2.0 구성 요소(QEMU)가 포함되므로 배포 시 대응 소스(업스트림 태그 + `engine/qemu/patches`)를 함께 제공해야 한다.
