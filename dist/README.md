# dist

- `androidware-0.0.7-m1.apk` — 2026-11-10 세션 3 debug 서명 APK(60,442,280 B, sha256 `6b276840bff824dd3e6569a046f9693a7fd0e5d18de0f6aa64a62548acb7319e`). 엔진 패치 0024(goldfish 화면·입력·배터리·오디오, 화면 버퍼 크래시 수정), "Android 7 guest…"/"Start Android", **Import game APK → 다음 Start Android에서 자동 설치·실행**, 게스트 오디오 재생, SD 카드. 0.0.6-m1과 같은 debug 키. 게스트 Android 이미지는 포함하지 않는다(앱이 dl.google.com에서 받음). 이 세션의 debug 키는 이전 APK와 다르므로 `adb uninstall dev.androidware.app` 후 설치.
- (이전) `androidware-0.0.5-m0.apk` — 세션 1(2026-11-05) debug APK. 저장소에서 제거됨(git 기록에 남음). 포함물: 앱 dex, `lib/arm64-v8a/libqemu-system-aarch64.so`(QEMU 11.1.3 aarch64-softmmu, TCG/VNC/QMP/slirp, 스트립), `lib/arm64-v8a/libqemu-arm.so`(QEMU arm-linux-user), `assets/selftest/bench-*-bionic`.
- 서명: 자체 생성 debug 키(`.debug.keystore`, 저장소 미포함). 재빌드하면 키가 바뀌므로 재설치 시 `adb uninstall dev.androidware.app` 후 설치한다.
- 설치: APK 파일을 폰에서 직접 열어 설치하거나 `adb install -r dist/androidware-0.0.7-m1.apk`
- 0.0.7-m1의 기기 실행 결과는 **미검증**이다. `docs/device-test-procedure.md`를 따라 측정한다.
- GPL-2.0 구성 요소(QEMU)가 포함되므로 배포 시 대응 소스(업스트림 태그 + `engine/qemu/patches`)를 함께 제공해야 한다.

- 0.0.4-m0부터 APK에 테스트 게스트(`assets/testguest`: Ubuntu `linux-image-6.8.0-71-generic` arm64 커널, GPL-2.0, 소스는 Ubuntu 아카이브 `linux-signed`/`linux` 6.8.0-71.71)와 네이티브 벤치(`libbench-arm64.so`)가 포함된다. 개인 테스트용 빌드이며 공개 배포 전에는 커널 번들을 제거하거나 소스 제공 고지를 붙인다.
