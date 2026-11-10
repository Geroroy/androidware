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
