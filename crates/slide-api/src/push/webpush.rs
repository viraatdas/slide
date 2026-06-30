//! Web Push (browser), via VAPID + RFC 8291 payload encryption.
//!
//! Uses the `web-push` crate for the ECE encryption and VAPID signing. The
//! subscription is the browser endpoint URL plus the client's `p256dh` and
//! `auth` keys captured at subscribe time.
//!
//! Disabled unless VAPID_PRIVATE_KEY and VAPID_SUBJECT are set; attempted sends
//! then return a logged delivery error.

use std::{sync::Arc, time::Duration};

use web_push::{
    ContentEncoding, SubscriptionInfo, Urgency, VapidSignatureBuilder, WebPushClient, WebPushError,
    WebPushMessageBuilder,
};

use super::IncomingPush;
use crate::config::Config;

const SEND_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone)]
pub struct WebPush(Option<Arc<Inner>>);

#[derive(Debug)]
pub enum WebPushSendError {
    DeadEndpoint(String),
    Other(String),
}

impl std::fmt::Display for WebPushSendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::DeadEndpoint(message) | Self::Other(message) => f.write_str(message),
        }
    }
}

fn classify_failure(error: WebPushError) -> WebPushSendError {
    let message = format!("webpush send failed: {error}");
    if matches!(
        error,
        WebPushError::EndpointNotValid | WebPushError::EndpointNotFound
    ) {
        WebPushSendError::DeadEndpoint(message)
    } else {
        WebPushSendError::Other(message)
    }
}

struct Inner {
    /// VAPID private key (base64url, the raw key bytes as in `.env.example`).
    private_key: String,
    subject: String,
    client: web_push::IsahcWebPushClient,
}

impl WebPush {
    pub fn from_config(cfg: &Config) -> Self {
        if cfg.vapid_private_key.is_empty() || cfg.vapid_subject.is_empty() {
            return WebPush(None);
        }
        let client = match web_push::IsahcWebPushClient::new() {
            Ok(c) => c,
            Err(e) => {
                tracing::error!(error = %e, "webpush: failed to build client — Web Push disabled");
                return WebPush(None);
            }
        };
        WebPush(Some(Arc::new(Inner {
            private_key: cfg.vapid_private_key.clone(),
            subject: cfg.vapid_subject.clone(),
            client,
        })))
    }

    pub fn enabled(&self) -> bool {
        self.0.is_some()
    }

    pub async fn send(
        &self,
        endpoint: &str,
        p256dh: Option<&str>,
        auth: Option<&str>,
        payload: &IncomingPush,
        ttl_secs: u32,
        topic: Option<&str>,
    ) -> Result<(), WebPushSendError> {
        let Some(inner) = &self.0 else {
            return Err(WebPushSendError::Other(
                "webpush disabled: missing or invalid credentials".to_string(),
            ));
        };
        let (Some(p256dh), Some(auth)) = (p256dh, auth) else {
            return Err(WebPushSendError::Other(
                "webpush: subscription missing p256dh/auth keys".to_string(),
            ));
        };
        let ttl_secs = payload.ttl_secs(ttl_secs);
        if payload.kind == "incoming_call" && ttl_secs == 0 {
            return Ok(());
        }

        let subscription = SubscriptionInfo::new(endpoint, p256dh, auth);

        let mut sig_builder = VapidSignatureBuilder::from_base64(
            &inner.private_key,
            web_push::URL_SAFE_NO_PAD,
            &subscription,
        )
        .map_err(|e| WebPushSendError::Other(format!("webpush vapid key invalid: {e}")))?;
        // VAPID `sub` claim: a mailto: or https: contact for the push service.
        sig_builder.add_claim("sub", inner.subject.as_str());
        let sig = sig_builder
            .build()
            .map_err(|e| WebPushSendError::Other(format!("webpush vapid build failed: {e}")))?;

        let body = serde_json::to_vec(&payload.data_map())
            .map_err(|e| WebPushSendError::Other(format!("webpush payload encode failed: {e}")))?;

        let mut builder = WebPushMessageBuilder::new(&subscription);
        builder.set_payload(ContentEncoding::Aes128Gcm, &body);
        builder.set_vapid_signature(sig);
        builder.set_ttl(ttl_secs);
        builder.set_urgency(Urgency::High);
        if let Some(topic) = topic {
            builder.set_topic(topic.to_string());
        }

        let message = builder
            .build()
            .map_err(|e| WebPushSendError::Other(format!("webpush message build failed: {e}")))?;

        tokio::time::timeout(SEND_TIMEOUT, inner.client.send(message))
            .await
            .map_err(|_| WebPushSendError::Other("webpush send timed out after 10s".to_string()))?
            .map_err(classify_failure)
    }
}

#[cfg(test)]
mod tests {
    use web_push::WebPushError;

    use super::{classify_failure, WebPushSendError};

    #[test]
    fn prunes_only_gone_or_missing_webpush_endpoints() {
        assert!(matches!(
            classify_failure(WebPushError::EndpointNotValid),
            WebPushSendError::DeadEndpoint(_)
        ));
        assert!(matches!(
            classify_failure(WebPushError::EndpointNotFound),
            WebPushSendError::DeadEndpoint(_)
        ));
        assert!(matches!(
            classify_failure(WebPushError::ServerError(None)),
            WebPushSendError::Other(_)
        ));
    }
}
