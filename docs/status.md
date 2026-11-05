# 진행 상태와 다음 단계

최종 갱신: 2026-11-05 (세션 1). 실제 기기 없이 진행했다. 기기 실행 결과는 전부 **미검증**이다.

## 완료한 것

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

## 빌드 결과 (이 세션)

- 빌드 환경: Ubuntu 24.04 x86_64 컨테이너, NDK r29(clang 21), meson 1.12.1, ninja 1.11.1, build-tools 35.0.0, JDK 21.
- `qemu-system-aarch64` (aarch64-softmmu, TCG, VNC, QMP, internal fdt, slirp): **빌드 성공.** ELF 64-bit PIE, AArch64, interpreter `/system/bin/linker64`, NEEDED = libz/libm/libdl/libc, LOAD 정렬 0x4000(16KB). 스트립 후 35,272,216 B.
- `qemu-arm` (arm-linux-user, libandroid-shmem 링크): **빌드 성공.** 스트립 후 4,230,192 B. 추가 패치 `0022-android-api26-ipc-decls.patch`(bionic API 26+ 선언 충돌 회피) 필요.
- APK: `dist/androidware-debug-m0.apk` 39,887,888 B, 16KB zipalign 검증 통과, debug 서명.

## 남은 장애물 (중요도 순)

1. **게스트 Android 이미지(U2)** — 가장 큰 미해결 과제. 필요한 것: `virt` 보드에서 부팅하는 arm64 커널(GKI + virtio-gpu/virtio-input/virtio-blk 내장 또는 모듈), arm64+armeabi-v7a multilib 사용자 공간(AOSP GSI `aosp_arm64` 계열), 간단한 vendor(드라이버 없는 `virt` 전용 HAL: drm_hwcomposer + minigbm/virtio-gpu, Mesa virgl). 이 세션에서는 dl.google.com/ci.android.com/googlesource 접근이 막혀 이미지를 받을 수 없었다. 다음 작업자는 네트워크가 되는 환경에서 (a) AOSP GSI arm64 + GKI `boot.img`를 QEMU `virt`로 부팅 시도, (b) 실패 시 Cuttlefish `aosp_cf_arm64_phone` 이미지의 커널/램디스크를 `virt`에서 재사용 가능한지 검토, (c) 그래도 안 되면 AOSP를 직접 빌드(`virt` 전용 device 트리)하는 순서로 진행한다. **이 항목이 해결되기 전에는 게임을 설치할 수 없다.**
2. **GPU 경로(U3)** — 게스트 Mesa virgl ↔ 호스트 virglrenderer(ANGLE 위 EGL/GLES). 참조: Termux `virglrenderer-android` 패치 10개. QEMU는 `--enable-virglrenderer --enable-opengl`로 재빌드해야 하며 `-display egl-headless` 대신 Android 서피스에 직접 그리는 디스플레이 백엔드가 필요하다.
3. **디스플레이 성능** — 현재 VNC(Raw)는 검증용이다. 1280x720 프레임을 소켓으로 복사하는 비용이 크므로 M1에서 SDL2 Android 포트 또는 전용 백엔드로 교체한다.
4. **오디오(U4)** — 현재 빌드는 오디오 백엔드 없음(`--audio-drv-list=`). AAudio 백엔드 추가 또는 SDL2 오디오.
5. **멀티터치** — `virtio-multitouch-pci` + QMP `mtt`는 코드만 있고 미검증(PC 검증에서는 장치 미부착 상태라 "Input handler not found" 응답을 확인했을 뿐).
6. **네트워크 선택 기능** — slirp 미포함 빌드. 사용자가 켤 수 있게 하려면 libslirp 4.7.0(소스 확보됨)을 크로스 빌드해 `--enable-slirp`로 재빌드.
7. **게임 APK 미제공(U9/U10)** — N.O.V.A. 3, 검은방 4의 실제 APK/OBB가 있어야 M2를 시작할 수 있다. 앱의 "Inspect game APK"로 분석 결과를 먼저 받는다.
8. **기기 검증(U1)** — `docs/device-test-procedure.md` 절차로 Galaxy S25 Ultra에서 1~4단계 실행. 특히 (a) `nativeLibraryDir`에서 exec 성공 여부, (b) 힙 태깅(MTE) 영향, (c) 16KB 페이지 여부, (d) TCG 성능 수치.

## 다음 작업 단위 제안 (우선순위)

1. 기기에서 스모크 테스트(절차서 1~4단계) → 결과를 `docs/compat.md`와 `docs/status.md`에 기록.
2. 게스트 Android 이미지 확보/부팅(장애물 1). 목표: `virt`에서 부팅 로고까지, 그 다음 `adb`(virtio-vsock 또는 guest 네트워크) 접속.
3. 사용자 제공 APK 분석 → 게스트 Android 버전/ABI 결정 확정.
4. virgl 경로(장애물 2)와 디스플레이 백엔드(장애물 3).

## 필요한 입력 (사용자)

- Galaxy S25 Ultra에서 "Host check" + 스모크 테스트 로그(Export log 결과 파일).
- N.O.V.A. 3 원작 APK(+OBB/추가 데이터)와 검은방 4 APK(+데이터) 파일. 분석 전까지 게스트 Android 버전을 확정하지 않는다.

## 세션 종료 시점 기록

- 완료: 방식 비교/선택, PC 실험(AArch32-in-AArch64 TCG 게스트, qemu-arm으로 bionic 정적 arm32 실행), Android용 QEMU 2종 크로스 빌드, 앱 골격 + APK, 재현 스크립트, 문서.
- 기기 실행: **미검증**(이 세션에 기기 없음). 첫 측정 항목은 앱의 "Host check"와 "Engine self-test"(qemu-arm으로 32비트 bionic 벤치를 돌려 Oryon 위 TCG 성능을 잰다), 그다음 `device-test-procedure.md`의 게스트 커널 부팅이다.
- 이 세션 환경 특이사항: dl.google.com/qemu.org/source.android.com/gitlab.freedesktop.org 차단 → NDK는 Docker Hub 이미지에서 추출, AOSP 소스는 LineageOS 미러, 의존성 소스는 Ubuntu deb-src 사용. 재현 스크립트는 네트워크가 정상인 환경을 가정한다.
- 알려진 미완: `scripts/build-engine.sh`는 이 세션의 수동 단계를 옮겨 적은 것이며 처음부터 끝까지 한 번에 실행해 검증하지는 않았다(각 단계는 개별 실행으로 성공). 다음 작업자는 깨끗한 환경에서 전체 실행을 한 번 확인해야 한다.
