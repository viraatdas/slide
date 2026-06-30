//! HTTP route table.

pub mod auth;
pub mod calls;
pub mod contacts;
pub mod users;
pub mod ws;

use axum::{
    routing::{get, post},
    Router,
};

use crate::state::AppState;

pub fn router(state: AppState) -> Router {
    let v1 = Router::new()
        // health
        .route("/health", get(|| async { "ok" }))
        // auth
        .route("/auth/request-otp", post(auth::request_otp))
        .route("/auth/verify-otp", post(auth::verify_otp))
        .route("/auth/firebase", post(auth::firebase_auth))
        .route("/auth/refresh", post(auth::refresh))
        .route("/auth/logout", post(auth::logout))
        // me
        .route("/me", get(users::get_me).patch(users::patch_me))
        .route("/me/avatar", post(users::post_avatar))
        .route("/devices", post(users::register_device))
        .route(
            "/push/register",
            post(users::register_push).delete(users::unregister_push),
        )
        // contacts
        .route("/contacts/sync", post(contacts::sync))
        .route("/contacts", get(contacts::list))
        // calls
        .route("/calls", post(calls::create_call).get(calls::list_calls))
        .route("/calls/{id}/accept", post(calls::accept_call))
        .route("/calls/{id}/decline", post(calls::decline_call))
        .route("/calls/{id}/leave", post(calls::leave_call))
        // realtime
        .route("/ws", get(ws::ws_handler))
        .with_state(state);

    Router::new().nest("/v1", v1)
}
