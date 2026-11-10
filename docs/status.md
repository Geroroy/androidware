# 진행 상태와 다음 단계

최종 갱신: 2026-11-10 (세션 5). 세션 2, 세션 1 기록은 아래에 그대로 둔다.

## 세션 5 (2026-11-10) — 기기에서 N.O.V.A. 3 "has stopped", 크래시 로그 수집 추가 (앱 0.0.10-m1)

기기 실측 6차(사용자 스크린샷): N.O.V.A. 3 APK가 게스트에 **설치되고 자동 시작**되었으나 곧 "N.O.V.A 3 has stopped" 대화상자가 떴다. 그래픽 모드(Software/Host GPU), 데이터(OBB) 유무, 크래시 원인은 **미확인** — 게스트에 adb가 없어 logcat이 앱 밖으로 나오지 않았다.

가능한 원인(추정, 우선순위 순): (1) Graphics가 기본값 Software(GLES 1.x 전용)인 상태 — GLES 2.0 게임은 시작 시 종료된다, (2) Host GPU 모드의 기기 쪽 렌더러 문제(기기 미검증 경로), (3) 게임 쪽 문제(추가 데이터 없음, 기기 ID null, Android 7 비호환 등).

추가한 것:
- 게스트 `aw-crashlog` 서비스(램디스크 오버레이): `logcat -b crash -b main AndroidRuntime:E DEBUG:I libEGL:W linker:W`를 커널 로그(시리얼 콘솔)로 복사. 앱이 `serial.log`를 따라가며 `androidware:`/`awcrash:` 줄을 로그 화면에 `[guest] …`로 표시, Export log에 포함.
- Start Android 때 `graphics:` 모드 표시, 가져온 APK의 매니페스트가 GLES 2.0 이상을 요구하는데 Software 모드면 경고.
- PC 검증(Software 모드, 앱과 같은 `GuestMedia` 코드): 설치 진행 줄 표시, GLES 2.0 테스트 앱에서 `E/libEGL: called unimplemented OpenGL ES API` 수집, 실행 중인 테스트 앱에 SIGSEGV를 보내 tombstone(backtrace 30프레임) 전체가 시리얼 로그에 기록됨. 앱 쪽 화면 표시(파일 tail)와 GLES 경고는 기기에서만 확인 가능(미검증).

### 기기 실측 7차 (0.0.10-m1, Export log) — 원인 확정, 0.0.11-m1에서 수정

- 기기 사실: **Host GPU 모드가 기기에서 동작**(렌더러 `Qualcomm / Adreno (TM) 830 / OpenGL ES 3.2`, 720x1280, 게스트 홈 화면까지 부팅). 설치된 게임은 `com.gameloft.android.ANMP.GloftNAHM`(Freedom Edition)과 검은방 1. 크래시 로그 수집 기능도 기기에서 동작.
- 크래시: 게임 프로세스 안의 WebView(Chromium 51)가 `[FATAL:gpu_info_collector_android.cc(183)] failed to create a temporary context … EGL_BAD_ATTRIBUTE`로 abort. 게스트의 `org.chromium.webview_shell`도 같은 메시지로 종료 → 게임 고유 문제가 아님.
- 원인: `rcQueryEGLString(EGL_EXTENSIONS)`가 호스트 드라이버의 확장 목록을 그대로 돌려줌 → 게스트 EGL이 `EGL_EXT_create_context_robustness`를 광고 → Chromium이 `eglCreateContext`에 robustness 속성을 넘김 → 게스트 `libEGL_emulation`이 모르는 속성이라 `EGL_BAD_ATTRIBUTE`. (원래 에뮬레이터의 Translator는 `EGL_KHR_image_base EGL_KHR_gl_texture_2D_image`만 알렸다.)
- 수정: `engine/emugl` 패치에서 EGL 확장 문자열을 Translator와 같은 목록으로, 버전을 1.4로 고정.
- PC 검증(llvmpipe): 수정 전 `webview_shell` 실행 → 같은 FATAL로 종료(재현 2회). 수정 후 → Activity 표시, 프로세스 생존. N.O.V.A. 3 자체는 APK가 없어 PC에서 실행하지 못했다. **기기 재확인 대기.**
- 크래시 로그 필터에 `EGL_emulation:E chromium:F` 추가.

### 기기 실측 8차 (0.0.11-m1, Export log) — WebView 크래시 해소, 게임 데이터 단계에서 종료

- 0.0.11-m1 수정 확인: WebView의 `gpu_info_collector` abort가 더 이상 없다. 게임이 GL 스레드를 만들고 네이티브 `Application::Init`까지 진행.
- 1차 실행(pid 1379): Java `NullPointerException`(`GameInstaller.run → … → SUtils.ReadFile`, `Context.getResources()` on null). GameInstaller는 Gameloft의 추가 데이터 확인·다운로드 단계.
- 2차 실행(pid 1699): 네이티브 SIGSEGV, `Application::Init → InitString → AdsManager::UpdateLanguage → CStrings::GetString`(this=NULL, fault addr 0x1dc4). 문자열 테이블이 로드되지 않은 상태.
- 해석(추정, APK 미확보로 미검증): 두 크래시 모두 **게임 추가 데이터(OBB/다운로드 리소스)가 게스트에 없는 것**과 맞는다. 게스트는 네트워크가 꺼져 있고, /data는 550 MB(140,800 블록)라 약 2 GB 데이터를 받을 공간도 없다. VM/GL 문제로 볼 근거는 현재 없음(`eglGetConfigAttrib … EGL_BAD_ATTRIBUTE` 3회는 미지원 속성 조회로, 크래시 지점과 무관).
- 필요한 것: 게임 데이터 전달 경로(OBB 가져오기 → 게스트 `/sdcard/Android/obb/<패키지>/`)와 큰 저장 공간. 사용자가 가진 데이터 형태 확인 후 진행.

(이전 계획) 사용자가 0.0.10-m1로 다시 실행 → `graphics:` 줄과 `[guest] awcrash:` 내용으로 원인 확정.

## 세션 4 요약 (2026-11-10) — 호스트 GPU GLES 2.0 경로 (앱 0.0.9-m1, 실험적)

N.O.V.A. 3에 필요한 GLES 2.0을 위해 게스트 GL 명령을 호스트 GPU에서 실행하는 경로를 만들었다. **PC(Mesa llvmpipe)에서만 검증했고, 기기 GPU와 실제 게임은 미검증이다.**

| 구성 요소 | 내용 |
|---|---|
| 엔진 goldfish-pipe (패치 0024) | 게스트 `/dev/qemu_pipe`의 `opengles` 연결을 유닉스 소켓으로 전달. 이 3.10 커널은 버퍼를 **게스트 가상 주소**로 넘기므로 접근 중인 CPU로 변환(`cpu_memory_rw_debug`) |
| 호스트 렌더러 `engine/emugl` | Android 에뮬레이터 2.0의 emugl(Apache-2.0)을 Translator 없이 시스템 EGL/GLES에 직접 연결한 렌더 서버. Linux용 `aw-gl-renderer`, Android용 `libawgl.so`(JNI 포함) |
| 앱 | "Android 7 guest…" → **Graphics** 전환(Software ↔ Host GPU). GPU 모드에서는 앱 프로세스 안의 렌더러가 SurfaceView에 직접 그리고, VncView는 투명하게 터치만 처리 |
| 게스트 설정 | 커널 인자 `qemu.gles=1`, 램디스크 오버레이로 `ro.opengles.version=131072`, `ro.sf.lcd_density` |

PC 결과: GPU 모드 부팅 96~97초, SurfaceFlinger·런처·Settings가 호스트 GL로 렌더링, 32비트 **GLES 2.0 테스트 앱 설치·실행(셰이더 링크 성공, 40~48 fps, llvmpipe)**. 소프트웨어 모드는 영향 없음(재검증).

알려진 한계·위험:
- PC의 Mesa에 GLES 1.x 컨텍스트가 없어 **GPU 모드의 GLES 1.x는 미검증**(검은방은 Software 모드 권장).
- 기기에서 미실행: `ANativeWindow` 출력, 드라이버(Adreno)와의 EGLImage 공유, 창 설정 선택.
- `rcCreateClientImage`가 호스트 EGLImage 핸들을 32비트로 자른다(64비트 드라이버에서 게스트가 GL 텍스처로 EGLImage를 만드는 앱은 깨짐). gralloc 경로는 무관.
- 렌더러가 앱 프로세스 안에서 돌므로 렌더러 크래시는 앱 종료로 이어진다.
- N.O.V.A. 3 자체: APK/데이터 미확보, 추가 데이터 다운로드 서버 종료 가능성, CPU 측 TCG 성능(FP 약 7배 저하)은 별개 문제.

## 기기 실측 5차 (2026-11-10, 앱 0.0.7-m1, 사용자 스크린샷)

- **Start Android로 Android 7.0 게스트가 기기에서 홈 화면까지 부팅**(480x800, VNC 표시). 터치 동작(사용자 확인). 부팅 시간·게임 실행·소리는 아직 보고 없음.
- 사용자 요청으로 0.0.8-m1에 전체 화면(Fullscreen 버튼, 가장자리 스와이프로 해제)과 게스트 해상도 선택(320x480 / 480x800 / 540x960 / 720x1280) 추가. PC에서 720x1280(부팅 98초), 320x480(73초) 부팅 확인. 1080p는 goldfish-fb 버퍼(8 MB 연속 할당)가 3.10 커널 한계를 넘어 제외.

## 세션 3 요약 (2026-11-10) — 게임 APK 자동 설치·실행, 오디오, SD 카드, 크래시 수정 (앱 0.0.7-m1)

목표: PC 없이 폰만으로 "게임 APK 가져오기 → Start Android → 게임 실행"까지 가게 한다. 아래는 전부 PC(TCG) 검증이며 **기기와 실제 게임(검은방)은 미검증**이다(이 세션에 게임 APK가 없음).

| 항목 | 결과 | 근거 |
|---|---|---|
| 게임 APK 전달 | **Import game APK** → `files/games/<package>.apk`. Start Android 때 전달 디스크(게스트 `vde`)에 담고, 램디스크 오버레이의 `aw-install` 서비스가 부팅 완료 후 `pm install` + 실행 | `GuestMedia.java`, `AndroidGuest.prepareBoot` |
| PC 검증(앱과 같은 Java 코드로 미디어 생성) | 테스트 APK 자동 설치 `Success` + 자동 실행: 끝까지 간 3회 모두 성공(다른 1회는 아래 크래시로 설치 단계 전에 중단) | `experiments/android-guest/RESULTS.md` |
| SD 카드 | 512 MB FAT32 이미지(`vdd`), 게스트가 `/storage/4157-0001`로 마운트. 게임 설치·초기화와 무관하게 유지 | 같은 문서 |
| 오디오 | 엔진에 goldfish-audio 추가(패치 0024). PCM(44.1 kHz 스테레오 16비트)을 유닉스 소켓으로 내보내고 앱 `AudioPlayer`가 `AudioTrack`으로 재생. PC에서 벨소리 재생 시 약 29초 분량 PCM 수신(무음 아님) 확인. **앱 쪽 재생(AudioTrack)은 기기에서만 확인 가능** | 같은 문서 |
| 간헐적 QEMU 종료(세션 2 미해결 항목) | **원인 확정·수정.** goldfish-fb가 `framebuffer_update_display`에 시작 행 -1을 넘겨 화면 버퍼 앞 한 줄(1,920 B)을 덮어씀 → 힙 손상 → SIGSEGV(8회 중 2회꼴). 시작 행 0으로 수정. 수정 전 같은 절차 약 9회 중 2회 크래시 → **수정 후 9회 중 0회** | 패치 0024 |
| GLES 2.0 (N.O.V.A. 3) | **미해결, 이번 세션에서 착수하지 않음.** 아래 장애물 1 | — |

### 세션 3 이후 남은 장애물

1. **GLES 2.0** — 변동 없음. goldfish pipe 장치 + 호스트 GLES 렌더러(Android 에뮬레이터 `android-emugl`의 디코더를 Android 호스트 EGL 위에 포팅)가 필요한 큰 작업이다. N.O.V.A. 3은 그 전까지 실행 불가.
2. **검은방 1 실제 실행 확인** — 게임 APK로 해 봐야 아는 것: SKT ARM DRM 서비스 바인딩 실패 시 동작, `READ_PHONE_STATE`(게스트에 모뎀/RIL 없음 → 기기 ID가 null일 수 있음), 화면/터치/소리.
3. **기기 검증** — 0.0.7-m1 전체 절차(`docs/device-test-procedure.md` M1).
4. 오디오 지연: 장치 쪽 큐 최대 약 190 ms + AudioTrack 버퍼. 체감 확인 후 조정.
5. OBB/추가 데이터가 있는 게임의 데이터 전달 경로(SD 카드 이미지에 쓰는 기능) 없음. 검은방 1은 필요 없음.

---

## 세션 2 요약 (2026-11-10) — 게스트 Android 이미지(장애물 1) 해결, PC에서 부팅·입력·32비트 GLES 앱 실행

이번 세션에서는 dl.google.com, ci.android.com, android.googlesource.com, source.android.com, qemu.org, gitlab.freedesktop.org 접근이 **가능**했다(세션 1에서는 차단).

| 항목 | 결과 | 근거 |
|---|---|---|
| Google 에뮬레이터 arm64 시스템 이미지(API 21~37) 32비트 지원 여부 | **전부 64비트 전용**(abilist `arm64-v8a`; API 28은 `/system/lib` 0개) → 게임 실행 불가 | `experiments/android-guest/RESULTS.md` |
| 게스트 선택 | **Android 7.0 `system-images;android-24;default;armeabi-v7a`** (전부 32비트, ranchu 3.10 커널) | 결정 D11 |
| 엔진 패치 0024: `virt,goldfish=on` → goldfish-fb / goldfish-events(키+멀티터치) / goldfish-battery | 작성, PC 빌드·검증 | `engine/qemu/patches/0024-android-goldfish-devices.patch` |
| PC 부팅 | `sys.boot_completed=1` **75~85초**(smp 2, TCG, 4코어 x86 호스트), 홈 화면 480x800 | 같은 문서 |
| 입력 | VNC/QMP 포인터 → 터치스크린(좌표 정확), ESC → BACK, Settings 조작 확인 | 같은 문서 |
| 32비트 네이티브 GLES 1.x 앱(armeabi-v7a NativeActivity) | 설치·실행 성공, PixelFlinger(소프트웨어) GLES-CM 1.0, 480x800 전체 갱신 약 20 fps(PC) | `experiments/android-guest/gles1-test/` |
| `scripts/build-engine.sh` 처음부터 끝까지 | **성공**(깨끗한 소스 트리, 공식 NDK r29를 dl.google.com에서 받아 사용). 세션 1의 "전체 실행 미검증" 해소. `libbench-arm64.so` 빌드 단계 누락을 추가 | 이 세션 로그 |
| 앱 0.0.6-m1 | "Android 7 guest…"(dl.google.com에서 공식 zip 다운로드+SHA-1 검증, 또는 zip 가져오기, 데이터 초기화), "Start Android" | `AndroidGuest.java`, `dist/androidware-0.0.6-m1.apk` |

**기기 실행은 미검증.** 다음 측정은 `docs/device-test-procedure.md`의 "M1: Android 게스트" 절.

### 세션 2 이후 남은 장애물 (중요도 순)

1. **GLES 2.0 (N.O.V.A. 3)** — 이 게스트의 GL은 소프트웨어 `libagl`(GLES 1.x 전용)이다. GLES 2.0 게임은 현재 경로로 실행할 수 없다. 선택지: (a) goldfish pipe + 호스트 GLES 디코더(Android 에뮬레이터의 `android-emugl`, Apache-2.0)를 엔진에 넣기, (b) virtio-gpu를 지원하는 새 커널 + 게스트 Mesa(virgl)로 Android 7 사용자 공간을 재구성. (a)가 이 이미지와 맞는 경로다.
2. **기기 검증** — S25 Ultra에서 부팅 시간, 홈 화면 반응, VNC 경로의 터치 지연, smp 2와 4 비교.
3. **게임 APK 전달** — 현재는 PC에서 userdata 이미지에 써 넣는 방식만 있다. 앱에서 `files/games/*.apk`를 게스트에 넣는 경로(시작 전 ext4 이미지에 기록, 또는 virtio-9p/virtio-console 채널) 필요.
4. **오디오** — goldfish-audio 장치 미구현(게임 무음).
5. **간헐적 QEMU 종료 1회**(PC, smp 4, 부팅 직후 screendump) — 재현 안 됨, 기기에서 관찰되면 우선 조사.
6. 검은방 1의 SKT ARM DRM/통신사 구성 요소 동작 여부는 실제 실행으로만 확인 가능(VM 문제와 분리해 기록).

---

## 세션 1 기록 (2026-11-05)

실제 기기 없이 진행했다. 아래 "남은 장애물"의 1번(게스트 Android 이미지)은 세션 2에서 해결했다.

### 완료한 것

| 항목 | 상태 | 증거 |
|---|---|---|
| 실행 방식 A/B/C/D 비교, 선택(A), 포기 조건 | 완료 | `docs/M0-approach-comparison.md`, `docs/decision-log.md` |
| 호스트 제약 근거(32비트 불가, execmem 허용, kvm/AVF 불가) | 소스·공식 문서로 확인 | `docs/evidence.md` F1~F5 |
| PC 실험: AArch64 TCG 게스트 안에서 32비트 ARM 실행 + TCG 비용 측정 | 완료 (x86_64 호스트) | `experiments/pc-tcg-aarch32/RESULTS.md` |
| PC 실험: 정적 bionic(NDK) arm32 바이너리를 `qemu-arm`(linux-user)로 실행 | 완료 (int 160ms / fp 763ms / mem 24ms, exit 0) | 세션 로그; `scripts/fetch-test-guest.sh`가 같은 바이너리를 기기용으로 생성 |
| 앱 명령행·RFB 핸드셰이크·QMP 흐름을 PC QEMU 8.2로 검증 | 완료 | `-nodefaults -display none -vnc unix: -qmp unix: -serial file:`로 부팅, Raw 인코딩 프레임 수신, 포인터 이벤트 전송, `query-status`/`quit` 성공 |
| Android arm64용 의존성 크로스 빌드 (pixman 0.42.2, libffi 3.4.6, pcre2 10.42, proxy-libintl 0.4, glib 2.80.0, 전부 static) | 완료 | `scripts/build-engine.sh` |
| QEMU v11.1.3 + Termux 패치 21개 적용, Android arm64 configure | 완료 (configure exit 0, `aarch64-softmmu`, VNC, TCG, internal fdt) | 같은 스크립트 |
| QEMU 빌드 (`qemu-system-aarch64` ELF) | 아래 "빌드 결과" 참조 | |
| 앱 골격(Java, AGP 없이 빌드) + debug APK | 완료 (컴파일·서명 성공) | `scripts/build-apk.sh`, `dist/` |

### 빌드 결과 (이 세션)

- 빌드 환경: Ubuntu 24.04 x86_64 컨테이너, NDK r29(clang 21), meson 1.12.1, ninja 1.11.1, build-tools 35.0.0, JDK 21.
- `qemu-system-aarch64` (aarch64-softmmu, TCG, VNC, QMP, internal fdt, slirp): **빌드 성공.** ELF 64-bit PIE, AArch64, interpreter `/system/bin/linker64`, NEEDED = libz/libm/libdl/libc, LOAD 정렬 0x4000(16KB). 스트립 후 35,272,216 B.
- `qemu-arm` (arm-linux-user, libandroid-shmem 링크): **빌드 성공.** 스트립 후 4,230,192 B. 추가 패치 `0022-android-api26-ipc-decls.patch`(bionic API 26+ 선언 충돌 회피) 필요.
- APK: `dist/androidware-0.0.5-m0.apk` 39,887,888 B, 16KB zipalign 검증 통과, debug 서명.

### 남은 장애물 (중요도 순)

1. **게스트 Android 이미지(U2)** — 가장 큰 미해결 과제. 필요한 것: `virt` 보드에서 부팅하는 arm64 커널(GKI + virtio-gpu/virtio-input/virtio-blk 내장 또는 모듈), arm64+armeabi-v7a multilib 사용자 공간(AOSP GSI `aosp_arm64` 계열), 간단한 vendor(드라이버 없는 `virt` 전용 HAL: drm_hwcomposer + minigbm/virtio-gpu, Mesa virgl). 이 세션에서는 dl.google.com/ci.android.com/googlesource 접근이 막혀 이미지를 받을 수 없었다. 다음 작업자는 네트워크가 되는 환경에서 (a) AOSP GSI arm64 + GKI `boot.img`를 QEMU `virt`로 부팅 시도, (b) 실패 시 Cuttlefish `aosp_cf_arm64_phone` 이미지의 커널/램디스크를 `virt`에서 재사용 가능한지 검토, (c) 그래도 안 되면 AOSP를 직접 빌드(`virt` 전용 device 트리)하는 순서로 진행한다. **이 항목이 해결되기 전에는 게임을 설치할 수 없다.**
2. **GPU 경로(U3)** — 게스트 Mesa virgl ↔ 호스트 virglrenderer(ANGLE 위 EGL/GLES). 참조: Termux `virglrenderer-android` 패치 10개. QEMU는 `--enable-virglrenderer --enable-opengl`로 재빌드해야 하며 `-display egl-headless` 대신 Android 서피스에 직접 그리는 디스플레이 백엔드가 필요하다.
3. **디스플레이 성능** — 현재 VNC(Raw)는 검증용이다. 1280x720 프레임을 소켓으로 복사하는 비용이 크므로 M1에서 SDL2 Android 포트 또는 전용 백엔드로 교체한다.
4. **오디오(U4)** — 현재 빌드는 오디오 백엔드 없음(`--audio-drv-list=`). AAudio 백엔드 추가 또는 SDL2 오디오.
5. **멀티터치** — `virtio-multitouch-pci` + QMP `mtt`는 코드만 있고 미검증(PC 검증에서는 장치 미부착 상태라 "Input handler not found" 응답을 확인했을 뿐).
6. **네트워크 선택 기능** — slirp 미포함 빌드. 사용자가 켤 수 있게 하려면 libslirp 4.7.0(소스 확보됨)을 크로스 빌드해 `--enable-slirp`로 재빌드.
7. **게임 APK 미제공(U9/U10)** — N.O.V.A. 3, 검은방 4의 실제 APK/OBB가 있어야 M2를 시작할 수 있다. 앱의 "Inspect game APK"로 분석 결과를 먼저 받는다.
8. **기기 검증(U1)** — `docs/device-test-procedure.md` 절차로 Galaxy S25 Ultra에서 1~4단계 실행. 특히 (a) `nativeLibraryDir`에서 exec 성공 여부, (b) 힙 태깅(MTE) 영향, (c) 16KB 페이지 여부, (d) TCG 성능 수치.

### 다음 작업 단위 제안 (우선순위)

1. 기기에서 스모크 테스트(절차서 1~4단계) → 결과를 `docs/compat.md`와 `docs/status.md`에 기록.
2. 게스트 Android 이미지 확보/부팅(장애물 1). 목표: `virt`에서 부팅 로고까지, 그 다음 `adb`(virtio-vsock 또는 guest 네트워크) 접속.
3. 사용자 제공 APK 분석 → 게스트 Android 버전/ABI 결정 확정.
4. virgl 경로(장애물 2)와 디스플레이 백엔드(장애물 3).

### 필요한 입력 (사용자)

- Galaxy S25 Ultra에서 "Host check" + 스모크 테스트 로그(Export log 결과 파일).
- N.O.V.A. 3 원작 APK(+OBB/추가 데이터)와 검은방 4 APK(+데이터) 파일. 분석 전까지 게스트 Android 버전을 확정하지 않는다.

### 세션 종료 시점 기록

- 완료: 방식 비교/선택, PC 실험(AArch32-in-AArch64 TCG 게스트, qemu-arm으로 bionic 정적 arm32 실행), Android용 QEMU 2종 크로스 빌드, 앱 골격 + APK, 재현 스크립트, 문서.
- 기기 실행: **미검증**(이 세션에 기기 없음). 첫 측정 항목은 앱의 "Host check"와 "Engine self-test"(qemu-arm으로 32비트 bionic 벤치를 돌려 Oryon 위 TCG 성능을 잰다), 그다음 `device-test-procedure.md`의 게스트 커널 부팅이다.
- 이 세션 환경 특이사항: dl.google.com/qemu.org/source.android.com/gitlab.freedesktop.org 차단 → NDK는 Docker Hub 이미지에서 추출, AOSP 소스는 LineageOS 미러, 의존성 소스는 Ubuntu deb-src 사용. 재현 스크립트는 네트워크가 정상인 환경을 가정한다.
- 알려진 미완: `scripts/build-engine.sh`는 이 세션의 수동 단계를 옮겨 적은 것이며 처음부터 끝까지 한 번에 실행해 검증하지는 않았다(각 단계는 개별 실행으로 성공). 다음 작업자는 깨끗한 환경에서 전체 실행을 한 번 확인해야 한다.

## 기기 실측 1차 (2026-11-10, Galaxy S25 Ultra, 사용자 스크린샷)

- CPU implementer 0x51 / part 0x001 (Qualcomm Oryon), page size 4096, `/dev/kvm: absent`, `android.software.virtualization_framework: true`(앱은 사용 불가, F5).
- `nativeLibraryDir`의 `libqemu-system-aarch64.so --version` 실행 **성공** (QEMU 11.1.3, exit 0, 150 ms). 비루팅 앱에서 엔진 exec 가능함을 기기에서 확인.
- `qemu-arm`으로 32비트 bionic 벤치 실행 **실패**: `error setting PER_LINUX32 personality: Invalid argument` → abort(exit 134). 원인: 64비트 전용 커널이 `personality(PER_LINUX32)`를 EINVAL로 거부하고 32비트 bionic이 시작 시 이를 필수로 요구. 조치: 패치 `0023-android-emulate-personality.patch`(QEMU가 persona를 자체 보관). 재측정 대기 중.
- UI: 버튼 3열 그리드, 세로보기 지원(0.0.3-m0).

## 기기 실측 2차 (2026-11-10, SM-S938N, Android 16 sdk 36, kernel 6.6.98-android15, SoC SM8750)

- `supported_abis: arm64-v8a`, `supported_32bit_abis: (none)` → U1 확인: 네이티브 32비트 실행 불가.
- 패치 0023 적용 후 `qemu-arm`(TCG user mode)으로 32비트 bionic 벤치 **성공** (2회): `int_ms=177/173 fp_ms=713/712 mem_ms=18/18`, 체크섬 일치, exit 0.
- 참고: 같은 바이너리를 PC(Xeon 2.1GHz, QEMU 8.2.2 qemu-arm)에서 돌린 값은 int 160 / fp 763 / mem 24. 기기 네이티브 기준값은 0.0.4-m0의 self-test에서 측정 예정(`libbench-arm64.so`).
- 0.0.4-m0: "Boot test" 버튼(번들된 Ubuntu arm64 커널 + initramfs로 게스트 부팅, 64/32비트 벤치 실행 후 자동 종료, 시리얼 콘솔 출력 표시). PC QEMU 8.2.2에서 동일 명령행으로 사전 검증: 64비트 int 213/fp 1271/mem 107, 32비트 int 230/fp 1235/mem 139, exit 0.

## 기기 실측 3차 (2026-11-10, SM-S938N, 앱 0.0.4-m0)

| 실행 | int_ms | fp_ms | mem_ms |
|---|---|---|---|
| 네이티브 64비트 (`libbench-arm64.so`) | 93 | 108 | 5 |
| `qemu-arm` TCG user mode, 32비트 bionic | 179 | 710 | 18 |
| 비율 (TCG / 네이티브) | 1.9x | 6.6x | 3.6x |

- Boot test 실패: `-vnc unix:...: could not find keymap file for language 'en-us'`, exit 1 (286 ms). 원인: VNC 서버가 시작 시 `-L` 디렉터리의 `keymaps/en-us`를 요구하는데 번들하지 않았음(PC 검증에서는 시스템 기본 데이터 디렉터리가 있어 드러나지 않았음). 조치: 0.0.5-m0에 `assets/qemu-share/keymaps/en-us` 포함. 재측정 대기 중.

## 기기 실측 4차 — Boot test 성공 (2026-11-10, SM-S938N, 앱 0.0.5-m0, 2회)

QEMU 11.1.3 `-M virt -cpu cortex-a57 -accel tcg,thread=multi -smp 2 -m 1024M`, 게스트 Ubuntu 6.8.0-71 arm64 커널 + initramfs. 두 번 모두 `[qemu exited with code 0]`, `=== DONE`, 체크섬 일치.

| 실행 | int_ms | fp_ms | mem_ms | 네이티브 대비 |
|---|---|---|---|---|
| 호스트 네이티브 64비트 | 93 | 108 | 5 | 1x |
| qemu-arm user mode, 32비트 | 179 | 710 | 18 | 1.9x / 6.6x / 3.6x |
| 게스트 안 64비트 프로세스 | 367, 271 | 625, 608 | 65, 64 | 2.9~3.9x / 5.7x / 13x |
| **게스트 안 32비트 프로세스** | 193, 194 | 805, 794 | 83, 71 | **2.1x / 7.4x / 14~17x** |

- 게스트 커널 로그: `CPU features: detected: 32-bit EL0 Support` → 64비트 전용 Oryon 위에서 게스트가 AArch32 EL0를 제공받음을 기기에서 확인.
- 부팅: 커널 시작부터 `/init`까지 게스트 시간 1.3~1.5초, QEMU 시작부터 종료까지 벽시계 6.1~6.9초.
- VNC 경로 동작(640x480 프레임 수신, "This VM has no graphic display device" 표시), `-serial file:` 로그 수집, 자동 종료 후 정리 동작.
- 알려진 사소한 문제: 게스트 종료 시 VNC 소켓이 닫히며 로그에 `null` 한 줄이 찍힘(표시상 문제).

**M0 판정**: "루팅 없는 S25 Ultra에서 QEMU 엔진 실행 + AArch64 게스트 부팅 + 게스트 안 32비트 ARM 실행"이 입증됨. 포기 조건 A-1/A-2/A-3는 아직 미판정(Android 게스트 이미지, 실제 게임, GPU 경로가 남음). 메모리 대역폭 14~17배 저하(softmmu)는 3D 게임에 큰 위험 요소로 기록.
