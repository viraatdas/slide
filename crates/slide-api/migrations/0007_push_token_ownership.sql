-- A push endpoint belongs to an app installation, not to an account.
--
-- The original (user_id, token) uniqueness allowed the same APNs/FCM/Web Push
-- endpoint to remain attached to several users after logout + account switch.
-- That leaks incoming-call metadata across accounts and sends duplicate rings.
-- Keep the most recently refreshed owner, then make token ownership global.

DELETE FROM push_subscriptions older
USING push_subscriptions newer
WHERE older.token = newer.token
  AND (
      older.updated_at < newer.updated_at
      OR (older.updated_at = newer.updated_at AND older.id < newer.id)
  );

CREATE UNIQUE INDEX idx_push_subscriptions_token
    ON push_subscriptions (token);

-- One account can ring on several installations, but exactly one installation
-- may win an accept. The winning installation stores a hash of its stable
-- accept key; retries with that key stay idempotent while siblings get 409.
ALTER TABLE call_participants ADD COLUMN accept_key TEXT;

-- Do not bulk-copy legacy Android `devices` rows here. Old clients did not
-- unregister those rows on logout, so treating every historical row as a live
-- FCM subscription would ring signed-out installations for their former
-- account. POST /devices mirrors the token when an authenticated legacy client
-- next opens, which proves the installation is still owned by that account.

-- The old server never expired ringing calls. Silently close that pre-rollout
-- backlog inside the migration, before the new sweeper starts; otherwise its
-- first tick would turn pre-cutover invitations into fresh visible "missed
-- call" notifications. This intentionally establishes a clean lifecycle
-- boundary: traffic created by the new server starts only after migrations.
WITH stale_calls AS (
    UPDATE calls
       SET status = 'missed',
           ended_at = COALESCE(ended_at, now())
     WHERE status = 'ringing'
    RETURNING id
)
UPDATE call_participants
   SET state = 'left',
       left_at = COALESCE(left_at, now())
 WHERE call_id IN (SELECT id FROM stale_calls)
   AND state IN ('ringing', 'joined');

-- The same rollout hazard exists for unanswered members of already-active
-- group calls. Leave the room active for joined members, but retire old
-- invitation rows without emitting user notifications.
UPDATE call_participants p
   SET state = 'left',
       left_at = COALESCE(p.left_at, now())
  FROM calls c
 WHERE p.call_id = c.id
   AND p.state = 'ringing'
   AND c.status = 'active';
