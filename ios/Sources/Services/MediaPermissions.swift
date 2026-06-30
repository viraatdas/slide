import AVFoundation
import UIKit

/// Explicit permission preflight for call media. Asking LiveKit to publish a
/// microphone from a cold/background CallKit answer cannot present the system
/// prompt reliably, so the control plane should fail cleanly when access has
/// never been granted instead of accepting a silent call.
@MainActor
enum MediaPermissions {
    static func requestMicrophoneAccess() async -> Bool {
        await requestAccess(for: .audio)
    }

    static func requestCameraAccess() async -> Bool {
        await requestAccess(for: .video)
    }

    private static func requestAccess(for mediaType: AVMediaType) async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: mediaType) {
        case .authorized:
            return true
        case .denied, .restricted:
            return false
        case .notDetermined:
            // Permission UI cannot be presented from a background PushKit
            // launch. The next foreground call attempt can ask normally.
            guard UIApplication.shared.applicationState == .active else { return false }
            return await withCheckedContinuation { continuation in
                AVCaptureDevice.requestAccess(for: mediaType) { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }
}

enum CallAcceptanceError: Error {
    case microphoneDenied
}
