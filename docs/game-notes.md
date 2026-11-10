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

## 검은방 밀실탈출 (1편) — 사용자 제공 APK 분석 (2026-11-10)

파일: `roomofprey.apk` 6,952,039 B, sha256 `aa114c7a13024c29d4c4ec8bfa6afb72b73f2e497b2b05d1af0a7a1dbdb4af06`. 저장소에는 넣지 않는다. 분석은 PC에서 `aapt2 dump badging`, `readelf`, `nm`, `dexdump`로 수행.

| 항목 | 값 |
|---|---|
| 패키지 | `co.kr.eamobile.roomofprey1`, versionName 01.00.00 (versionCode 10000) |
| 라벨 | "검은방 밀실탈출 (@litfrozen)" → 원본이 아니라 제3자가 수정·재패키징한 빌드 (compileSdk 23으로 재빌드, `lib/armeabi/libroomofprey1.so.bak`·`.idb` 동봉, `.so`와 `.bak`은 3바이트 차이) |
| minSdk / targetSdk | 5 (Android 2.0) / 지정 없음 |
| 네이티브 ABI | **`armeabi`만** (ARMv5TE, 32비트). arm64 없음 → 이 폰에서 직접 설치·실행 불가, 번역 필수 |
| 네이티브 의존 | libc, libstdc++, libm, libdl, liblog, **libGLESv1_CM** (GLES 1.x 고정 파이프라인) |
| GL 사용 | 약 20개 함수: `glOrthof`, `glTexImage2D`, `glTexSubImage2D`, `glDrawArrays`, `glVertexPointer`, `glTexCoordPointer` 등 → 소프트웨어 프레임버퍼를 텍스처로 올려 사각형 하나를 그리는 2D 구조로 보임 |
| Java 측 | `co.kr.eamobile.CletEntry` JNI(nativeInitKernel/InitDisplay/InitVFS/Loop/TouchHandleEvent/KeyHandleEvent 등 19개), `MidasGLSurfaceView`(GLSurfaceView), 오디오는 Java(`AudioTrack`, `MediaPlayer`, `SoundPool`), `VideoView` 참조 있음 |
| 데이터 | `assets/data.vfs.mp3` 3.9 MB (자체 VFS; 확장자는 압축 회피용으로 보임). OBB/추가 다운로드 없음 |
| 화면 | 세로 고정(screenOrientation=portrait), densities 120/160/240, `assets/mtouchdevice.txt`에 SHW-M110S(갤럭시 S)·M180S·M250S·NexusS |
| 통신사 구성 요소 | SKT ARM DRM(`com.skt.arm.aidl.IArmService`, `co.kr.eamobile.drm.SKTActivity`), T ad SDK, FeelingK IAP, 네이티브에 소켓 사용과 `ngw.gxg.com`, `210.222.18.28` 문자열 |
| 권한 | READ_PHONE_STATE, INTERNET, WRITE_EXTERNAL_STORAGE, WRITE_SETTINGS, CALL_PHONE 등 |

판단:
- 2D이고 네이티브 코드가 작으며(약 0.95 MB) GLES 1.x 호출이 단순하다. 기기 실측(정수 약 2배 저하)에 비춰 **CPU 성능 면에서는 유망**하다. GPU 가속(virgl) 없이 게스트 소프트웨어 GLES로도 가능할 수 있다(미검증).
- 게스트 Android 요건: `armeabi` 실행(= armeabi-v7a 지원 게스트면 충분), GLES 1.x, 세로 화면, 텔레포니 기능 선언(`android.hardware.telephony` 암시) → 게스트에서 `READ_PHONE_STATE` 호출이 실패하지 않는지 확인 필요.
- 위험: 원본은 SKT ARM DRM 서비스(T스토어 앱)에 바인딩한다. 이 빌드가 그 검사를 우회하도록 수정된 것인지는 실행해 봐야 안다. 광고/IAP/게임 서버 접속 실패는 VM 문제와 구분해 기록한다(게스트 네트워크 기본 비활성).
- 정정: 이전 메모(F11)의 "안드로이드판은 4편뿐"은 이 APK와 맞지 않는다. 1편의 SKT용 Android 빌드가 존재한다.
