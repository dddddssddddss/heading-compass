import Foundation

/// 앱이 내보내는 방향 한 건. 백엔드로 보내는 JSON과 같다 (Android와 동일 형식).
struct HeadingSample: Codable {
    /// 진북 기준 바라보는 방향 (0~360°)
    var heading: Double
    var magneticHeading: Double
    var declination: Double
    /// OS가 알려준 오차 (°). 모르면 null
    var accuracyDeg: Double?
    var quality: Quality
    /// 자기 간섭으로 자이로만으로 추적 중
    var gyroHold: Bool
    var pose: Pose
    var axis: FacingAxis
    /// 측정 시각 (Unix ms)
    var ts: Int64
}

private let directions8 = ["북", "북동", "동", "남동", "남", "남서", "서", "북서"]

extension HeadingSample {
    var directionWord: String { directions8[Int((heading / 45).rounded()) % 8] }

    var qualityWord: String {
        switch quality {
        case .good: return "정확도 좋음"
        case .unknown: return "정확도 확인 중"
        case .poor: return gyroHold ? "주변 자기 간섭, 방향 부정확할 수 있음" : "방향 부정확, 폰으로 8자를 그려 보정하세요"
        }
    }

    var spokenText: String { "\(directionWord), \(Int(heading.rounded()) % 360)도, \(qualityWord)" }
}
