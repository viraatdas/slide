//! APNs VoIP push (iOS).
//!
//! HTTP/2 to `api.push.apple.com` (or the sandbox host), authenticated with a
//! provider JWT signed ES256 using the APNs `.p8` key. A VoIP push wakes the
//! app even when fully closed so CallKit can ring.
//!
//! Disabled unless APNS_KEY_ID, APNS_TEAM_ID, APNS_KEY_P8 and APNS_TOPIC are
//! all set; attempted sends then return a logged delivery error.

use std::sync::{Arc, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use jsonwebtoken::{Algorithm, EncodingKey, Header};
use serde::Serialize;
use serde_json::json;

use super::IncomingPush;
use crate::config::Config;

/// Refresh the provider JWT well before APNs' 60-min limit.
const TOKEN_REFRESH_SECS: u64 = 45 * 60;
const BACKGROUND_PUSH_TYPE: &str = "background";
const BACKGROUND_PRIORITY: &str = "5";

/// Why an APNs send failed. `DeadToken` means APNs told us the device token is
/// permanently gone (HTTP 410 "Unregistered", or 400 with reason
/// "BadDeviceToken") — callers should prune the subscription row. Everything
/// else (5xx, network, auth) is `Other` and must NOT trigger pruning.
#[derive(Debug)]
pub enum ApnsError {
    DeadToken(String),
    Other(String),
}

impl std::fmt::Display for ApnsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ApnsError::DeadToken(msg) | ApnsError::Other(msg) => f.write_str(msg),
        }
    }
}

/// Classify an APNs error response: 410 always means the token is gone;
/// 400 only when the body's reason is "BadDeviceToken".
fn classify_failure(status: reqwest::StatusCode, body: &str) -> ApnsError {
    let msg = format!("apns status {status}: {body}");
    let reason = serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .and_then(|value| value.get("reason")?.as_str().map(str::to_owned));
    if status == reqwest::StatusCode::GONE
        || (status == reqwest::StatusCode::BAD_REQUEST
            && reason.as_deref() == Some("BadDeviceToken"))
    {
        ApnsError::DeadToken(msg)
    } else {
        ApnsError::Other(msg)
    }
}

#[derive(Clone)]
pub struct Apns(Option<Arc<Inner>>);

struct Inner {
    key_id: String,
    team_id: String,
    topic: String,
    /// Topic for standard (visible) alert pushes: the bare bundle id, i.e. the
    /// VoIP topic with its ".voip" suffix trimmed (or APNS_ALERT_TOPIC).
    alert_topic: String,
    host: &'static str,
    encoding_key: EncodingKey,
    http: reqwest::Client,
    /// Cached provider token: (jwt, issued_at_epoch_secs).
    cached: RwLock<Option<(String, u64)>>,
}

#[derive(Serialize)]
struct Claims {
    iss: String,
    iat: u64,
}

fn background_terminal_body(payload: &IncomingPush) -> serde_json::Value {
    json!({
        "aps": { "content-available": 1 },
        "type": payload.kind,
        "callId": payload.call_id,
    })
}

impl Apns {
    pub fn from_config(cfg: &Config) -> Self {
        if cfg.apns_key_id.is_empty()
            || cfg.apns_team_id.is_empty()
            || cfg.apns_key_p8.is_empty()
            || cfg.apns_topic.is_empty()
        {
            return Apns(None);
        }

        // APNS_KEY_P8 may be the PEM contents or a path to the .p8 file.
        let pem = load_p8(&cfg.apns_key_p8);
        let encoding_key = match EncodingKey::from_ec_pem(pem.as_bytes()) {
            Ok(k) => k,
            Err(e) => {
                tracing::error!(error = %e, "apns: invalid .p8 key — APNs disabled");
                return Apns(None);
            }
        };

        // HTTP/2 is required by APNs. reqwest negotiates h2 over TLS (ALPN);
        // force prior-knowledge off but ensure http2 is allowed.
        let http = match reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(10))
            .build()
        {
            Ok(c) => c,
            Err(e) => {
                tracing::error!(error = %e, "apns: failed to build http client — APNs disabled");
                return Apns(None);
            }
        };

        let host = if cfg.apns_env == "sandbox" {
            "https://api.sandbox.push.apple.com"
        } else {
            "https://api.push.apple.com"
        };

        Apns(Some(Arc::new(Inner {
            key_id: cfg.apns_key_id.clone(),
            team_id: cfg.apns_team_id.clone(),
            topic: cfg.apns_topic.clone(),
            alert_topic: cfg.apns_alert_topic.clone(),
            host,
            encoding_key,
            http,
            cached: RwLock::new(None),
        })))
    }

    pub fn enabled(&self) -> bool {
        self.0.is_some()
    }

    pub async fn send(
        &self,
        device_token: &str,
        payload: &IncomingPush,
        ttl_secs: u32,
        collapse_id: Option<&str>,
    ) -> Result<(), ApnsError> {
        let Some(inner) = &self.0 else {
            return Err(ApnsError::Other(
                "apns disabled: missing or invalid credentials".to_string(),
            ));
        };

        let jwt = inner.provider_token().map_err(ApnsError::Other)?;
        let ttl_secs = payload.ttl_secs(ttl_secs);
        if payload.kind == "incoming_call" && ttl_secs == 0 {
            return Ok(());
        }

        // VoIP push: `aps` is empty; our routing fields ride alongside.
        let body = json!({
            "aps": {},
            "type": payload.kind,
            "callId": payload.call_id,
            "callType": payload.call_type,
            "fromUserId": payload.from_user_id,
            "fromName": payload.from_name,
            "videoEnabled": payload.video_enabled,
            "ringStyle": payload.ring_style,
            "knock": payload.knock,
            "expiresAt": payload.expires_at_ms,
        });

        let url = format!("{}/3/device/{}", inner.host, device_token);
        let mut req = inner
            .http
            .post(&url)
            .bearer_auth(&jwt)
            .header("apns-topic", &inner.topic)
            .header("apns-push-type", "voip")
            .header("apns-priority", "10")
            // Never let APNs deliver a ringing invitation after the server's
            // ring window has closed. A bounded expiry is more reliable than
            // an unbounded stored notification and less lossy than expiry=0.
            .header("apns-expiration", {
                payload
                    .expires_at_ms
                    .map(|deadline_ms| deadline_ms.max(0) as u64 / 1_000)
                    .unwrap_or_else(|| unix_now().saturating_add(u64::from(ttl_secs)))
                    .to_string()
            });
        if let Some(collapse_id) = collapse_id {
            req = req.header("apns-collapse-id", collapse_id);
        }
        let resp = req
            .json(&body)
            .send()
            .await
            .map_err(|e| ApnsError::Other(format!("apns request failed: {e}")))?;

        let status = resp.status();
        if status.is_success() {
            Ok(())
        } else {
            let txt = resp.text().await.unwrap_or_default();
            Err(classify_failure(status, &txt))
        }
    }

    /// Send a standard, user-visible alert push (banner + sound) to a regular
    /// (non-VoIP) device token. Uses the bare bundle-id topic and
    /// `apns-push-type: alert`. `sound` is a bundled sound file name
    /// (e.g. "knock.caf"); `None` plays the system default.
    pub async fn send_alert(
        &self,
        device_token: &str,
        title: &str,
        body: &str,
        collapse_id: Option<&str>,
        sound: Option<&str>,
        ttl_secs: u32,
    ) -> Result<(), ApnsError> {
        let Some(inner) = &self.0 else {
            return Err(ApnsError::Other(
                "apns disabled: missing or invalid credentials".to_string(),
            ));
        };

        let jwt = inner.provider_token().map_err(ApnsError::Other)?;

        let payload = json!({
            "aps": {
                "alert": { "title": title, "body": body },
                "sound": sound.unwrap_or("default"),
            }
        });

        let url = format!("{}/3/device/{}", inner.host, device_token);
        let mut req = inner
            .http
            .post(&url)
            .bearer_auth(&jwt)
            .header("apns-topic", &inner.alert_topic)
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .header(
                "apns-expiration",
                unix_now().saturating_add(u64::from(ttl_secs)).to_string(),
            );
        if let Some(cid) = collapse_id {
            req = req.header("apns-collapse-id", cid);
        }
        let resp = req
            .json(&payload)
            .send()
            .await
            .map_err(|e| ApnsError::Other(format!("apns alert request failed: {e}")))?;

        let status = resp.status();
        if status.is_success() {
            Ok(())
        } else {
            let txt = resp.text().await.unwrap_or_default();
            Err(classify_failure(status, &txt))
        }
    }

    /// Wake the regular app process to reconcile a terminal call event. Apple
    /// requires every VoIP push to report a new call, so cancellation/answered-
    /// elsewhere events use the standard token as silent background pushes.
    pub async fn send_background_terminal(
        &self,
        device_token: &str,
        payload: &IncomingPush,
        collapse_id: Option<&str>,
        ttl_secs: u32,
    ) -> Result<(), ApnsError> {
        let Some(inner) = &self.0 else {
            return Err(ApnsError::Other(
                "apns disabled: missing or invalid credentials".to_string(),
            ));
        };

        let jwt = inner.provider_token().map_err(ApnsError::Other)?;
        let url = format!("{}/3/device/{}", inner.host, device_token);
        let mut req = inner
            .http
            .post(&url)
            .bearer_auth(&jwt)
            .header("apns-topic", &inner.alert_topic)
            .header("apns-push-type", BACKGROUND_PUSH_TYPE)
            .header("apns-priority", BACKGROUND_PRIORITY)
            .header(
                "apns-expiration",
                unix_now().saturating_add(u64::from(ttl_secs)).to_string(),
            );
        if let Some(collapse_id) = collapse_id {
            req = req.header("apns-collapse-id", collapse_id);
        }
        let response = req
            .json(&background_terminal_body(payload))
            .send()
            .await
            .map_err(|error| {
                ApnsError::Other(format!("apns background request failed: {error}"))
            })?;

        let status = response.status();
        if status.is_success() {
            Ok(())
        } else {
            let body = response.text().await.unwrap_or_default();
            Err(classify_failure(status, &body))
        }
    }
}

impl Inner {
    /// Return a cached provider JWT, minting a fresh one when stale.
    fn provider_token(&self) -> Result<String, String> {
        let now = unix_now();
        if let Ok(guard) = self.cached.read() {
            if let Some((tok, iat)) = guard.as_ref() {
                if now.saturating_sub(*iat) < TOKEN_REFRESH_SECS {
                    return Ok(tok.clone());
                }
            }
        }

        let mut header = Header::new(Algorithm::ES256);
        header.kid = Some(self.key_id.clone());
        let claims = Claims {
            iss: self.team_id.clone(),
            iat: now,
        };
        let tok = jsonwebtoken::encode(&header, &claims, &self.encoding_key)
            .map_err(|e| format!("apns jwt sign failed: {e}"))?;

        if let Ok(mut guard) = self.cached.write() {
            *guard = Some((tok.clone(), now));
        }
        Ok(tok)
    }
}

/// Accept the PEM inline or as a filesystem path.
fn load_p8(value: &str) -> String {
    if value.contains("BEGIN") {
        value.to_string()
    } else {
        match std::fs::read_to_string(value) {
            Ok(contents) => contents,
            Err(_) => value.to_string(),
        }
    }
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use reqwest::StatusCode;
    use uuid::Uuid;

    use super::{
        background_terminal_body, classify_failure, ApnsError, BACKGROUND_PRIORITY,
        BACKGROUND_PUSH_TYPE,
    };
    use crate::push::IncomingPush;

    #[test]
    fn only_permanent_apns_errors_are_dead_tokens() {
        assert!(matches!(
            classify_failure(StatusCode::GONE, r#"{"reason":"Unregistered"}"#),
            ApnsError::DeadToken(_)
        ));
        assert!(matches!(
            classify_failure(StatusCode::BAD_REQUEST, r#"{"reason":"BadDeviceToken"}"#),
            ApnsError::DeadToken(_)
        ));
        assert!(matches!(
            classify_failure(StatusCode::BAD_REQUEST, r#"{"reason":"BadTopic"}"#),
            ApnsError::Other(_)
        ));
        assert!(matches!(
            classify_failure(StatusCode::INTERNAL_SERVER_ERROR, ""),
            ApnsError::Other(_)
        ));
    }

    #[test]
    fn terminal_background_push_has_silent_aps_and_routing_fields() {
        let call_id = Uuid::new_v4();
        let payload = IncomingPush {
            kind: "call_ended".to_string(),
            call_id: Some(call_id),
            call_type: None,
            from_user_id: Uuid::new_v4(),
            from_name: "Slide".to_string(),
            video_enabled: true,
            ring_style: "call".to_string(),
            knock: false,
            expires_at_ms: None,
        };

        let body = background_terminal_body(&payload);
        assert_eq!(body["aps"]["content-available"], 1);
        assert!(body["aps"].get("alert").is_none());
        assert_eq!(body["type"], "call_ended");
        assert_eq!(body["callId"], call_id.to_string());
        assert_eq!(BACKGROUND_PUSH_TYPE, "background");
        assert_eq!(BACKGROUND_PRIORITY, "5");
    }
}
