# 결정 기록

| # | 날짜 | 결정 | 근거 | 되돌릴 조건 |
|---|---|---|---|---|
| D1 | 2026-11-05 | 실행 방식 **A(QEMU TCG 전체 시스템)**를 주 경로로 선택 | `M0-approach-comparison.md` | A-1/A-2/A-3 포기 조건 중 하나 확정 |
| D2 | 2026-11-05 | 엔진은 **업스트림 QEMU v11.1.3** + Termux Android 패치 21개(소스 기준 재적용) | F7: 유지되는 패치 세트, 같은 버전 | Termux가 QEMU 패치를 중단하거나 업스트림이 Android를 직접 지원 |
| D3 | 2026-11-05 | 게스트 CPU 모델 기본값 `cortex-a57`, 보드 `virt`, 가속 `tcg,thread=multi` | F15: AArch32 EL0 확인, MTTCG로 멀티코어 활용 | 기기 측정에서 다른 모델이 유리하면 변경 |
| D4 | 2026-11-05 | 디스플레이 1차 경로는 **VNC over unix socket**(앱 내 최소 RFB 클라이언트), 제어는 **QMP over unix socket** | 네이티브 UI 백엔드 없이도 화면·터치·종료를 즉시 검증 가능; QEMU SDL2는 멀티터치 미지원(F6) | M1에서 virtio-gpu 스캔아웃을 SurfaceView로 직접 전달하는 백엔드로 교체 |
| D5 | 2026-11-05 | 멀티터치는 QMP `input-send-event`(`mtt`) + `virtio-multitouch-pci` | F6 | 지연이 크면 QEMU에 Android 전용 입력 백엔드 추가 |
| D6 | 2026-11-05 | 앱 UI 언어는 **Java**, 빌드는 **AGP 없이** aapt2/javac/d8/apksigner 스크립트 | 이 환경에서 Google Maven(dl.google.com) 차단; 엔진 통합에 JNI/프로세스 실행만 필요 | Android Studio 사용이 가능해지면 Gradle 래퍼 추가(스크립트는 유지) |
| D7 | 2026-11-05 | 엔진 바이너리는 `lib/arm64-v8a/libqemu-system-aarch64.so`(ELF 실행 파일)로 APK에 넣고 `nativeLibraryDir`에서 `exec` | F3: targetSdk 29+에서 데이터 디렉터리 execve 금지 | Android 정책 변화 시 `dlopen`+`qemu_init` 방식(Limbo)으로 전환 |
| D8 | 2026-11-05 | 게스트 네트워크 **기본 비활성**(`-nic none`), M0 빌드는 slirp 미포함 | 요구사항; 검은방4의 종료된 서버 접속 시도를 VM 문제와 분리 | 사용자가 켤 때만 slirp 포함 빌드 사용 |
| D9 | 2026-11-05 | 게스트 Android 버전은 **virtio를 지원하는 GKI 계열(Android 11+ arm64 multilib)**을 1차 후보로 두고, 2.3/4.x는 보류 | goldfish 계열 구형 커널은 virtio-gpu 없음(U2) | APK 분석에서 2.3/4.x 전용 동작이 필수로 드러나면 재검토 |
| D10 | 2026-11-05 | NDK r29, minSdk 29(앱), API 28(엔진 컴파일 기준), 16KB 페이지 정렬 링크 | Docker Hub `cimg/android:2026.10.1-ndk`에서 확보 가능한 최신 NDK; `-Wl,-z,max-page-size=16384` | — |
