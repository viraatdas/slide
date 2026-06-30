//! Call control plane: create / accept / decline / leave / history.
//!
//! This owns the *control* path only — room allocation, participant state, and
//! signaling fan-out. Media (SDP/ICE/RTP) happens on the SFU node the client
//! reaches via `sfuUrl` + `joinToken`.

use axum::{
    extract::{Path, Query, State},
    http::{header::AUTHORIZATION, HeaderMap, StatusCode},
    Json,
};
use chrono::{DateTime, Utc};
use futures::future::join_all;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

use slide_core::{
    error::{AppError, AppResult},
    models::{Call, CallStatus, CallType, ParticipantState},
    turn::IceServer,
};

use crate::{auth::AuthUser, otp_store, sfu_client, state::AppState};

// ── Views ────────────────────────────────────────────────────────────────────

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ParticipantView {
    user_id: Uuid,
    state: String,
    joined_at: Option<DateTime<Utc>>,
    left_at: Option<DateTime<Utc>>,
    display_name: Option<String>,
    phone: Option<String>,
    avatar_url: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CallView {
    id: Uuid,
    room_id: String,
    sfu_node_id: String,
    #[serde(rename = "type")]
    call_type: CallType,
    created_by: Uuid,
    status: String,
    video_enabled: bool,
    ring_style: String,
    started_at: Option<DateTime<Utc>>,
    ended_at: Option<DateTime<Utc>>,
    created_at: DateTime<Utc>,
    participants: Vec<ParticipantView>,
}

#[derive(sqlx::FromRow)]
struct ParticipantRow {
    user_id: Uuid,
    state: ParticipantState,
    joined_at: Option<DateTime<Utc>>,
    left_at: Option<DateTime<Utc>>,
    display_name: Option<String>,
    phone: Option<String>,
    avatar_url: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JoinResponse {
    call: CallView,
    join_token: String,
    sfu_url: String,
    ice_servers: Vec<IceServer>,
}

fn participant_state_str(s: &slide_core::models::ParticipantState) -> String {
    serde_json::to_value(s)
        .ok()
        .and_then(|v| v.as_str().map(|s| s.to_string()))
        .unwrap_or_default()
}

fn call_status_str(s: &slide_core::models::CallStatus) -> String {
    serde_json::to_value(s)
        .ok()
        .and_then(|v| v.as_str().map(|s| s.to_string()))
        .unwrap_or_default()
}

async fn load_call_view(state: &AppState, call_id: Uuid) -> AppResult<CallView> {
    let call: Call = sqlx::query_as("SELECT * FROM calls WHERE id = $1")
        .bind(call_id)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound)?;

    let parts: Vec<ParticipantRow> = sqlx::query_as(
        "SELECT p.user_id, p.state, p.joined_at, p.left_at,
                    u.display_name, u.phone, u.avatar_url
               FROM call_participants p
               LEFT JOIN users u ON u.id = p.user_id
              WHERE p.call_id = $1",
    )
    .bind(call_id)
    .fetch_all(&state.db)
    .await?;

    Ok(CallView {
        id: call.id,
        room_id: call.room_id,
        sfu_node_id: call.sfu_node_id,
        call_type: call.call_type,
        created_by: call.created_by,
        status: call_status_str(&call.status),
        video_enabled: call.video_enabled,
        ring_style: call.ring_style,
        started_at: call.started_at,
        ended_at: call.ended_at,
        created_at: call.created_at,
        participants: parts
            .into_iter()
            .map(|p| ParticipantView {
                user_id: p.user_id,
                state: participant_state_str(&p.state),
                joined_at: p.joined_at,
                left_at: p.left_at,
                display_name: p.display_name,
                phone: p.phone,
                avatar_url: p.avatar_url,
            })
            .collect(),
    })
}

/// Call history/recovery is another pre-answer transport. A knock recipient
/// may poll immediately after push or WebSocket delivery, so mask every other
/// participant until this requester has actually joined. `joined_at` remains
/// set after a later leave, preserving the reveal for answered call history.
fn sanitize_call_view_for_requester(mut view: CallView, requester_id: Uuid) -> CallView {
    if view.ring_style != "knock" || view.created_by == requester_id {
        return view;
    }
    let requester_answered = view
        .participants
        .iter()
        .find(|participant| participant.user_id == requester_id)
        .and_then(|participant| participant.joined_at)
        .is_some();
    if requester_answered {
        return view;
    }

    view.created_by = Uuid::nil();
    for participant in &mut view.participants {
        if participant.user_id == requester_id {
            continue;
        }
        participant.user_id = Uuid::nil();
        participant.display_name = None;
        participant.phone = None;
        participant.avatar_url = None;
    }
    view
}

async fn participant_ids(state: &AppState, call_id: Uuid) -> AppResult<Vec<Uuid>> {
    let rows: Vec<(Uuid,)> =
        sqlx::query_as("SELECT user_id FROM call_participants WHERE call_id = $1")
            .bind(call_id)
            .fetch_all(&state.db)
            .await?;
    Ok(rows.into_iter().map(|(id,)| id).collect())
}

async fn participant_ids_in_tx(
    tx: &mut Transaction<'_, Postgres>,
    call_id: Uuid,
) -> AppResult<Vec<Uuid>> {
    let rows: Vec<(Uuid,)> =
        sqlx::query_as("SELECT user_id FROM call_participants WHERE call_id = $1")
            .bind(call_id)
            .fetch_all(&mut **tx)
            .await?;
    Ok(rows.into_iter().map(|(id,)| id).collect())
}

async fn call_display_name(state: &AppState, user_id: Uuid) -> AppResult<String> {
    let caller: Option<(Option<String>, String)> =
        sqlx::query_as("SELECT display_name, phone FROM users WHERE id = $1")
            .bind(user_id)
            .fetch_optional(&state.db)
            .await?;
    Ok(caller
        .as_ref()
        .and_then(|(name, _)| name.as_ref())
        .map(|name| name.trim())
        .filter(|name| !name.is_empty())
        .map(str::to_string)
        .or_else(|| caller.as_ref().map(|(_, phone)| phone.clone()))
        .unwrap_or_else(|| "Slide".to_string()))
}

// ── POST /calls ───────────────────────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateCallBody {
    #[serde(rename = "type")]
    pub call_type: CallType,
    pub participant_user_ids: Vec<Uuid>,
    #[serde(default = "default_video_enabled")]
    pub video_enabled: bool,
    /// `knock` is intentionally one-to-one: anonymous group lifecycle events
    /// cannot safely identify accepted/remaining members before each answers.
    #[serde(default = "default_ring_style")]
    pub ring_style: String,
}

fn default_video_enabled() -> bool {
    true
}

fn default_ring_style() -> String {
    "call".to_string()
}

fn validate_ring_style(call_type: CallType, ring_style: &str) -> AppResult<String> {
    if !matches!(ring_style, "call" | "knock") {
        return Err(AppError::bad_request("ringStyle must be call or knock"));
    }
    if ring_style == "knock" && matches!(call_type, CallType::Group) {
        return Err(AppError::bad_request(
            "ringStyle knock supports one_to_one calls only",
        ));
    }
    Ok(ring_style.to_string())
}

const MAX_CALLEES: usize = 8;
pub(crate) const ANONYMOUS_CALLER_NAME: &str = "Someone";
const CALL_ACCEPT_KEY_HEADER: &str = "x-call-accept-key";

/// Knock identity is revealed by the authoritative `/accept` response, never
/// by a pre-answer transport. A nil UUID keeps released clients that require a
/// syntactically valid `fromUserId` working without exposing a contact-mappable
/// account id.
fn preanswer_caller_identity(is_knock: bool, caller_id: Uuid, caller_name: &str) -> (Uuid, String) {
    if is_knock {
        (Uuid::nil(), ANONYMOUS_CALLER_NAME.to_string())
    } else {
        (caller_id, caller_name.to_string())
    }
}

fn incoming_call_event(
    view: &CallView,
    caller_id: Uuid,
    caller_name: &str,
    expires_at_ms: i64,
) -> serde_json::Value {
    let is_knock = view.ring_style == "knock";
    let (public_caller_id, public_caller_name) =
        preanswer_caller_identity(is_knock, caller_id, caller_name);
    let mut event = json!({
        "type": "incoming_call",
        "callId": view.id,
        "callType": view.call_type,
        "videoEnabled": view.video_enabled,
        "ringStyle": &view.ring_style,
        "knock": is_knock,
        "fromUserId": public_caller_id,
        "fromName": public_caller_name,
        "expiresAt": expires_at_ms,
    });
    if !is_knock {
        // CallView contains participant names, phones, avatars, and createdBy.
        // Omitting it for knocks is required in addition to masking the two
        // top-level identity fields. `/accept` still returns the full view.
        event["call"] = json!(view);
    }
    event
}

/// Hash the client installation's stable accept key before persistence. New
/// clients send `X-Call-Accept-Key`; released clients fall back to the current
/// bearer token so a lost-response retry remains idempotent during its normal
/// short accept window. The fallback is compatibility-only because access
/// tokens can rotate; maintained clients must send the explicit key.
fn call_accept_key(headers: &HeaderMap) -> AppResult<String> {
    let source = if let Some(value) = headers.get(CALL_ACCEPT_KEY_HEADER) {
        let key = value
            .to_str()
            .map_err(|_| AppError::validation("X-Call-Accept-Key must be valid ASCII"))?
            .trim();
        if !(8..=128).contains(&key.len())
            || !key
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
        {
            return Err(AppError::validation(
                "X-Call-Accept-Key must be 8-128 URL-safe characters",
            ));
        }
        format!("client:{key}")
    } else {
        let bearer = headers
            .get(AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.strip_prefix("Bearer "))
            .ok_or(AppError::Unauthorized)?;
        format!("legacy:{bearer}")
    };

    let mut hasher = Sha256::new();
    hasher.update(source.as_bytes());
    Ok(hex::encode(hasher.finalize()))
}

/// A joined callee is owned by the installation that won `/accept`. Sibling
/// devices still share the same account participant, so an unscoped cleanup
/// from a losing/logout race must not end the winner's media call. Creator and
/// pre-migration rows have no stored key and retain legacy leave semantics.
fn installation_may_leave(stored_accept_key: Option<&str>, requested_accept_key: &str) -> bool {
    stored_accept_key.is_none_or(|stored| stored == requested_accept_key)
}

pub async fn create_call(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    Json(body): Json<CreateCallBody>,
) -> AppResult<Json<JoinResponse>> {
    // Creating a call causes high-priority push fanout. Bound it per account so
    // a compromised client cannot turn this endpoint into a notification-spam
    // service. Normal retries and quick redials remain comfortably below this.
    otp_store::rate_limit(&state, &format!("rl:calls:10s:{uid}"), 5, 10).await?;
    otp_store::rate_limit(&state, &format!("rl:calls:1h:{uid}"), 60, 3_600).await?;

    let ring_style = validate_ring_style(body.call_type, &body.ring_style)?;
    // Validate participants.
    let mut callees: Vec<Uuid> = body
        .participant_user_ids
        .into_iter()
        .filter(|id| *id != uid)
        .collect();
    callees.sort();
    callees.dedup();

    if callees.is_empty() {
        return Err(AppError::bad_request("at least one participant required"));
    }
    if callees.len() > MAX_CALLEES {
        return Err(AppError::bad_request("calls support at most 8 invitees"));
    }
    if matches!(body.call_type, CallType::OneToOne) && callees.len() != 1 {
        return Err(AppError::bad_request(
            "one_to_one needs exactly one participant",
        ));
    }

    // Ensure all callees exist.
    let found: Vec<(Uuid,)> = sqlx::query_as("SELECT id FROM users WHERE id = ANY($1)")
        .bind(&callees)
        .fetch_all(&state.db)
        .await?;
    if found.len() != callees.len() {
        return Err(AppError::bad_request("unknown participant"));
    }

    let from_name = call_display_name(&state, uid).await?;
    let ice = sfu_client::ice_servers(&state, uid);

    let mut tx = state.db.begin().await?;
    // Serialize creates from this account just long enough to make identical
    // 1:1 retries idempotent. This catches double taps and HTTP retries even
    // though released clients do not yet send an explicit idempotency key.
    let lock_key = i64::from_be_bytes(uid.as_bytes()[..8].try_into().expect("UUID prefix"));
    sqlx::query("SELECT pg_advisory_xact_lock($1)")
        .bind(lock_key)
        .execute(&mut *tx)
        .await?;

    if matches!(body.call_type, CallType::OneToOne) {
        let existing: Option<Call> = sqlx::query_as(
            "SELECT c.*
               FROM calls c
               JOIN call_participants peer
                 ON peer.call_id = c.id AND peer.user_id = $2
              WHERE c.created_by = $1
                AND c.type = 'one_to_one'
                AND c.status = 'ringing'
                AND c.video_enabled = $3
                AND c.ring_style = $4
                AND c.created_at > now() - make_interval(secs => $5)
                AND (SELECT count(*) FROM call_participants p WHERE p.call_id = c.id) = 2
              ORDER BY c.created_at DESC
              LIMIT 1
              FOR UPDATE OF c",
        )
        .bind(uid)
        .bind(callees[0])
        .bind(body.video_enabled)
        .bind(&ring_style)
        .bind(state.cfg.call_ring_timeout_secs as f64)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(existing) = existing {
            let (sfu_url, join_token) = sfu_client::media_join(
                &state,
                uid,
                Some(&from_name),
                existing.id,
                &existing.room_id,
                &existing.sfu_node_id,
            )?;
            tx.commit().await?;
            let view = load_call_view(&state, existing.id).await?;
            let ring_state = state.clone();
            let ring_view = view.clone();
            let ring_callees = callees.clone();
            tokio::spawn(async move {
                ring_callees_for_call(ring_state, ring_callees, uid, from_name, ring_view).await;
            });
            return Ok(Json(JoinResponse {
                call: view,
                join_token,
                sfu_url,
                ice_servers: ice,
            }));
        }
    }

    let call_id = Uuid::new_v4();
    let alloc = sfu_client::allocate_room(&state, call_id);
    // Minting is pure but can fail on bad production configuration. Do it
    // before persisting/ringing so the caller never gets an error for a ghost
    // invitation that still reaches the callee.
    let (sfu_url, join_token) = sfu_client::media_join(
        &state,
        uid,
        Some(&from_name),
        call_id,
        &alloc.room_id,
        &alloc.sfu_node_id,
    )?;

    sqlx::query(
        "INSERT INTO calls (id, room_id, sfu_node_id, type, created_by, status, video_enabled, ring_style)
         VALUES ($1, $2, $3, $4, $5, 'ringing', $6, $7)",
    )
    .bind(call_id)
    .bind(&alloc.room_id)
    .bind(&alloc.sfu_node_id)
    .bind(body.call_type)
    .bind(uid)
    .bind(body.video_enabled)
    .bind(&ring_style)
    .execute(&mut *tx)
    .await?;

    // Creator joins immediately; callees are ringing.
    sqlx::query(
        "INSERT INTO call_participants (call_id, user_id, state, joined_at)
         VALUES ($1, $2, 'joined', now())",
    )
    .bind(call_id)
    .bind(uid)
    .execute(&mut *tx)
    .await?;

    for c in &callees {
        sqlx::query(
            "INSERT INTO call_participants (call_id, user_id, state) VALUES ($1, $2, 'ringing')",
        )
        .bind(call_id)
        .bind(c)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;

    let view = load_call_view(&state, call_id).await?;

    let ring_state = state.clone();
    let ring_view = view.clone();
    let ring_callees = callees.clone();
    tokio::spawn(async move {
        ring_callees_for_call(ring_state, ring_callees, uid, from_name, ring_view).await;
    });

    Ok(Json(JoinResponse {
        call: view,
        join_token,
        sfu_url,
        ice_servers: ice,
    }))
}

async fn ring_callees_for_call(
    state: AppState,
    callees: Vec<Uuid>,
    caller_id: Uuid,
    from_name: String,
    view: CallView,
) {
    let call_id = view.id;
    let is_knock = view.ring_style == "knock";
    let expires_at = view.created_at + chrono::Duration::seconds(state.cfg.call_ring_timeout_secs);
    let expires_at_ms = expires_at.timestamp_millis();
    let (public_caller_id, public_caller_name) =
        preanswer_caller_identity(is_knock, caller_id, &from_name);
    let event = incoming_call_event(&view, caller_id, &from_name, expires_at_ms);

    let push_payload = crate::push::IncomingPush {
        kind: "incoming_call".to_string(),
        call_id: Some(view.id),
        call_type: serde_json::to_value(view.call_type)
            .ok()
            .and_then(|value| value.as_str().map(str::to_string)),
        from_user_id: public_caller_id,
        from_name: public_caller_name,
        video_enabled: view.video_enabled,
        ring_style: view.ring_style,
        knock: is_knock,
        expires_at_ms: Some(expires_at_ms),
    };

    // Group members fan out concurrently so one provider timeout cannot make
    // later callees miss most of the ring window (groups are capped at eight).
    join_all(callees.into_iter().map(|callee| {
        let event = event.clone();
        let state = &state;
        let push_payload = &push_payload;
        async move {
            if !invitation_is_deliverable(state, call_id, callee, expires_at_ms).await {
                tracing::info!(callee = %callee, call = %call_id, "skipping stale incoming-call delivery");
                return;
            }
            let delivered = state.hub.publish(callee, event).await;
            // A successful queue write is not proof a mobile app can render
            // the event: iOS keeps suspended sockets open, and one foreground
            // device must not suppress pushes to this user's other devices.
            tracing::info!(callee = %callee, websocket_deliveries = delivered, "sending incoming-call push");
            // Accept/leave can race the detached ring task while its WS event
            // is queued. Recheck again immediately before the slower provider
            // path; terminal events/collapse handle the remaining tiny race.
            if !invitation_is_deliverable(state, call_id, callee, expires_at_ms).await {
                tracing::info!(callee = %callee, call = %call_id, "skipping stale incoming-call push");
                return;
            }
            state
                .push
                .notify_incoming(&state.db, callee, push_payload)
                .await;
        }
    }))
    .await;
}

async fn invitation_is_deliverable(
    state: &AppState,
    call_id: Uuid,
    callee: Uuid,
    expires_at_ms: i64,
) -> bool {
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
    .bind(callee)
    .bind(expires_at_ms)
    .fetch_one(&state.db)
    .await
    {
        Ok(deliverable) => deliverable,
        Err(error) => {
            tracing::warn!(call = %call_id, %callee, %error, "failed to recheck invitation; suppressing delivery");
            false
        }
    }
}

fn spawn_call_closed_push(state: AppState, recipients: Vec<Uuid>, call_id: Uuid) {
    tokio::spawn(async move {
        let payload = crate::push::IncomingPush {
            kind: "call_ended".to_string(),
            call_id: Some(call_id),
            call_type: None,
            // Terminal routing needs only callId. Keeping this anonymous also
            // prevents a cancelled/missed knock from revealing the caller to a
            // recipient who never answered.
            from_user_id: Uuid::nil(),
            from_name: "Slide".to_string(),
            video_enabled: true,
            ring_style: "call".to_string(),
            knock: false,
            expires_at_ms: None,
        };
        join_all(
            recipients
                .into_iter()
                .map(|recipient| state.push.notify_incoming(&state.db, recipient, &payload)),
        )
        .await;
    });
}

/// Tell the accepting account's other installations to stop ringing. The
/// installation that performed the accept already has matching connecting
/// media state and ignores this idempotent event; idle siblings dismiss it.
fn spawn_call_accepted_push(state: AppState, user_id: Uuid, call_id: Uuid) {
    tokio::spawn(async move {
        let payload = crate::push::IncomingPush {
            kind: "call_accepted".to_string(),
            call_id: Some(call_id),
            call_type: None,
            from_user_id: user_id,
            from_name: "Slide".to_string(),
            video_enabled: true,
            ring_style: "call".to_string(),
            knock: false,
            expires_at_ms: None,
        };
        state
            .push
            .notify_incoming(&state.db, user_id, &payload)
            .await;
    });
}

/// Fire-and-forget visible "missed knock" alert to the callee(s) when a
/// ringing 1:1 call ends unanswered. Mirrors `spawn_call_closed_push`.
fn spawn_missed_call_alert(
    state: AppState,
    recipients: Vec<Uuid>,
    call_id: Uuid,
    ring_style: String,
) {
    tokio::spawn(async move {
        let (title, body) = if ring_style == "knock" {
            ("You missed a knock", "Someone knocked while you were away")
        } else {
            ("Missed call", "Open Knock Knock to call them back")
        };
        let collapse_id = format!("missed-{call_id}");
        join_all(recipients.into_iter().map(|recipient| {
            state.push.notify_alert(
                &state.db,
                recipient,
                title,
                body,
                Some(&collapse_id),
                None,
                86_400,
            )
        }))
        .await;
    });
}

#[derive(sqlx::FromRow)]
struct ExpiredCall {
    id: Uuid,
    created_by: Uuid,
    ring_style: String,
}

#[derive(sqlx::FromRow)]
struct ExpiredParticipant {
    call_id: Uuid,
    user_id: Uuid,
    state: ParticipantState,
}

#[derive(sqlx::FromRow)]
struct ExpiredInvite {
    call_id: Uuid,
    user_id: Uuid,
    ring_style: String,
}

/// Durable ringing-call expiry. This scans Postgres rather than sleeping per
/// create, so stale calls are repaired after API restarts as well.
pub async fn run_call_expirer(state: AppState) {
    let mut ticker = tokio::time::interval(std::time::Duration::from_secs(5));
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        ticker.tick().await;
        if let Err(error) = expire_stale_calls(&state).await {
            tracing::error!(%error, "call expiry sweep failed");
        }
    }
}

async fn expire_stale_calls(state: &AppState) -> AppResult<usize> {
    let mut tx = state.db.begin().await?;
    let expired: Vec<ExpiredCall> = sqlx::query_as(
        "WITH candidates AS (
             SELECT id
               FROM calls
              WHERE status = 'ringing'
                AND created_at <= now() - make_interval(secs => $1)
              ORDER BY created_at
              FOR UPDATE SKIP LOCKED
              LIMIT 100
         )
         UPDATE calls c
            SET status = 'missed', ended_at = now()
           FROM candidates
          WHERE c.id = candidates.id
         RETURNING c.id, c.created_by, c.ring_style",
    )
    .bind(state.cfg.call_ring_timeout_secs as f64)
    .fetch_all(&mut *tx)
    .await?;

    // A group call becomes active as soon as one person accepts, but its other
    // ringing invitations still need the same deadline. Expire only those
    // participant rows; the active room remains alive for joined members.
    let expired_invites: Vec<ExpiredInvite> = sqlx::query_as(
        "WITH candidates AS (
             SELECT p.id, p.call_id, p.user_id, c.ring_style
               FROM call_participants p
               JOIN calls c ON c.id = p.call_id
              WHERE p.state = 'ringing'
                AND c.status = 'active'
                AND c.created_at <= now() - make_interval(secs => $1)
              ORDER BY c.created_at
              FOR UPDATE OF p SKIP LOCKED
              LIMIT 100
         )
         UPDATE call_participants p
            SET state = 'left', left_at = COALESCE(p.left_at, now())
           FROM candidates
          WHERE p.id = candidates.id
         RETURNING p.call_id, p.user_id, candidates.ring_style",
    )
    .bind(state.cfg.call_ring_timeout_secs as f64)
    .fetch_all(&mut *tx)
    .await?;

    if expired.is_empty() && expired_invites.is_empty() {
        tx.commit().await?;
        return Ok(0);
    }

    let call_ids: Vec<Uuid> = expired.iter().map(|call| call.id).collect();
    let participants: Vec<ExpiredParticipant> = sqlx::query_as(
        "SELECT call_id, user_id, state
           FROM call_participants
          WHERE call_id = ANY($1)",
    )
    .bind(&call_ids)
    .fetch_all(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE call_participants
            SET state = 'left', left_at = COALESCE(left_at, now())
          WHERE call_id = ANY($1) AND state IN ('ringing', 'joined')",
    )
    .bind(&call_ids)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    for call in &expired {
        let all: Vec<Uuid> = participants
            .iter()
            .filter(|participant| participant.call_id == call.id)
            .map(|participant| participant.user_id)
            .collect();
        let callees: Vec<Uuid> = participants
            .iter()
            .filter(|participant| participant.call_id == call.id)
            .filter(|participant| {
                participant.user_id != call.created_by
                    && matches!(participant.state, ParticipantState::Ringing)
            })
            .map(|participant| participant.user_id)
            .collect();
        let event = json!({ "type": "call_ended", "callId": call.id });
        state.hub.publish_many(&all, &event).await;
        spawn_call_closed_push(state.clone(), all, call.id);
        spawn_missed_call_alert(state.clone(), callees, call.id, call.ring_style.clone());
    }

    for invite in &expired_invites {
        let event = json!({ "type": "call_ended", "callId": invite.call_id });
        state.hub.publish(invite.user_id, event).await;
        spawn_call_closed_push(state.clone(), vec![invite.user_id], invite.call_id);
        spawn_missed_call_alert(
            state.clone(),
            vec![invite.user_id],
            invite.call_id,
            invite.ring_style.clone(),
        );

        let others: Vec<Uuid> = participant_ids(state, invite.call_id)
            .await?
            .into_iter()
            .filter(|user_id| *user_id != invite.user_id)
            .collect();
        let participant_left = json!({
            "type": "participant_left",
            "callId": invite.call_id,
            "userId": invite.user_id,
        });
        state.hub.publish_many(&others, &participant_left).await;
    }

    let count = expired.len() + expired_invites.len();
    tracing::info!(
        calls = expired.len(),
        group_invites = expired_invites.len(),
        "expired unanswered calls/invitations"
    );
    Ok(count)
}

// ── helpers to ensure the caller belongs to the call ──────────────────────────

struct ParticipantAccess {
    call: Call,
    participant_state: ParticipantState,
    accept_key: Option<String>,
}

async fn require_participant_for_update(
    tx: &mut Transaction<'_, Postgres>,
    call_id: Uuid,
    uid: Uuid,
) -> AppResult<ParticipantAccess> {
    // The call row is the lifecycle mutex. Every state-changing endpoint locks
    // it before reading participant state, so accept/decline/leave races are
    // serialized even when the API later runs on several nodes.
    let call: Call = sqlx::query_as("SELECT * FROM calls WHERE id = $1 FOR UPDATE")
        .bind(call_id)
        .fetch_optional(&mut **tx)
        .await?
        .ok_or(AppError::NotFound)?;
    let participant: Option<(ParticipantState, Option<String>)> = sqlx::query_as(
        "SELECT state, accept_key FROM call_participants
              WHERE call_id = $1 AND user_id = $2
              FOR UPDATE",
    )
    .bind(call_id)
    .bind(uid)
    .fetch_optional(&mut **tx)
    .await?;
    let (participant_state, accept_key) = participant.ok_or(AppError::Forbidden)?;
    Ok(ParticipantAccess {
        call,
        participant_state,
        accept_key,
    })
}

// ── POST /calls/:id/accept ────────────────────────────────────────────────────

pub async fn accept_call(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    headers: HeaderMap,
    Path(call_id): Path<Uuid>,
) -> AppResult<Json<JoinResponse>> {
    let requested_accept_key = call_accept_key(&headers)?;
    let display_name = call_display_name(&state, uid).await?;
    let mut tx = state.db.begin().await?;
    let access = require_participant_for_update(&mut tx, call_id, uid).await?;
    let call = access.call;
    if matches!(
        call.status,
        CallStatus::Ended | CallStatus::Missed | CallStatus::Declined
    ) {
        return Err(AppError::conflict("call already ended"));
    }
    if matches!(access.participant_state, ParticipantState::Ringing)
        && call.created_at
            <= Utc::now() - chrono::Duration::seconds(state.cfg.call_ring_timeout_secs)
    {
        return Err(AppError::conflict("call invitation expired"));
    }
    let newly_joined = match (access.participant_state, call.status) {
        (ParticipantState::Ringing, CallStatus::Ringing | CallStatus::Active) => true,
        (ParticipantState::Joined, CallStatus::Active)
            if access.accept_key.as_deref() == Some(requested_accept_key.as_str()) =>
        {
            false
        }
        (ParticipantState::Joined, CallStatus::Active) => {
            return Err(AppError::conflict("call answered on another installation"));
        }
        _ => return Err(AppError::conflict("call is not ringing")),
    };
    let (sfu_url, join_token) = sfu_client::media_join(
        &state,
        uid,
        Some(&display_name),
        call_id,
        &call.room_id,
        &call.sfu_node_id,
    )?;
    let ice = sfu_client::ice_servers(&state, uid);

    if newly_joined {
        sqlx::query(
            "UPDATE call_participants
                    SET state = 'joined',
                        joined_at = COALESCE(joined_at, now()),
                        accept_key = $3
                  WHERE call_id = $1 AND user_id = $2 AND state = 'ringing'",
        )
        .bind(call_id)
        .bind(uid)
        .bind(&requested_accept_key)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "UPDATE calls
                    SET status = 'active', started_at = COALESCE(started_at, now())
                  WHERE id = $1",
        )
        .bind(call_id)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;

    let view = load_call_view(&state, call_id).await?;

    // Retrying with the winning installation key returns a fresh media token
    // without replaying the lifecycle event; a sibling key was rejected above.
    // The first accept is published back to the accepting user as well as the
    // other participants so sibling installations stop ringing.
    if newly_joined {
        let participants = participant_ids(&state, call_id).await?;
        let event = json!({ "type": "call_accepted", "callId": call_id, "userId": uid });
        state.hub.publish_many(&participants, &event).await;
        spawn_call_accepted_push(state.clone(), uid, call_id);
    }

    Ok(Json(JoinResponse {
        call: view,
        join_token,
        sfu_url,
        ice_servers: ice,
    }))
}

// ── POST /calls/:id/decline ───────────────────────────────────────────────────

pub async fn decline_call(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    Path(call_id): Path<Uuid>,
) -> AppResult<StatusCode> {
    let mut tx = state.db.begin().await?;
    let access = require_participant_for_update(&mut tx, call_id, uid).await?;
    let call = access.call;
    if matches!(
        call.status,
        CallStatus::Ended | CallStatus::Missed | CallStatus::Declined
    ) {
        tx.commit().await?;
        return Ok(StatusCode::NO_CONTENT);
    }
    if !matches!(access.participant_state, ParticipantState::Ringing) {
        tx.commit().await?;
        return Ok(StatusCode::NO_CONTENT);
    }

    let others: Vec<Uuid> = participant_ids_in_tx(&mut tx, call_id)
        .await?
        .into_iter()
        .filter(|id| *id != uid)
        .collect();

    if matches!(call.call_type, CallType::OneToOne) {
        if !matches!(call.status, CallStatus::Ringing) {
            tx.commit().await?;
            return Ok(StatusCode::NO_CONTENT);
        }
        sqlx::query("UPDATE calls SET status = 'declined', ended_at = now() WHERE id = $1")
            .bind(call_id)
            .execute(&mut *tx)
            .await?;
        sqlx::query(
            "UPDATE call_participants SET state = 'declined' WHERE call_id = $1 AND user_id = $2",
        )
        .bind(call_id)
        .bind(uid)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "UPDATE call_participants
                SET state = 'left', left_at = COALESCE(left_at, now())
              WHERE call_id = $1 AND state = 'joined'",
        )
        .bind(call_id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        let event = json!({ "type": "call_declined", "callId": call_id, "userId": uid });
        state.hub.publish_many(&others, &event).await;
        let end = json!({ "type": "call_ended", "callId": call_id });
        let mut all = others;
        all.push(uid);
        state.hub.publish_many(&all, &end).await;
        spawn_call_closed_push(state.clone(), all, call_id);
        return Ok(StatusCode::NO_CONTENT);
    }

    sqlx::query(
        "UPDATE call_participants SET state = 'declined' WHERE call_id = $1 AND user_id = $2",
    )
    .bind(call_id)
    .bind(uid)
    .execute(&mut *tx)
    .await?;

    let mut ended = false;
    // The creator is inserted as joined before anyone answers. It must not keep
    // an otherwise-empty group invitation alive after the last callee declines.
    let remaining_callees: Vec<(Uuid,)> = sqlx::query_as(
        "SELECT user_id
           FROM call_participants
          WHERE call_id = $1
            AND user_id <> $2
            AND state IN ('ringing', 'joined')",
    )
    .bind(call_id)
    .bind(call.created_by)
    .fetch_all(&mut *tx)
    .await?;
    if remaining_callees.is_empty() {
        sqlx::query("UPDATE calls SET status = 'ended', ended_at = now() WHERE id = $1")
            .bind(call_id)
            .execute(&mut *tx)
            .await?;
        sqlx::query(
            "UPDATE call_participants
                SET state = 'left', left_at = COALESCE(left_at, now())
              WHERE call_id = $1 AND state IN ('ringing', 'joined')",
        )
        .bind(call_id)
        .execute(&mut *tx)
        .await?;
        ended = true;
    }
    tx.commit().await?;

    // `call_declined` is terminal in released clients, so a single member
    // declining a live group is represented as participant_left instead.
    let event = json!({ "type": "participant_left", "callId": call_id, "userId": uid });
    state.hub.publish_many(&others, &event).await;
    let self_end = json!({ "type": "call_ended", "callId": call_id });
    state.hub.publish(uid, self_end).await;
    spawn_call_closed_push(state.clone(), vec![uid], call_id);
    if ended {
        let end = json!({ "type": "call_ended", "callId": call_id });
        state.hub.publish_many(&others, &end).await;
        spawn_call_closed_push(state.clone(), others, call_id);
    }

    Ok(StatusCode::NO_CONTENT)
}

// ── POST /calls/:id/leave ─────────────────────────────────────────────────────

pub async fn leave_call(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    headers: HeaderMap,
    Path(call_id): Path<Uuid>,
) -> AppResult<StatusCode> {
    let mut tx = state.db.begin().await?;
    let access = require_participant_for_update(&mut tx, call_id, uid).await?;
    if matches!(access.participant_state, ParticipantState::Joined) && access.accept_key.is_some() {
        let requested_accept_key = call_accept_key(&headers)?;
        if !installation_may_leave(access.accept_key.as_deref(), &requested_accept_key) {
            tracing::info!(call = %call_id, user = %uid, "ignoring leave from non-owning installation");
            tx.commit().await?;
            return Ok(StatusCode::NO_CONTENT);
        }
    }
    let call = access.call;
    if matches!(
        call.status,
        CallStatus::Ended | CallStatus::Missed | CallStatus::Declined
    ) {
        tx.commit().await?;
        return Ok(StatusCode::NO_CONTENT);
    }

    let others: Vec<Uuid> = participant_ids_in_tx(&mut tx, call_id)
        .await?
        .into_iter()
        .filter(|id| *id != uid)
        .collect();

    if matches!(call.call_type, CallType::OneToOne) {
        if matches!(access.participant_state, ParticipantState::Ringing) {
            if !matches!(call.status, CallStatus::Ringing) {
                tx.commit().await?;
                return Ok(StatusCode::NO_CONTENT);
            }
            sqlx::query("UPDATE calls SET status = 'declined', ended_at = now() WHERE id = $1")
                .bind(call_id)
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                "UPDATE call_participants SET state = 'declined'
                  WHERE call_id = $1 AND user_id = $2 AND state = 'ringing'",
            )
            .bind(call_id)
            .bind(uid)
            .execute(&mut *tx)
            .await?;
            sqlx::query(
                "UPDATE call_participants
                    SET state = 'left', left_at = COALESCE(left_at, now())
                  WHERE call_id = $1 AND state = 'joined'",
            )
            .bind(call_id)
            .execute(&mut *tx)
            .await?;
            tx.commit().await?;
            let event = json!({ "type": "call_declined", "callId": call_id, "userId": uid });
            state.hub.publish_many(&others, &event).await;
            let end = json!({ "type": "call_ended", "callId": call_id });
            let mut all = others;
            all.push(uid);
            state.hub.publish_many(&all, &end).await;
            spawn_call_closed_push(state.clone(), all, call_id);
            return Ok(StatusCode::NO_CONTENT);
        }

        if !matches!(access.participant_state, ParticipantState::Joined) {
            tx.commit().await?;
            return Ok(StatusCode::NO_CONTENT);
        }

        sqlx::query(
            "UPDATE call_participants SET state = 'left', left_at = now()
             WHERE call_id = $1 AND user_id = $2",
        )
        .bind(call_id)
        .bind(uid)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "UPDATE call_participants
                SET state = 'left', left_at = COALESCE(left_at, now())
              WHERE call_id = $1 AND state = 'ringing'",
        )
        .bind(call_id)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "UPDATE call_participants
                SET state = 'left', left_at = COALESCE(left_at, now())
              WHERE call_id = $1 AND state = 'joined'",
        )
        .bind(call_id)
        .execute(&mut *tx)
        .await?;
        let final_status = if matches!(call.status, CallStatus::Ringing) {
            CallStatus::Missed
        } else {
            CallStatus::Ended
        };
        sqlx::query(
            "UPDATE calls
                SET status = $2, ended_at = now()
              WHERE id = $1",
        )
        .bind(call_id)
        .bind(final_status)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        let event = json!({ "type": "participant_left", "callId": call_id, "userId": uid });
        state.hub.publish_many(&others, &event).await;
        let end = json!({ "type": "call_ended", "callId": call_id });
        let mut all = others.clone();
        all.push(uid);
        state.hub.publish_many(&all, &end).await;
        // Caller hung up before the callee answered → tell the callee they
        // missed it with a visible alert push (the VoIP ring alone vanishes).
        if matches!(final_status, CallStatus::Missed) && uid == call.created_by {
            spawn_missed_call_alert(
                state.clone(),
                others.clone(),
                call_id,
                call.ring_style.clone(),
            );
        }
        spawn_call_closed_push(state.clone(), all, call_id);
        return Ok(StatusCode::NO_CONTENT);
    }

    if !matches!(
        access.participant_state,
        ParticipantState::Joined | ParticipantState::Ringing
    ) {
        tx.commit().await?;
        return Ok(StatusCode::NO_CONTENT);
    }

    let ringing_recipients: Vec<Uuid> = sqlx::query_as::<_, (Uuid,)>(
        "SELECT user_id
           FROM call_participants
          WHERE call_id = $1 AND state = 'ringing' AND user_id <> $2",
    )
    .bind(call_id)
    .bind(uid)
    .fetch_all(&mut *tx)
    .await?
    .into_iter()
    .map(|(user_id,)| user_id)
    .collect();

    sqlx::query(
        "UPDATE call_participants SET state = 'left', left_at = now()
         WHERE call_id = $1 AND user_id = $2",
    )
    .bind(call_id)
    .bind(uid)
    .execute(&mut *tx)
    .await?;

    // End the call when no one is still joined.
    let still_joined: Vec<(Uuid,)> = sqlx::query_as(
        "SELECT user_id FROM call_participants WHERE call_id = $1 AND state = 'joined'",
    )
    .bind(call_id)
    .fetch_all(&mut *tx)
    .await?;
    let mut final_status = None;
    if still_joined.is_empty() {
        let status = if matches!(call.status, CallStatus::Ringing) {
            CallStatus::Missed
        } else {
            CallStatus::Ended
        };
        sqlx::query("UPDATE calls SET status = $2, ended_at = now() WHERE id = $1")
            .bind(call_id)
            .bind(status)
            .execute(&mut *tx)
            .await?;
        sqlx::query(
            "UPDATE call_participants
                SET state = 'left', left_at = COALESCE(left_at, now())
              WHERE call_id = $1 AND state = 'ringing'",
        )
        .bind(call_id)
        .execute(&mut *tx)
        .await?;
        final_status = Some(status);
    }
    tx.commit().await?;

    let event = json!({ "type": "participant_left", "callId": call_id, "userId": uid });
    state.hub.publish_many(&others, &event).await;
    let self_end = json!({ "type": "call_ended", "callId": call_id });
    state.hub.publish(uid, self_end).await;
    spawn_call_closed_push(state.clone(), vec![uid], call_id);
    if let Some(status) = final_status {
        let end = json!({ "type": "call_ended", "callId": call_id });
        state.hub.publish_many(&others, &end).await;
        if matches!(status, CallStatus::Missed) {
            spawn_missed_call_alert(
                state.clone(),
                ringing_recipients,
                call_id,
                call.ring_style.clone(),
            );
        }
        spawn_call_closed_push(state.clone(), others, call_id);
    }

    Ok(StatusCode::NO_CONTENT)
}

// ── GET /calls?cursor= ────────────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct HistoryQuery {
    pub cursor: Option<String>,
    pub limit: Option<i64>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryResponse {
    calls: Vec<CallView>,
    next_cursor: Option<String>,
}

pub async fn list_calls(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    Query(q): Query<HistoryQuery>,
) -> AppResult<Json<HistoryResponse>> {
    let limit = q.limit.unwrap_or(30).clamp(1, 100);
    let cursor_ts: Option<DateTime<Utc>> = match q.cursor.as_deref() {
        Some(c) => Some(
            c.parse()
                .map_err(|_| AppError::bad_request("invalid cursor"))?,
        ),
        None => None,
    };

    // Calls the user participates in, newest first, keyset-paginated by created_at.
    let calls: Vec<Call> = sqlx::query_as(
        "SELECT c.* FROM calls c
           JOIN call_participants p ON p.call_id = c.id
          WHERE p.user_id = $1
            AND ($2::timestamptz IS NULL OR c.created_at < $2)
          ORDER BY c.created_at DESC
          LIMIT $3",
    )
    .bind(uid)
    .bind(cursor_ts)
    .bind(limit)
    .fetch_all(&state.db)
    .await?;

    let next_cursor = if calls.len() as i64 == limit {
        calls.last().map(|c| c.created_at.to_rfc3339())
    } else {
        None
    };

    let mut views = Vec::with_capacity(calls.len());
    for c in calls {
        let view = load_call_view(&state, c.id).await?;
        views.push(sanitize_call_view_for_requester(view, uid));
    }

    Ok(Json(HistoryResponse {
        calls: views,
        next_cursor,
    }))
}

#[cfg(test)]
mod tests {
    use axum::http::{header::AUTHORIZATION, HeaderMap, HeaderValue};
    use chrono::Utc;
    use slide_core::models::CallType;
    use uuid::Uuid;

    use super::{
        call_accept_key, incoming_call_event, installation_may_leave,
        sanitize_call_view_for_requester, validate_ring_style, CallView, ParticipantView,
        ANONYMOUS_CALLER_NAME, CALL_ACCEPT_KEY_HEADER,
    };

    fn call_view(ring_style: &str, caller_id: Uuid) -> CallView {
        CallView {
            id: Uuid::new_v4(),
            room_id: "room-secret".to_string(),
            sfu_node_id: "sfu-secret".to_string(),
            call_type: CallType::OneToOne,
            created_by: caller_id,
            status: "ringing".to_string(),
            video_enabled: false,
            ring_style: ring_style.to_string(),
            started_at: None,
            ended_at: None,
            created_at: Utc::now(),
            participants: vec![ParticipantView {
                user_id: caller_id,
                state: "joined".to_string(),
                joined_at: Some(Utc::now()),
                left_at: None,
                display_name: Some("Private Caller".to_string()),
                phone: Some("+15555550123".to_string()),
                avatar_url: Some("https://example.test/private.jpg".to_string()),
            }],
        }
    }

    #[test]
    fn knock_invitation_contains_no_preanswer_identity_or_full_call_view() {
        let caller_id = Uuid::new_v4();
        let event = incoming_call_event(
            &call_view("knock", caller_id),
            caller_id,
            "Private Caller",
            1_900_000_000_000,
        );

        assert_eq!(event["fromUserId"], Uuid::nil().to_string());
        assert_eq!(event["fromName"], ANONYMOUS_CALLER_NAME);
        assert!(event.get("call").is_none());
        let encoded = event.to_string();
        assert!(!encoded.contains("Private Caller"));
        assert!(!encoded.contains("+15555550123"));
        assert!(!encoded.contains(&caller_id.to_string()));
    }

    #[test]
    fn normal_call_invitation_keeps_caller_identity_and_call_view() {
        let caller_id = Uuid::new_v4();
        let event = incoming_call_event(
            &call_view("call", caller_id),
            caller_id,
            "Visible Caller",
            1_900_000_000_000,
        );

        assert_eq!(event["fromUserId"], caller_id.to_string());
        assert_eq!(event["fromName"], "Visible Caller");
        assert_eq!(event["call"]["createdBy"], caller_id.to_string());
    }

    #[test]
    fn knocks_are_one_to_one_only() {
        assert!(validate_ring_style(CallType::OneToOne, "knock").is_ok());
        assert!(validate_ring_style(CallType::Group, "call").is_ok());
        assert!(validate_ring_style(CallType::Group, "knock").is_err());
    }

    #[test]
    fn unanswered_knock_history_masks_identity_but_answered_history_reveals_it() {
        let caller_id = Uuid::new_v4();
        let callee_id = Uuid::new_v4();
        let mut unanswered = call_view("knock", caller_id);
        unanswered.participants.push(ParticipantView {
            user_id: callee_id,
            state: "ringing".to_string(),
            joined_at: None,
            left_at: None,
            display_name: Some("Recipient".to_string()),
            phone: None,
            avatar_url: None,
        });

        let hidden = sanitize_call_view_for_requester(unanswered.clone(), callee_id);
        assert_eq!(hidden.created_by, Uuid::nil());
        let hidden_caller = hidden
            .participants
            .iter()
            .find(|participant| participant.user_id != callee_id)
            .unwrap();
        assert_eq!(hidden_caller.user_id, Uuid::nil());
        assert!(hidden_caller.display_name.is_none());
        assert!(hidden_caller.phone.is_none());

        let callee = unanswered
            .participants
            .iter_mut()
            .find(|participant| participant.user_id == callee_id)
            .unwrap();
        callee.state = "joined".to_string();
        callee.joined_at = Some(Utc::now());
        let revealed = sanitize_call_view_for_requester(unanswered, callee_id);
        assert_eq!(revealed.created_by, caller_id);
        assert!(revealed
            .participants
            .iter()
            .any(|participant| participant.display_name.as_deref() == Some("Private Caller")));
    }

    #[test]
    fn explicit_accept_key_is_stable_and_installation_scoped() {
        let mut first = HeaderMap::new();
        first.insert(
            CALL_ACCEPT_KEY_HEADER,
            HeaderValue::from_static("installation-one"),
        );
        first.insert(AUTHORIZATION, HeaderValue::from_static("Bearer access-a"));
        let mut retry = first.clone();
        retry.insert(
            AUTHORIZATION,
            HeaderValue::from_static("Bearer refreshed-token"),
        );
        let mut sibling = first.clone();
        sibling.insert(
            CALL_ACCEPT_KEY_HEADER,
            HeaderValue::from_static("installation-two"),
        );

        assert_eq!(
            call_accept_key(&first).unwrap(),
            call_accept_key(&retry).unwrap()
        );
        assert_ne!(
            call_accept_key(&first).unwrap(),
            call_accept_key(&sibling).unwrap()
        );
    }

    #[test]
    fn legacy_accept_retry_is_scoped_to_its_bearer_token() {
        let mut first = HeaderMap::new();
        first.insert(AUTHORIZATION, HeaderValue::from_static("Bearer access-a"));
        let mut sibling = HeaderMap::new();
        sibling.insert(AUTHORIZATION, HeaderValue::from_static("Bearer access-b"));

        assert_eq!(
            call_accept_key(&first).unwrap(),
            call_accept_key(&first).unwrap()
        );
        assert_ne!(
            call_accept_key(&first).unwrap(),
            call_accept_key(&sibling).unwrap()
        );
    }

    #[test]
    fn only_the_accepting_installation_may_leave_a_joined_callee() {
        assert!(installation_may_leave(None, "legacy-or-creator"));
        assert!(installation_may_leave(Some("winner"), "winner"));
        assert!(!installation_may_leave(Some("winner"), "sibling"));
    }
}
