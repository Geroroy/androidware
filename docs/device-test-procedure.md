# 기기 테스트 절차 (M0 스모크 테스트)

목적: 루팅하지 않은 Galaxy S25 Ultra에서 (1) 호스트 사실 측정, (2) QEMU 엔진 실행, (3) AArch64 게스트 커널 부팅, (4) 게스트 안에서 32비트 ARM 바이너리 실행을 확인한다. 게임은 아직 쓰지 않는다.

## 준비
1. PC에서 `scripts/build-engine.sh` → `scripts/build-apk.sh` → `scripts/fetch-test-guest.sh`.
2. `adb push dist/test-guest/vmlinuz-arm64 dist/test-guest/initrd-test.img /sdcard/Download/`
3. `adb install -r app/build/androidware-debug.apk` (또는 `dist/`의 APK).

## 절차와 기대 결과
| 단계 | 조작 | 기대 결과 | 기록할 것 |
|---|---|---|---|
| 1 | 앱 실행 → **Host check** | `supported_32bit_abis: (none)`, `/dev/kvm: absent` 또는 `open=...EACCES`, `virtualization_framework: true/false`, 커널 버전 | 전체 출력(Export log) |
| 2 | **Import kernel** → `vmlinuz-arm64`, **Import initrd** → `initrd-test.img` | 로그에 `imported -> /data/user/0/dev.androidware.app/files/guest/...` | 복사 시간 |
| 3 | **Start guest** | 로그에 `$ .../libqemu-system-aarch64.so -L ...` 명령행, 종료 코드 없이 유지, `vnc: server RFB 003.008` | 시작까지 걸린 시간 |
| 4 | 10~60초 대기 후 **Export log** | `serial.log`에 `=== GUEST: aarch64 6.8.0-71-generic`, `exit status of /bench-arm64-bionic = 0`, `exit status of /bench-arm32-bionic = 0`, `=== DONE`, 그리고 `[qemu exited with code 0]` | `int_ms/fp_ms/mem_ms` 값 두 줄(64비트/32비트) — 이것이 기기 최초의 TCG 성능 수치 |
| 5 | 실패 시 | `qemu.log`의 오류(예: `execmem`, `dlopen`, SELinux denial은 `adb logcat | grep avc`) | 그대로 첨부 |

## 판정
- 4단계까지 성공: "엔진 실행 + 게스트 부팅 + AArch32 실행" 기기 검증 완료. `docs/compat.md`의 엔진 스모크 테스트 행을 갱신.
- 3단계에서 즉시 종료: SELinux/exec 문제. `adb logcat -b all | grep -i avc` 결과를 `docs/status.md` 장애물에 기록.
- 4단계에서 `serial.log` 비어 있음: `-serial file:` 경로 권한 또는 커널 콘솔 설정(`console=ttyAMA0`) 확인.

## 다음 단계로 넘어가는 조건
- 32비트 바이너리의 `fp_ms`가 PC 실험 대비 어떤 비율인지 기록(기기 TCG 백엔드 성능의 첫 지표).
