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

## M1: Android 7.0 게스트 + 게임 (앱 0.0.11-m1)

준비: `adb install -r dist/androidware-0.0.11-m1.apk`(서명 키가 바뀌었으면 먼저 `adb uninstall dev.androidware.app`). 기기에 약 3 GB 여유 공간.

| 단계 | 조작 | 기대 결과 | 기록할 것 |
|---|---|---|---|
| A1 | **Android 7 guest…** → Download (또는 PC에서 받은 `armeabi-v7a-24_r07.zip`을 Import) | 로그에 진행률, 마지막에 `Android guest installed in .../files/android24` | 다운로드·압축 해제 시간 |
| A2 | **Start Android** | 명령행에 `-M virt,highmem=off,goldfish=on -cpu cortex-a15`, 화면에 부팅 로고("android") → 홈 화면 | 부팅 로고가 뜬 시각, 홈 화면이 뜬 시각(시작부터 초) |
| A3 | 홈 화면에서 앱 서랍(가운데 아래 버튼) 탭, Settings 실행, 기기 BACK 버튼 | 탭 위치대로 반응, BACK은 게스트 뒤로가기 | 체감 지연(탭→화면 변화) |
| A4 | **Export log** | `serial.log`에 `init:` 로그, 오류 시 `qemu.log` | 파일 첨부 |
| A5 | **Stop guest** 후 다시 **Start Android** | 두 번째 부팅(데이터 유지) | 두 번째 부팅 시간 |

판정: A2에서 홈 화면까지 뜨면 "기기에서 Android 게스트 부팅" 검증 완료 → `docs/compat.md` 갱신.

### 호스트 GPU 모드 (GLES 2.0, 실험적)

"Android 7 guest…" → **Graphics**를 눌러 Host GPU로 바꾼 뒤 Start Android. 로그에 `host GPU renderer: <벤더> / <GPU> / <버전>`이 찍히고 화면이 뜨면 성공. 화면이 검거나 앱이 종료되면 Graphics를 다시 눌러 Software로 되돌리고, Export log와 함께(가능하면 `adb logcat -s awgl`) 알려 준다. GLES 2.0 게임(N.O.V.A. 3)은 이 모드에서만 실행된다. 검은방(GLES 1.x)은 Software 모드를 먼저 쓴다.

### 게임 실행 (PC 불필요)

| 단계 | 조작 | 기대 결과 | 기록할 것 |
|---|---|---|---|
| G1 | 게스트가 꺼진 상태에서 **Import game APK** → 게임 APK 선택 | APK 분석 출력 뒤 `game <패키지> registered: ...` | 분석 출력 |
| G2 | **Start Android** | 로그 `guest games: <패키지> (auto-start)`. 홈 화면이 뜬 뒤 수십 초 안에 게임이 자동으로 뜬다(첫 부팅에서만 설치, 이후 부팅은 실행만) | 홈 화면 시각, 게임 화면 시각 |
| G3 | 게임 조작 | 터치 반응, 소리 | 타이틀 진입 / 실제 플레이 여부, 소리 유무·지연, 멈춤 여부 |
| G3b | 게임이 "has stopped"로 죽으면 | 앱 로그 화면에 `[guest] awcrash: …` 줄(Java `FATAL EXCEPTION` 또는 네이티브 `F/DEBUG` backtrace). Start Android 직후 `graphics:` 줄과 GLES 경고도 확인 | Export log 파일 |
| G4 | **Stop guest** → **Export log** | `serial.log`에 `androidware: install <패키지>: Success`(또는 실패 사유), `androidware: starting <패키지>` | 로그 파일 |

게임이 뜨지 않으면 `serial.log`의 `androidware:` 줄과 그 뒤 로그가 원인 파악의 출발점이다(설치 실패 사유는 `Failure [...]`로 찍힌다).
