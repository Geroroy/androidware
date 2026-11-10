# androidware — 최신 Android 기기에서 구형 Android 게임 실행 환경

목표: 루팅하지 않은 Galaxy S25 Ultra(이후 Tab S11 Ultra)에서 2011~2012년 Android 게임(N.O.V.A. 3, 검은방 4)이 요구하는 실행 환경을 제공하는 앱. 단순 런처가 아니라 **구형 게임이 요구하는 CPU(32비트 ARM)·OS·GLES 환경**을 제공하는 것이 핵심이다.

현재 단계: **M0 완료(기기에서 엔진·AArch64 게스트·32비트 실행 확인), M1 진행 중.** 요약은 `docs/status.md`, 방식 비교와 선택 이유는 `docs/M0-approach-comparison.md`.

## 업데이트 (2026-11-10, 세션 2)
- 게스트 Android: Google 공식 **Android 7.0 ARMv7 에뮬레이터 이미지**(전부 32비트)를 QEMU `virt`에서 부팅. 엔진 패치 0024로 goldfish 화면·터치·배터리 장치 추가. PC에서 홈 화면까지 75~85초, 터치·키 동작, 32비트 GLES 1.x 테스트 앱 실행 확인(`experiments/android-guest/RESULTS.md`).
- 앱: "Android 7 guest…"(공식 이미지 다운로드/가져오기) + "Start Android".
- (세션 3) 앱 0.0.7-m1: **Import game APK** → **Start Android**만으로 게임이 게스트에 설치·실행된다(PC 불필요). 오디오(goldfish-audio → 앱 AudioTrack), SD 카드 추가, 간헐적 엔진 크래시 수정. PC 검증만 했고 기기·실제 게임은 미검증.
- (세션 4) 앱 0.0.9-m1: 호스트 GPU 그래픽 모드(GLES 2.0, 실험적) 추가 — 엔진 goldfish-pipe + `engine/emugl` 렌더러. PC에서 GLES 2.0 테스트 앱 실행 확인, 기기 미검증.
- 남은 핵심 과제: 기기에서 GPU 모드 확인, 실제 게임(검은방, N.O.V.A. 3) 실행 확인.

## 한 줄 결론 (2026-11-05)
- 대상 기기는 32비트 ARM 코드를 하드웨어로 실행하지 못한다(Samsung 공식). 모든 경로에 소프트웨어 명령어 번역이 필요하다.
- 선택한 방식은 **A: 업스트림 QEMU(TCG) 전체 시스템 에뮬레이션, AArch64 게스트 + 32비트 사용자 공간**. 루팅 없이 동작하는 선례(Termux)가 있고, x86 PC에서 "AArch64 게스트 안의 32비트 바이너리 실행"을 실험으로 확인했으며, 같은 QEMU 11.1.3을 NDK r29로 Android arm64용 크로스 빌드했다.
- 아직 기기에서 실행한 결과는 없다(기기 미연결). 모든 기기 성능 수치는 **미검증**이다.

## 저장소 구조
```
app/            Android 앱 (Java, AGP 없이 빌드). MainActivity, EngineService, VncView, QmpClient, ImportManager, ApkInspector, HostInfo
engine/qemu/    QEMU Android 패치(Termux 유래, GPL-2.0) 및 setjmp 보조 파일
engine/build/   크로스 빌드용 meson cross 파일 템플릿, glib Android 패치
scripts/        fetch-engine-sources.sh, build-engine.sh, build-apk.sh, fetch-test-guest.sh, fetch-android-guest.sh, install-and-run.sh
experiments/    PC(x86_64)에서 수행한 실험: TCG/AArch32(pc-tcg-aarch32), Android 7 게스트 부팅·GLES1 테스트 앱(android-guest)
docs/           방식 비교, 근거, 결정 기록, 아키텍처, 의존성, 호환성 표, 상태/다음 단계, 기기 테스트 절차
dist/           현재 단계에서 생성한 debug APK
```

## 재현
```bash
# 0) NDK r29, build-tools 35, platform 35: dl.google.com 공식 zip을 /opt/android-sdk에 (scripts/env.sh 참고)
# 1) 엔진 소스 받기 (github.com, archive.ubuntu.com deb-src 필요)
scripts/fetch-engine-sources.sh
# 2) 엔진 크로스 빌드 (NDK r29, meson, ninja, autotools 필요; 결과: app/jniLibs/arm64-v8a/libqemu-system-aarch64.so)
scripts/build-engine.sh
# 3) APK 빌드 (build-tools 35, platform 35 android.jar; 결과: app/build/androidware-debug.apk)
scripts/build-apk.sh
# 4) 기기 스모크 테스트용 게스트 만들기 (Ubuntu arm64 커널 + 32/64비트 테스트 바이너리 initramfs)
scripts/fetch-test-guest.sh
# 4b) PC에서 Android 7 게스트 시험: scripts/fetch-android-guest.sh 후 experiments/android-guest/boot.py
# 5) 설치/실행
scripts/install-and-run.sh
```
도구 경로는 `scripts/env.sh`의 환경 변수(`ANDROID_SDK_ROOT`, `ANDROID_NDK_ROOT`, `BUILD_TOOLS`, `ANDROID_JAR`)로 바꾼다.

## 원칙
- 게임·펌웨어·독점 번역 라이브러리는 번들하지 않는다. 게임 파일은 사용자가 SAF로 가져온다.
- 게스트 네트워크는 기본 비활성.
- 설치 성공 / 앱 실행 / 타이틀 진입 / 실제 플레이를 별개 결과로 기록한다(`docs/compat.md`).
