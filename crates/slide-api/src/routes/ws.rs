//! App-signaling WebSocket: `GET /v1/ws?token=<accessToken>`.
//!
//! Carries call lifecycle + presence events to the client. The token is passed
//! as a query parameter because browsers/native sockets can't easily set the
//! Authorization header on the upgrade request. On connect we mark the user
//! present; on disconnect, absent.

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Query, State,
    },
    response::Response,
};
use serde::Deserialize;
use serde_json::{json, Value};
use uuid::Uuid;

use crate::{auth, state::AppState};

#[derive(Deserialize)]
pub struct WsQuery {
    pub token: String,
}

pub async fn ws_handler(
    State(state): State<AppState>,
    Query(q): Query<WsQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    // Authenticate before upgrading.
    let uid = match auth::verify_query_token(&state, &q.token) {
        Ok(uid) => uid,
        Err(_) => {
            return axum::http::StatusCode::UNAUTHORIZED.into_response_via();
        }
    };
    ws.max_message_size(16 * 1024)
        .max_frame_size(16 * 1024)
        .on_upgrade(move |socket| handle_socket(socket, state, uid))
}

// Tiny helper to turn a status into a Response without pulling in IntoResponse
// at the call site above (keeps the match arms tidy).
trait IntoResponseExt {
    fn into_response_via(self) -> Response;
}
impl IntoResponseExt for axum::http::StatusCode {
    fn into_response_via(self) -> Response {
        use axum::response::IntoResponse;
        self.into_response()
    }
}

fn anonymous_knock_event(seq: Option<u64>, dt: Option<u64>) -> Value {
    json!({
        "type": "knock",
        // A nil UUID preserves the released wire shape without exposing an id
        // that the recipient can resolve through its contacts before answer.
        "fromUserId": Uuid::nil(),
        "fromName": crate::routes::calls::ANONYMOUS_CALLER_NAME,
        "seq": seq,
        "dt": dt,
    })
}

async fn handle_socket(socket: WebSocket, state: AppState, uid: Uuid) {
    use futures::{SinkExt, StreamExt};
    use std::time::Duration;

    let (mut sender, mut receiver) = socket.split();
    let (conn_id, mut rx) = state.hub.connect(uid).await;

    // Announce presence to nobody in particular yet; mark last_seen.
    let _ = sqlx::query("UPDATE users SET last_seen_at = now() WHERE id = $1")
        .bind(uid)
        .execute(&state.db)
        .await;

    // Outbound pump: hub events → socket.
    let mut send_task = tokio::spawn(async move {
        while let Some(evt) = rx.recv().await {
            let txt = evt.to_string();
            if sender.send(Message::Text(txt.into())).await.is_err() {
                break;
            }
        }
    });

    // Inbound pump: handle client → server messages (heartbeat, presence_ping).
    let state_in = state.clone();
    let mut recv_task = tokio::spawn(async move {
        loop {
            // Native clients heartbeat every 25s. Retire sockets that stop
            // speaking instead of treating an iOS-suspended TCP connection as
            // presence forever. Foreground clients reconnect automatically.
            let next = tokio::time::timeout(Duration::from_secs(90), receiver.next()).await;
            let Some(Ok(msg)) = next.ok().flatten() else {
                break;
            };
            match msg {
                Message::Text(t) => {
                    if let Ok(v) = serde_json::from_str::<Value>(&t) {
                        match v.get("type").and_then(|x| x.as_str()) {
                            Some("heartbeat") | Some("presence_ping") => {
                                let _ = sqlx::query(
                                    "UPDATE users SET last_seen_at = now() WHERE id = $1",
                                )
                                .bind(uid)
                                .execute(&state_in.db)
                                .await;
                            }
                            // Live "knock": relay each tap to the target user's
                            // sockets in real time. The pattern's rhythm is the
                            // arrival timing of these messages (plus an optional
                            // `dt` for jitter-smoothed playback). No DB, no call
                            // row — a knock is a lightweight presence ping you
                            // can feel. Sender identity stays masked; the real
                            // call invitation reveals it only after acceptance.
                            Some("knock") => {
                                if let Some(to) = v
                                    .get("to")
                                    .and_then(|x| x.as_str())
                                    .and_then(|s| Uuid::parse_str(s).ok())
                                    .filter(|target| *target != uid)
                                {
                                    if !state_in.hub.allow_knock(uid).await {
                                        tracing::debug!(user = %uid, "dropping rate-limited knock");
                                        continue;
                                    }
                                    let seq = v.get("seq").and_then(Value::as_u64);
                                    let dt = v
                                        .get("dt")
                                        .and_then(Value::as_u64)
                                        .map(|millis| millis.min(10_000));
                                    let out = anonymous_knock_event(seq, dt);
                                    state_in.hub.publish(to, out).await;
                                    // The tap rhythm is live-only. Closed-app
                                    // knock rings are real call invitations
                                    // created through POST /calls with
                                    // ringStyle="knock", so they have a call id
                                    // and can be reported through CallKit/Telecom.
                                }
                            }
                            _ => {}
                        }
                    }
                }
                Message::Ping(_) => { /* axum auto-pongs */ }
                Message::Close(_) => break,
                _ => {}
            }
        }
    });

    // Tell the user their own socket is live (handy for the client to confirm).
    state.hub.publish(uid, json!({ "type": "connected" })).await;

    // When either side ends, tear down.
    tokio::select! {
        _ = &mut send_task => recv_task.abort(),
        _ = &mut recv_task => send_task.abort(),
    }

    state.hub.disconnect(uid, conn_id).await;
}

#[cfg(test)]
mod tests {
    use uuid::Uuid;

    use super::anonymous_knock_event;

    #[test]
    fn live_knock_taps_do_not_reveal_sender_identity() {
        let event = anonymous_knock_event(Some(3), Some(120));
        assert_eq!(event["fromUserId"], Uuid::nil().to_string());
        assert_eq!(event["fromName"], "Someone");
        assert_eq!(event["seq"], 3);
        assert_eq!(event["dt"], 120);
    }
}
