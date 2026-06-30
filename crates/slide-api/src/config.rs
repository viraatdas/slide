//! Runtime configuration, loaded from environment (see `.env.example`).

use std::env;

#[derive(Clone)]
pub struct Config {
    pub database_url: String,
    pub redis_url: String,

    pub jwt_secret: String,
    pub access_ttl_secs: i64,
    pub refresh_ttl_secs: i64,
    pub join_ttl_secs: i64,
    /// Maximum time a call may remain unanswered before the durable sweeper
    /// marks it missed and emits terminal events.
    pub call_ring_timeout_secs: i64,

    pub otp_ttl_secs: i64,
    pub otp_max_attempts: i64,
    pub otp_pepper: String,
    pub default_region: String,

    pub sms_provider: String,
    pub twilio_account_sid: String,
    pub twilio_auth_token: String,
    pub twilio_from: String,
    /// AWS region for SNS (SMS). Defaults to us-east-1.
    pub aws_region: String,
    /// Firebase project id; when set, POST /auth/firebase verifies Firebase ID
    /// tokens (phone auth via the Firebase SDK on-device).
    pub firebase_project_id: String,
    /// Sender ID shown on the SMS where carriers support it (optional).
    pub sms_sender_id: String,
    /// DANGEROUS: when true, /auth/request-otp echoes the code in its response.
    /// MUST be false in production. Decoupled from sms_provider so a "console"
    /// provider never implies leaking the code.
    pub expose_dev_otp: bool,

    pub sfu_public_url: String,
    pub sfu_jwt_secret: String,
    pub sfu_node_id: String,

    /// LiveKit media server. When `livekit_url` is set the call control plane
    /// hands clients a LiveKit access token (signed with `livekit_api_secret`)
    /// + the LiveKit ws URL instead of the legacy custom-SFU join token.
    pub livekit_url: String,
    pub livekit_api_key: String,
    pub livekit_api_secret: String,

    pub turn_uris: Vec<String>,
    pub turn_shared_secret: String,
    pub turn_cred_ttl_secs: i64,

    // Reserved for the real S3 avatar upload path (routes::users::post_avatar).
    #[allow(dead_code)]
    pub s3_bucket: String,
    pub s3_public_base_url: String,

    pub api_bind: String,

    // ── Push notifications (all optional; empty ⇒ that provider is disabled) ──
    /// APNs (iOS VoIP push).
    pub apns_key_id: String,
    pub apns_team_id: String,
    /// The full contents of the .p8 auth key (PEM). May also be a file path.
    pub apns_key_p8: String,
    /// APNs topic = bundle id + ".voip" (e.g. "app.exla.slide.voip").
    pub apns_topic: String,
    /// APNs topic for standard alert pushes = the bare bundle id (e.g.
    /// "app.exla.slide"). Defaults to `apns_topic` with a trailing ".voip"
    /// trimmed; override with APNS_ALERT_TOPIC.
    pub apns_alert_topic: String,
    /// "sandbox" | "prod". Defaults to "prod".
    pub apns_env: String,

    /// FCM (Android). Service-account JSON, inline or a file path.
    pub fcm_service_account_json: String,
    /// Optional override; otherwise taken from the service-account JSON.
    pub fcm_project_id: String,

    /// Web Push (browser) VAPID keys (base64url). The public key is the
    /// applicationServerKey clients subscribe with; the server signs with the
    /// private key. Kept in config for completeness / a future "get VAPID
    /// public key" endpoint.
    #[allow(dead_code)]
    pub vapid_public_key: String,
    pub vapid_private_key: String,
    /// VAPID subject, e.g. "mailto:ops@exla.ai".
    pub vapid_subject: String,
}

fn var(key: &str, default: &str) -> String {
    env::var(key).unwrap_or_else(|_| default.to_string())
}

fn var_i64(key: &str, default: i64) -> i64 {
    env::var(key)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

impl Config {
    pub fn from_env() -> Self {
        Self {
            database_url: var(
                "DATABASE_URL",
                "postgres://slide:slide@localhost:5432/slide",
            ),
            redis_url: var("REDIS_URL", "redis://localhost:6379"),

            jwt_secret: var("JWT_SECRET", "dev-only-insecure-secret-change-me"),
            access_ttl_secs: var_i64("ACCESS_TOKEN_TTL_SECS", 900),
            refresh_ttl_secs: var_i64("REFRESH_TOKEN_TTL_SECS", 5_184_000),
            join_ttl_secs: var_i64("JOIN_TOKEN_TTL_SECS", 300),
            call_ring_timeout_secs: var_i64("CALL_RING_TIMEOUT_SECS", 45).clamp(10, 120),

            otp_ttl_secs: var_i64("OTP_TTL_SECS", 300),
            otp_max_attempts: var_i64("OTP_MAX_ATTEMPTS", 5),
            otp_pepper: var("OTP_PEPPER", "dev-only-otp-pepper-change-me"),
            default_region: var("DEFAULT_REGION", "US"),

            sms_provider: var("SMS_PROVIDER", "console"),
            twilio_account_sid: var("TWILIO_ACCOUNT_SID", ""),
            twilio_auth_token: var("TWILIO_AUTH_TOKEN", ""),
            twilio_from: var("TWILIO_FROM_NUMBER", ""),
            aws_region: var("AWS_REGION", "us-east-1"),
            firebase_project_id: var("FIREBASE_PROJECT_ID", ""),
            sms_sender_id: var("SMS_SENDER_ID", ""),
            // Only ever true when explicitly opted in. Never derive from provider.
            expose_dev_otp: var("EXPOSE_DEV_OTP", "false") == "true",

            sfu_public_url: var("SFU_PUBLIC_URL", "ws://localhost:9000"),
            sfu_jwt_secret: var("SFU_JWT_SECRET", "dev-only-sfu-secret-change-me"),
            sfu_node_id: var("SFU_NODE_ID", "sfu-local-1"),

            livekit_url: var("LIVEKIT_URL", ""),
            livekit_api_key: var("LIVEKIT_API_KEY", ""),
            livekit_api_secret: var("LIVEKIT_API_SECRET", ""),

            turn_uris: var("TURN_URIS", "")
                .split(',')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect(),
            turn_shared_secret: var("TURN_SHARED_SECRET", "dev-only-turn-secret-change-me"),
            turn_cred_ttl_secs: var_i64("TURN_CRED_TTL_SECS", 600),

            s3_bucket: var("S3_BUCKET", "slide-avatars"),
            s3_public_base_url: var("S3_PUBLIC_BASE_URL", ""),

            api_bind: var("API_BIND", "0.0.0.0:8080"),

            apns_key_id: var("APNS_KEY_ID", ""),
            apns_team_id: var("APNS_TEAM_ID", ""),
            apns_key_p8: var("APNS_KEY_P8", ""),
            apns_topic: var("APNS_TOPIC", "app.exla.slide.voip"),
            apns_alert_topic: {
                let topic = var("APNS_TOPIC", "app.exla.slide.voip");
                var(
                    "APNS_ALERT_TOPIC",
                    topic.strip_suffix(".voip").unwrap_or(&topic),
                )
            },
            apns_env: var("APNS_ENV", "prod"),

            fcm_service_account_json: var("FCM_SERVICE_ACCOUNT_JSON", ""),
            fcm_project_id: var("FCM_PROJECT_ID", ""),

            vapid_public_key: var("VAPID_PUBLIC_KEY", ""),
            vapid_private_key: var("VAPID_PRIVATE_KEY", ""),
            vapid_subject: var("VAPID_SUBJECT", ""),
        }
    }

    /// `true` only when the OTP code may be echoed in the API response. This is
    /// a SECURITY-sensitive override that must be explicitly enabled and is NOT
    /// implied by the SMS provider. In production this is false, so even if SMS
    /// delivery is misconfigured the code is never leaked to the caller.
    pub fn is_dev_sms(&self) -> bool {
        self.expose_dev_otp
    }
}
