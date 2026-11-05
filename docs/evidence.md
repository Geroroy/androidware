# 근거 목록 (Facts F# / Unverified U#)

각 항목은 "어디서 확인했는가"를 적는다. 이 세션의 네트워크 정책상 접근이 차단된 호스트(dl.google.com, source.android.com, qemu.org, gitlab.freedesktop.org, docs.samsungknox.com 등)의 내용은 웹 검색 스니펫이나 GitHub 미러 소스로 대신 확인했고, 그 경우 그렇게 표기한다.

## 확인한 사실

- **F1 — Galaxy S24 이후 64비트 앱만 지원.** Samsung Knox KBA-1150 "Sunsetting 32-bit app support on Samsung devices": "Starting with the release of the Galaxy S24 ... Samsung devices exclusively support 64-bit apps ... 32-bit apps won't be supported for the Galaxy S24 series and all future Galaxy series." (검색 스니펫; 원문 호스트 docs.samsungknox.com은 이 세션에서 차단됨.)
- **F2 — Snapdragon 8 Gen 3는 AArch32 코어 없음, OEM은 번역기(Tango) 사용.** Android Authority 2023-10-31: "Qualcomm's Snapdragon 8 Gen 3 does not have any CPU cores that support AArch32"; Xiaomi 14/OnePlus 12가 Amanieu Systems의 Tango(32→64비트 바이너리 번역기)를 탑재. Snapdragon 8 Elite 제품 브리프는 Oryon을 "64-bit Architecture"로만 표기(AArch32 유무를 명시하지 않음 → CPU 수준 확인은 U1으로 기기에서 측정).
- **F3 — 앱 도메인 SELinux 정책.** LineageOS 미러 `android_system_sepolicy`(2026-10-06 커밋 기준):
  - `private/app.te:213` `allow appdomain self:process execmem;` → JIT 가능.
  - `private/app_neverallows.te` "Block calling execve() on files in an apps home directory ... For compatibility, allow for targetApi <= 28" → targetSdk 29+ 앱은 데이터 디렉터리에서 execve 불가. 반면 `nativeLibraryDir`(APK에서 추출된 lib)의 실행 파일은 이 규칙의 대상이 아님.
  - `private/untrusted_app_all.te:27` `allow untrusted_app_all app_data_file:file { r_file_perms execute };` → mmap(PROT_EXEC)은 가능.
  - `private/seapp_contexts` minTargetSdkVersion별 도메인 분리(29/30/32/34/37).
- **F4 — /dev/kvm 접근 불가.** 같은 소스 `private/crosvm.te:8-10`: `neverallow { domain -crosvm_domain -ueventd -shell } kvm_device:chr_file getattr;` 및 `neverallow { domain -crosvm_domain -ueventd } kvm_device:chr_file ~getattr;`.
- **F5 — AVF 권한은 서드파티 불가.** LineageOS 미러 `android_packages_modules_Virtualization` `android/android.system.virtualmachine.res/AndroidManifest.xml:26-27`: `android.permission.MANAGE_VIRTUAL_MACHINE` `protectionLevel="signature|preinstalled|development"`. README(검색 스니펫): "they are all @SystemApi and require the restricted android.permission.MANAGE_VIRTUAL_MACHINE permission, so they are not available to third party apps."
- **F6 — QEMU 멀티터치.** QEMU v11.1.3 소스 `qapi/ui.json:1116` `InputMultiTouchEvent`(Since 8.1), `hw/virtio/virtio-input-pci.c:32` `virtio-multitouch-pci`, `ui/input.c:531` `qemu_input_queue_mtt`. `ui/sdl2.c`에는 touch/finger 처리 없음; `ui/gtk.c:1198` `gd_touch_event`만 멀티터치 지원. (GitHub 미러 `qemu/qemu` 태그 v11.1.3.)
- **F7 — Termux가 QEMU 11.1.3을 Android용으로 빌드.** `termux-packages` 커밋 20ef6a0(2026-11-05) `packages/qemu-system-x86-64-headless/build.sh`: `TERMUX_PKG_VERSION="1:11.1.3"`, 타깃에 `aarch64-softmmu`, `arm-linux-user` 포함, 서브패키지 `qemu-user-arm`, `qemu-system-aarch64-headless`, 21개 Android 패치, `TERMUX_PKG_LICENSE="GPL-2.0"`. Termux는 루팅이 필요 없는 일반 앱이다.
- **F8 — virglrenderer on Android(ANGLE).** 같은 저장소 `packages/virglrenderer-android/build.sh`: virglrenderer 1.3.0 + libepoxy 1.5.10, `TERMUX_PKG_DEPENDS="angle-android"`, 패치 `0003-use-angle`, `0004-Fallback-to-ashmem`, `0008-Impl-DXTn-S3TC-texture-decompression`.
- **F9 — Limbo 상태.** `limboemu/limbo` 마지막 커밋 2022-03-09, `README.developers`: QEMU 5.1.0/2.9.1, NDK r14b/r23b, SDL2 2.0.8, glib 2.56.1. QEMU를 `.so`로 로드해 `qemu_init`/`qemu_main_loop`를 dlsym으로 호출(`vm-executor-jni.c:230-262`).
- **F10 — PC 실험 결과.** `experiments/pc-tcg-aarch32/RESULTS.md`. AArch64 게스트(Ubuntu 6.8 커널) 안에서 정적 arm32 바이너리가 `cortex-a57/a76/neoverse-n1/max` 모델 모두에서 정상 종료(체크섬 일치). TCG 비용: 정수 1.2~1.9배, FP 약 10배, 메모리 3~15배.
- **F11 — 검은방4 안드로이드판.** 안드로이드판은 "검은방4 - 밀실탈출"만 존재(시리즈 중 유일), EA모바일 코리아가 2011-10-17 T스토어 등 이통 3사 오픈마켓에 출시(디지털투데이·인벤 기사, 나무위키). 2014-06-30 서비스 종료(나무위키). → 통신사 스토어 DRM/인증 서버 의존 가능성(U9).
- **F12 — N.O.V.A. 3 패키지명.** 원작(2012) `com.gameloft.android.ANMP.GloftN3HM`(이탈리아 매체 2건), Freedom Edition은 `com.gameloft.android.ANMP.GloftNAHM`(APKMirror, minSdk 21). ABI/minSdk는 실제 APK 분석 전까지 미확정(U10).
- **F13 — 64비트 전용 환경.** developer.android.com "Support 64-bit architectures": Pixel 7이 최초 64비트 전용 출시 기기, API 31+ 에뮬레이터 이미지는 64비트 전용, `adb install --abi`로 ABI 강제 가능.
- **F14 — glib/bionic.** glib 2.80 `meson.build:469` `host_system != 'android'`에서 statx 테스트 제외; bionic에 libintl 없음(빌드 로그 `ggettext.c:45 'libintl.h' file not found`) → proxy-libintl로 해결.
- **F15 — QEMU CPU 모델의 AArch32 EL0.** F10 실험으로 확인(게스트 `/proc/cpuinfo` CPU part 0xd07/0xd0b/0xd0c/0x051, 32비트 ELF 실행 성공).

## 아직 검증하지 못한 가정

- **U1** Galaxy S25 Ultra 실제 값: `SUPPORTED_32_BIT_ABIS`(비어 있을 것으로 예상), 커널 버전(6.6.x로 보도됨, 미확인), `/dev/kvm` 접근 결과, `virtualization_framework` 기능 플래그. → 앱 "Host check" 버튼으로 측정.
- **U2** `virt` 보드에서 virtio-gpu까지 부팅하는 Android 게스트 이미지 확보 방법(GKI 커널 + GSI arm64 multilib + vendor 구성). 미착수.
- **U3** Android 호스트에서 virglrenderer+ANGLE 동작 여부와 성능.
- **U4** 오디오 백엔드(AAudio) 구현 필요.
- **U5/U6** 기기에서의 2D/3D 게임 성능.
- **U7** Samsung S25의 AVF 제공 여부(앱이 쓸 수 없으므로 결과와 무관).
- **U8** AOSP berberis의 arm32 게스트 프런트엔드 유무(소스 미열람).
- **U9** 검은방4의 DRM/네트워크 인증 의존.
- **U10** N.O.V.A. 3 APK의 minSdk/targetSdk/ABI/OBB 구조.
