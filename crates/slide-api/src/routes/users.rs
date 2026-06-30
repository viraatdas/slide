//! /me, avatar upload, and device registration.

use axum::{
    extract::{Multipart, State},
    http::StatusCode,
    Json,
};
use serde::Deserialize;
use serde_json::{json, Value};

use slide_core::{
    error::{AppError, AppResult},
    models::{Device, Platform, User},
};

use crate::{auth::AuthUser, state::AppState};

/// GET /me
pub async fn get_me(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
) -> AppResult<Json<User>> {
    let user: User = sqlx::query_as("SELECT * FROM users WHERE id = $1")
        .bind(uid)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::NotFound)?;
    Ok(Json(user))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PatchMeBody {
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
}

/// PATCH /me — update display name and/or avatar. COALESCE keeps existing
/// values when a field is omitted.
pub async fn patch_me(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    Json(body): Json<PatchMeBody>,
) -> AppResult<Json<User>> {
    if let Some(name) = &body.display_name {
        if name.trim().is_empty() || name.len() > 80 {
            return Err(AppError::validation("display name must be 1–80 chars"));
        }
    }
    let user: User = sqlx::query_as(
        "UPDATE users
           SET display_name = COALESCE($2, display_name),
               avatar_url   = COALESCE($3, avatar_url)
         WHERE id = $1
         RETURNING *",
    )
    .bind(uid)
    .bind(body.display_name.as_deref())
    .bind(body.avatar_url.as_deref())
    .fetch_one(&state.db)
    .await?;
    Ok(Json(user))
}

/// POST /me/avatar — multipart image upload.
///
/// In dev (no S3 configured) the image is accepted and a stable placeholder
/// URL derived from the user id is stored, so the flow is exercisable without
/// object storage. With `S3_PUBLIC_BASE_URL` set this is where a real upload
/// to S3-compatible storage would happen.
pub async fn post_avatar(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    mut multipart: Multipart,
) -> AppResult<Json<Value>> {
    let mut received = false;
    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::bad_request(format!("bad multipart: {e}")))?
    {
        let data = field
            .bytes()
            .await
            .map_err(|e| AppError::bad_request(format!("bad upload: {e}")))?;
        if data.len() > 5 * 1024 * 1024 {
            return Err(AppError::validation("avatar must be ≤ 5MB"));
        }
        received = !data.is_empty();
        // TODO(storage): upload `data` to S3 and use the returned key.
    }
    if !received {
        return Err(AppError::bad_request("no image provided"));
    }

    let base = if state.cfg.s3_public_base_url.is_empty() {
        "https://avatars.slide.local".to_string()
    } else {
        state.cfg.s3_public_base_url.clone()
    };
    let avatar_url = format!("{base}/{uid}.jpg");

    sqlx::query("UPDATE users SET avatar_url = $2 WHERE id = $1")
        .bind(uid)
        .bind(&avatar_url)
        .execute(&state.db)
        .await?;

    Ok(Json(json!({ "avatarUrl": avatar_url })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceBody {
    pub push_token: String,
    pub platform: Platform,
    #[serde(default)]
    pub app_version: String,
}

/// POST /devices — upsert by push token.
pub async fn register_device(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    Json(body): Json<DeviceBody>,
) -> AppResult<Json<Device>> {
    validate_native_token(&body.push_token, body.platform)?;
    let mut tx = state.db.begin().await?;
    let device: Device = sqlx::query_as(
        "INSERT INTO devices (user_id, push_token, platform, app_version)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (push_token)
         DO UPDATE SET user_id = EXCLUDED.user_id,
                       platform = EXCLUDED.platform,
                       app_version = EXCLUDED.app_version,
                       updated_at = now()
         RETURNING *",
    )
    .bind(uid)
    .bind(&body.push_token)
    .bind(body.platform)
    .bind(&body.app_version)
    .fetch_one(&mut *tx)
    .await?;

    // Backward compatibility for released Android clients: they registered
    // their FCM token through /devices before /push/register existed. Delivery
    // reads push_subscriptions, so mirror Android tokens here or those installs
    // can never receive a background call. iOS /devices tokens are ambiguous
    // (standard APNs vs PushKit) and are deliberately not guessed.
    if matches!(body.platform, Platform::Android) {
        sqlx::query(
            "INSERT INTO push_subscriptions (user_id, kind, token, app_version)
             VALUES ($1, 'fcm', $2, $3)
             ON CONFLICT (token)
             DO UPDATE SET user_id = EXCLUDED.user_id,
                           kind = EXCLUDED.kind,
                           p256dh = NULL,
                           auth = NULL,
                           app_version = EXCLUDED.app_version,
                           updated_at = now()",
        )
        .bind(uid)
        .bind(&body.push_token)
        .bind(&body.app_version)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    Ok(Json(device))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushRegisterBody {
    /// Device token (APNs/FCM) or the Web Push endpoint URL.
    pub push_token: String,
    /// 'apns' (standard alert token) | 'apns_voip' | 'fcm' | 'webpush'.
    pub kind: String,
    /// Web Push only: the client public key (base64url).
    #[serde(default)]
    pub p256dh: Option<String>,
    /// Web Push only: the client auth secret (base64url).
    #[serde(default)]
    pub auth: Option<String>,
    /// Optional, informational.
    #[serde(default)]
    pub platform: Option<String>,
    #[serde(default)]
    pub app_version: String,
}

/// POST /push/register — transfer/upsert a globally owned push endpoint.
///
/// Separate from POST /devices: subscriptions live in `push_subscriptions`,
/// which does not use the `platform` enum, so Web Push works without an enum
/// migration. The legacy /devices endpoint keeps functioning unchanged.
pub async fn register_push(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    Json(body): Json<PushRegisterBody>,
) -> AppResult<Json<Value>> {
    let kind = body.kind.trim();
    if !matches!(kind, "apns" | "apns_voip" | "fcm" | "webpush") {
        return Err(AppError::validation(
            "kind must be one of apns|apns_voip|fcm|webpush",
        ));
    }
    validate_push_subscription(
        kind,
        &body.push_token,
        body.p256dh.as_deref(),
        body.auth.as_deref(),
    )?;
    let _ = &body.platform; // accepted for client convenience; not persisted.

    sqlx::query(
        "INSERT INTO push_subscriptions (user_id, kind, token, p256dh, auth, app_version)
         VALUES ($1, $2, $3, $4, $5, $6)
         ON CONFLICT (token)
         DO UPDATE SET user_id = EXCLUDED.user_id,
                       kind = EXCLUDED.kind,
                       p256dh = EXCLUDED.p256dh,
                       auth = EXCLUDED.auth,
                       app_version = EXCLUDED.app_version,
                       updated_at = now()",
    )
    .bind(uid)
    .bind(kind)
    .bind(&body.push_token)
    .bind(body.p256dh.as_deref())
    .bind(body.auth.as_deref())
    .bind(&body.app_version)
    .execute(&state.db)
    .await?;

    Ok(Json(json!({ "ok": true })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushUnregisterBody {
    pub push_token: String,
}

/// DELETE /push/register — detach one installation on logout/token rotation.
/// Ownership is checked so one account cannot unregister another's endpoint.
pub async fn unregister_push(
    State(state): State<AppState>,
    AuthUser(uid): AuthUser,
    Json(body): Json<PushUnregisterBody>,
) -> AppResult<StatusCode> {
    if body.push_token.trim().is_empty() || body.push_token.len() > 4096 {
        return Err(AppError::validation("valid pushToken required"));
    }

    let mut tx = state.db.begin().await?;
    sqlx::query("DELETE FROM push_subscriptions WHERE user_id = $1 AND token = $2")
        .bind(uid)
        .bind(&body.push_token)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM devices WHERE user_id = $1 AND push_token = $2")
        .bind(uid)
        .bind(&body.push_token)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

fn validate_native_token(token: &str, platform: Platform) -> AppResult<()> {
    let token = token.trim();
    if token.is_empty() || token.len() > 4096 || token.chars().any(char::is_whitespace) {
        return Err(AppError::validation("invalid pushToken"));
    }
    if matches!(platform, Platform::Ios)
        && (token.len() < 32 || !token.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err(AppError::validation("invalid iOS pushToken"));
    }
    Ok(())
}

fn validate_push_subscription(
    kind: &str,
    token: &str,
    p256dh: Option<&str>,
    auth: Option<&str>,
) -> AppResult<()> {
    match kind {
        "apns" | "apns_voip" => validate_native_token(token, Platform::Ios),
        "fcm" => validate_native_token(token, Platform::Android),
        "webpush" => {
            let endpoint = reqwest::Url::parse(token)
                .map_err(|_| AppError::validation("invalid webpush endpoint"))?;
            let host = endpoint
                .host_str()
                .ok_or_else(|| AppError::validation("invalid webpush endpoint"))?;
            let local_host = host.eq_ignore_ascii_case("localhost")
                || host.ends_with(".localhost")
                || host.parse::<std::net::IpAddr>().is_ok();
            let known_push_service = host.eq_ignore_ascii_case("fcm.googleapis.com")
                || host.ends_with(".push.services.mozilla.com")
                || host.eq_ignore_ascii_case("web.push.apple.com")
                || host.ends_with(".notify.windows.com");
            if endpoint.scheme() != "https"
                || endpoint.username() != ""
                || endpoint.password().is_some()
                || local_host
                || !known_push_service
                || token.len() > 4096
            {
                return Err(AppError::validation("invalid webpush endpoint"));
            }
            let valid_key = |value: &str, min: usize, max: usize| {
                (min..=max).contains(&value.len())
                    && value
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'='))
            };
            if !p256dh.is_some_and(|v| valid_key(v, 32, 256))
                || !auth.is_some_and(|v| valid_key(v, 8, 128))
            {
                return Err(AppError::validation(
                    "webpush requires valid p256dh and auth keys",
                ));
            }
            Ok(())
        }
        _ => Err(AppError::validation("invalid push kind")),
    }
}

#[cfg(test)]
mod tests {
    use slide_core::models::Platform;

    use super::{validate_native_token, validate_push_subscription};

    #[test]
    fn rejects_path_injection_in_apns_token() {
        assert!(validate_native_token("abc/../../device", Platform::Ios).is_err());
    }

    #[test]
    fn rejects_local_webpush_endpoint() {
        let key = "A".repeat(87);
        let auth = "B".repeat(22);
        assert!(validate_push_subscription(
            "webpush",
            "https://127.0.0.1/internal",
            Some(&key),
            Some(&auth)
        )
        .is_err());
    }

    #[test]
    fn accepts_provider_shaped_tokens() {
        assert!(validate_push_subscription("fcm", "fcm-token:abc_123", None, None).is_ok());
        assert!(validate_push_subscription("apns_voip", &"a".repeat(64), None, None).is_ok());
        assert!(validate_push_subscription(
            "webpush",
            "https://fcm.googleapis.com/fcm/send/example",
            Some(&"A".repeat(87)),
            Some(&"B".repeat(22))
        )
        .is_ok());
    }
}
