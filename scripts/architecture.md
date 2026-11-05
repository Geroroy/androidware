# 아키텍처 (M0 시점)

```
+------------------------------------------------------------------+
| Android app (dev.androidware.app, Java, targetSdk 35, minSdk 29) |
|                                                                  |
|  MainActivity ── VncView (RFB 3.8 client, Raw/CopyRect)          |
|       │            └─ touch → RFB PointerEvent (single pointer)  |
|       │            └─ multi-touch → QmpClient.sendTouch (mtt)    |
|       ├─ HostInfo (ABI/kernel/kvm/AVF 진단)                       |
|       ├─ ImportManager (SAF → files/guest, files/games; 스트리밍)  |
|       ├─ ApkInspector (패키지/SDK/ABI/자산 분석, M2 기초)            |
|       └─ GuestProfile (files/profiles/*.json)                     |
|                                                                  |
|  EngineService (foreground) ── ProcessBuilder ──┐                |
|       └─ QemuLauncher (명령행 조립)              │                |
|       └─ QmpClient (quit, input-send-event)      │                |
+-------------------------------------------------│----------------+
                                                  ▼ exec
   nativeLibraryDir/libqemu-system-aarch64.so  (ELF 실행 파일, GPL)
        -M virt -cpu cortex-a57 -accel tcg,thread=multi
        -vnc unix:files/run/vnc.sock  -qmp unix:files/run/qmp.sock
        -serial file:files/run/serial.log  -nic none
        [-device virtio-gpu-pci -device virtio-multitouch-pci -device virtio-keyboard-pci]
        [-drive virtio-blk]  [-kernel/-initrd]
                                                  ▼
        게스트 커널(AArch64, COMPAT) → 32비트/64비트 사용자 공간
```

분리 원칙(요구사항): 엔진(`engine/`), 앱 UI(`app/.../MainActivity`, `VncView`), 게임 프로필(`GuestProfile`, 이후 `games/` 프로필), 파일 가져오기(`ImportManager`), 진단(`HostInfo`, `ApkInspector`, 로그 내보내기)을 별도 클래스/디렉터리로 둔다.

## 대용량 파일 처리
- 가져오기는 1 MiB 버퍼 스트리밍 복사(`ImportManager.copyToDir`), `.part` 임시 파일 후 rename.
- APK 분석은 zip 중앙 디렉터리와 `PackageManager.getPackageArchiveInfo`만 사용(압축 해제 없음).

## 프로세스/데이터 정리
- `EngineService.stop()`: QMP `quit` → 3초 대기 → `destroy()` → 2초 → `destroyForcibly()`; 소켓 파일 삭제.
- `onTaskRemoved`/`onDestroy`에서 동일 정리. 게스트는 서비스보다 오래 살지 않는다.
- 게스트 네트워크 기본 `-nic none`.

## 나중에 교체될 부분
- VNC → virtio-gpu 스캔아웃을 SurfaceView/AHardwareBuffer로 직접 전달하는 QEMU 디스플레이 백엔드(가능하면 SDL2 Android 포트 재사용).
- 오디오: QEMU에 AAudio 백엔드 추가 또는 SDL2 오디오.
- 게스트 Android 이미지 + virgl(호스트 ANGLE/EGL) 경로.
