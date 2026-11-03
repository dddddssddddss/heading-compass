import Foundation

/// HeadingConfig.endpoint로 방향을 POST한다. 주소가 없으면 아무것도 하지 않는다.
/// 5° 이상 바뀌었거나 1초가 지났을 때만 보내고, 이전 요청이 끝나기 전에는 새로 보내지 않는다.
final class HeadingReporter {
    private struct Payload: Encodable {
        var deviceId: String
        var platform = "ios"
        var sample: HeadingSample
    }

    private let deviceId: String
    private var lastHeading: Double?
    private var lastTime = 0.0
    private var inFlight = false
    private let encoder = JSONEncoder()

    init() {
        let key = "deviceId"
        if let id = UserDefaults.standard.string(forKey: key) {
            deviceId = id
        } else {
            deviceId = UUID().uuidString
            UserDefaults.standard.set(deviceId, forKey: key)
        }
    }

    func offer(_ sample: HeadingSample, now: Double) {
        guard let url = HeadingConfig.endpoint, !inFlight else { return }
        if let last = lastHeading,
           abs(wrap180(sample.heading - last)) < HeadingConfig.sendMinDeltaDeg,
           now - lastTime < HeadingConfig.sendMaxInterval { return }
        guard let body = try? encoder.encode(Payload(deviceId: deviceId, sample: sample)) else { return }

        lastHeading = sample.heading
        lastTime = now
        inFlight = true

        var req = URLRequest(url: url, timeoutInterval: 3)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        URLSession.shared.dataTask(with: req) { [weak self] _, _, _ in
            DispatchQueue.main.async { self?.inFlight = false }
        }.resume()
    }
}
