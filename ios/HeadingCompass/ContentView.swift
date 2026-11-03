import SwiftUI
import AVFoundation
import UIKit

/// 화면 전체가 버튼 하나. 탭(VoiceOver는 두 번 탭)하면 현재 방향을 읽어 준다.
struct ContentView: View {
    @StateObject private var engine = HeadingEngine()
    @Environment(\.scenePhase) private var scenePhase
    private let speech = AVSpeechSynthesizer()

    var body: some View {
        Button(action: announce) {
            VStack(spacing: 16) {
                if let s = engine.sample {
                    Text(String(format: "%03d°", Int(s.heading.rounded()) % 360))
                        .font(.system(size: 88, weight: .bold, design: .rounded))
                        .monospacedDigit()
                    Text(s.directionWord)
                        .font(.system(size: 48, weight: .heavy))
                    Text(s.qualityWord)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(s.quality == .poor ? Color.orange : Color.secondary)
                        .multilineTextAlignment(.center)
                } else {
                    Text(engine.statusMessage ?? "방향 측정 준비 중")
                        .font(.title.bold())
                        .multilineTextAlignment(.center)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(engine.sample?.spokenText ?? engine.statusMessage ?? "방향 측정 준비 중")
        .accessibilityHint("두 번 탭하면 현재 방향을 읽습니다")
        .onAppear(perform: resume)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { resume() } else { engine.stop(); UIApplication.shared.isIdleTimerDisabled = false }
        }
    }

    private func resume() {
        engine.start()
        UIApplication.shared.isIdleTimerDisabled = true
    }

    private func announce() {
        let text = engine.sample?.spokenText ?? engine.statusMessage ?? "방향 측정 준비 중"
        if UIAccessibility.isVoiceOverRunning {
            UIAccessibility.post(notification: .announcement, argument: text)
        } else {
            speech.stopSpeaking(at: .immediate)
            let u = AVSpeechUtterance(string: text)
            u.voice = AVSpeechSynthesisVoice(language: "ko-KR")
            speech.speak(u)
        }
    }
}
