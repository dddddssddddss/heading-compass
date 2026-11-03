# 방향 앱 (iOS / Android)

사용자가 바라보는 방향을 휴대폰 센서만으로(GPS 없이) 계산한다. 두 앱은 같은 계산 로직과 같은 출력 형식을 쓴다.

## 실행

- **iOS**: `ios/HeadingCompass.xcodeproj`를 Xcode로 열고, Signing & Capabilities에서 Team을 고른 뒤 실기기로 실행. 시뮬레이터에는 센서가 없다. 번들 ID `com.example.headingcompass`는 필요하면 바꾼다.
- **Android**: `android/` 폴더를 Android Studio로 열면 Gradle 9.8.2를 받아 동기화한다. 명령줄에서 `./gradlew`를 쓰려면 한 번 `gradle wrapper`로 래퍼 파일을 만든다.

## 계산 방식

1. **OS 센서 퓨전**: iOS `CMDeviceMotion`(`.xMagneticNorthZVertical`), Android `TYPE_ROTATION_VECTOR`. 자이로·가속도·자력계를 OS가 합친 자세를 받는다.
2. **바라보는 방향**: 폰을 눕히면 폰 윗변, 세우면 후면 카메라가 향하는 쪽. 두 축의 수평 성분을 더해 자세가 바뀌어도 값이 튀지 않는다.
3. **자이로 예측 + 나침반 보정**: 지구 수직축 기준 자이로 회전량으로 방향을 예측하고, 나침반 쪽으로 천천히 당긴다(품질 좋음 0.6초, 확인 중 2초 시정수).
4. **자기 간섭 감지 → 자이로로 버티기**: 아래 중 하나면 1초간 나침반 보정을 멈춘다(`gyroHold: true`, `quality: "poor"`).
   - 나침반 회전 속도와 자이로 회전 속도 차이가 30°/s 이상 (자이로는 안 도는데 나침반만 돈다)
   - 자기장 세기가 25~70µT 범위 밖 (철골, 자석, 전자기기)
5. **진북 보정**: iOS는 `CLHeading`의 진북−자북 차이(없으면 −9°), Android는 `GeomagneticField`로 서울 좌표 기준 계산.

설정값은 `HeadingConfig.swift` / `HeadingConfig.kt`에 있다.

## 백엔드 전송

`HeadingConfig`의 endpoint가 비어 있으면 보내지 않는다. 주소를 넣으면 5° 이상 바뀌었거나 1초가 지났을 때 POST한다.

```json
{
  "deviceId": "앱 설치마다 고정된 UUID",
  "platform": "ios | android",
  "sample": {
    "heading": 47.5,
    "magneticHeading": 56.5,
    "declination": -9.0,
    "accuracyDeg": 12.0,
    "quality": "good | poor | unknown",
    "gyroHold": false,
    "pose": "flat | upright | tilted | faceDown",
    "axis": "top | camera | mixed",
    "ts": 1793680506183
  }
}
```

- `heading`: 진북 기준 바라보는 방향(0~360°). 서버는 이 값을 쓴다.
- `quality`가 `poor`인 값은 믿지 않는다. `ts`가 몇 초 지난 값은 현재 방향으로 쓰지 않는다.
- `accuracyDeg`: OS가 알려준 오차. 모르면 `null`.

## 한계

- 앱이 화면에 떠 있는 동안만 측정·전송한다(백그라운드 미지원).
- 폰을 주머니에 넣으면 폰 방향과 몸 방향이 다르다(`pose`로 구분).
