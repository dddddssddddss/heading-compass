import Foundation

// 플랫폼 무관 방향 계산. Android의 HeadingFilter.kt와 같은 로직이다.
//
// 1) OS가 합친 자세(회전행렬)에서 "바라보는 방향"을 구한다.
//    폰을 눕히면 폰 윗변, 세우면 후면 카메라가 향하는 쪽. 오른쪽 모서리 기준이라 기울기와 무관하다.
// 2) 그 값을 그대로 쓰지 않고, 자이로 회전량으로 예측한 뒤 나침반 쪽으로 천천히 당긴다.
//    자기 간섭이 감지되면 당기기를 멈추고 자이로만으로 버틴다.

enum Pose: String, Codable { case flat, upright, tilted, faceDown }
enum FacingAxis: String, Codable { case top, camera, mixed }
enum Quality: String, Codable { case good, poor, unknown }

struct FusionInput {
    /// 단조 증가 시각 (초)
    var timestamp: Double
    /// 기기 → 지구(동, 북, 위) 회전행렬, 행 우선 9개. v_world = M · v_device
    var deviceToENU: [Double]
    /// 자이로 회전 속도 (rad/s, 기기 좌표)
    var rotationRate: (x: Double, y: Double, z: Double)
    /// 보정된 자기장 세기 (µT). 모르면 nil
    var fieldMicroTesla: Double?
    /// 자력계가 보정 안 된 상태인지
    var magnetometerUncalibrated: Bool
    /// OS가 알려준 방향 오차 (°). 모르면 nil
    var osAccuracyDeg: Double?
}

struct FusionOutput {
    var magneticHeading: Double
    var quality: Quality
    var accuracyDeg: Double?
    /// 자기 간섭 때문에 나침반 보정을 멈추고 자이로만으로 추적 중
    var gyroHold: Bool
    var pose: Pose
    var axis: FacingAxis
}

final class HeadingFilter {
    private var heading: Double?          // 필터된 자북 기준 방향
    private var lastT: Double?
    private var lastOS: Double?
    private var mismatch = 0.0            // 나침반 회전 속도와 자이로 회전 속도 차이 (°/s, 평활)
    private var disturbedUntil = 0.0

    func reset() {
        heading = nil; lastT = nil; lastOS = nil; mismatch = 0; disturbedUntil = 0
    }

    func update(_ input: FusionInput) -> FusionOutput? {
        let m = input.deviceToENU
        let t = input.timestamp
        var dt = lastT.map { t - $0 } ?? 0
        lastT = t
        if !(dt > 0 && dt < 0.2) { dt = 0 }

        // 기기 축의 지구 좌표 = 행렬의 열. 지구 '위'의 기기 좌표 = 3번째 행.
        let xE = m[0], xN = m[3]                 // 폰 오른쪽 모서리(+X)
        let yE = m[1], yN = m[4]                 // 폰 윗변(+Y)
        let zE = m[2], zN = m[5], zU = m[8]      // 화면 바깥(+Z), 후면 카메라는 −Z
        let up = (x: m[6], y: m[7], z: m[8])

        // 바라보는 방향 = 오른쪽 모서리의 수평 방향을 왼쪽으로 90° 돌린 쪽.
        // 앞뒤·좌우로 기울여도 오른쪽 모서리의 수평 방향은 그대로라 기울기에 영향받지 않는다.
        // 폰을 옆으로 세워 오른쪽 모서리가 수직이면 나침반 값 없이 자이로로만 이어 간다.
        let osHeading: Double? = hypot(xE, xN) < 0.2 ? nil : wrap360(atan2(-xN, xE) * 180 / .pi)

        let pose: Pose = zU > 0.82 ? .flat : zU < -0.82 ? .faceDown : abs(zU) < 0.42 ? .upright : .tilted
        let wTop = hypot(yE, yN), wCam = hypot(zE, zN)
        let axis: FacingAxis = wTop > wCam * 2 ? .top : wCam > wTop * 2 ? .camera : .mixed

        // 지구 수직축 기준 회전 속도. 위에서 봤을 때 시계 방향이 방향 증가.
        let w = input.rotationRate
        let yawRate = -(w.x * up.x + w.y * up.y + w.z * up.z) * 180 / .pi

        // 자기 간섭 감지 1: 나침반은 도는데 자이로는 안 돈다 (또는 반대)
        if let os = osHeading, let prev = lastOS, dt > 0 {
            let osRate = wrap180(os - prev) / dt
            mismatch += (abs(osRate - yawRate) - mismatch) * 0.3
            if mismatch > 30 { disturbedUntil = t + 1.0 }
        }
        lastOS = osHeading

        // 자기 간섭 감지 2: 자기장 세기가 지구 자기장 범위를 벗어남
        if let f = input.fieldMicroTesla, !HeadingConfig.fieldRangeMicroTesla.contains(f) {
            disturbedUntil = t + 1.0
        }
        let disturbed = t < disturbedUntil

        let quality: Quality
        if disturbed || input.magnetometerUncalibrated || (input.osAccuracyDeg ?? 0) > 30 {
            quality = .poor
        } else if input.fieldMicroTesla != nil && (input.osAccuracyDeg ?? 0) <= 20 {
            quality = .good
        } else {
            quality = .unknown
        }

        // 예측: 자이로 회전량만큼 돌린다
        if var h = heading, dt > 0 {
            h += yawRate * dt
            heading = wrap360(h)
        }

        // 보정: 나침반 쪽으로 당긴다. 품질이 나쁘면 당기지 않는다.
        if let os = osHeading {
            if let h = heading {
                let err = wrap180(os - h)
                let tau: Double? = quality == .good ? 0.6 : quality == .unknown ? 2.0 : nil
                if abs(err) > 90 && quality != .poor {
                    heading = os                                  // 크게 어긋나면 다시 맞춤
                } else if let tau, dt > 0 {
                    heading = wrap360(h + err * (1 - exp(-dt / tau)))
                }
            } else {
                heading = os
            }
        }

        guard let h = heading else { return nil }
        return FusionOutput(magneticHeading: h, quality: quality, accuracyDeg: input.osAccuracyDeg,
                            gyroHold: disturbed, pose: pose, axis: axis)
    }
}

func wrap360(_ d: Double) -> Double { let r = d.truncatingRemainder(dividingBy: 360); return r < 0 ? r + 360 : r }
func wrap180(_ d: Double) -> Double { wrap360(d + 180) - 180 }
