//! FCM push (Android), via the FCM HTTP v1 API.
//!
//! Auth is a Google OAuth2 access token, obtained by signing a JWT assertion
//! (RS256) with the service-account private key and exchanging it at Google's
//! token endpoint. We send a high-priority **data** message so the client can
//! ring even when backgrounded.
//!
//! Disabled unless FCM_SERVICE_ACCOUNT_JSON is set (inline or path); attempted
//! sends then return a logged delivery error.

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use jsonwebtoken::{Algorithm, EncodingKey, Header};
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::sync::RwLock;

use super::IncomingPush;
use crate::config::Config;

const SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";
/// Refresh the OAuth token a little before its ~1h expiry.
const TOKEN_LEEWAY_SECS: u64 = 60;

#[derive(Debug)]
pub enum FcmError {
    DeadToken(String),
    Other(String),
}

impl std::fmt::Display for FcmError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::DeadToken(message) | Self::Other(message) => f.write_str(message),
        }
    }
}

fn classify_failure(status: reqwest::StatusCode, body: &str) -> FcmError {
    let message = format!("fcm status {status}: {body}");
    let unregistered = serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .is_some_and(|value| json_contains_string(&value, "errorCode", "UNREGISTERED"));
    if unregistered {
        FcmError::DeadToken(message)
    } else {
        FcmError::Other(message)
    }
}

fn json_contains_string(value: &serde_json::Value, key: &str, expected: &str) -> bool {
    match value {
        serde_json::Value::Object(map) => {
            map.get(key).and_then(serde_json::Value::as_str) == Some(expected)
                || map
                    .values()
                    .any(|child| json_contains_string(child, key, expected))
        }
        serde_json::Value::Array(values) => values
            .iter()
            .any(|child| json_contains_string(child, key, expected)),
        _ => false,
    }
}

#[derive(Clone)]
pub struct Fcm(Option<Arc<Inner>>);

struct Inner {
    project_id: String,
    client_email: String,
    token_uri: String,
    encoding_key: EncodingKey,
    http: reqwest::Client,
    /// Cached OAuth access token: (token, expires_at_epoch_secs).
    cached: RwLock<Option<(String, u64)>>,
}

#[derive(Deserialize)]
struct ServiceAccount {
    project_id: String,
    private_key: String,
    client_email: String,
    #[serde(default = "default_token_uri")]
    token_uri: String,
}

fn default_token_uri() -> String {
    "https://oauth2.googleapis.com/token".to_string()
}

#[derive(Serialize)]
struct Assertion {
    iss: String,
    scope: String,
    aud: String,
    iat: u64,
    exp: u64,
}

#[derive(Deserialize)]
struct TokenResp {
    access_token: String,
    #[serde(default)]
    expires_in: u64,
}

impl Fcm {
    pub fn from_config(cfg: &Config) -> Self {
        if cfg.fcm_service_account_json.is_empty() {
            return Fcm(None);
        }

        let raw = load_json(&cfg.fcm_service_account_json);
        let sa: ServiceAccount = match serde_json::from_str(&raw) {
            Ok(sa) => sa,
            Err(e) => {
                tracing::error!(error = %e, "fcm: invalid service-account JSON — FCM disabled");
                return Fcm(None);
            }
        };

        let encoding_key = match EncodingKey::from_rsa_pem(sa.private_key.as_bytes()) {
            Ok(k) => k,
            Err(e) => {
                tracing::error!(error = %e, "fcm: invalid private_key — FCM disabled");
                return Fcm(None);
            }
        };

        let project_id = if cfg.fcm_project_id.is_empty() {
            sa.project_id
        } else {
            cfg.fcm_project_id.clone()
        };

        let http = match reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(10))
            .build()
        {
            Ok(c) => c,
            Err(e) => {
                tracing::error!(error = %e, "fcm: failed to build http client — FCM disabled");
                return Fcm(None);
            }
        };

        Fcm(Some(Arc::new(Inner {
            project_id,
            client_email: sa.client_email,
            token_uri: sa.token_uri,
            encoding_key,
            http,
            cached: RwLock::new(None),
        })))
    }

    pub fn enabled(&self) -> bool {
        self.0.is_some()
    }

    /// Prepare the OAuth credential separately so the caller can revalidate
    /// subscription ownership after a potentially slow token refresh and
    /// immediately before the provider request.
    pub async fn prepare_access_token(&self) -> Result<String, FcmError> {
        let Some(inner) = &self.0 else {
            return Err(FcmError::Other(
                "fcm disabled: missing or invalid credentials".to_string(),
            ));
        };
        inner.access_token().await.map_err(FcmError::Other)
    }

    pub async fn send_prepared(
        &self,
        access_token: &str,
        device_token: &str,
        payload: &IncomingPush,
        ttl_secs: u32,
        collapse_key: Option<&str>,
    ) -> Result<(), FcmError> {
        let Some(inner) = &self.0 else {
            return Err(FcmError::Other(
                "fcm disabled: missing or invalid credentials".to_string(),
            ));
        };
        let ttl_secs = payload.ttl_secs(ttl_secs);
        if payload.kind == "incoming_call" && ttl_secs == 0 {
            return Ok(());
        }

        // FCM `data` values must be strings; data_map() already produces those.
        let mut android = json!({
            "priority": "high",
            "ttl": format!("{ttl_secs}s"),
        });
        if let Some(collapse_key) = collapse_key {
            android["collapse_key"] = json!(collapse_key);
        }
        let message = json!({
            "message": {
                "token": device_token,
                "android": android,
                "data": payload.data_map(),
            }
        });

        let url = format!(
            "https://fcm.googleapis.com/v1/projects/{}/messages:send",
            inner.project_id
        );
        let resp = inner
            .http
            .post(&url)
            .bearer_auth(access_token)
            .json(&message)
            .send()
            .await
            .map_err(|e| FcmError::Other(format!("fcm request failed: {e}")))?;

        let status = resp.status();
        if status.is_success() {
            Ok(())
        } else {
            let txt = resp.text().await.unwrap_or_default();
            Err(classify_failure(status, &txt))
        }
    }
}

impl Inner {
    async fn access_token(&self) -> Result<String, String> {
        let now = unix_now();
        {
            let guard = self.cached.read().await;
            if let Some((tok, exp)) = guard.as_ref() {
                if now + TOKEN_LEEWAY_SECS < *exp {
                    return Ok(tok.clone());
                }
            }
        }

        // Serialize refreshes and double-check after taking the write lock.
        // Incoming fanout is concurrent; without this every device would make
        // its own OAuth token request when the cache is cold or expires.
        let mut guard = self.cached.write().await;
        if let Some((token, expires_at)) = guard.as_ref() {
            if now + TOKEN_LEEWAY_SECS < *expires_at {
                return Ok(token.clone());
            }
        }

        let iat = now;
        let exp = now + 3600;
        let assertion = Assertion {
            iss: self.client_email.clone(),
            scope: SCOPE.to_string(),
            aud: self.token_uri.clone(),
            iat,
            exp,
        };
        let jwt = jsonwebtoken::encode(
            &Header::new(Algorithm::RS256),
            &assertion,
            &self.encoding_key,
        )
        .map_err(|e| format!("fcm assertion sign failed: {e}"))?;

        let params = [
            ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
            ("assertion", jwt.as_str()),
        ];
        let resp = self
            .http
            .post(&self.token_uri)
            .form(&params)
            .send()
            .await
            .map_err(|e| format!("fcm token request failed: {e}"))?;
        if !resp.status().is_success() {
            let status = resp.status();
            let txt = resp.text().await.unwrap_or_default();
            return Err(format!("fcm token status {status}: {txt}"));
        }
        let tok: TokenResp = resp
            .json()
            .await
            .map_err(|e| format!("fcm token decode failed: {e}"))?;

        let expires_in = if tok.expires_in == 0 {
            3600
        } else {
            tok.expires_in
        };
        *guard = Some((tok.access_token.clone(), now + expires_in));
        Ok(tok.access_token)
    }
}

/// Accept the JSON inline or as a filesystem path.
fn load_json(value: &str) -> String {
    let trimmed = value.trim_start();
    if trimmed.starts_with('{') {
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

    use super::{classify_failure, FcmError};

    #[test]
    fn recognizes_only_structured_unregistered_token_errors() {
        let unregistered = r#"{
          "error": {"status":"NOT_FOUND", "details":[{"errorCode":"UNREGISTERED"}]}
        }"#;
        assert!(matches!(
            classify_failure(StatusCode::NOT_FOUND, unregistered),
            FcmError::DeadToken(_)
        ));
        assert!(matches!(
            classify_failure(StatusCode::NOT_FOUND, r#"{"error":{"status":"NOT_FOUND"}}"#),
            FcmError::Other(_)
        ));
        assert!(matches!(
            classify_failure(
                StatusCode::BAD_REQUEST,
                r#"{"error":{"details":[{"errorCode":"INVALID_ARGUMENT"}]}}"#
            ),
            FcmError::Other(_)
        ));
    }
}
