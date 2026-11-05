# 의존성 (버전·커밋·라이선스)

소스는 저장소에 벤더링하지 않는다. `scripts/fetch-engine-sources.sh`가 아래 버전을 받는다. 게임, 펌웨어, 독점 번역 라이브러리는 포함하지 않는다.

| 구성 요소 | 버전 / 커밋 | 라이선스 | 용도 | 출처 |
|---|---|---|---|---|
| QEMU | v11.1.3 (GitHub 미러 `qemu/qemu` 태그) | GPL-2.0 | 엔진 (`aarch64-softmmu`: TCG, VNC, QMP, slirp; `arm-linux-user`: 자가 테스트/접근 D 엔진) | https://github.com/qemu/qemu |
| QEMU subproject dtc | b6910bec11614980a21e46fbccc35934b671bd81 | GPL-2.0 / BSD-2 | libfdt(`virt` 보드 DT) | https://github.com/qemu/dtc |
| QEMU subproject keycodemapdb | f5772a62ec52591ff6870b7e8ef32482371f22c6 | BSD-2 | 입력 키코드 테이블 생성 | https://github.com/qemu/keycodemapdb |
| QEMU subproject berkeley-softfloat-3 | b64af41c3276f97f0e181920400ee056b9c88037 | BSD-3 | FP 에뮬레이션 테스트 | https://github.com/qemu/berkeley-softfloat-3 |
| QEMU subproject berkeley-testfloat-3 | e7af9751d9f9fd3b47911f51a5cfd08af256a9ab | BSD-3 | (빌드 의존) | https://github.com/qemu/berkeley-testfloat-3 |
| Termux QEMU Android 패치 | termux-packages 20ef6a0bb633ed659685e26dcfe192a81fee83c9 (2026-11-05), `packages/qemu-system-x86-64-headless/0000..0021` + 본 저장소 추가 패치 `0022` | 패키지(QEMU) 라이선스 승계 = GPL-2.0 | bionic 호환 패치 (`engine/qemu/patches`) | https://github.com/termux/termux-packages |
| glib | 2.80.0 (Ubuntu noble `glib2.0_2.80.0.orig.tar.xz`) + `engine/build/glib-2.80.0-android-meson.patch` + Termux glib 패치 7개 | LGPL-2.1+ | QEMU 필수 | archive.ubuntu.com (deb-src) |
| pixman | 0.42.2 (Ubuntu noble orig), `a64-neon` 비활성 | MIT | QEMU 디스플레이 서피스 | archive.ubuntu.com |
| libffi | 3.4.6 (Ubuntu noble orig) | MIT | glib 의존 | archive.ubuntu.com |
| pcre2 | 10.42 (Ubuntu noble orig) | BSD-3 | glib 의존 | archive.ubuntu.com |
| proxy-libintl | 0.4 | LGPL-2.1 | bionic에 없는 libintl 스텁 | https://github.com/frida/proxy-libintl |
| libslirp | 4.7.0 (Ubuntu noble orig) | BSD-3 | 게스트 user-mode 네트워크(앱 기본 비활성) | archive.ubuntu.com |
| libandroid-shmem | termux/libandroid-shmem (HEAD, 2026-11-05 클론) | BSD-3 | `qemu-arm`(linux-user)의 System V shm 에뮬레이션 | https://github.com/termux/libandroid-shmem |
| zlib, liblog, libm, libdl, libc | Android NDK r29 sysroot (API 28) | zlib / Apache-2.0 / BSD | 동적 링크 | NDK |
| Android NDK | r29 (29.0.14206865), clang 21 | NDK 라이선스 | 크로스 컴파일 | Docker Hub `cimg/android:2026.10.1-ndk`에서 추출 (dl.google.com이 이 세션에서 차단) |
| Android SDK | platform 35 `android.jar`, build-tools 35.0.0 (aapt2, d8 8.6.2, zipalign, apksigner) | SDK 라이선스 | APK 빌드 | 동일 |
| Ubuntu arm64 커널 (실험/스모크 테스트 게스트) | `linux-image-6.8.0-71-generic` arm64 | GPL-2.0 | PC 실험 및 기기 스모크 테스트용 게스트 커널 (재배포하지 않고 스크립트로 내려받음) | ports.ubuntu.com |

## 재배포 가능성 정리

- 배포 가능한 것: 위 오픈소스 전부(각 라이선스 고지 필요; QEMU는 GPL-2.0이므로 APK 배포 시 대응 소스(패치 포함) 공개 의무).
- 사용자 가져오기(import)로만 다루는 것: 게임 APK/OBB, 상용 OS 이미지, 독점 번역기(Tango/houdini/ndk_translation 등은 사용하지 않음).
- 공개 가능한 대안: 게스트 Android 이미지는 AOSP GSI(Apache-2.0 + 각 구성 요소 라이선스)와 GKI 커널(GPL-2.0)로 만들 계획(U2).
