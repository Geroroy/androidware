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
| D11 | 2026-11-10 | 게스트 Android를 **Android 7.0 `system-images;android-24;default;armeabi-v7a`**(Google 공식 에뮬레이터 이미지, 전부 32비트, ranchu 3.10 커널)로 정한다. D9를 대체 | Google의 arm64 에뮬레이터 이미지는 API 21~37 모두 64비트 전용(32비트 라이브러리 없음); armv7 이미지는 게임과 같은 ABI이고 2011~2012 게임에 맞는 Android 버전; QEMU `virt`에서 바로 부팅 확인 | GLES 2.0 경로가 이 이미지로 불가능하다고 판명되면 새 커널+virtio-gpu 게스트 재검토 |
| D12 | 2026-11-10 | ranchu 커널용 **goldfish 장치(fb, events, battery)를 엔진에 추가**(패치 0024, `-M virt,goldfish=on`). 게스트 CPU는 `cortex-a15` | 3.10 커널에 virtio-input/virtio-gpu/USB/PCI가 없어 입력은 goldfish_events뿐; 장치 3개 약 700줄, 업스트림 장치 모델과 분리된 선택 기능 | 게스트를 virtio 계열 커널로 바꾸면 제거 |
| D13 | 2026-11-10 | 게스트 이미지는 APK에 넣지 않고 **앱이 dl.google.com에서 공식 zip을 받아(SHA-1 검증) 앱 저장소에 푼다**(또는 같은 zip을 가져오기) | Android SDK 라이선스 이미지의 재배포 회피; 사용자 조작 1회 | 자체 빌드 이미지(오픈소스)로 바꾸면 번들 검토 |
| D14 | 2026-11-10 | NDK r29 / build-tools 35 / platform 35를 **dl.google.com 공식 배포본**으로 전환(D10의 Docker Hub 추출 대체) | 이번 세션에서 접근 가능, SHA-1 검증 | — |
| D15 | 2026-11-10 | 게임 APK는 **전달 디스크(게스트 vde, 단순 블록 형식) + 램디스크 오버레이의 설치 서비스**로 게스트에 넣는다. 램디스크는 원본 뒤에 cpio를 덧붙여(커널이 순서대로 풀어 덮어씀) 앱이 매 부팅 전에 만든다 | 게스트 네트워크·adb 없음; Android에서 ext4 이미지를 직접 고칠 도구 없음; Google 이미지 파일을 수정본으로 재배포하지 않음 | 게스트 네트워크+adb 경로가 생기면 대체 가능 |
| D16 | 2026-11-10 | 오디오는 QEMU 오디오 서브시스템 대신 **goldfish-audio 장치 → chardev(유닉스 소켓) → 앱 AudioTrack** | 엔진을 오디오 백엔드 없이 빌드(`--audio-drv-list=`)한 상태 유지, AAudio 백엔드 작성 불필요, PC에서 파일로 검증 가능 | 지연이 크면 엔진 내 AAudio 백엔드 |
