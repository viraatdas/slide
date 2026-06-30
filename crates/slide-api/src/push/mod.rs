//! Server-side push notifications for every real call invitation. WebSocket
//! fanout and push run together; `callId` makes duplicate delivery idempotent.
//!
//! Three providers, each its own module. Missing credentials do not stop the
//! API from booting, but sends are reported as failures so production cannot
//! silently pretend an offline call was delivered:
//!
//!   - [`apns`]    — iOS VoIP push (APNs HTTP/2, ES256 JWT).
//!   - [`fcm`]     — Android data message (FCM HTTP v1, OAuth2 service account).
//!   - [`webpush`] — browser Web Push (VAPID, RFC 8291 ECE).
//!
//! [`Push`] is built once at boot, stored on [`crate::state::AppState`], and
//! summarizes which providers are live. [`Push::notify_incoming`] loads a
//! user's `push_subscriptions` and fans the payload out to the matching sender.

pub mod apns;
pub mod fcm;
pub mod webpush;

use chrono::Utc;
use futures::{stream, StreamExt};
use serde::Serialize;
use serde_json::Value;
use sqlx::PgPool;
use uuid::Uuid;

use crate::config::Config;

const TERMINAL_PUSH_TTL_SECS: u32 = 30;

/// A single stored subscription row (one row per device/browser).
#[derive(Debug, sqlx::FromRow)]
struct Subscription {
    kind: String,
    token: String,
    p256dh: Option<String>,
    auth: Option<String>,
}

/// The payload that rings a callee. Serialized into each provider's transport.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IncomingPush {
    /// "incoming_call" — and we add `knock: true` for escalated knocks.
    #[serde(rename = "type")]
    pub kind: String,
    pub call_id: Option<Uuid>,
    pub call_type: Option<String>,
    pub from_user_id: Uuid,
    pub from_name: String,
    /// Whether accepting this call should publish camera immediately.
    pub video_enabled: bool,
    /// "call" for normal calls, "knock" for call-style knock invitations.
    pub ring_style: String,
    /// True when this push is an offline knock escalated to a ringable call,
    /// so clients can label it differently.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub knock: bool,
    /// Absolute server-side ring deadline in Unix epoch milliseconds. It is
    /// included on the wire so clients can reject a delayed transport, and is
    /// also the source of truth for each provider's remaining TTL.
    #[serde(rename = "expiresAt", skip_serializing_if = "Option::is_none")]
    pub expires_at_ms: Option<i64>,
}

impl IncomingPush {
    /// Flatten to a JSON object for transports that carry an arbitrary map
    /// (FCM data, Web Push body). String-valued so FCM `data` is happy.
    fn data_map(&self) -> Value {
        let mut map = serde_json::Map::new();
        map.insert("type".into(), Value::String(self.kind.clone()));
        if let Some(id) = self.call_id {
            map.insert("callId".into(), Value::String(id.to_string()));
        }
        if let Some(ct) = &self.call_type {
            map.insert("callType".into(), Value::String(ct.clone()));
        }
        map.insert(
            "fromUserId".into(),
            Value::String(self.from_user_id.to_string()),
        );
        map.insert("fromName".into(), Value::String(self.from_name.clone()));
        map.insert(
            "videoEnabled".into(),
            Value::String(self.video_enabled.to_string()),
        );
        map.insert("ringStyle".into(), Value::String(self.ring_style.clone()));
        if self.knock {
            map.insert("knock".into(), Value::String("true".into()));
        }
        if let Some(expires_at_ms) = self.expires_at_ms {
            // FCM data values must all be strings; Web Push clients already
            // tolerate string/number boolean compatibility fields.
            map.insert("expiresAt".into(), Value::String(expires_at_ms.to_string()));
        }
        Value::Object(map)
    }

    pub(super) fn ttl_secs(&self, ring_ttl_secs: u32) -> u32 {
        if self.kind == "incoming_call" {
            self.expires_at_ms
                .map(|deadline_ms| {
                    ((deadline_ms - Utc::now().timestamp_millis()) / 1_000)
                        .clamp(0, i64::from(ring_ttl_secs)) as u32
                })
                .unwrap_or(ring_ttl_secs)
        } else {
            TERMINAL_PUSH_TTL_SECS
        }
    }

    fn collapse_key(&self) -> Option<String> {
        self.call_id.map(|id| id.to_string())
    }

    fn webpush_topic(&self) -> Option<String> {
        self.call_id.map(|id| id.simple().to_string())
    }

    fn is_terminal(&self) -> bool {
        matches!(
            self.kind.as_str(),
            "call_accepted" | "call_declined" | "call_ended"
        )
    }
}

/// Holds the three (possibly disabled) senders.
#[derive(Clone)]
pub struct Push {
    apns: apns::Apns,
    fcm: fcm::Fcm,
    webpush: webpush::WebPush,
    ring_ttl_secs: u32,
}

impl Push {
    /// Build from config. Each sender self-disables when its env is unset.
    pub fn from_config(cfg: &Config) -> Self {
        Self {
            apns: apns::Apns::from_config(cfg),
            fcm: fcm::Fcm::from_config(cfg),
            webpush: webpush::WebPush::from_config(cfg),
            ring_ttl_secs: cfg.call_ring_timeout_secs.clamp(10, 120) as u32,
        }
    }

    /// Human-readable list of enabled providers for the startup log.
    pub fn enabled_summary(&self) -> String {
        let mut on = Vec::new();
        if self.apns.enabled() {
            on.push("apns");
        }
        if self.fcm.enabled() {
            on.push("fcm");
        }
        if self.webpush.enabled() {
            on.push("webpush");
        }
        if on.is_empty() {
            "none (all push providers disabled — set credentials to enable)".to_string()
        } else {
            on.join(", ")
        }
    }

    pub fn any_enabled(&self) -> bool {
        self.apns.enabled() || self.fcm.enabled() || self.webpush.enabled()
    }

    /// Load a user's subscriptions and dispatch `payload` to each matching
    /// sender. Never returns an error: a push failure must not fail the call —
    /// every problem is logged and swallowed.
    pub async fn notify_incoming(&self, db: &PgPool, user_id: Uuid, payload: &IncomingPush) {
        let subs: Vec<Subscription> = match sqlx::query_as(
            "SELECT kind, token, p256dh, auth FROM push_subscriptions WHERE user_id = $1",
        )
        .bind(user_id)
        .fetch_all(db)
        .await
        {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!(user = %user_id, error = %e, "push: failed to load subscriptions");
                return;
            }
        };

        if subs.is_empty() {
            tracing::info!(user = %user_id, "push: no subscriptions for incoming event");
            return;
        }

        let collapse_key = payload.collapse_key();
        let webpush_topic = payload.webpush_topic();
        stream::iter(subs)
            .for_each_concurrent(8, |sub| {
                let collapse_key = collapse_key.clone();
                let webpush_topic = webpush_topic.clone();
                async move {
                    self.notify_one_incoming(
                        db,
                        user_id,
                        payload,
                        sub,
                        collapse_key.as_deref(),
                        webpush_topic.as_deref(),
                    )
                    .await;
                }
            })
            .await;
    }

    #[allow(clippy::too_many_arguments)]
    async fn notify_one_incoming(
        &self,
        db: &PgPool,
        user_id: Uuid,
        payload: &IncomingPush,
        sub: Subscription,
        collapse_key: Option<&str>,
        webpush_topic: Option<&str>,
    ) {
        if payload.kind == "incoming_call"
            && !Self::invitation_is_current(db, user_id, payload).await
        {
            tracing::info!(user = %user_id, kind = %sub.kind, "push: invitation no longer ringing");
            return;
        }
        // The subscription list above is a snapshot. Logout/account switching
        // can transfer this globally-unique token while sibling provider sends
        // are in flight; never send an old account's payload to its new owner.
        if !Self::subscription_is_current(db, user_id, &sub).await {
            tracing::info!(user = %user_id, kind = %sub.kind, "push: subscription ownership changed before send");
            return;
        }
        // Subscription lookup and sibling sends may consume part of the ring
        // window. Recompute immediately before this provider request.
        let ttl_secs = payload.ttl_secs(self.ring_ttl_secs);
        if payload.kind == "incoming_call" && ttl_secs == 0 {
            tracing::info!(user = %user_id, kind = %sub.kind, "push: invitation expired before send");
            return;
        }
        let result = match sub.kind.as_str() {
            "apns_voip" if payload.kind == "incoming_call" => {
                let sent = self
                    .apns
                    .send(&sub.token, payload, ttl_secs, collapse_key)
                    .await;
                self.reap_dead_apns_token(db, user_id, &sub.token, sent)
                    .await
            }
            "apns_voip" => {
                tracing::debug!(
                    kind = %payload.kind,
                    "push: skipping APNs VoIP for non-call notification"
                );
                Ok(())
            }
            // Incoming calls must use PushKit, but terminal events use a silent
            // standard APNs push. Sending a second VoIP push merely to cancel a
            // call violates PushKit's report-every-push contract.
            "apns" if payload.is_terminal() => {
                let sent = self
                    .apns
                    .send_background_terminal(&sub.token, payload, collapse_key, ttl_secs)
                    .await;
                self.reap_dead_apns_token(db, user_id, &sub.token, sent)
                    .await
            }
            "apns" => Ok(()),
            "fcm" => {
                let sent = match self.fcm.prepare_access_token().await {
                    Ok(access_token) => {
                        // A cold OAuth refresh can take seconds. Repeat the
                        // ownership check after it, immediately before FCM's
                        // HTTP request, so an account switch during refresh
                        // cannot receive the old account's call.
                        if !Self::subscription_is_current(db, user_id, &sub).await {
                            tracing::info!(user = %user_id, kind = %sub.kind, "push: FCM ownership changed during credential refresh");
                            return;
                        }
                        self.fcm
                            .send_prepared(
                                &access_token,
                                &sub.token,
                                payload,
                                ttl_secs,
                                collapse_key,
                            )
                            .await
                    }
                    Err(error) => Err(error),
                };
                self.reap_dead_fcm_token(db, user_id, &sub.token, sent)
                    .await
            }
            "webpush" => {
                let sent = self
                    .webpush
                    .send(
                        &sub.token,
                        sub.p256dh.as_deref(),
                        sub.auth.as_deref(),
                        payload,
                        ttl_secs,
                        webpush_topic,
                    )
                    .await;
                self.reap_dead_webpush_endpoint(db, user_id, &sub.token, sent)
                    .await
            }
            other => {
                tracing::warn!(kind = %other, "push: unknown subscription kind, skipping");
                Ok(())
            }
        };
        if let Err(error) = result {
            tracing::warn!(user = %user_id, kind = %sub.kind, %error, "push: send failed");
        }
    }

    async fn invitation_is_current(db: &PgPool, user_id: Uuid, payload: &IncomingPush) -> bool {
        let (Some(call_id), Some(expires_at_ms)) = (payload.call_id, payload.expires_at_ms) else {
            return false;
        };
        if Utc::now().timestamp_millis() >= expires_at_ms {
            return false;
        }
        match sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (
                 SELECT 1
                   FROM calls c
                   JOIN call_participants p ON p.call_id = c.id
                  WHERE c.id = $1
                    AND p.user_id = $2
                    AND p.state = 'ringing'
                    AND c.status IN ('ringing', 'active')
                    AND clock_timestamp() < to_timestamp($3::double precision / 1000.0)
             )",
        )
        .bind(call_id)
        .bind(user_id)
        .bind(expires_at_ms)
        .fetch_one(db)
        .await
        {
            Ok(current) => current,
            Err(error) => {
                tracing::warn!(call = %call_id, user = %user_id, %error, "push: failed invitation recheck");
                false
            }
        }
    }

    async fn subscription_is_current(
        db: &PgPool,
        user_id: Uuid,
        subscription: &Subscription,
    ) -> bool {
        match sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (
                 SELECT 1
                   FROM push_subscriptions
                  WHERE user_id = $1 AND token = $2 AND kind = $3
             )",
        )
        .bind(user_id)
        .bind(&subscription.token)
        .bind(&subscription.kind)
        .fetch_one(db)
        .await
        {
            Ok(current) => current,
            Err(error) => {
                tracing::warn!(
                    user = %user_id,
                    kind = %subscription.kind,
                    %error,
                    "push: failed subscription ownership recheck"
                );
                false
            }
        }
    }

    /// When APNs reports the device token is permanently dead (410, or 400
    /// "BadDeviceToken"), delete the subscription row so we stop pushing to
    /// it. Other failures pass through unchanged for the caller to log.
    async fn reap_dead_apns_token(
        &self,
        db: &PgPool,
        user_id: Uuid,
        token: &str,
        result: Result<(), apns::ApnsError>,
    ) -> Result<(), String> {
        match result {
            Ok(()) => Ok(()),
            Err(apns::ApnsError::DeadToken(reason)) => {
                tracing::info!(
                    user = %user_id,
                    reason = %reason,
                    "push: pruning dead APNs token"
                );
                if let Err(e) =
                    sqlx::query("DELETE FROM push_subscriptions WHERE user_id = $1 AND token = $2")
                        .bind(user_id)
                        .bind(token)
                        .execute(db)
                        .await
                {
                    tracing::warn!(user = %user_id, error = %e, "push: failed to prune dead APNs token");
                }
                Ok(())
            }
            Err(apns::ApnsError::Other(e)) => Err(e),
        }
    }

    async fn reap_dead_fcm_token(
        &self,
        db: &PgPool,
        user_id: Uuid,
        token: &str,
        result: Result<(), fcm::FcmError>,
    ) -> Result<(), String> {
        match result {
            Ok(()) => Ok(()),
            Err(fcm::FcmError::DeadToken(reason)) => {
                self.delete_dead_subscription(db, user_id, token, "FCM", &reason)
                    .await;
                Ok(())
            }
            Err(fcm::FcmError::Other(error)) => Err(error),
        }
    }

    async fn reap_dead_webpush_endpoint(
        &self,
        db: &PgPool,
        user_id: Uuid,
        token: &str,
        result: Result<(), webpush::WebPushSendError>,
    ) -> Result<(), String> {
        match result {
            Ok(()) => Ok(()),
            Err(webpush::WebPushSendError::DeadEndpoint(reason)) => {
                self.delete_dead_subscription(db, user_id, token, "Web Push", &reason)
                    .await;
                Ok(())
            }
            Err(webpush::WebPushSendError::Other(error)) => Err(error),
        }
    }

    async fn delete_dead_subscription(
        &self,
        db: &PgPool,
        user_id: Uuid,
        token: &str,
        provider: &str,
        reason: &str,
    ) {
        tracing::info!(
            user = %user_id,
            provider,
            reason,
            "push: pruning dead subscription"
        );
        if let Err(error) =
            sqlx::query("DELETE FROM push_subscriptions WHERE user_id = $1 AND token = $2")
                .bind(user_id)
                .bind(token)
                .execute(db)
                .await
        {
            tracing::warn!(user = %user_id, provider, %error, "push: failed to prune dead subscription");
        }
    }

    /// Send a standard, user-visible alert notification (banner + sound) to a
    /// user's devices. Currently fans out to `kind = 'apns'` subscriptions
    /// (regular APNs device tokens, NOT the VoIP tokens used for ringing).
    /// `sound` is a bundled notification sound file (None → system default).
    /// Like [`Self::notify_incoming`], never returns an error: every problem
    /// is logged and swallowed.
    #[allow(clippy::too_many_arguments)]
    pub async fn notify_alert(
        &self,
        db: &PgPool,
        user_id: Uuid,
        title: &str,
        body: &str,
        collapse_id: Option<&str>,
        sound: Option<&str>,
        ttl_secs: u32,
    ) {
        let subs: Vec<Subscription> = match sqlx::query_as(
            "SELECT kind, token, p256dh, auth FROM push_subscriptions WHERE user_id = $1",
        )
        .bind(user_id)
        .fetch_all(db)
        .await
        {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!(user = %user_id, error = %e, "push: failed to load subscriptions");
                return;
            }
        };

        if subs.is_empty() {
            tracing::info!(user = %user_id, "push: no subscriptions for alert");
            return;
        }

        stream::iter(subs)
            .for_each_concurrent(8, |sub| async move {
                self.notify_one_alert(db, user_id, sub, title, body, collapse_id, sound, ttl_secs)
                    .await;
            })
            .await;
    }

    #[allow(clippy::too_many_arguments)]
    async fn notify_one_alert(
        &self,
        db: &PgPool,
        user_id: Uuid,
        sub: Subscription,
        title: &str,
        body: &str,
        collapse_id: Option<&str>,
        sound: Option<&str>,
        ttl_secs: u32,
    ) {
        if sub.kind != "apns" {
            return;
        }
        if !Self::subscription_is_current(db, user_id, &sub).await {
            tracing::info!(user = %user_id, kind = %sub.kind, "push: alert subscription ownership changed before send");
            return;
        }
        let sent = self
            .apns
            .send_alert(&sub.token, title, body, collapse_id, sound, ttl_secs)
            .await;
        if let Err(error) = self
            .reap_dead_apns_token(db, user_id, &sub.token, sent)
            .await
        {
            tracing::warn!(user = %user_id, kind = %sub.kind, %error, "push: alert send failed");
        }
    }
}

#[cfg(test)]
mod tests {
    use chrono::Utc;
    use serde_json::json;
    use uuid::Uuid;

    use super::IncomingPush;

    #[test]
    fn call_style_knock_push_contains_call_contract_fields() {
        let call_id = Uuid::parse_str("11111111-1111-4111-8111-111111111111").unwrap();
        let from_user_id = Uuid::parse_str("22222222-2222-4222-8222-222222222222").unwrap();
        let payload = IncomingPush {
            kind: "incoming_call".to_string(),
            call_id: Some(call_id),
            call_type: Some("one_to_one".to_string()),
            from_user_id,
            from_name: "Taariv".to_string(),
            video_enabled: false,
            ring_style: "knock".to_string(),
            knock: true,
            expires_at_ms: Some(1_900_000_000_123),
        };

        assert_eq!(
            payload.data_map(),
            json!({
                "type": "incoming_call",
                "callId": call_id.to_string(),
                "callType": "one_to_one",
                "fromUserId": from_user_id.to_string(),
                "fromName": "Taariv",
                "videoEnabled": "false",
                "ringStyle": "knock",
                "knock": "true",
                "expiresAt": "1900000000123",
            })
        );
        assert_eq!(
            serde_json::to_value(&payload).unwrap()["expiresAt"],
            json!(1_900_000_000_123_i64)
        );
    }

    #[test]
    fn normal_call_push_does_not_set_knock_flag() {
        let from_user_id = Uuid::parse_str("22222222-2222-4222-8222-222222222222").unwrap();
        let payload = IncomingPush {
            kind: "incoming_call".to_string(),
            call_id: None,
            call_type: None,
            from_user_id,
            from_name: "Nikita".to_string(),
            video_enabled: true,
            ring_style: "call".to_string(),
            knock: false,
            expires_at_ms: None,
        };

        let data = payload.data_map();
        assert_eq!(data["type"], "incoming_call");
        assert_eq!(data["videoEnabled"], "true");
        assert_eq!(data["ringStyle"], "call");
        assert!(data.get("knock").is_none());
    }

    #[test]
    fn call_ended_push_keeps_terminal_event_type() {
        let call_id = Uuid::parse_str("11111111-1111-4111-8111-111111111111").unwrap();
        let from_user_id = Uuid::parse_str("22222222-2222-4222-8222-222222222222").unwrap();
        let payload = IncomingPush {
            kind: "call_ended".to_string(),
            call_id: Some(call_id),
            call_type: None,
            from_user_id,
            from_name: "Slide".to_string(),
            video_enabled: true,
            ring_style: "call".to_string(),
            knock: false,
            expires_at_ms: None,
        };

        let data = payload.data_map();
        assert_eq!(data["type"], "call_ended");
        assert_eq!(data["callId"], call_id.to_string());
        assert!(data.get("knock").is_none());
    }

    #[test]
    fn expired_invitation_has_zero_provider_ttl() {
        let payload = IncomingPush {
            kind: "incoming_call".to_string(),
            call_id: Some(Uuid::new_v4()),
            call_type: Some("one_to_one".to_string()),
            from_user_id: Uuid::new_v4(),
            from_name: "Slide".to_string(),
            video_enabled: true,
            ring_style: "call".to_string(),
            knock: false,
            expires_at_ms: Some(Utc::now().timestamp_millis() - 1),
        };

        assert_eq!(payload.ttl_secs(45), 0);
    }
}
