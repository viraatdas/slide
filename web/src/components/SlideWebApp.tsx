"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowIncomingIcon,
  ArrowOutgoingIcon,
  MicIcon,
  MicOffIcon,
  PhoneIcon,
  VideoIcon,
  VideoOffIcon,
} from "./icons";
import PhoneField from "./PhoneField";
import { KnockSurface, KnockIncoming, playKnock, vibrateKnock } from "./Knock";
import { Room, RoomEvent, Track, VideoPresets, type RemoteTrack } from "livekit-client";
import { disableWebPush, enableWebPush } from "../lib/push";
import { firebaseAuth } from "../lib/firebase";
import {
  RecaptchaVerifier,
  signInWithPhoneNumber,
  type ConfirmationResult,
} from "firebase/auth";

// slide-api on Fly. AWS App Runner's Envoy ingress rejects WebSocket upgrades
// (403), so the /v1/ws signaling socket can't connect there; calls never ring.
// Fly serves WebSockets, so the API (REST + WS) lives there.
const API_BASE =
  process.env.NEXT_PUBLIC_SLIDE_API_BASE_URL ??
  "https://slide-api.fly.dev/v1";

type AuthTokens = {
  accessToken: string;
  refreshToken: string;
};

type User = {
  id: string;
  phone: string;
  displayName?: string | null;
};

type ContactSyncResult = {
  phone: string;
  displayName?: string | null;
  userId?: string | null;
  onSlide: boolean;
};

type ServerContact = {
  id: string;
  ownerUserId: string;
  contactUserId?: string | null;
  phone: string;
  displayName?: string | null;
  avatarUrl?: string | null;
};

type IceServer = {
  urls: string[];
  username?: string | null;
  credential?: string | null;
};

type Call = {
  id: string;
  type?: string;
  createdBy?: string;
  status?: string;
  videoEnabled?: boolean;
  ringStyle?: string;
  createdAt?: string;
  expiresAt?: string | number;
  participants?: CallParticipant[];
};

type CallParticipant = {
  userId: string;
  state?: string;
  displayName?: string | null;
  phone?: string | null;
  avatarUrl?: string | null;
};

type HistoryResponse = {
  calls: Call[];
  nextCursor?: string | null;
};

type CallSession = {
  call: Call;
  joinToken: string;
  sfuUrl: string;
  iceServers: IceServer[];
};

type SignalEvent = {
  type: string;
  callId?: string;
  callType?: string;
  fromUserId?: string;
  fromName?: string;
  phone?: string;
  videoEnabled?: boolean | string;
  ringStyle?: string;
  knock?: boolean | string;
  expiresAt?: string | number;
  call?: Call;
  from?: string | User;
};

type IncomingCall = {
  callId: string;
  fromUserId: string;
  fromName: string;
  video: boolean;
  ringStyle: string;
  expiresAt?: number;
};

type ActiveCall = {
  callId: string;
  peerName: string;
  direction: "incoming" | "outgoing";
  video: boolean;
  phone?: string | null;
  userId?: string | null;
};

type KnockSession = {
  userId: string;
  /** Name shown while tapping; remains anonymous for an incoming knock-back. */
  name: string;
  /** Identity retained internally and revealed only when the user places a call. */
  identityName: string;
};

type Contact = {
  userId: string;
  phone: string;
  displayName?: string | null;
};

type RecentCall = {
  id: string;
  peerName: string;
  phone?: string | null;
  userId?: string | null;
  direction: "incoming" | "outgoing";
  video: boolean;
  startedAt: number;
  durationSec: number;
  connected: boolean;
  label?: string;
};

type LookupState =
  | { status: "idle" }
  | { status: "checking" }
  | { status: "found"; contact: ContactSyncResult }
  | { status: "not-found" }
  | { status: "self" }
  | { status: "error"; message: string };

type TerminalEventType = "call_accepted" | "call_declined" | "call_ended";
type PushRegistrationState = "idle" | "registering" | "registered" | "failed";

const TERMINAL_TOMBSTONE_TTL_MS = 5 * 60 * 1000;
const MAX_TERMINAL_TOMBSTONES = 128;
const volatileCallAcceptKeys = new Map<string, string>();

class StaleCallOperationError extends Error {
  constructor() {
    super("Call operation is no longer current");
    this.name = "StaleCallOperationError";
  }
}

function storedTokens(): AuthTokens | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem("slide.web.tokens");
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AuthTokens;
  } catch {
    return null;
  }
}

function saveTokens(tokens: AuthTokens | null) {
  if (typeof window === "undefined") return;
  if (tokens) {
    window.localStorage.setItem("slide.web.tokens", JSON.stringify(tokens));
  } else {
    window.localStorage.removeItem("slide.web.tokens");
  }
}

function loadList<T>(key: string): T[] {
  if (typeof window === "undefined") return [];
  const raw = window.localStorage.getItem(key);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as T[]) : [];
  } catch {
    return [];
  }
}

function saveList<T>(key: string, list: T[]) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(key, JSON.stringify(list));
}

function initials(name: string) {
  const cleaned = name.trim();
  if (!cleaned) return "?";
  if (/^\+?\d/.test(cleaned)) {
    const digits = cleaned.replace(/\D/g, "");
    return digits.slice(-2) || "#";
  }
  const parts = cleaned.split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function formatDuration(totalSec: number) {
  const sec = Math.max(0, Math.floor(totalSec));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const mm = h > 0 ? String(m).padStart(2, "0") : String(m);
  return `${h > 0 ? `${h}:` : ""}${mm}:${String(s).padStart(2, "0")}`;
}

function relativeTime(ts: number) {
  const diff = Date.now() - ts;
  const min = Math.floor(diff / 60000);
  if (min < 1) return "Just now";
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day === 1) return "Yesterday";
  if (day < 7) return `${day}d ago`;
  return new Date(ts).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
  });
}

function recentOutcome(call: RecentCall) {
  if (call.label) return call.label;
  if (call.connected) return formatDuration(call.durationSec);
  return call.direction === "incoming" ? "Missed call" : "No answer";
}

function apiUrl(path: string) {
  return `${API_BASE.replace(/\/$/, "")}${path}`;
}

class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

// Decode a JWT's `exp` (seconds since epoch) without verifying the signature;
// used only to decide whether to proactively refresh before opening the socket.
function tokenExpiry(jwt: string): number | null {
  try {
    const payload = jwt.split(".")[1];
    if (!payload) return null;
    const json = JSON.parse(
      atob(payload.replace(/-/g, "+").replace(/_/g, "/")),
    );
    return typeof json.exp === "number" ? json.exp : null;
  } catch {
    return null;
  }
}

function humanizeCallError(message: string): string {
  if (/participant required/i.test(message)) {
    return "You can't call your own number. Try a different one.";
  }
  if (/exactly one participant/i.test(message)) {
    return "Group calls aren't supported on the web yet.";
  }
  if (/unknown participant/i.test(message)) {
    return "That person isn't reachable on Slide right now.";
  }
  return message;
}

function wsUrl(token: string) {
  const url = new URL(apiUrl("/ws"));
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("token", token);
  return url.toString();
}

async function jsonFetch<T>(
  path: string,
  token: string | null,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers);
  if (!(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(apiUrl(path), { ...init, headers });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const data = await response.json();
      message = data?.error?.message ?? data?.message ?? message;
    } catch {
      // non-JSON body; keep the status line.
    }
    throw new ApiError(response.status, message);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/**
 * Fire a terminal call mutation with the current bearer token and `keepalive`.
 * This deliberately bypasses token refresh so logout/unload can start the
 * request before local credentials are cleared and let the browser finish it.
 */
function bestEffortCallResolution(
  callId: string,
  action: "leave" | "decline",
  accessToken: string | null,
) {
  if (!callId || !accessToken) return;
  const headers: Record<string, string> = { Authorization: `Bearer ${accessToken}` };
  if (action === "leave") headers["X-Call-Accept-Key"] = callAcceptKey(callId);
  void fetch(apiUrl(`/calls/${callId}/${action}`), {
    method: "POST",
    headers,
    keepalive: true,
  }).catch(() => undefined);
}

function callAcceptKey(callId: string): string {
  const storageKey = `slide.web.callAcceptKey.${callId}`;
  const inMemory = volatileCallAcceptKeys.get(callId);
  if (inMemory) return inMemory;
  try {
    const existing = window.sessionStorage.getItem(storageKey);
    if (existing && /^[A-Za-z0-9_-]{8,128}$/.test(existing)) {
      volatileCallAcceptKeys.set(callId, existing);
      return existing;
    }
  } catch {
    // Some embedded/private contexts block storage; retain the key in memory.
  }
  // Per-call + per-tab is intentional. A localStorage key shared by two tabs
  // would make the server treat both as an idempotent retry and issue two media
  // tokens. sessionStorage survives reload but isolates independently opened tabs.
  const generated =
    typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random()
          .toString(36)
          .slice(2)}`;
  volatileCallAcceptKeys.set(callId, generated);
  try {
    window.sessionStorage.setItem(storageKey, generated);
  } catch {
    // The in-memory key still makes retries safe for this page lifetime.
  }
  return generated;
}

function isAnsweredElsewhereError(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 409 &&
    /answered on another installation/i.test(error.message)
  );
}

function isActionableUserId(value: string | null | undefined): value is string {
  return Boolean(
    value &&
      value !== "unknown" &&
      value !== "00000000-0000-0000-0000-000000000000",
  );
}

async function reconcileAmbiguousAccept(
  callId: string,
  acceptKey: string,
  accessToken: string,
) {
  for (const delay of [700, 1400, 2800]) {
    await new Promise((resolve) => window.setTimeout(resolve, delay));
    try {
      await jsonFetch<CallSession>(`/calls/${callId}/accept`, accessToken, {
        method: "POST",
        headers: { "X-Call-Accept-Key": acceptKey },
      });
      // This key owns the accepted participant (either from the original request
      // or this retry). The UI abandoned it, so close only our confirmed winner.
      bestEffortCallResolution(callId, "leave", accessToken);
      return;
    } catch (error) {
      if (isAnsweredElsewhereError(error)) return;
      if (error instanceof ApiError && error.status < 500) return;
    }
  }
}

function incomingFrom(event: SignalEvent): IncomingCall | null {
  const callId = event.callId ?? event.call?.id;
  if (!callId) return null;
  const fromObject = typeof event.from === "object" ? event.from : null;
  const rawFromUserId =
    event.fromUserId ??
    (typeof event.from === "string" ? event.from : undefined) ??
    fromObject?.id ??
    event.call?.createdBy ??
    "unknown";
  const fromUserId = isActionableUserId(rawFromUserId) ? rawFromUserId : "";
  const fromName =
    event.fromName ??
    fromObject?.displayName ??
    fromObject?.phone ??
    "Slide";
  const rawVideo = event.videoEnabled ?? event.call?.videoEnabled;
  const video =
    typeof rawVideo === "string" ? rawVideo !== "false" : rawVideo ?? true;
  const isKnock = event.knock === true || event.knock === "true";
  const ringStyle =
    event.ringStyle ?? event.call?.ringStyle ?? (isKnock ? "knock" : "call");
  const expiresAt = parseExpiresAt(event.expiresAt ?? event.call?.expiresAt);
  return { callId, fromUserId, fromName, video, ringStyle, expiresAt };
}

function incomingFromCall(call: Call, currentUserId: string): IncomingCall | null {
  const me = call.participants?.find((participant) => participant.userId === currentUserId);
  if (me?.state !== "ringing" || call.createdBy === currentUserId) return null;
  const caller = call.participants?.find((participant) => participant.userId === call.createdBy);
  return {
    callId: call.id,
    fromUserId: isActionableUserId(call.createdBy ?? caller?.userId)
      ? (call.createdBy ?? caller?.userId ?? "")
      : "",
    fromName: caller?.displayName ?? caller?.phone ?? "Slide",
    video: call.videoEnabled ?? true,
    ringStyle: call.ringStyle ?? "call",
    expiresAt: parseExpiresAt(call.expiresAt),
  };
}

function parseExpiresAt(value: string | number | undefined): number | undefined {
  if (value === undefined) return undefined;
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function invitationExpired(call: IncomingCall): boolean {
  return call.expiresAt !== undefined && call.expiresAt <= Date.now();
}

function serverContactToContact(contact: ServerContact): Contact | null {
  if (!contact.contactUserId) return null;
  return {
    userId: contact.contactUserId,
    phone: contact.phone,
    displayName: contact.displayName,
  };
}

function assertBrowserReachableSfu(session: CallSession) {
  if (typeof window === "undefined") return;
  const url = new URL(session.sfuUrl);
  const localSfu = url.hostname === "localhost" || url.hostname === "127.0.0.1";
  const localPage =
    window.location.hostname === "localhost" ||
    window.location.hostname === "127.0.0.1";
  if (localSfu && !localPage) {
    throw new Error("The API returned a local SFU URL. Set SFU_PUBLIC_URL.");
  }
  if (window.location.protocol === "https:" && url.protocol !== "wss:") {
    throw new Error("Browser calls on HTTPS need a wss SFU URL.");
  }
}

// Pretty-print a phone number as it's typed: "+1 415 555 0123" / "415 555 0123".
// Backend normalization strips the spaces, so this is display-only.
function formatDial(raw: string): string {
  const hadPlus = raw.trimStart().startsWith("+");
  let digits = raw.replace(/\D/g, "");
  let cc = "";
  // Peel off a country code when there's an explicit + or more than 10 digits.
  if (hadPlus || digits.length > 10) {
    if (digits.startsWith("1")) {
      cc = "1";
      digits = digits.slice(1);
    } else {
      const n = Math.max(0, digits.length - 10);
      cc = digits.slice(0, n);
      digits = digits.slice(n);
    }
  }
  const groups: string[] = [];
  if (digits.length) groups.push(digits.slice(0, 3));
  if (digits.length > 3) groups.push(digits.slice(3, 6));
  if (digits.length > 6) groups.push(digits.slice(6, 10));
  const local = groups.join(" ");
  if (cc) return local ? `+${cc} ${local}` : `+${cc}`;
  if (hadPlus) return `+${local}`;
  return local;
}

export default function SlideWebApp() {
  const [tokens, setTokens] = useState<AuthTokens | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [authStep, setAuthStep] = useState<"phone" | "code">("phone");
  const [authBusy, setAuthBusy] = useState(false);
  const [authError, setAuthError] = useState<string | null>(null);
  const [dialNumber, setDialNumber] = useState("");
  // Audio/Video slider for the lookup result; you pick, then knock to call.
  const [dialVideo, setDialVideo] = useState(true);
  const [lookup, setLookup] = useState<LookupState>({ status: "idle" });
  const [incoming, setIncoming] = useState<IncomingCall | null>(null);
  // Knock: real-time "tap a rhythm" presence ritual. `knockSession` is the
  // full-screen duet stage; `knockTheirPulse` ticks when the peer in that
  // session knocks back so their ripple blooms on our stage.
  const [knockSession, setKnockSession] = useState<KnockSession | null>(null);
  const [knockTheirPulse, setKnockTheirPulse] = useState(0);
  const [knocking, setKnocking] = useState<{
    fromUserId: string;
    fromName: string;
    pulse: number;
  } | null>(null);
  const [activeCall, setActiveCall] = useState<ActiveCall | null>(null);
  const [notificationState, setNotificationState] = useState("default");
  const [pushRegistrationState, setPushRegistrationState] =
    useState<PushRegistrationState>("idle");
  const [status, setStatus] = useState("Ready");
  const [localStream, setLocalStream] = useState<MediaStream | null>(null);
  const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null);
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [recents, setRecents] = useState<RecentCall[]>([]);
  const [peerConnected, setPeerConnected] = useState(false);
  const [remoteVideoReady, setRemoteVideoReady] = useState(false);
  const [muted, setMuted] = useState(false);
  const [cameraOff, setCameraOff] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [callError, setCallError] = useState<string | null>(null);

  const tokensRef = useRef<AuthTokens | null>(null);
  const refreshInFlight = useRef<Promise<string> | null>(null);
  const localVideo = useRef<HTMLVideoElement | null>(null);
  const remoteVideo = useRef<HTMLVideoElement | null>(null);
  const signalingSocket = useRef<WebSocket | null>(null);
  // LiveKit room for the media plane (replaces the custom SFU socket + PC).
  const room = useRef<Room | null>(null);
  const audioContext = useRef<AudioContext | null>(null);
  const ringTimer = useRef<number | null>(null);
  const callStartRef = useRef<number | null>(null);
  const everConnectedRef = useRef(false);
  const incomingRef = useRef<IncomingCall | null>(null);
  const activeCallRef = useRef<ActiveCall | null>(null);
  const localStreamRef = useRef<MediaStream | null>(null);
  const remoteStreamRef = useRef<MediaStream | null>(null);
  const terminalCallsRef = useRef<
    Map<string, { expiresAt: number; type: TerminalEventType }>
  >(new Map());
  const mediaOperationRef = useRef<{ generation: number; callId: string | null }>({
    generation: 0,
    callId: null,
  });
  const outgoingCallGenerationRef = useRef<number | null>(null);
  const pushRegistrationInFlight = useRef<Promise<boolean> | null>(null);
  const pushRegistrationEpochRef = useRef(0);
  // Distinguishes this tab's in-flight answer from a sibling installation's
  // `call_accepted` event. The latter should dismiss our ringing UI; the
  // former is only an idempotent confirmation.
  const acceptingCallIdRef = useRef<string | null>(null);
  const knockSeq = useRef(0);
  const knockLastTap = useRef<number | null>(null);
  const knockClearTimer = useRef<number | null>(null);
  const knockSessionRef = useRef<KnockSession | null>(null);

  const signedIn = Boolean(tokens && user);

  useEffect(() => {
    const initial = storedTokens();
    tokensRef.current = initial;
    setTokens(initial);
    setContacts(loadList<Contact>("slide.web.contacts"));
    setRecents(loadList<RecentCall>("slide.web.recents"));
    setNotificationState(
      typeof Notification === "undefined" ? "unsupported" : Notification.permission,
    );
  }, []);

  useEffect(() => {
    tokensRef.current = tokens;
  }, [tokens]);

  const applyTokens = useCallback((next: AuthTokens | null) => {
    tokensRef.current = next;
    saveTokens(next);
    setTokens(next);
    if (!next) setUser(null);
  }, []);

  // Coalesced silent refresh: rotate the refresh token, mint a fresh access
  // token, persist both. Mirrors the iOS/Android 401-refresh behavior so web
  // sessions don't die after the 15-minute access-token TTL.
  const refreshAccessToken = useCallback(async (): Promise<string> => {
    if (refreshInFlight.current) return refreshInFlight.current;
    const current = tokensRef.current;
    if (!current?.refreshToken) throw new ApiError(401, "Not signed in");
    const attempt = (async () => {
      try {
        const res = await jsonFetch<AuthTokens>("/auth/refresh", null, {
          method: "POST",
          body: JSON.stringify({ refreshToken: current.refreshToken }),
        });
        const next = {
          accessToken: res.accessToken,
          refreshToken: res.refreshToken,
        };
        applyTokens(next);
        return next.accessToken;
      } finally {
        refreshInFlight.current = null;
      }
    })();
    refreshInFlight.current = attempt;
    return attempt;
  }, [applyTokens]);

  // Authenticated fetch with one transparent refresh-and-retry on 401.
  const authedFetch = useCallback(
    async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
      const access = tokensRef.current?.accessToken ?? null;
      try {
        return await jsonFetch<T>(path, access, init);
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status === 401 &&
          tokensRef.current?.refreshToken
        ) {
          try {
            const fresh = await refreshAccessToken();
            return await jsonFetch<T>(path, fresh, init);
          } catch {
            applyTokens(null);
          }
        }
        throw error;
      }
    },
    [refreshAccessToken, applyTokens],
  );

  const registerWebPush = useCallback(async (): Promise<boolean> => {
    if (
      !tokensRef.current ||
      typeof Notification === "undefined" ||
      Notification.permission !== "granted"
    ) {
      setPushRegistrationState("idle");
      return false;
    }
    if (pushRegistrationInFlight.current) {
      return pushRegistrationInFlight.current;
    }

    const epoch = pushRegistrationEpochRef.current;
    setPushRegistrationState("registering");
    const attempt = enableWebPush((subscription) =>
      authedFetch("/push/register", {
        method: "POST",
        body: JSON.stringify({
          pushToken: subscription.endpoint,
          kind: "webpush",
          p256dh: subscription.p256dh,
          auth: subscription.auth,
          platform: "web",
          appVersion: "web",
        }),
      }),
    ).then((registered) => {
      if (pushRegistrationEpochRef.current === epoch && tokensRef.current) {
        setPushRegistrationState(registered ? "registered" : "failed");
      }
      return registered;
    });
    pushRegistrationInFlight.current = attempt;
    try {
      return await attempt;
    } finally {
      if (pushRegistrationInFlight.current === attempt) {
        pushRegistrationInFlight.current = null;
      }
    }
  }, [authedFetch]);

  // Return a token guaranteed fresh for ~the next minute, refreshing if the
  // current one is expired or about to expire. Used before opening the socket.
  const ensureFreshToken = useCallback(async (): Promise<string | null> => {
    const current = tokensRef.current;
    if (!current) return null;
    const exp = tokenExpiry(current.accessToken);
    const now = Math.floor(Date.now() / 1000);
    if (exp !== null && exp - now < 60) {
      try {
        return await refreshAccessToken();
      } catch {
        applyTokens(null);
        return null;
      }
    }
    return current.accessToken;
  }, [refreshAccessToken, applyTokens]);

  useEffect(() => {
    if (!tokens) return;
    authedFetch<User>("/me")
      .then(setUser)
      .catch(() => applyTokens(null));
  }, [tokens, authedFetch, applyTokens]);

  // Permission survives browser sessions, but backend registration may not
  // (logout, token ownership transfer, endpoint rotation). Re-assert the
  // current subscription after every successful sign-in without prompting.
  useEffect(() => {
    if (!signedIn || typeof Notification === "undefined") return;
    if (Notification.permission === "granted") {
      void registerWebPush();
    }
  }, [registerWebPush, signedIn, user?.id]);

  useEffect(() => {
    incomingRef.current = incoming;
  }, [incoming]);

  const setCurrentActiveCall = useCallback((next: ActiveCall | null) => {
    activeCallRef.current = next;
    setActiveCall(next);
  }, []);

  const rememberTerminalCall = useCallback(
    (callId: string, type: TerminalEventType = "call_ended") => {
      const tombstones = terminalCallsRef.current;
      const now = Date.now();
      for (const [id, tombstone] of tombstones) {
        if (tombstone.expiresAt <= now) tombstones.delete(id);
      }
      const existing = tombstones.get(callId);
      if (
        type === "call_accepted" &&
        existing &&
        existing.type !== "call_accepted"
      ) {
        return;
      }
      // Refresh insertion order for duplicate provider/WS terminal delivery.
      tombstones.delete(callId);
      tombstones.set(callId, {
        expiresAt: now + TERMINAL_TOMBSTONE_TTL_MS,
        type,
      });
      while (tombstones.size > MAX_TERMINAL_TOMBSTONES) {
        const oldest = tombstones.keys().next().value as string | undefined;
        if (!oldest) break;
        tombstones.delete(oldest);
      }
    },
    [],
  );

  const isTerminalCall = useCallback((callId: string) => {
    const tombstone = terminalCallsRef.current.get(callId);
    if (tombstone === undefined) return false;
    if (tombstone.expiresAt <= Date.now()) {
      terminalCallsRef.current.delete(callId);
      return false;
    }
    return true;
  }, []);

  const beginMediaOperation = useCallback((callId: string | null) => {
    const generation = mediaOperationRef.current.generation + 1;
    mediaOperationRef.current = { generation, callId };
    return generation;
  }, []);

  const bindMediaOperation = useCallback(
    (generation: number, callId: string) => {
      if (mediaOperationRef.current.generation !== generation) return false;
      mediaOperationRef.current = { generation, callId };
      return !isTerminalCall(callId);
    },
    [isTerminalCall],
  );

  const isCurrentMediaOperation = useCallback(
    (generation: number, callId: string) =>
      mediaOperationRef.current.generation === generation &&
      mediaOperationRef.current.callId === callId &&
      !isTerminalCall(callId),
    [isTerminalCall],
  );

  const invalidateMediaOperation = useCallback((callId?: string) => {
    const current = mediaOperationRef.current;
    if (callId !== undefined && current.callId !== callId) return false;
    mediaOperationRef.current = {
      generation: current.generation + 1,
      callId: null,
    };
    return true;
  }, []);

  useEffect(() => {
    activeCallRef.current = activeCall;
  }, [activeCall]);

  useEffect(() => {
    localStreamRef.current = localStream;
  }, [localStream]);

  useEffect(() => {
    remoteStreamRef.current = remoteStream;
  }, [remoteStream]);

  useEffect(() => {
    knockSessionRef.current = knockSession;
  }, [knockSession]);

  useEffect(() => {
    if (localVideo.current) localVideo.current.srcObject = localStream;
  }, [localStream, activeCall]);

  useEffect(() => {
    if (remoteVideo.current) remoteVideo.current.srcObject = remoteStream;
  }, [remoteStream, activeCall]);

  useEffect(() => {
    if (!activeCall) {
      setElapsed(0);
      return;
    }
    const tick = () =>
      setElapsed(
        callStartRef.current
          ? Math.floor((Date.now() - callStartRef.current) / 1000)
          : 0,
      );
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [activeCall]);

  const ensureAudio = useCallback(() => {
    if (typeof window === "undefined") return null;
    const AudioCtor = window.AudioContext ?? window.webkitAudioContext;
    if (!AudioCtor) return null;
    if (!audioContext.current) audioContext.current = new AudioCtor();
    void audioContext.current.resume();
    return audioContext.current;
  }, []);

  const stopRingtone = useCallback(() => {
    if (ringTimer.current !== null) {
      window.clearInterval(ringTimer.current);
      ringTimer.current = null;
    }
  }, []);

  const playRingtone = useCallback(() => {
    stopRingtone();
    const ctx = ensureAudio();
    if (!ctx) return;

    const playTone = (frequency: number, offset: number) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = frequency;
      osc.type = "sine";
      osc.connect(gain);
      gain.connect(ctx.destination);
      const start = ctx.currentTime + offset;
      gain.gain.setValueAtTime(0, start);
      gain.gain.linearRampToValueAtTime(0.08, start + 0.04);
      gain.gain.linearRampToValueAtTime(0, start + 0.42);
      osc.start(start);
      osc.stop(start + 0.45);
    };

    const cycle = () => {
      playTone(880, 0);
      playTone(1175, 0.46);
    };
    cycle();
    ringTimer.current = window.setInterval(cycle, 2200);
  }, [ensureAudio, stopRingtone]);

  const recordRecent = useCallback((call: RecentCall) => {
    setRecents((current) => {
      if (current.some((entry) => entry.id === call.id)) return current;
      const next = [call, ...current].slice(0, 20);
      saveList("slide.web.recents", next);
      return next;
    });
  }, []);

  const endCall = useCallback(
    (notifyServer = true, expectedCallId?: string) => {
      const currentCall = activeCallRef.current;
      const operationMatches =
        expectedCallId !== undefined &&
        mediaOperationRef.current.callId === expectedCallId;
      if (
        expectedCallId !== undefined &&
        currentCall?.callId !== expectedCallId &&
        !operationMatches
      ) {
        return;
      }

      stopRingtone();
      const callId = currentCall?.callId ?? expectedCallId;
      if (callId) {
        rememberTerminalCall(callId, "call_ended");
        invalidateMediaOperation(callId);
      } else {
        invalidateMediaOperation();
      }

      // Null the ref first so LiveKit's Disconnected handler cannot re-enter.
      const lkRoom = room.current;
      room.current = null;
      lkRoom?.localParticipant.trackPublications.forEach((publication) => {
        publication.track?.mediaStreamTrack.stop();
      });
      lkRoom?.disconnect();
      localStreamRef.current?.getTracks().forEach((track) => track.stop());
      remoteStreamRef.current?.getTracks().forEach((track) => track.stop());
      localStreamRef.current = null;
      remoteStreamRef.current = null;

      if (currentCall) {
        const connected = everConnectedRef.current;
        const startedAt = callStartRef.current ?? Date.now();
        recordRecent({
          id: `${currentCall.callId}-${startedAt}`,
          peerName: currentCall.peerName,
          phone: currentCall.phone,
          userId: currentCall.userId,
          direction: currentCall.direction,
          video: currentCall.video,
          startedAt,
          durationSec: connected ? Math.round((Date.now() - startedAt) / 1000) : 0,
          connected,
        });
      }
      if (notifyServer && tokensRef.current && currentCall) {
        void authedFetch(`/calls/${currentCall.callId}/leave`, {
          method: "POST",
          headers: { "X-Call-Accept-Key": callAcceptKey(currentCall.callId) },
        }).catch(() => undefined);
      }
      callStartRef.current = null;
      everConnectedRef.current = false;
      setPeerConnected(false);
      setRemoteVideoReady(false);
      setLocalStream(null);
      setRemoteStream(null);
      setCurrentActiveCall(null);
      setStatus("Ready");
    },
    [
      authedFetch,
      invalidateMediaOperation,
      recordRecent,
      rememberTerminalCall,
      setCurrentActiveCall,
      stopRingtone,
    ],
  );

  const handleTerminalEvent = useCallback(
    (type: TerminalEventType, callId: string) => {
      if (type === "call_accepted") {
        const acceptedHere =
          acceptingCallIdRef.current === callId ||
          activeCallRef.current?.callId === callId;
        if (acceptedHere) return;

        rememberTerminalCall(callId, "call_accepted");
        invalidateMediaOperation(callId);
        if (incomingRef.current?.callId === callId) {
          stopRingtone();
          incomingRef.current = null;
          setIncoming(null);
          setStatus("Answered on another device");
        }
        return;
      }

      rememberTerminalCall(callId, type);
      invalidateMediaOperation(callId);
      const ringing = incomingRef.current;
      const wasAccepting = acceptingCallIdRef.current === callId;
      if (ringing?.callId === callId && activeCallRef.current?.callId !== callId) {
        recordRecent({
          id: `${callId}-missed`,
          peerName: ringing.fromName,
          userId: ringing.fromUserId,
          direction: "incoming",
          video: ringing.video,
          startedAt: Date.now(),
          durationSec: 0,
          connected: false,
        });
        stopRingtone();
        incomingRef.current = null;
        setIncoming(null);
        setStatus("Ready");
      }
      if (activeCallRef.current?.callId === callId) {
        endCall(false, callId);
      } else if (wasAccepting) {
        setStatus("Ready");
      }
    },
    [
      endCall,
      invalidateMediaOperation,
      recordRecent,
      rememberTerminalCall,
      stopRingtone,
    ],
  );

  const presentIncomingCall = useCallback(
    (next: IncomingCall) => {
      if (!tokensRef.current) return false;
      if (isTerminalCall(next.callId)) return false;
      if (invitationExpired(next)) {
        rememberTerminalCall(next.callId);
        void authedFetch(`/calls/${next.callId}/decline`, { method: "POST" }).catch(
          () => undefined,
        );
        if (incomingRef.current?.callId === next.callId) {
          stopRingtone();
          incomingRef.current = null;
          setIncoming(null);
        }
        return false;
      }

      const currentCall = activeCallRef.current;
      const currentIncoming = incomingRef.current;
      if (acceptingCallIdRef.current === next.callId) return true;
      if (
        outgoingCallGenerationRef.current !== null ||
        acceptingCallIdRef.current !== null ||
        (currentCall && currentCall.callId !== next.callId) ||
        (currentIncoming && currentIncoming.callId !== next.callId)
      ) {
        rememberTerminalCall(next.callId);
        void authedFetch(`/calls/${next.callId}/decline`, { method: "POST" }).catch(
          () => undefined,
        );
        return false;
      }
      if (currentIncoming?.callId === next.callId) return true;

      if (next.ringStyle === "knock") {
        if (knockClearTimer.current) {
          window.clearTimeout(knockClearTimer.current);
          knockClearTimer.current = null;
        }
        setKnocking(null);
      }
      incomingRef.current = next;
      setIncoming(next);
      setStatus("Incoming call");
      playRingtone();
      return true;
    },
    [
      authedFetch,
      isTerminalCall,
      playRingtone,
      rememberTerminalCall,
      stopRingtone,
    ],
  );

  useEffect(() => {
    if (!incoming?.expiresAt) return;
    const delay = Math.max(0, incoming.expiresAt - Date.now());
    const callId = incoming.callId;
    const timer = window.setTimeout(() => {
      if (incomingRef.current?.callId !== callId) return;
      rememberTerminalCall(callId);
      incomingRef.current = null;
      setIncoming(null);
      stopRingtone();
      void authedFetch(`/calls/${callId}/decline`, { method: "POST" }).catch(
        () => undefined,
      );
    }, delay);
    return () => window.clearTimeout(timer);
  }, [authedFetch, incoming, rememberTerminalCall, stopRingtone]);

  const hydrateIncomingCalls = useCallback(
    async (preferredCallId?: string | null) => {
      if (!tokensRef.current || !user?.id) return false;
      const response = await authedFetch<HistoryResponse>("/calls?limit=20");
      const incomingCalls = response.calls
        .map((call) => incomingFromCall(call, user.id))
        .filter(
          (call): call is IncomingCall =>
            call !== null && !isTerminalCall(call.callId),
        );
      const next =
        incomingCalls.find((call) => call.callId === preferredCallId) ?? incomingCalls[0];
      if (!next) {
        if (preferredCallId) {
          setIncoming((current) => (current?.callId === preferredCallId ? null : current));
        }
        return false;
      }
      return presentIncomingCall(next);
    },
    [authedFetch, isTerminalCall, presentIncomingCall, user?.id],
  );

  useEffect(() => {
    if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
    const onMessage = (event: MessageEvent) => {
      const message = event.data as
        | { type?: string; call?: SignalEvent; eventType?: string; callId?: string }
        | null;
      if (!message) return;

      if (message.type === "slide-call-terminal") {
        const terminalType = message.eventType as TerminalEventType | undefined;
        const callId = message.callId ?? message.call?.callId;
        if (
          callId &&
          (terminalType === "call_accepted" ||
            terminalType === "call_declined" ||
            terminalType === "call_ended")
        ) {
          handleTerminalEvent(terminalType, callId);
        }
        return;
      }

      if (
        message.type !== "slide-notification-click" &&
        message.type !== "slide-push-event" &&
        message.type !== "slide-stale-invitation"
      ) {
        return;
      }
      const next = message.call ? incomingFrom(message.call) : null;
      if (next) presentIncomingCall(next);
      void hydrateIncomingCalls(next?.callId ?? message.call?.callId).catch(
        () => undefined,
      );
    };
    navigator.serviceWorker.addEventListener("message", onMessage);
    return () => navigator.serviceWorker.removeEventListener("message", onMessage);
  }, [handleTerminalEvent, hydrateIncomingCalls, presentIncomingCall]);

  useEffect(() => {
    if (!signedIn || typeof window === "undefined") return;
    const params = new URLSearchParams(window.location.search);
    const callId = params.get("incomingCallId");
    void hydrateIncomingCalls(callId).catch(() => undefined);
    if (callId) {
      params.delete("incomingCallId");
      const query = params.toString();
      window.history.replaceState(
        null,
        "",
        `${window.location.pathname}${query ? `?${query}` : ""}`,
      );
    }
  }, [hydrateIncomingCalls, signedIn]);

  const rememberContact = useCallback((contact: Contact) => {
    setContacts((current) => {
      const next = [
        contact,
        ...current.filter((entry) => entry.userId !== contact.userId),
      ];
      saveList("slide.web.contacts", next);
      return next;
    });
  }, []);

  const refreshContacts = useCallback(async () => {
    if (!tokensRef.current) return;
    const serverContacts = await authedFetch<ServerContact[]>("/contacts");
    const onSlideContacts = serverContacts
      .map(serverContactToContact)
      .filter((contact): contact is Contact => Boolean(contact));
    saveList("slide.web.contacts", onSlideContacts);
    setContacts(onSlideContacts);
  }, [authedFetch]);

  useEffect(() => {
    if (!signedIn) return;
    void refreshContacts().catch(() => undefined);
    const id = window.setInterval(() => {
      void refreshContacts().catch(() => undefined);
    }, 15000);
    return () => window.clearInterval(id);
  }, [refreshContacts, signedIn]);

  useEffect(() => {
    if (!tokens) {
      signalingSocket.current?.close();
      signalingSocket.current = null;
      return;
    }

    let cancelled = false;
    let socket: WebSocket | null = null;
    let reconnectTimer: number | null = null;
    let heartbeatTimer: number | null = null;
    let attempts = 0;

    const clearHeartbeat = () => {
      if (heartbeatTimer !== null) {
        window.clearInterval(heartbeatTimer);
        heartbeatTimer = null;
      }
    };

    const scheduleReconnect = () => {
      if (cancelled || !tokensRef.current) return;
      const delay = Math.min(1000 * 2 ** attempts, 15000);
      attempts += 1;
      reconnectTimer = window.setTimeout(connect, delay);
    };

    const connect = async () => {
      if (cancelled) return;
      const token = await ensureFreshToken();
      if (cancelled || !token) return;
      const ws = new WebSocket(wsUrl(token));
      socket = ws;
      signalingSocket.current = ws;
      ws.onopen = () => {
        attempts = 0;
        setStatus("Browser calls are online");
        clearHeartbeat();
        heartbeatTimer = window.setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: "heartbeat" }));
          }
        }, 25000);
      };
      ws.onclose = () => {
        clearHeartbeat();
        if (cancelled) return;
        setStatus("Browser calls are offline");
        scheduleReconnect();
      };
      ws.onerror = () => setStatus("Signaling error");
      ws.onmessage = (message) => {
        let event: SignalEvent | null = null;
        try {
          event = JSON.parse(String(message.data)) as SignalEvent;
        } catch {
          event = null;
        }
        if (!event) return;
        if (event.type === "contacts_updated") {
          void refreshContacts().catch(() => undefined);
          return;
        }
        if (event.type === "incoming_call") {
          const next = incomingFrom(event);
          if (!next) return;
          presentIncomingCall(next);
          return;
        }
        if (event.type === "knock") {
          const fromUserId = isActionableUserId(event.fromUserId) ? event.fromUserId : "";
          const fromName = event.fromName ?? "Someone";
          // Every tap feels + sounds, with gentle pitch variation so a rhythm
          // reads as musical rather than robotic.
          playKnock(ensureAudio(), 0.9 + Math.random() * 0.2);
          vibrateKnock();

          // Anonymous live beats commonly race just ahead of their durable
          // knock invitation. Once the answer surface exists, keep the rhythm
          // feedback but never cover its controls with a raw-tap overlay.
          if (
            incomingRef.current?.ringStyle === "knock" ||
            activeCallRef.current !== null ||
            outgoingCallGenerationRef.current !== null ||
            acceptingCallIdRef.current !== null
          ) {
            return;
          }

          // If we're already in the duet stage with this person, land their tap
          // there (a blooming ripple) instead of popping the incoming card.
          if (knockSessionRef.current?.userId === fromUserId) {
            setKnockTheirPulse((n) => n + 1);
            return;
          }

          setKnocking((cur) => ({
            fromUserId,
            fromName,
            pulse: (cur?.fromUserId === fromUserId ? cur.pulse : 0) + 1,
          }));
          if (knockClearTimer.current) window.clearTimeout(knockClearTimer.current);
          knockClearTimer.current = window.setTimeout(() => setKnocking(null), 4000);
        }
        if (event.type === "call_accepted") {
          const eventCallId = event.callId ?? event.call?.id;
          if (eventCallId) handleTerminalEvent("call_accepted", eventCallId);
          return;
        }
        if (event.type === "call_ended" || event.type === "call_declined") {
          const eventCallId = event.callId ?? event.call?.id;
          if (eventCallId) handleTerminalEvent(event.type, eventCallId);
          return;
        }
      };
    };

    void connect();

    return () => {
      cancelled = true;
      if (reconnectTimer !== null) window.clearTimeout(reconnectTimer);
      clearHeartbeat();
      socket?.close();
      signalingSocket.current = null;
    };
  }, [
    authedFetch,
    ensureAudio,
    ensureFreshToken,
    playRingtone,
    presentIncomingCall,
    refreshContacts,
    handleTerminalEvent,
    tokens,
  ]);

  const sendKnock = useCallback(
    (toUserId: string) => {
      const sock = signalingSocket.current;
      if (!sock || sock.readyState !== WebSocket.OPEN) {
        setStatus("Knock needs browser calls online");
        return;
      }
      const now = Date.now();
      const dt = knockLastTap.current ? now - knockLastTap.current : 0;
      knockLastTap.current = now;
      knockSeq.current += 1;
      sock.send(
        JSON.stringify({
          type: "knock",
          to: toUserId,
          fromName: user?.displayName || user?.phone || "Someone",
          seq: knockSeq.current,
          dt,
        }),
      );
      playKnock(ensureAudio(), 0.9 + Math.random() * 0.2);
      vibrateKnock();
    },
    [ensureAudio, user],
  );

  // Open the full-screen duet stage for a person (also used by "knock back").
  const openKnock = useCallback(
    (userId: string, name: string, identityName: string = name) => {
      knockSeq.current = 0;
      knockLastTap.current = null;
      setKnockTheirPulse(0);
      setKnocking(null);
      setKnockSession({ userId, name, identityName });
    },
    [],
  );

  // Firebase phone auth: send the SMS via Google (no carrier registration), then
  // exchange the verified ID token for Slide tokens at /auth/firebase.
  const recaptchaRef = useRef<RecaptchaVerifier | null>(null);
  const confirmationRef = useRef<ConfirmationResult | null>(null);

  const requestOtp = async () => {
    setAuthBusy(true);
    setAuthError(null);
    try {
      const e164 = phone.startsWith("+") ? phone : `+1${phone.replace(/\D/g, "")}`;
      const auth = firebaseAuth();
      if (!recaptchaRef.current) {
        recaptchaRef.current = new RecaptchaVerifier(auth, "recaptcha-container", {
          size: "invisible",
        });
      }
      confirmationRef.current = await signInWithPhoneNumber(
        auth,
        e164,
        recaptchaRef.current,
      );
      setAuthStep("code");
    } catch (error) {
      // Reset the verifier so a retry gets a fresh challenge.
      recaptchaRef.current?.clear();
      recaptchaRef.current = null;
      setAuthError(
        error instanceof Error ? error.message : "Could not send a verification code.",
      );
    } finally {
      setAuthBusy(false);
    }
  };

  const verifyOtp = async () => {
    setAuthBusy(true);
    setAuthError(null);
    try {
      if (!confirmationRef.current) throw new Error("Request a code first.");
      const cred = await confirmationRef.current.confirm(code);
      const idToken = await cred.user.getIdToken();
      const response = await jsonFetch<
        AuthTokens & { user: User; isNewUser: boolean }
      >("/auth/firebase", null, {
        method: "POST",
        body: JSON.stringify({ idToken }),
      });
      applyTokens({
        accessToken: response.accessToken,
        refreshToken: response.refreshToken,
      });
      setUser(response.user);
      setAuthStep("phone");
      setCode("");
      confirmationRef.current = null;
      ensureAudio();
    } catch (error) {
      setAuthError(
        error instanceof Error ? error.message : "That code did not verify.",
      );
    } finally {
      setAuthBusy(false);
    }
  };

  const logout = () => {
    const accessToken = tokensRef.current?.accessToken ?? null;
    // Resolve every locally-owned call surface while this bearer still belongs
    // to the account. `keepalive` lets these finish if logout navigates/closes.
    const activeCallId = activeCallRef.current?.callId;
    const acceptingCallId = acceptingCallIdRef.current;
    const ringingCallId = incomingRef.current?.callId;
    if (activeCallId) bestEffortCallResolution(activeCallId, "leave", accessToken);
    // An in-flight accept is intentionally not resolved here: until its keyed
    // response returns, this tab cannot know whether a sibling installation won.
    // Its accept catch/reconciliation leaves only after confirming ownership.
    if (ringingCallId && ringingCallId !== activeCallId && ringingCallId !== acceptingCallId) {
      bestEffortCallResolution(ringingCallId, "decline", accessToken);
    }

    // Detach the browser endpoint while the bearer token still belongs to this
    // account, then invalidate it locally even if the network cleanup fails.
    void disableWebPush((endpoint) =>
      jsonFetch("/push/register", accessToken, {
        method: "DELETE",
        body: JSON.stringify({ pushToken: endpoint }),
      }),
    ).catch(() => undefined);
    const refreshToken = tokensRef.current?.refreshToken;
    if (refreshToken) {
      void jsonFetch("/auth/logout", null, {
        method: "POST",
        body: JSON.stringify({ refreshToken }),
      }).catch(() => undefined);
    }
    saveList("slide.web.contacts", []);
    saveList("slide.web.recents", []);
    setContacts([]);
    setRecents([]);
    pushRegistrationEpochRef.current += 1;
    pushRegistrationInFlight.current = null;
    setPushRegistrationState("idle");
    invalidateMediaOperation();
    outgoingCallGenerationRef.current = null;
    acceptingCallIdRef.current = null;
    incomingRef.current = null;
    setIncoming(null);
    stopRingtone();
    endCall(false);
    applyTokens(null);
  };

  const enableNotifications = async () => {
    ensureAudio();
    if (typeof Notification === "undefined") {
      setNotificationState("unsupported");
      return;
    }
    const permission = await Notification.requestPermission();
    setNotificationState(permission);
    if (permission === "granted" && tokensRef.current) {
      await registerWebPush();
    } else {
      setPushRegistrationState("idle");
    }
  };

  const checkNumber = async () => {
    if (!tokensRef.current) return;
    setCallError(null);
    setLookup({ status: "checking" });
    try {
      const results = await authedFetch<ContactSyncResult[]>("/contacts/sync", {
        method: "POST",
        body: JSON.stringify({ phones: [dialNumber], names: [dialNumber] }),
      });
      const contact = results[0];
      if (contact?.userId && contact.userId === user?.id) {
        setLookup({ status: "self" });
      } else if (contact?.onSlide && contact.userId) {
        setLookup({ status: "found", contact });
        rememberContact({
          userId: contact.userId,
          phone: contact.phone,
          displayName: contact.displayName,
        });
      } else {
        setLookup({ status: "not-found" });
      }
    } catch {
      setLookup({ status: "error", message: "Could not check that number." });
    }
  };

  const callContact = useCallback(
    (
      entry: {
        userId?: string | null;
        phone?: string | null;
        displayName?: string | null;
        peerName?: string;
      },
      video: boolean,
      ringStyle = "call",
    ) => {
      if (!entry.userId) {
        if (entry.phone) {
          setDialNumber(formatDial(entry.phone));
          setLookup({ status: "idle" });
        }
        return;
      }
      void startCall(
        {
          phone: entry.phone ?? entry.peerName ?? "",
          displayName: entry.displayName ?? entry.peerName ?? null,
          userId: entry.userId,
          onSlide: true,
        },
        video,
        ringStyle,
      );
    },
    // startCall is stable across renders for our purposes
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [tokens],
  );

  const toggleMute = useCallback(() => {
    setMuted((current) => {
      const next = !current;
      void room.current?.localParticipant.setMicrophoneEnabled(!next);
      return next;
    });
  }, []);

  const toggleCamera = useCallback(() => {
    setCameraOff((current) => {
      const next = !current;
      void room.current?.localParticipant.setCameraEnabled(!next);
      return next;
    });
  }, []);

  const startMedia = async (
    session: CallSession,
    peerName: string,
    direction: "incoming" | "outgoing",
    video: boolean,
    operationGeneration: number,
    meta: { phone?: string | null; userId?: string | null } = {},
  ) => {
    const callId = session.call.id;
    const assertCurrent = () => {
      if (!isCurrentMediaOperation(operationGeneration, callId)) {
        throw new StaleCallOperationError();
      }
    };

    assertCurrent();
    stopRingtone();
    setStatus("Connecting media");
    setPeerConnected(false);
    setRemoteVideoReady(false);
    setMuted(false);
    setCameraOff(false);
    everConnectedRef.current = false;
    callStartRef.current = Date.now();
    assertBrowserReachableSfu(session);
    setCurrentActiveCall({
      callId,
      peerName,
      direction,
      video,
      phone: meta.phone ?? null,
      userId: meta.userId ?? null,
    });

    // Remote media accumulates here as the other participant publishes tracks.
    const remote = new MediaStream();
    remoteStreamRef.current = remote;
    setRemoteStream(remote);

    const refreshRemote = () => {
      if (!isCurrentMediaOperation(operationGeneration, callId)) return;
      setRemoteStream(new MediaStream(remote.getTracks()));
      setRemoteVideoReady(remote.getVideoTracks().some((track) => track.readyState === "live"));
    };
    const markConnected = () => {
      if (!isCurrentMediaOperation(operationGeneration, callId)) return;
      setStatus("Connected");
      setPeerConnected(true);
      if (!everConnectedRef.current) {
        everConnectedRef.current = true;
        callStartRef.current = Date.now();
      }
    };

    const lkRoom = new Room({
      adaptiveStream: true,
      dynacast: true,
      // Capture + publish at 720p so the remote feed is crisp (the default
      // capture is softer). Simulcast layers let LiveKit drop to lower
      // resolutions only when bandwidth / the display size calls for it.
      videoCaptureDefaults: { resolution: VideoPresets.h720.resolution },
      publishDefaults: {
        videoSimulcastLayers: [VideoPresets.h180, VideoPresets.h360],
        videoEncoding: VideoPresets.h720.encoding,
      },
    });
    assertCurrent();
    room.current = lkRoom;

    lkRoom.on(RoomEvent.TrackSubscribed, (track: RemoteTrack) => {
      if (!isCurrentMediaOperation(operationGeneration, callId)) {
        track.mediaStreamTrack.stop();
        return;
      }
      if (track.kind === Track.Kind.Video || track.kind === Track.Kind.Audio) {
        remote.addTrack(track.mediaStreamTrack);
        refreshRemote();
        markConnected();
      }
    });
    lkRoom.on(RoomEvent.TrackUnsubscribed, (track: RemoteTrack) => {
      if (!isCurrentMediaOperation(operationGeneration, callId)) return;
      remote.removeTrack(track.mediaStreamTrack);
      refreshRemote();
    });
    // The far side hung up / the room emptied → tear our side down too.
    lkRoom.on(RoomEvent.ParticipantDisconnected, () => {
      if (lkRoom.numParticipants === 0) endCall(true, callId);
    });
    lkRoom.on(RoomEvent.Disconnected, () => {
      if (room.current === lkRoom) endCall(true, callId);
    });

    let mediaVideoEnabled = video;
    try {
      // Connect, then publish camera/mic. TCP fallback (port 7881) is built in,
      // so this works even where UDP is blocked.
      await lkRoom.connect(session.sfuUrl, session.joinToken);
      assertCurrent();
      await lkRoom.localParticipant.setMicrophoneEnabled(true);
      assertCurrent();
      if (video) {
        try {
          await lkRoom.localParticipant.setCameraEnabled(true);
        } catch {
          // Camera denial/unavailability should not discard a working accepted
          // audio call. Keep the mic and room, and make the UI authoritative.
          assertCurrent();
          mediaVideoEnabled = false;
          setCameraOff(true);
        }
      }
      assertCurrent();

      // Mirror the locally published tracks into a stream for the self-preview.
      const localTracks: MediaStreamTrack[] = [];
      lkRoom.localParticipant.trackPublications.forEach((pub) => {
        const track = pub.track?.mediaStreamTrack;
        if (track) localTracks.push(track);
      });
      assertCurrent();
      const local = new MediaStream(localTracks);
      localStreamRef.current = local;
      setLocalStream(local);

      setCurrentActiveCall({
        callId,
        peerName,
        direction,
        video: mediaVideoEnabled,
        phone: meta.phone ?? null,
        userId: meta.userId ?? null,
      });
    } catch (error) {
      const stillCurrent = isCurrentMediaOperation(operationGeneration, callId);
      if (room.current === lkRoom) room.current = null;
      lkRoom.localParticipant.trackPublications.forEach((publication) => {
        publication.track?.mediaStreamTrack.stop();
      });
      remote.getTracks().forEach((track) => track.stop());
      lkRoom.disconnect();
      if (stillCurrent) {
        localStreamRef.current = null;
        remoteStreamRef.current = null;
        setLocalStream(null);
        setRemoteStream(null);
        setRemoteVideoReady(false);
        if (activeCallRef.current?.callId === callId) {
          setCurrentActiveCall(null);
        }
      }
      throw error;
    }
  };

  const startCall = async (
    contact: ContactSyncResult,
    video: boolean,
    ringStyle = "call",
  ) => {
    if (!tokensRef.current || !contact.userId) return;
    if (
      outgoingCallGenerationRef.current !== null ||
      acceptingCallIdRef.current !== null ||
      activeCallRef.current !== null ||
      incomingRef.current !== null
    ) {
      setStatus("Finish the current call first");
      return;
    }
    if (contact.userId === user?.id) {
      setCallError("You can't call your own number.");
      return;
    }
    setCallError(null);
    rememberContact({
      userId: contact.userId,
      phone: contact.phone,
      displayName: contact.displayName,
    });
    let resolutionAccessToken = tokensRef.current.accessToken;
    const operationGeneration = beginMediaOperation(null);
    outgoingCallGenerationRef.current = operationGeneration;
    let createdCallId: string | null = null;
    try {
      setStatus("Starting call");
      const session = await authedFetch<CallSession>("/calls", {
        method: "POST",
        body: JSON.stringify({
          type: "one_to_one",
          participantUserIds: [contact.userId],
          videoEnabled: video,
          ringStyle,
        }),
      });
      resolutionAccessToken = tokensRef.current?.accessToken ?? resolutionAccessToken;
      createdCallId = session.call.id;
      // A very fast answer can beat the create response back to this tab. For
      // the caller, `call_accepted` is progress, not a sibling-device terminal.
      if (terminalCallsRef.current.get(createdCallId)?.type === "call_accepted") {
        terminalCallsRef.current.delete(createdCallId);
      }
      if (!bindMediaOperation(operationGeneration, createdCallId)) {
        throw new StaleCallOperationError();
      }
      await startMedia(
        session,
        contact.displayName || contact.phone,
        "outgoing",
        video,
        operationGeneration,
        { phone: contact.phone, userId: contact.userId },
      );
    } catch (error) {
      if (createdCallId) {
        bestEffortCallResolution(createdCallId, "leave", resolutionAccessToken);
      }
      const operationStillCurrent =
        mediaOperationRef.current.generation === operationGeneration;
      if (!operationStillCurrent) {
        return;
      }
      if (error instanceof StaleCallOperationError) {
        invalidateMediaOperation(createdCallId ?? undefined);
        setStatus("Ready");
        return;
      }
      invalidateMediaOperation(createdCallId ?? undefined);
      const message =
        error instanceof ApiError
          ? humanizeCallError(error.message)
          : "Could not start the call.";
      setCallError(message);
      setStatus("Ready");
    } finally {
      if (outgoingCallGenerationRef.current === operationGeneration) {
        outgoingCallGenerationRef.current = null;
      }
    }
  };

  const beginKnockCall = (session: KnockSession) => {
    // The first physical tap creates the durable invitation so a suspended or
    // closed recipient rings. The raw tap is only a live rhythm enhancement.
    sendKnock(session.userId);
    setKnockSession(null);
    void startCall(
      {
        phone: session.identityName,
        displayName: session.identityName,
        userId: session.userId,
        onSlide: true,
      },
      false,
      "knock",
    );
  };

  const acceptIncoming = async () => {
    const call = incomingRef.current;
    if (
      !tokensRef.current ||
      !call ||
      acceptingCallIdRef.current !== null ||
      activeCallRef.current !== null ||
      outgoingCallGenerationRef.current !== null
    ) {
      return;
    }
    if (isTerminalCall(call.callId) || invitationExpired(call)) {
      rememberTerminalCall(call.callId);
      incomingRef.current = null;
      setIncoming(null);
      stopRingtone();
      void authedFetch(`/calls/${call.callId}/decline`, { method: "POST" }).catch(
        () => undefined,
      );
      return;
    }
    acceptingCallIdRef.current = call.callId;
    let resolutionAccessToken = tokensRef.current.accessToken;
    const acceptKey = callAcceptKey(call.callId);
    const operationGeneration = beginMediaOperation(call.callId);
    let acceptedByThisInstallation = false;
    try {
      setIncoming(null);
      incomingRef.current = null;
      stopRingtone();
      let session: CallSession;
      try {
        session = await authedFetch<CallSession>(`/calls/${call.callId}/accept`, {
          method: "POST",
          headers: { "X-Call-Accept-Key": acceptKey },
        });
      } catch (firstError) {
        resolutionAccessToken = tokensRef.current?.accessToken ?? resolutionAccessToken;
        if (
          isAnsweredElsewhereError(firstError) ||
          (firstError instanceof ApiError && firstError.status < 500)
        ) {
          throw firstError;
        }
        // The first response may have been lost after the server committed.
        // Retry with the same key so the backend can return our session without
        // allowing another installation to join.
        await new Promise((resolve) => window.setTimeout(resolve, 350));
        session = await authedFetch<CallSession>(`/calls/${call.callId}/accept`, {
          method: "POST",
          headers: { "X-Call-Accept-Key": acceptKey },
        });
      }
      resolutionAccessToken = tokensRef.current?.accessToken ?? resolutionAccessToken;
      acceptedByThisInstallation = true;
      if (!bindMediaOperation(operationGeneration, call.callId)) {
        throw new StaleCallOperationError();
      }
      const caller = session.call.participants?.find(
        (participant) => participant.userId === session.call.createdBy,
      );
      const acceptedPeerName =
        caller?.displayName || caller?.phone || call.fromName || "Someone";
      const acceptedPeerUserId =
        caller?.userId || (isActionableUserId(call.fromUserId) ? call.fromUserId : null);
      await startMedia(
        session,
        acceptedPeerName,
        "incoming",
        call.video,
        operationGeneration,
        { userId: acceptedPeerUserId },
      );
    } catch (error) {
      if (isAnsweredElsewhereError(error)) {
        rememberTerminalCall(call.callId, "call_accepted");
        if (mediaOperationRef.current.generation === operationGeneration) {
          invalidateMediaOperation(call.callId);
        }
        setStatus("Answered on another device");
        stopRingtone();
        return;
      }
      const currentOperation = mediaOperationRef.current;
      const operationStillCurrent = currentOperation.generation === operationGeneration;
      // A stale response must not tear down a newer owner of the same accepted
      // call. Other stale paths (logout/terminal/new call) still resolve this
      // abandoned participant using the bearer captured before auth changes.
      const newerOperationOwnsCall =
        !operationStillCurrent && currentOperation.callId === call.callId;
      if (acceptedByThisInstallation && !newerOperationOwnsCall) {
        bestEffortCallResolution(call.callId, "leave", resolutionAccessToken);
      } else if (
        !acceptedByThisInstallation &&
        !(error instanceof ApiError && error.status < 500)
      ) {
        // We do not know whether a transport/5xx failure happened before or
        // after commit. Reconcile with the same key; only a confirmed owner may
        // send `/leave`, so a sibling installation's winning call stays intact.
        void reconcileAmbiguousAccept(call.callId, acceptKey, resolutionAccessToken);
      }
      if (!operationStillCurrent) {
        return;
      }
      if (error instanceof StaleCallOperationError) {
        invalidateMediaOperation(call.callId);
        setStatus("Ready");
        return;
      }
      invalidateMediaOperation(call.callId);
      setCallError(
        error instanceof ApiError
          ? humanizeCallError(error.message)
          : "Could not answer the call.",
      );
      setStatus("Ready");
      stopRingtone();
    } finally {
      if (acceptingCallIdRef.current === call.callId) {
        acceptingCallIdRef.current = null;
      }
    }
  };

  const declineIncoming = async () => {
    if (!tokens || !incoming) return;
    const call = incoming;
    rememberTerminalCall(call.callId);
    invalidateMediaOperation(call.callId);
    setIncoming(null);
    incomingRef.current = null;
    stopRingtone();
    recordRecent({
      id: `${call.callId}-${Date.now()}`,
      peerName: call.fromName,
      userId: call.fromUserId,
      direction: "incoming",
      video: call.video,
      startedAt: Date.now(),
      durationSec: 0,
      connected: false,
      label: "Declined",
    });
    await authedFetch(`/calls/${call.callId}/decline`, {
      method: "POST",
    }).catch(() => undefined);
  };

  const notificationLabel = useMemo(() => {
    if (notificationState === "granted" && pushRegistrationState === "registered") {
      return "Notifications on";
    }
    if (notificationState === "granted" && pushRegistrationState === "registering") {
      return "Enabling notifications…";
    }
    if (notificationState === "granted" && pushRegistrationState === "failed") {
      return "Retry notifications";
    }
    if (notificationState === "granted") return "Enable notifications";
    if (notificationState === "denied") return "Notifications blocked";
    if (notificationState === "unsupported") return "Notifications unavailable";
    return "Enable notifications";
  }, [notificationState, pushRegistrationState]);

  return (
    <section id="web" className="border-b border-hairline bg-bg">
      <div
        className={`mx-auto grid min-h-[calc(100vh-72px)] gap-8 px-6 py-10 lg:py-12 ${
          signedIn
            ? "max-w-xl place-items-center"
            : "max-w-6xl lg:grid-cols-[0.86fr_1.14fr] lg:items-center"
        }`}
      >
        {!signedIn ? (
          <div className="max-w-xl">
            <p className="text-[12px] font-light uppercase tracking-label text-text-secondary">
              iOS, Android, and web
            </p>
            <h1 className="mt-5 text-[56px] font-light leading-[0.95] tracking-wordmark text-text sm:text-[84px]">
              Slide
            </h1>
            <p className="mt-6 max-w-md text-[21px] font-light leading-snug text-text sm:text-[25px]">
              Phone-number video calls for the people you already know.
            </p>
            <p className="mt-4 max-w-md text-[15px] font-light leading-relaxed text-text-secondary">
              Sign in with your number, verify by code, and call someone by typing
              their phone number. Browser notifications ring when a call comes in.
            </p>
            <div className="mt-8 flex flex-wrap gap-3 text-[13px] text-text-secondary">
              <span className="rounded-full border border-hairline px-3 py-1">Web app</span>
              <span className="rounded-full border border-hairline px-3 py-1">iPhone</span>
              <span className="rounded-full border border-hairline px-3 py-1">Android</span>
            </div>
          </div>
        ) : null}

        <div className="w-full rounded-[8px] border border-hairline bg-white p-4 shadow-[0_1px_0_rgba(10,10,10,0.04)] sm:p-5">
          <div className="flex items-center justify-between border-b border-hairline pb-4">
            <div>
              <p className="text-[12px] font-light uppercase tracking-label text-text-secondary">
                Browser call surface
              </p>
              <p className="mt-1 text-[15px] text-text">{status}</p>
            </div>
            {signedIn ? (
              <button
                className="rounded-full border border-hairline px-3 py-1.5 text-[13px] text-text-secondary transition-colors hover:border-text/30 hover:text-text"
                onClick={logout}
              >
                Sign out
              </button>
            ) : null}
          </div>

          {!signedIn ? (
            <div className="grid gap-5 pt-5">
              <div>
                <h2 className="text-[28px] font-light leading-tight text-text">
                  Sign in by phone
                </h2>
                <p className="mt-2 text-[14px] leading-relaxed text-text-secondary">
                  Your phone number is your account. Enter the verification code
                  to create or open your Slide account.
                </p>
              </div>
              {authStep === "phone" ? (
                <div className="grid gap-2">
                  <span className="text-[12px] uppercase tracking-label text-text-secondary">
                    Phone number
                  </span>
                  <PhoneField onChange={setPhone} onEnter={requestOtp} />
                </div>
              ) : (
                <label className="grid gap-2">
                  <span className="text-[12px] uppercase tracking-label text-text-secondary">
                    Verification code
                  </span>
                  <input
                    value={code}
                    onChange={(event) => setCode(event.target.value)}
                    inputMode="numeric"
                    placeholder="123456"
                    className="h-12 rounded-[8px] border border-hairline bg-bg px-4 text-[24px] font-light tracking-[0.18em] outline-none transition-colors focus:border-text/40"
                  />
                </label>
              )}
              {authError ? <p className="text-[13px] text-danger">{authError}</p> : null}
              {/* Invisible reCAPTCHA target for Firebase phone auth. */}
              <div id="recaptcha-container" />
              <button
                className="h-12 rounded-[8px] bg-text px-4 text-[14px] font-medium text-white transition-opacity disabled:opacity-40"
                disabled={authBusy || (authStep === "phone" ? phone.length < 4 : code.length < 4)}
                onClick={authStep === "phone" ? requestOtp : verifyOtp}
              >
                {authBusy ? "Working" : authStep === "phone" ? "Send code" : "Verify"}
              </button>
            </div>
          ) : (
            <div className="grid gap-5 pt-5">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h2 className="text-[26px] font-light text-text">
                    {user?.displayName || user?.phone}
                  </h2>
                  <p className="text-[13px] text-text-secondary">
                    Call anyone on Slide by phone number.
                  </p>
                </div>
                <button
                  onClick={enableNotifications}
                  className="rounded-[8px] border border-hairline px-4 py-2 text-[13px] text-text transition-colors hover:border-text/30"
                >
                  {notificationLabel}
                </button>
              </div>

              <div className="grid gap-3 rounded-[8px] bg-bg-grouped p-4">
                <label className="grid gap-2">
                  <span className="text-[12px] uppercase tracking-label text-text-secondary">
                    Call by phone number
                  </span>
                  <div className="flex flex-col gap-2 sm:flex-row">
                    <input
                      value={dialNumber}
                      onChange={(event) => {
                        setDialNumber(formatDial(event.target.value));
                        setLookup({ status: "idle" });
                        setCallError(null);
                      }}
                      inputMode="tel"
                      placeholder="+1 415 555 0123"
                      className="h-12 flex-1 rounded-[8px] border border-hairline bg-white px-4 text-[18px] font-light outline-none transition-colors focus:border-text/40"
                    />
                    <button
                      className="h-12 rounded-[8px] bg-text px-5 text-[14px] font-medium text-white transition-opacity disabled:opacity-40"
                      disabled={lookup.status === "checking" || dialNumber.replace(/\D/g, "").length < 4}
                      onClick={checkNumber}
                    >
                      {lookup.status === "checking" ? "Checking" : "Check"}
                    </button>
                  </div>
                </label>

                {lookup.status === "found" ? (
                  <div className="rounded-[12px] border border-hairline bg-white p-5">
                    <div className="flex items-center gap-3">
                      <div className="flex h-12 w-12 items-center justify-center rounded-full border border-hairline text-[14px] text-text-secondary">
                        {(lookup.contact.displayName || lookup.contact.phone).slice(0, 2).toUpperCase()}
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-[17px] text-text">
                          {lookup.contact.displayName || lookup.contact.phone}
                        </p>
                        <p className="text-[12px] text-text-secondary">On Slide</p>
                      </div>
                    </div>

                    {/* Audio ↔ Video slider: a dark pill glides under the choice. */}
                    <div className="relative mt-5 flex rounded-full bg-bg-grouped p-1 text-[14px]">
                      <span
                        className="pointer-events-none absolute bottom-1 top-1 w-[calc(50%-4px)] rounded-full bg-text transition-[left] duration-300 ease-out"
                        style={{ left: dialVideo ? "calc(50% + 0px)" : "4px" }}
                      />
                      <button
                        onClick={() => setDialVideo(false)}
                        className={`relative z-10 flex flex-1 items-center justify-center gap-2 py-2 transition-colors ${
                          !dialVideo ? "text-white" : "text-text-secondary"
                        }`}
                      >
                        <PhoneIcon className="h-4 w-4" />
                        Audio
                      </button>
                      <button
                        onClick={() => setDialVideo(true)}
                        className={`relative z-10 flex flex-1 items-center justify-center gap-2 py-2 transition-colors ${
                          dialVideo ? "text-white" : "text-text-secondary"
                        }`}
                      >
                        <VideoIcon className="h-4 w-4" />
                        Video
                      </button>
                    </div>

                    {/* Knock pad: opens the realtime Knock surface. */}
                    <div className="mt-6 flex flex-col items-center">
                      <button
                        aria-label={`Knock ${lookup.contact.displayName || lookup.contact.phone}`}
                        onClick={() => {
                          if (!lookup.contact.userId) return;
                          openKnock(
                            lookup.contact.userId,
                            lookup.contact.displayName || lookup.contact.phone,
                          );
                        }}
                        className="grid h-32 w-32 select-none place-items-center rounded-full border border-hairline bg-bg-grouped text-[48px] transition-transform active:scale-95 active:bg-text/[0.06]"
                      >
                        ✊
                      </button>
                      <p className="mt-3 text-[13px] text-text-secondary">
                        Knock knock knock. They feel every knock.
                      </p>
                      <button
                        onClick={() => startCall(lookup.contact, dialVideo)}
                        className="mt-5 inline-flex h-12 w-full items-center justify-center gap-2 rounded-full bg-text px-5 text-[14px] font-medium text-white transition-transform active:scale-[0.98]"
                      >
                        {dialVideo ? (
                          <VideoIcon className="h-4 w-4" />
                        ) : (
                          <PhoneIcon className="h-4 w-4" />
                        )}
                        {dialVideo ? "Start video call" : "Start call"}
                      </button>
                    </div>
                  </div>
                ) : null}

                {lookup.status === "self" ? (
                  <p className="text-[13px] text-text-secondary">
                    That&apos;s your own number. Enter someone else&apos;s to call
                    them.
                  </p>
                ) : null}
                {lookup.status === "not-found" ? (
                  <p className="text-[13px] text-text-secondary">
                    That number is not on Slide yet. Send them the site link.
                  </p>
                ) : null}
                {lookup.status === "error" ? (
                  <p className="text-[13px] text-danger">{lookup.message}</p>
                ) : null}
                {callError ? (
                  <p className="text-[13px] text-danger">{callError}</p>
                ) : null}
              </div>

              {contacts.length > 0 ? (
                <div className="grid gap-2">
                  <span className="text-[12px] uppercase tracking-label text-text-secondary">
                    On Slide
                  </span>
                  <div className="grid gap-1.5">
                    {contacts.map((contact) => {
                      const name = contact.displayName || contact.phone;
                      return (
                        <div
                          key={contact.userId}
                          className="group flex items-center gap-3 rounded-[8px] border border-transparent px-2 py-2 transition-colors hover:border-hairline hover:bg-bg-grouped"
                        >
                          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-bg-grouped text-[12px] font-medium text-text-secondary">
                            {initials(name)}
                          </div>
                          <div className="min-w-0 flex-1">
                            <p className="truncate text-[15px] text-text">{name}</p>
                            {contact.displayName ? (
                              <p className="truncate text-[12px] text-text-secondary">
                                {contact.phone}
                              </p>
                            ) : null}
                          </div>
	                          <div className="flex gap-1.5 opacity-70 transition-opacity group-hover:opacity-100">
	                            <button
	                              className="flex h-9 w-9 items-center justify-center rounded-full border border-hairline text-[17px] text-text transition-colors hover:border-text/30"
	                              onClick={() => openKnock(contact.userId, name)}
	                              aria-label={`Knock ${name}`}
	                            >
	                              ✊
	                            </button>
	                            <button
	                              className="flex h-9 w-9 items-center justify-center rounded-full border border-hairline text-text transition-colors hover:border-text/30"
	                              onClick={() => callContact(contact, false)}
                              aria-label={`Audio call ${name}`}
                            >
                              <PhoneIcon className="h-4 w-4" />
                            </button>
                            <button
                              className="flex h-9 w-9 items-center justify-center rounded-full bg-text text-white transition-opacity hover:opacity-90"
                              onClick={() => callContact(contact, true)}
                              aria-label={`Video call ${name}`}
                            >
                              <VideoIcon className="h-4 w-4" />
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ) : null}

              {recents.length > 0 ? (
                <div className="grid gap-2">
                  <div className="flex items-center justify-between">
                    <span className="text-[12px] uppercase tracking-label text-text-secondary">
                      Recent
                    </span>
                    <button
                      className="text-[12px] text-text-secondary transition-colors hover:text-text"
                      onClick={() => {
                        setRecents([]);
                        saveList("slide.web.recents", []);
                      }}
                    >
                      Clear
                    </button>
                  </div>
                  <div className="grid gap-0.5">
                    {recents.slice(0, 6).map((call) => {
                      const missed = !call.connected;
                      return (
                        <div
                          key={call.id}
                          className="group flex items-center gap-2 rounded-[8px] px-2 py-2 transition-colors hover:bg-bg-grouped"
                        >
                          <button
                            className="flex min-w-0 flex-1 items-center gap-3 text-left"
                            onClick={() => callContact(call, false, "knock")}
                            aria-label={`Knock ${call.peerName}`}
                          >
                            <span
                              className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${
                                missed
                                  ? "bg-danger/10 text-danger"
                                  : "bg-bg-grouped text-text-secondary"
                              }`}
                            >
                              {call.direction === "incoming" ? (
                                <ArrowIncomingIcon className="h-4 w-4" />
                              ) : (
                                <ArrowOutgoingIcon className="h-4 w-4" />
                              )}
                            </span>
                            <span className="min-w-0 flex-1">
                              <span
                                className={`block truncate text-[15px] ${
                                  missed ? "text-danger" : "text-text"
                                }`}
                              >
                                {call.peerName}
                              </span>
                              <span className="block truncate text-[12px] text-text-secondary">
                                {call.video ? "Video" : "Audio"} · {recentOutcome(call)}
                              </span>
                            </span>
                          </button>
                          <span className="shrink-0 text-[12px] text-text-secondary">
                            {relativeTime(call.startedAt)}
                          </span>
                          <button
                            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-hairline text-[15px] text-text transition-colors hover:border-text/30"
                            onClick={() => callContact(call, false, "knock")}
                            aria-label={`Knock ${call.peerName}`}
                          >
                            ✊
                          </button>
                          <button
                            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-text-secondary transition-colors hover:text-text"
                            onClick={() => callContact(call, call.video)}
                            aria-label={`Call ${call.peerName}`}
                          >
                            {call.video ? (
                              <VideoIcon className="h-4 w-4" />
                            ) : (
                              <PhoneIcon className="h-4 w-4" />
                            )}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ) : null}

              {contacts.length === 0 && recents.length === 0 ? (
                <div className="rounded-[8px] border border-dashed border-hairline px-4 py-8 text-center">
                  <p className="text-[14px] text-text">No calls yet</p>
                  <p className="mt-1 text-[13px] text-text-secondary">
                    Look up a phone number above to start your first call.
                    People you reach show up here.
                  </p>
                </div>
              ) : null}
            </div>
          )}
        </div>
      </div>

      {activeCall ? (
        <div className="fixed inset-0 z-50 flex flex-col bg-[#0b0b0c] text-white">
          <video
            ref={remoteVideo}
            autoPlay
            playsInline
            className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-500 ${
              activeCall.video && remoteVideoReady && status !== "Call failed"
                ? "opacity-100"
                : "opacity-0"
            }`}
          />

          {!(activeCall.video && remoteVideoReady && status !== "Call failed") ? (
            <div className="absolute inset-0 grid place-items-center px-6">
              <div className="flex flex-col items-center text-center">
                <div className="flex h-28 w-28 items-center justify-center rounded-full border border-white/15 bg-white/[0.06] text-[34px] font-light backdrop-blur-sm">
                  {initials(activeCall.peerName)}
                </div>
                <h2 className="mt-6 text-[30px] font-light">{activeCall.peerName}</h2>
                <p className="mt-2 text-[15px] text-white/60">
                  {status === "Call failed"
                    ? "Call failed"
                    : activeCall.video && peerConnected && !remoteVideoReady
                      ? "Waiting for video…"
                      : peerConnected
                        ? formatDuration(elapsed)
                      : activeCall.direction === "outgoing"
                        ? "Calling…"
                        : "Connecting…"}
                </p>
              </div>
            </div>
          ) : (
            <div className="pointer-events-none absolute inset-x-0 top-0 bg-gradient-to-b from-black/55 to-transparent p-6">
              <p className="text-[17px] font-light">{activeCall.peerName}</p>
              <p className="text-[13px] text-white/60">{formatDuration(elapsed)}</p>
            </div>
          )}

          {activeCall.video ? (
            <div className="absolute right-5 top-5 h-40 w-28 overflow-hidden rounded-[14px] border border-white/15 bg-black/50 shadow-[0_8px_30px_rgba(0,0,0,0.4)] sm:h-44 sm:w-32">
              <video
                ref={localVideo}
                autoPlay
                muted
                playsInline
                className={`h-full w-full object-cover transition-opacity ${
                  cameraOff ? "opacity-0" : "opacity-100"
                }`}
              />
              {cameraOff ? (
                <div className="absolute inset-0 grid place-items-center text-white/50">
                  <VideoOffIcon className="h-6 w-6" />
                </div>
              ) : null}
            </div>
          ) : (
            <video ref={localVideo} autoPlay muted playsInline className="hidden" />
          )}

          <div className="absolute inset-x-0 bottom-0 flex items-center justify-center gap-5 bg-gradient-to-t from-black/70 to-transparent px-6 pb-10 pt-16">
            <button
              onClick={toggleMute}
              aria-pressed={muted}
              aria-label={muted ? "Unmute" : "Mute"}
              className={`flex h-14 w-14 items-center justify-center rounded-full backdrop-blur-sm transition-colors ${
                muted ? "bg-white text-text" : "bg-white/15 text-white hover:bg-white/25"
              }`}
            >
              {muted ? <MicOffIcon className="h-6 w-6" /> : <MicIcon className="h-6 w-6" />}
            </button>
            {activeCall.video ? (
              <button
                onClick={toggleCamera}
                aria-pressed={cameraOff}
                aria-label={cameraOff ? "Turn camera on" : "Turn camera off"}
                className={`flex h-14 w-14 items-center justify-center rounded-full backdrop-blur-sm transition-colors ${
                  cameraOff
                    ? "bg-white text-text"
                    : "bg-white/15 text-white hover:bg-white/25"
                }`}
              >
                {cameraOff ? (
                  <VideoOffIcon className="h-6 w-6" />
                ) : (
                  <VideoIcon className="h-6 w-6" />
                )}
              </button>
            ) : null}
            <button
              onClick={() => endCall()}
              aria-label="End call"
              className="flex h-16 w-16 items-center justify-center rounded-full bg-danger text-white shadow-[0_8px_30px_rgba(229,72,77,0.45)] transition-transform hover:scale-105"
            >
              <PhoneIcon className="h-7 w-7 rotate-[135deg]" />
            </button>
          </div>
        </div>
      ) : null}

      {knockSession ? (
        <KnockSurface
          name={knockSession.name}
          theirPulse={knockTheirPulse}
          onTap={() => beginKnockCall(knockSession)}
          onCall={() => beginKnockCall(knockSession)}
          onClose={() => setKnockSession(null)}
        />
      ) : null}

      {knocking && !knockSession ? (
        <KnockIncoming
          pulseKey={knocking.pulse}
          onKnockBack={
            isActionableUserId(knocking.fromUserId)
              ? () => {
                  const k = knocking;
                  openKnock(k.fromUserId, "Someone", k.fromName);
                  sendKnock(k.fromUserId);
                }
              : undefined
          }
          onCall={
            isActionableUserId(knocking.fromUserId)
              ? () => {
                  const k = knocking;
                  setKnocking(null);
                  startCall(
                    {
                      phone: k.fromName,
                      displayName: k.fromName,
                      userId: k.fromUserId,
                      onSlide: true,
                    },
                    true,
                  );
                }
              : undefined
          }
          onDismiss={() => setKnocking(null)}
        />
      ) : null}

      {incoming ? (
        <div className="fixed inset-0 z-50 grid place-items-center bg-white/92 px-6 backdrop-blur-sm">
          <div className="w-full max-w-sm rounded-[8px] border border-hairline bg-white p-6 text-center shadow-[0_20px_80px_rgba(10,10,10,0.10)]">
            <div className="mx-auto flex h-24 w-24 animate-gentle-pulse items-center justify-center rounded-full border border-hairline text-[28px] font-light text-text">
              {incoming.ringStyle === "knock" ? "✊" : initials(incoming.fromName)}
            </div>
            <h2 className="mt-5 text-[30px] font-light text-text">
              {incoming.ringStyle === "knock" ? "Knock knock." : incoming.fromName}
            </h2>
            <p className="mt-1 text-[14px] text-text-secondary">
              {incoming.ringStyle === "knock"
                ? "Someone's at your door"
                : incoming.video
                  ? "Incoming browser video call"
                  : "Incoming browser call"}
            </p>
            <div className="mt-8 flex items-end justify-center gap-6">
              <div className="flex flex-col items-center gap-2">
                <button
                  className="flex h-16 w-16 items-center justify-center rounded-full bg-danger text-white transition-transform hover:scale-105"
                  onClick={declineIncoming}
                  aria-label="Decline"
                >
                  <PhoneIcon className="h-7 w-7 rotate-[135deg]" />
                </button>
                <span className="text-[12px] text-text-secondary">Decline</span>
              </div>
              <div className="flex flex-col items-center gap-2">
                <button
                  className="flex h-16 w-16 items-center justify-center rounded-full bg-text text-white transition-transform hover:scale-105"
                  onClick={() => acceptIncoming()}
                  aria-label="Accept call"
                >
                  {incoming.video ? (
                    <VideoIcon className="h-7 w-7" />
                  ) : (
                    <PhoneIcon className="h-7 w-7" />
                  )}
                </button>
                <span className="text-[12px] text-text-secondary">Accept</span>
              </div>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}

declare global {
  interface Window {
    webkitAudioContext?: typeof AudioContext;
  }
}
