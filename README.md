# androidware — 최신 Android 기기에서 구형 Android 게임 실행 환경

목표: 루팅하지 않은 Galaxy S25 Ultra(이후 Tab S11 Ultra)에서 2011~2012년 Android 게임(N.O.V.A. 3, 검은방 4)이 요구하는 실행 환경을 제공하는 앱. 단순 런처가 아니라 **구형 게임이 요구하는 CPU(32비트 ARM)·OS·GLES 환경**을 제공하는 것이 핵심이다.

현재 단계: **M0 기술 검증 진행 중.** 요약은 `docs/status.md`, 방식 비교와 선택 이유는 `docs/M0-approach-comparison.md`.

## 한 줄 결론 (2026-11-05)
- 대상 기기는 32비트 ARM 코드를 하드웨어로 실행하지 못한다(Samsung 공식). 모든 경로에 소프트웨어 명령어 번역이 필요하다.
- 선택한 방식은 **A: 업스트림 QEMU(TCG) 전체 시스템 에뮬레이션, AArch64 게스트 + 32비트 사용자 공간**. 루팅 없이 동작하는 선례(Termux)가 있고, x86 PC에서 "AArch64 게스트 안의 32비트 바이너리 실행"을 실험으로 확인했으며, 같은 QEMU 11.1.3을 NDK r29로 Android arm64용 크로스 빌드했다.
- 아직 기기에서 실행한 결과는 없다(기기 미연결). 모든 기기 성능 수치는 **미검증**이다.

## 저장소 구조
```
app/            Android 앱 (Java, AGP 없이 빌드). MainActivity, EngineService, VncView, QmpClient, ImportManager, ApkInspector, HostInfo
engine/qemu/    QEMU Android 패치(Termux 유래, GPL-2.0) 및 setjmp 보조 파일
engine/build/   크로스 빌드용 meson cross 파일 템플릿, glib Android 패치
scripts/        fetch-engine-sources.sh, build-engine.sh, build-apk.sh, fetch-test-guest.sh, install-and-run.sh
experiments/    PC(x86_64)에서 수행한 TCG/AArch32 실험과 결과(RESULTS.md)
docs/           방식 비교, 근거, 결정 기록, 아키텍처, 의존성, 호환성 표, 상태/다음 단계, 기기 테스트 절차
dist/           현재 단계에서 생성한 debug APK
```

## 재현
```bash
# 1) 엔진 소스 받기 (github.com, archive.ubuntu.com 필요)
scripts/fetch-engine-sources.sh
# 2) 엔진 크로스 빌드 (NDK r29, meson, ninja, autotools 필요; 결과: app/jniLibs/arm64-v8a/libqemu-system-aarch64.so)
scripts/build-engine.sh
# 3) APK 빌드 (build-tools 35, platform 35 android.jar; 결과: app/build/androidware-debug.apk)
scripts/build-apk.sh
# 4) 기기 스모크 테스트용 게스트 만들기 (Ubuntu arm64 커널 + 32/64비트 테스트 바이너리 initramfs)
scripts/fetch-test-guest.sh
# 5) 설치/실행
scripts/install-and-run.sh
```
도구 경로는 `scripts/env.sh`의 환경 변수(`ANDROID_SDK_ROOT`, `ANDROID_NDK_ROOT`, `BUILD_TOOLS`, `ANDROID_JAR`)로 바꾼다.

## 원칙
- 게임·펌웨어·독점 번역 라이브러리는 번들하지 않는다. 게임 파일은 사용자가 SAF로 가져온다.
- 게스트 네트워크는 기본 비활성.
- 설치 성공 / 앱 실행 / 타이틀 진입 / 실제 플레이를 별개 결과로 기록한다(`docs/compat.md`).
