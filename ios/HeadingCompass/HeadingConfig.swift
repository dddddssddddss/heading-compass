import Foundation

enum HeadingConfig {
    /// 방향을 받을 백엔드 주소. nil이면 아무것도 보내지 않는다.
    /// 예: URL(string: "https://example.com/api/heading")
    static let endpoint: URL? = nil

    /// 이 각도 이상 바뀌었거나
    static let sendMinDeltaDeg = 5.0
    /// 이 시간이 지나면 전송 (초)
    static let sendMaxInterval = 1.0

    /// 진북 보정값을 OS에서 못 받을 때 쓰는 기본 자편각 (서울 약 −9°, 서쪽이 음수)
    static let defaultDeclinationDeg = -9.0

    /// 지구 자기장 세기 정상 범위 (µT). 한국은 약 50µT. 벗어나면 자기 간섭으로 본다.
    static let fieldRangeMicroTesla = 25.0...70.0
}
