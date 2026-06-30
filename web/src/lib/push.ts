/**
 * Web Push helper for the Slide web app.
 *
 * Registers /sw.js, requests Notification permission, subscribes via the
 * Push API, and registers the subscription with the Slide backend.
 *
 * All entry points are guarded for SSR / unsupported browsers.
 */

const VAPID_PUBLIC_KEY =
  process.env.NEXT_PUBLIC_VAPID_PUBLIC_KEY ??
  "BMPf99ro_pKbqPSFLkGNYcM_lCWTS85ge4rNlvhdvn_bVqW-FI1Buk04OYPsnj3OJWhyJJ-j73X4E9yDq3JxLX4";

function pushSupported(): boolean {
  return (
    typeof window !== "undefined" &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    typeof Notification !== "undefined"
  );
}

/** Convert a base64url VAPID key into the Uint8Array the Push API expects. */
function urlBase64ToUint8Array(base64String: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  const buffer = new ArrayBuffer(raw.length);
  const output = new Uint8Array(buffer);
  for (let i = 0; i < raw.length; i += 1) {
    output[i] = raw.charCodeAt(i);
  }
  return output;
}

async function getSubscription(): Promise<PushSubscription | null> {
  const registration = await navigator.serviceWorker.register("/sw.js");
  await navigator.serviceWorker.ready;

  const existing = await registration.pushManager.getSubscription();
  if (existing) return existing;

  return registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(VAPID_PUBLIC_KEY),
  });
}

/**
 * Enable web push for the signed-in user: register the SW, ensure a push
 * subscription exists, and POST it to the backend device registry.
 *
 * `registerSubscription` should perform the authenticated POST /push/register
 * call (the access token lives in the caller). Returns false when unsupported,
 * permission is not granted, or anything fails.
 */
export async function enableWebPush(
  registerSubscription: (subscription: {
    endpoint: string;
    p256dh: string;
    auth: string;
  }) => Promise<unknown>,
): Promise<boolean> {
  if (!pushSupported()) return false;
  if (Notification.permission !== "granted") return false;

  try {
    const subscription = await getSubscription();
    if (!subscription) return false;
    const json = subscription.toJSON();
    const endpoint = json.endpoint;
    const p256dh = json.keys?.p256dh;
    const auth = json.keys?.auth;
    if (!endpoint || !p256dh || !auth) return false;
    await registerSubscription({ endpoint, p256dh, auth });
    return true;
  } catch (err) {
    if (typeof console !== "undefined") {
      console.warn("Slide web push registration failed", err);
    }
    return false;
  }
}

/**
 * Detach this browser's existing subscription before signing out. The endpoint
 * identifies an installation, so leaving it attached to the old account can
 * route that person's future calls to the next person using this browser.
 *
 * Unsubscribe locally even when the API cleanup fails; the push service will
 * then reject the dead endpoint and the backend can prune it on the next send.
 */
export async function disableWebPush(
  unregisterSubscription: (endpoint: string) => Promise<unknown>,
): Promise<void> {
  if (!pushSupported()) return;

  const registration = await navigator.serviceWorker.getRegistration();
  const subscription = await registration?.pushManager.getSubscription();
  if (!subscription) return;

  try {
    await unregisterSubscription(subscription.endpoint);
  } finally {
    await subscription.unsubscribe().catch(() => false);
  }
}
