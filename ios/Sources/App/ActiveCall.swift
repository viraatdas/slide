import Foundation
import Combine

/// Observable state for the call currently presented (incoming or in-call).
@MainActor
final class ActiveCall: ObservableObject, Identifiable {
    enum Direction { case incoming, outgoing }
    enum Status { case ringing, dialing, connecting, active, failed, ended }

    let id = UUID()
    /// Stable UUID for CallKit. May be supplied so a call surfaced from a VoIP
    /// push matches the UUID already reported to CallKit.
    let uuid: UUID

    let direction: Direction
    @Published var remoteName: String
    let remotePhone: String
    @Published var remoteUserId: String?
    @Published var isVideo: Bool
    let isKnock: Bool

    /// Whether this is a group call (more than one other participant).
    let isGroup: Bool
    /// Display names of the invited group members (excludes self). For 1:1 this
    /// is just `[remoteName]`.
    @Published var memberNames: [String]

    @Published var status: Status
    @Published var callId: String?
    @Published var session: CallSession?
    /// Mirrors mute changes initiated from the system CallKit UI. The in-call
    /// view observes this and applies it to the media service.
    @Published var systemMuted = false

    /// Increments for every knock tap received from the remote party while this
    /// call is on screen — the ringing UI observes it to thump per tap.
    @Published var knockPulse: Int = 0

    /// The knocker's rhythm: seconds between taps received while this call was
    /// ringing (often while the phone was locked). Replayed as haptics when
    /// the door screen appears — you feel exactly how they knocked.
    var knockRhythm: [Double] = []

    /// Set when the call ends with a reason worth reading ("They can't talk
    /// right now", "Call ended"). The call screen shows it briefly before
    /// dismissing instead of vanishing mid-thought.
    @Published var endMessage: String?

    init(direction: Direction, remoteName: String, remotePhone: String,
         remoteUserId: String?, isVideo: Bool, status: Status,
         isKnock: Bool = false,
         isGroup: Bool = false, memberNames: [String] = [],
         uuid: UUID = UUID()) {
        self.uuid = uuid
        self.direction = direction
        self.remoteName = remoteName
        self.remotePhone = remotePhone
        self.remoteUserId = remoteUserId
        self.isVideo = isVideo
        self.isKnock = isKnock
        self.status = status
        self.isGroup = isGroup
        self.memberNames = memberNames.isEmpty ? [remoteName] : memberNames
    }

    /// Terminal call mutations are idempotent. Return the id on every cleanup
    /// path so a later hangup/view teardown can retry after a transient failed
    /// `/leave` instead of permanently orphaning the server-side call.
    func callIdForBackendResolution() -> String? {
        guard let callId, !callId.isEmpty else { return nil }
        return callId
    }
}
