/* Slide web push service worker. Keep tiny, no deps. */

function matchingClients() {
  return self.clients.matchAll({ type: "window", includeUncontrolled: true });
}

function postToClients(message) {
  return matchingClients().then((clients) => {
    clients.forEach((client) => client.postMessage(message));
  });
}

function closeCallNotifications(callId) {
  return self.registration
    .getNotifications({ tag: `slide-${callId}` })
    .then((notifications) => notifications.forEach((notification) => notification.close()));
}

self.addEventListener("push", (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch (_err) {
    payload = { body: event.data ? event.data.text() : "" };
  }

  const data = payload.data || payload;
  const type = data.type || payload.type;
  const callId = data.callId || payload.callId || "";
  const expiresAt = data.expiresAt || payload.expiresAt;
  if (
    (type === "call_ended" || type === "call_declined" || type === "call_accepted") &&
    callId
  ) {
    event.waitUntil(
      Promise.all([
        closeCallNotifications(callId),
        postToClients({
          type: "slide-call-terminal",
          eventType: type,
          callId,
          call: data,
        }),
      ]),
    );
    return;
  }
  const ringStyle = data.ringStyle || payload.ringStyle || "call";
  const fromUserId = data.fromUserId || payload.fromUserId || "";
  const videoEnabled = data.videoEnabled ?? payload.videoEnabled ?? "true";
  const isCallInvite = type === "incoming_call";
  const isKnock =
    ringStyle === "knock" ||
    type === "knock" ||
    data.knock === true ||
    data.knock === "true";
  const fromName = payload.title || data.fromName || payload.fromName || "Slide";
  const body =
    payload.body ||
    (isKnock ? "Someone's at your door" : "Incoming Knock Knock call");

  // A knock stays anonymous until it is answered. Keep the caller in the
  // notification data so the app can reveal them after acceptance, never in
  // lock-screen copy.
  const title = isKnock ? "Knock knock…" : fromName;

  const notificationData = {
    callId,
    type,
    ringStyle,
    knock: isKnock,
    fromUserId,
    fromName,
    videoEnabled,
    expiresAt,
  };
  const parsedExpiresAt = Number(expiresAt);
  if (isCallInvite && Number.isFinite(parsedExpiresAt) && parsedExpiresAt <= Date.now()) {
    event.waitUntil(
      Promise.all([
        closeCallNotifications(callId),
        postToClients({ type: "slide-stale-invitation", call: notificationData }),
      ]),
    );
    return;
  }

  event.waitUntil(
    Promise.all([
      // The service worker is the sole owner of system notifications. Always
      // show the durable surface, even beside a visible tab: a reloaded browser
      // may not have a user-activated AudioContext, so its in-page ringtone can
      // be blocked by autoplay policy.
      self.registration.showNotification(title, {
        body,
        icon: "/icon-512.png",
        badge: "/icon-512.png",
        tag: callId ? `slide-${callId}` : isKnock ? "slide-knock" : "slide-call",
        renotify: true,
        requireInteraction: isCallInvite,
        data: notificationData,
      }),
      isCallInvite
        ? postToClients({ type: "slide-push-event", call: notificationData })
        : Promise.resolve(),
    ]),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const call = event.notification.data || {};
  const params = new URLSearchParams();
  if (call.callId) params.set("incomingCallId", call.callId);
  const target = `/web${params.toString() ? `?${params.toString()}` : ""}`;
  event.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((clients) => {
        for (const client of clients) {
          if (client.url.includes("/web") && "focus" in client) {
            if ("postMessage" in client) {
              client.postMessage({ type: "slide-notification-click", call });
            }
            return client.focus();
          }
        }
        if (self.clients.openWindow) {
          return self.clients.openWindow(target);
        }
        return undefined;
      }),
  );
});
