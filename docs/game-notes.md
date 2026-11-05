# 대상 게임 메모 (APK 분석 전)

이름만으로 호환성을 단정하지 않는다. 아래는 공개 자료로 확인한 범위이며, 실제 APK/데이터가 제공되면 앱의 "Inspect game APK"(`ApkInspector`) 결과로 대체한다.

## N.O.V.A. 3 (Gameloft, 2012)
- 패키지명: `com.gameloft.android.ANMP.GloftN3HM` (원작). Freedom Edition은 `com.gameloft.android.ANMP.GloftNAHM`이며 별개 빌드(APKMirror 기준 minSdk 21). (F12)
- 미확인(U10): 원작 APK의 minSdk/targetSdk, `lib/armeabi-v7a` 구성(libnova3 계열 단일 대형 .so일 것으로 추정), 데이터 다운로드 방식(설치 후 Gameloft 서버에서 추가 리소스를 받는 구조였다면 서버 종료가 실행 불가의 원인일 수 있음 → VM 문제와 분리해 기록).
- 예상 병목: 네이티브 C++ 엔진(FP/NEON 집약) → TCG에서 약 10배 저하(F10). GLES 2.0 렌더링은 virgl 경로가 필요.
- 확인 항목(M2): 타이틀 진입, 실제 3D 플레이, 화면, 오디오, 멀티터치(이동+조준 동시 입력).

## 검은방 4: 밀실탈출 (EA모바일 코리아, 2011)
- 시리즈 중 유일한 안드로이드판. 2011-10-17 T스토어 등 이통 3사 오픈마켓 출시, 2014-06-30 서비스 종료. (F11)
- 미확인(U9): 통신사 스토어 DRM(ARM: Application Rights Management) 및 실행 시 인증 서버 접속 여부. 종료된 서버 때문에 실행이 막히면 이는 VM 문제가 아니라 배포 채널 문제이며 별도로 기록한다.
- 미확인: 네이티브 라이브러리 유무(순수 Dalvik이면 TCG 부담이 훨씬 적음), 영상 코덱(컷신), 한글 폰트 의존(게스트 이미지에 한글 폰트 포함 필요), 저장 데이터 경로.
- 확인 항목(M2): 한글 표시, 영상 재생, 터치 반응, 저장·불러오기.
