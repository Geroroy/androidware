# Android 게스트 부팅 실험 (PC, 2026-11-10 세션 2)

환경: Ubuntu 24.04 x86_64 컨테이너(4코어, 15 GB), QEMU 11.1.3 + 패치 0000~0024를 x86_64 호스트용으로 빌드(`aarch64-softmmu`, TCG). 기기 결과가 아니다.

## 게스트 이미지 후보 조사 (dl.google.com, 이번 세션에서 접근 가능)

| 이미지 | 32비트 ARM | 판단 |
|---|---|---|
| `system-images;android-{21..37};{default,google_apis};arm64-v8a` | **없음.** zip 안 build.prop 전부 `ro.product.cpu.abilist=arm64-v8a`; API 28 default는 system 파티션을 풀어 `/system/lib` 0개, `app_process32` 없음을 확인 | 탈락 (게임의 `armeabi`/`armeabi-v7a` .so를 실행할 수 없음) |
| `system-images;android-24;default;armeabi-v7a` (`armeabi-v7a-24_r07.zip`, 283,677,512 B, SHA-1 `3454546b4eed2d6c3dd06d47757d6da9f4176033`) | **전부 32비트** (`armeabi-v7a,armeabi`) | **채택.** Android 7.0, Linux 3.10 `kernel-ranchu`(`CONFIG_ARCH_VIRT`, virtio-mmio blk/net/console, PL011, PL031, goldfish fb/events/battery/audio/pipe, `FB_SIMPLE`, binder 32비트 ABI). system/userdata는 raw ext4 |
| `system-images;android-25;google_apis;armeabi-v7a` | 32비트 | 예비 (953 MB, Google API 포함) |
| ci.android.com Cuttlefish/GSI | — | 레거시 빌드 API가 rate limit(403, "migrate to Build API v4")으로 막혀 조사하지 않음 |

## 부팅 결과

명령행은 앱의 `QemuLauncher`(`GuestProfile.KIND_ANDROID_RANCHU`)와 같다:
`-M virt,highmem=off,goldfish=on -cpu cortex-a15 -accel tcg,thread=multi -m 1024`, `-kernel kernel-ranchu -initrd ramdisk.img`, virtio-blk-device 3개(**data, cache, system 순서**: ranchu 커널은 virtio-mmio 디스크를 생성 역순으로 vda..로 이름 붙이고 `fstab.ranchu`는 vda=system, vdb=cache, vdc=data), 커널 인자 `androidboot.hardware=ranchu qemu=1 qemu.gles=0 androidboot.selinux=permissive`.

| 단계 | 구성 | 결과 |
|---|---|---|
| 1 | 업스트림 QEMU 8.2(Ubuntu), DTB 수정 없음 | init·서비스 전부 시작, 콘솔 셸. surfaceflinger만 "failed to open framebuffer"로 재시작 반복 |
| 2 | QEMU 8.2 + DTB에 `simple-framebuffer`(r5g6b5, 480x800, RAM 상단 8 MB, `mem=1016M`) | **`sys.boot_completed=1` (2분 이내), 홈 화면 렌더링**(`pmemsave`로 확인). 입력 장치 없음 |
| 3 | QEMU 11.1.3 + 패치 0024(`virt,goldfish=on`) | `sys.boot_completed=1` **75~85초(smp 2)**, 142~150초(smp 4, 호스트 4코어라 경합). goldfish-fb 이중 버퍼(pan 0↔800), `screendump`, 배터리(AC, 100%) |
| 4 | 같은 구성, QMP `input-send-event`(abs+btn, key) | `getevent`: `qwerty2` 장치, 키 + MT 축. `dumpsys input`: touchScreen, 탭 좌표 (421.9, 199.2) 정확히 전달. Settings에서 탭→"Change wallpaper" 진입, ESC→BACK 동작 |
| 5 | 32비트 GLES 1.x NativeActivity(`gles1-test/`, armeabi-v7a, NDK r29) | `pm install` 성공, 실행. `GL_RENDERER=Android PixelFlinger 1.4`, `OpenGL ES-CM 1.0`. 256x256 RGB565 프레임을 매 프레임 CPU로 그려 `glTexSubImage2D` + 전체 화면 사각형: **약 20 fps**(480x800, 화면 전체 갱신). 터치 좌표 수신 |

스크린샷은 저장소에 넣지 않았다(재현: `scripts/fetch-android-guest.sh` 후 `QEMU=... experiments/android-guest/boot.py dist/android-guest <tag>`).

## 발견한 문제와 처리

- 패치 0024 첫 버전에서 탭이 "위에서 아래로 스와이프"로 처리됨: goldfish 커널 드라이버가 `EV_KEY`마다 `input_sync()`를 넣으므로 `BTN_TOUCH`가 좌표보다 먼저 가면 좌표 없는 접촉이 먼저 보고된다. `BTN_TOUCH`를 좌표 뒤로 옮겨 해결.
- NativeActivity 테스트 앱이 검은 화면: 창 버퍼(RGBA_8888)와 EGL 설정(RGB565) 불일치. `ANativeWindow_setBuffersGeometry(…, EGL_NATIVE_VISUAL_ID)`로 해결. `GLSurfaceView`를 쓰는 게임(검은방 1)은 프레임워크가 처리한다.
- Launcher3 "Welcome" 카드의 GOT IT 탭은 반응이 없었다(다른 UI는 정상). 원인 미조사.
- Settings 앱이 한 번 "has stopped"로 종료됨(탭/뒤로 시퀀스 후). 원인 미조사.
- 1회: smp 4 부팅 직후 첫 `screendump`에서 QEMU 프로세스가 메시지 없이 종료. 이후 같은 구성으로 5회 더 실행(그중 3회는 gdb 아래)했을 때는 재현되지 않음. **미해결**, 기기에서 같은 증상이 나오면 우선 조사.

## 한계 (중요)

- 게스트 GL은 소프트웨어 `libagl`(PixelFlinger)이며 **GLES 1.x만** 지원한다. 검은방 1(GLES 1.x, 2D)은 이 경로로 가능성이 있지만, **N.O.V.A. 3(GLES 2.0)은 이 경로로 실행 불가**. GLES 2.0은 (a) goldfish pipe + 호스트 GLES 렌더러, 또는 (b) virtio-gpu를 지원하는 새 커널 + 게스트 Mesa가 필요하다.
- 오디오(goldfish-audio) 미구현. 게임 오디오는 무음.
- 네트워크 없음(`-nic none`), adb 없음: APK는 userdata 이미지에 써 넣고 콘솔에서 `pm install`.
