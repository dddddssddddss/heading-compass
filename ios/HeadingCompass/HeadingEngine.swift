import Foundation
import CoreMotion
import CoreLocation

/// iOS 센서 → HeadingFilter → HeadingSample.
///
/// - CMDeviceMotion (.xMagneticNorthZVertical): OS가 자이로·가속도·자력계를 합친 자세, 보정된 자기장, 자이로
/// - CLHeading: OS의 방향 오차(headingAccuracy), 자편각(trueHeading − magneticHeading)
///   위치 권한 없이도 방향 이벤트는 오며, 이때 trueHeading이 없으면 기본 자편각을 쓴다.
final class HeadingEngine: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var sample: HeadingSample?
    @Published private(set) var statusMessage: String?

    private let motion = CMMotionManager()
    private let location = CLLocationManager()
    private let filter = HeadingFilter()
    private let reporter = HeadingReporter()

    private var osAccuracyDeg: Double?
    private var declination = HeadingConfig.defaultDeclinationDeg
    private var running = false

    func start() {
        guard !running else { return }
        let frame = CMAttitudeReferenceFrame.xMagneticNorthZVertical
        guard motion.isDeviceMotionAvailable,
              CMMotionManager.availableAttitudeReferenceFrames().contains(frame) else {
            statusMessage = "이 기기는 방향 센서를 지원하지 않습니다"
            return
        }
        running = true
        filter.reset()

        motion.deviceMotionUpdateInterval = 1.0 / 50
        motion.startDeviceMotionUpdates(using: frame, to: .main) { [weak self] dm, error in
            guard let self else { return }
            if let dm { self.handle(dm) } else if error != nil { self.statusMessage = "센서를 읽을 수 없습니다" }
        }

        location.delegate = self
        location.headingFilter = kCLHeadingFilterNone
        location.headingOrientation = .portrait
        if CLLocationManager.headingAvailable() { location.startUpdatingHeading() }
    }

    func stop() {
        guard running else { return }
        running = false
        motion.stopDeviceMotionUpdates()
        location.stopUpdatingHeading()
    }

    private func handle(_ dm: CMDeviceMotion) {
        let enu = deviceToENU(dm)
        let field = dm.magneticField
        let uncalibrated = field.accuracy == .uncalibrated
        let magnitude = uncalibrated ? nil : sqrt(field.field.x * field.field.x + field.field.y * field.field.y + field.field.z * field.field.z)

        let input = FusionInput(
            timestamp: dm.timestamp,
            deviceToENU: enu,
            rotationRate: (dm.rotationRate.x, dm.rotationRate.y, dm.rotationRate.z),
            fieldMicroTesla: magnitude,
            magnetometerUncalibrated: uncalibrated,
            osAccuracyDeg: osAccuracyDeg)
        guard let out = filter.update(input) else { return }

        let s = HeadingSample(
            heading: wrap360(out.magneticHeading + declination),
            magneticHeading: out.magneticHeading,
            declination: declination,
            accuracyDeg: out.accuracyDeg,
            quality: out.quality,
            gyroHold: out.gyroHold,
            pose: out.pose,
            axis: out.axis,
            ts: Int64(Date().timeIntervalSince1970 * 1000))
        sample = s
        statusMessage = nil
        reporter.offer(s, now: dm.timestamp)
    }

    /// CoreMotion 회전행렬을 "기기 → 동·북·위" 행렬로 바꾼다.
    /// 행렬 방향(기기→기준 / 기준→기기)은 중력 벡터와 맞는 쪽을 골라 정한다.
    private func deviceToENU(_ dm: CMDeviceMotion) -> [Double] {
        let r = dm.attitude.rotationMatrix
        let a = [r.m11, r.m12, r.m13, r.m21, r.m22, r.m23, r.m31, r.m32, r.m33]
        let b = [r.m11, r.m21, r.m31, r.m12, r.m22, r.m32, r.m13, r.m23, r.m33]
        let g = dm.gravity
        let up = [-g.x, -g.y, -g.z]
        // v_ref = C · v_device 이면 기준 좌표 '위'의 기기 좌표는 C의 3번째 행
        let dotA = a[6] * up[0] + a[7] * up[1] + a[8] * up[2]
        let dotB = b[6] * up[0] + b[7] * up[1] + b[8] * up[2]
        let c = dotA >= dotB ? a : b
        // 기준 좌표: x = 자북, y = 서, z = 위  →  동 = −y, 북 = x, 위 = z
        return [-c[3], -c[4], -c[5],
                 c[0],  c[1],  c[2],
                 c[6],  c[7],  c[8]]
    }

    // MARK: CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        osAccuracyDeg = newHeading.headingAccuracy >= 0 ? newHeading.headingAccuracy : nil
        if newHeading.trueHeading >= 0 {
            declination = wrap180(newHeading.trueHeading - newHeading.magneticHeading)
        }
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool { true }
}
