# M0 — 실행 방식 비교 (A/B/C/D)

작성일 2026-11-05. 이 문서는 **확인한 사실(F#, `evidence.md` 참조)**과 **아직 검증하지 못한 가정(U#)**을 구분해서 적는다. 실제 기기(Galaxy S25 Ultra)에서 측정한 값은 아직 없다.

## 문제의 핵심 제약

1. **대상 기기는 32비트 ARM 코드를 하드웨어로 실행하지 못한다.** Samsung Knox 공식 문서가 Galaxy S24 이후 모든 Galaxy는 64비트 앱만 지원한다고 명시한다 (F1). Snapdragon 8 Gen 3부터 AArch32를 지원하는 코어가 없고, 8 Elite(Oryon)는 Qualcomm 자료에 "64-bit architecture"로만 표기된다 (F2). 즉 N.O.V.A. 3 같은 2012년 게임의 `armeabi-v7a` 네이티브 라이브러리는 **어떤 방식을 택하든 소프트웨어 명령어 번역**을 거쳐야 한다. 기기에서 `Build.SUPPORTED_32_BIT_ABIS`가 비어 있는지는 앱의 "Host check" 버튼으로 확인한다 (U1: 미측정).
2. **루팅 없는 앱이 쓸 수 있는 커널 기능은 한정적이다.** 앱 도메인은 JIT용 `execmem`이 허용되고(F3), 앱 데이터 디렉터리의 파일 `execve`는 targetSdk 29부터 금지되지만 `nativeLibraryDir`의 실행 파일은 실행 가능하다(F3). `/dev/kvm`은 crosvm 도메인 외 모든 도메인에 neverallow(F4), AVF의 `MANAGE_VIRTUAL_MACHINE`은 `signature|preinstalled|development`(F5)라 서드파티 앱은 쓸 수 없다.

## A. QEMU 전체 시스템 에뮬레이션 (TCG)

| 항목 | 내용 |
|---|---|
| 루팅 없이 가능? | **가능.** Termux(일반 앱)가 QEMU 11.1.3 `aarch64-softmmu`를 Android aarch64용으로 빌드·배포한다 (F7). 필요한 것은 `execmem`(JIT)과 `nativeLibraryDir` 실행 권한뿐이다 (F3). 본 저장소에서 NDK r29로 동일 버전을 크로스 빌드했다 (`docs/status.md`의 빌드 결과 참조). |
| 32비트 ARM 라이브러리 실행 | 게스트 CPU 모델(`cortex-a57` 등)이 AArch32 EL0를 제공하고, **64비트 게스트 커널의 COMPAT 계층이 32비트 프로세스를 실행**한다. 호스트 CPU에 AArch32가 전혀 없는 x86_64에서 실험으로 확인했다 (F10/F15). 게스트 Android가 `armeabi-v7a`를 포함한 multilib 빌드이면 32비트 앱이 그대로 동작한다. |
| 호스트 CPU/커널 요구 | 특별한 것 없음. 성능은 TCG의 aarch64 백엔드 품질에 달려 있다. MTE 태그 포인터 때문에 Android 12+에서 힙 태깅을 꺼야 한다 (Termux 패치 0016). |
| 구형 Android 사용자 공간 ↔ 커널 호환 | 게스트가 **자기 커널**을 들고 가므로 호스트 커널과 무관하다. 대신 게스트 커널이 QEMU `virt` 보드(virtio-blk/net/gpu/input, PL011, GICv3)를 지원해야 한다. Android 2.3/4.x의 goldfish 커널은 virtio-gpu가 없고 유지되지 않으므로(U2) **게스트 Android는 virtio를 지원하는 GKI 계열(Android 11+)이 현실적 후보**다. |
| GLES 1.x/2.0, EGL | 게스트: Mesa `virgl` Gallium 드라이버(GLES 1.1/2.0/3.x를 virtio-gpu 명령으로 전달). 호스트: `virglrenderer`가 Android의 EGL/GLES 위에서 동작해야 한다. Termux가 ANGLE 기반 `virglrenderer-android` 1.3.0을 빌드하고 있어(F8) **코드는 존재**하지만 우리 환경에서는 미검증(U3). 폴백은 게스트 SwiftShader(소프트웨어)로, TCG 위에서 매우 느리다. |
| 오디오 | QEMU 오디오 백엔드 중 Android용은 없음. SDL2 Android 포트(zlib 라이선스)의 오디오 백엔드를 쓰거나(Limbo 방식, F9) `-audiodev wav/dbus` 대신 **커스텀 백엔드(AAudio)**를 추가해야 한다 (U4). |
| 멀티터치 | QEMU 8.1+의 `virtio-multitouch-pci`와 QAPI `InputMultiTouchEvent`가 있다 (F6). VNC/SDL2 UI는 멀티터치를 전달하지 못하고 GTK만 지원하므로, 앱에서 **QMP `input-send-event`(type `mtt`)**로 직접 주입하는 방식을 택했다 (`QmpClient.sendTouch`). |
| 코드/라이선스/유지보수 | QEMU GPL-2.0(활발), glib LGPL-2.1, pixman MIT, libffi MIT, pcre2 BSD, proxy-libintl LGPL-2.1, Termux 패치(각 패키지 라이선스 승계). Limbo(GPL)는 2022년 QEMU 5.1에서 멈춰 참고용으로만 쓴다 (F9). |
| 2D 게임 병목 | TCG 정수 코드는 네이티브의 1.2~1.9배, 메모리 복사 3~15배, 부동소수점 약 10배 느림 (F10). 2D 비주얼노벨(검은방4)은 CPU 요구가 낮아 **플레이 가능성 있음**(U5: 기기 미측정). 영상 재생(검은방 컷신)은 소프트웨어 디코딩이면 느릴 수 있다. |
| 3D 게임 병목 | FP/NEON 10배 저하 + virgl 명령 직렬화 + 게스트 ART/SurfaceFlinger 오버헤드. N.O.V.A. 3는 **낮은 기대치**로 두고 반드시 측정 후 판단한다 (U6). |
| 확인한 것 | QEMU가 Android 비루팅 앱에서 빌드·실행된다는 선례(F7), AArch32-in-AArch64 게스트 동작(F10), TCG 비용 프로파일(F10), Android용 크로스 빌드 성공 여부(`status.md`). |
| 미확인 | 기기 성능(U1,U5,U6), Android 게스트 이미지 확보/부팅(U2), virgl on Android(U3), 오디오(U4). |

## B. 호스트 커널 공유 컨테이너 / 앱 가상화

| 항목 | 내용 |
|---|---|
| 루팅 없이 가능? | 부분적. `proot`류(ptrace 기반)는 Linux 루트FS를 돌릴 수 있지만 Android 사용자 공간은 아니다. 마운트/PID 네임스페이스와 `/dev/binder` 컨텍스트 매니저 등록은 비루팅 앱에 없다(호스트 servicemanager가 이미 소유). VirtualApp류 "앱 가상화"는 호스트 ART 안에서 dex를 실행하는 방식이라 **네이티브 라이브러리는 호스트 ABI여야 한다.** |
| 32비트 ARM 라이브러리 실행 | **불가.** 호스트 커널을 공유하므로 CPU가 AArch32를 못 하면 끝이다 (F1/F2). 번역기를 끼우면 그건 D다. |
| 결론 | 단독으로는 막힌 경로. 64비트 네이티브만 가진 구형 앱(드묾)의 Java API 호환 문제에만 쓸 수 있다. |

## C. 호스트가 허용하는 하드웨어 가상화

| 항목 | 내용 |
|---|---|
| /dev/kvm | AOSP sepolicy가 crosvm 도메인 외 전부 neverallow (F4). 앱은 `open()`조차 못 한다. 앱의 "Host check"가 실제 결과를 기록한다 (U1). |
| AVF/pKVM | `MANAGE_VIRTUAL_MACHINE`은 signature/preinstalled 전용 (F5). Pixel의 Terminal 앱은 시스템 앱이다. Samsung S25의 AVF 제공 여부도 불명(U7). |
| 32비트 | 설령 KVM을 써도 **게스트 EL0 AArch32는 호스트 CPU 기능**이므로 64비트 전용 코어에서는 어차피 불가. KVM은 TCG의 속도 문제만 해결하고 32비트 문제는 해결하지 못한다. |
| 결론 | 루팅 없는 일반 기기에서는 **제외**. 루팅/OEM 협력 전제라면 A의 가속기로만 의미가 있다. |

## D. 명령어 번역 + 구형 Android 런타임 호환 계층 (프로세스 내)

| 항목 | 내용 |
|---|---|
| 개념 | 게임의 dex는 호스트 ART에서 실행하고, `armeabi-v7a` `.so`는 프로세스 안의 ARM32→AArch64 번역기로 실행하며 JNI/EGL/bionic 호출을 호스트로 중계한다. Google의 `libhoudini`/`libndk_translation`, Xiaomi·OnePlus의 Tango(Amanieu Systems)가 이 구조다 (F2). |
| 루팅 없이 가능? | 원리상 가능(`execmem` 허용). 그러나 Native Bridge 인터페이스(`libnativebridge`)는 시스템 컴포넌트라 앱이 등록할 수 없고, 앱 안에서 **자체 JNI 브리지**를 구현해야 한다. |
| 사용 가능한 번역기 | Tango: 독점. houdini/ndk_translation: 독점이며 x86 호스트용. AOSP berberis: Apache-2.0이지만 riscv64→x86_64 호스트 중심(U8: 이번에 소스 미열람). **QEMU linux-user(`qemu-arm`)**: 오픈소스이고 Termux가 Android용으로 빌드(F7)하지만 "Linux 프로세스"를 돌리는 것이라 Android 앱 런타임(bionic 32비트, JNI, EGL 중계)은 전부 새로 만들어야 한다. |
| 그래픽/오디오/터치 | GPU는 **호스트 네이티브** → 유일하게 3D 성능이 나올 수 있는 경로. 입력/오디오도 호스트 그대로. |
| 병목·리스크 | 번역기 자체는 QEMU TCG 재사용 가능(FP 10배 저하는 동일). 리스크는 호환 계층의 범위: bionic 32비트 ABI, pthread, JNIEnv 테이블 썽킹, EGL/GLES 함수 포인터 썽킹, 게임별 누락 API. "CPU 에뮬레이터를 새로 만들지 않는다"는 원칙엔 맞지만 **Houdini급 런타임을 새로 만드는 일**이 된다. |
| 결론 | 장기적으로 3D 성능 목표에 가장 유망하지만 M0 범위의 최소 실험이 성립하지 않는다(실험 단위가 "번역기+bionic+JNI 브리지"). A로 실행 가능성을 먼저 입증하고, A의 성능 측정 결과가 나쁘면 D를 별도 트랙으로 연다. |

## 선택: A (QEMU TCG, aarch64 게스트 + 32비트 사용자 공간)

선택 이유:
1. 비루팅 앱에서 동작한다는 **공개 선례**(Termux)와 우리의 크로스 빌드로 실행 가능성이 가장 빨리 입증된다.
2. 32비트 라이브러리 문제를 게스트 커널 COMPAT로 해결하며, 이를 x86 호스트에서 **실험으로 확인**했다.
3. 유지되는 엔진(QEMU)을 쓰고, 그래픽(virtio-gpu/virgl)·입력(virtio-multitouch)·저장(virtio-blk) 모두 표준 virtio로 해결 경로가 존재한다.

포기 조건(이 중 하나라도 확정되면 A를 중단하고 D 또는 범위 축소를 결정):
- (A-1) Android 게스트 이미지가 `virt` 보드에서 virtio-gpu까지 부팅하도록 만드는 데 합리적 노력으로 실패.
- (A-2) 기기 측정에서 2D 게임(검은방4)마저 플레이 불가(목표: 안정 20fps 이상, 터치 지연 100ms 이하) 또는 게스트 부팅이 매번 5분을 넘고 스냅샷으로도 해결되지 않음.
- (A-3) Android 호스트에서 virglrenderer(ANGLE/EGL) 경로가 동작하지 않고 소프트웨어 렌더링만 가능.

## 흔한 오류에 대한 우리의 입장

- ARM64 기기라서 32비트가 돈다고 가정하지 않는다 → F1/F2, 기기에서 `SUPPORTED_32_BIT_ABIS`를 측정.
- 게스트 CPU 이름/ABI 속성만으로 호환이 생기지 않는다 → 32비트 실행은 QEMU CPU 모델의 AArch32 EL0 + 게스트 커널 COMPAT + 게스트 Android의 32비트 라이브러리 세 가지가 모두 필요.
- Native Bridge 인터페이스 ≠ 번역기 → D에서 분리해 기술.
- `/dev/kvm`, AVF, pKVM은 앱이 못 쓴다 → F4/F5.
- Android Emulator PC 구성 요소(ranchu/goldfish, gfxstream 호스트 라이브러리)를 APK에 그대로 쓰지 않는다 → 업스트림 QEMU `virt` + virtio만 사용.
- 임의의 Android 이미지를 붙이면 부팅된다고 가정하지 않는다 → 게스트 이미지 선정은 M0의 남은 과제(U2)로 명시.
- VMOS류 내부 구조는 비공개 → 참고하지 않는다.
