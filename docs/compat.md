# 호환성 표 (실측만 기록)

| 게임 | 기기 | 게스트 | 설치 | 앱 실행 | 타이틀 진입 | 실제 플레이 | FPS/프레임타임 | 메모리 | 비고 |
|---|---|---|---|---|---|---|---|---|---|
| (없음) | — | — | — | — | — | — | — | — | 실제 기기 측정 전. APK 미제공. |

측정 방법(예정): 게스트 안에서 `dumpsys gfxinfo <pkg>`/`SurfaceFlinger --latency`로 프레임타임, 호스트에서 `adb shell dumpsys meminfo dev.androidware.app` 및 QEMU 프로세스 RSS, 기기 조건(온도/배터리/성능 모드)을 함께 기록한다.

## 엔진 단독 스모크 테스트 (게임 없이)

| 항목 | 결과 | 조건 |
|---|---|---|
| PC(x86_64)에서 AArch64 게스트 + AArch32 사용자 공간 | 성공 (4개 CPU 모델) | `experiments/pc-tcg-aarch32/RESULTS.md` |
| Galaxy S25 Ultra(SM-S938N, Android 16)에서 엔진 실행 + 게스트 커널 부팅 + 게스트 안 32비트 실행 | **성공** (2회, 2026-11-10) | 수치는 `docs/status.md` "기기 실측 4차" |
| PC(x86_64)에서 Android 7.0 armv7 게스트 부팅(`virt,goldfish=on`) | **성공**: boot_completed 75~85초(smp 2), 홈 화면, 터치·키 | `experiments/android-guest/RESULTS.md` |
| PC에서 게스트 안 32비트 GLES 1.x 테스트 앱 | **성공**: 설치·실행, 소프트웨어 GL 약 20 fps(480x800 전체 갱신) | 같은 문서. 게임 아님 |
| PC에서 APK 자동 설치·실행(전달 디스크), SD 카드, 오디오 PCM 출력 | **성공** (2026-11-10 세션 3) | 같은 문서 "세션 3" |
| S25 Ultra에서 Android 7.0 게스트 | **홈 화면 부팅 + 터치 동작** (2026-11-10, 0.0.7-m1, 사용자 스크린샷). 게임·소리 미확인 | `docs/device-test-procedure.md` M1 절 |
