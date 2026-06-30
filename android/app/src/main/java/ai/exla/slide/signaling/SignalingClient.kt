package ai.exla.slide.signaling

import ai.exla.slide.data.auth.TokenStore
import ai.exla.slide.data.model.SignalEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

/**
 * App-level signaling socket (plane A): GET /v1/ws?token=<accessToken>.
 * Surfaces incoming_call / call_* / participant_* / presence_update events,
 * sends heartbeat/presence_ping, and reconnects with capped exponential backoff.
 * WebRTC SDP/ICE happens with the SFU (plane B), not here.
 */
class SignalingClient(
    private val tokenStore: TokenStore,
    private val wsBaseUrl: String,
    private val json: Json,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<SignalEnvelope>(extraBufferCapacity = 32)
    val events: SharedFlow<SignalEnvelope> = _events.asSharedFlow()

    private val _connected = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 4)
    val connected: SharedFlow<Boolean> = _connected.asSharedFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var pendingSocket: WebSocket? = null
    private var loopJob: Job? = null
    private var heartbeatJob: Job? = null
    private var attempt = 0
    @Volatile private var running = false
    private val generation = AtomicLong(0)

    @Synchronized
    fun connect() {
        if (running) return
        running = true
        attempt = 0
        val currentGeneration = generation.incrementAndGet()
        loopJob = scope.launch { connectLoop(currentGeneration) }
    }

    @Synchronized
    fun disconnect() {
        running = false
        generation.incrementAndGet()
        heartbeatJob?.cancel()
        loopJob?.cancel()
        pendingSocket?.cancel()
        webSocket?.cancel()
        pendingSocket = null
        webSocket = null
        _connected.tryEmit(false)
    }

    fun send(envelope: SignalEnvelope): Boolean {
        val payload = json.encodeToString(SignalEnvelope.serializer(), envelope)
        return webSocket?.send(payload) ?: false
    }

    /**
     * Relay a single knock tap to [to] (a callee user-id UUID). The wire shape is
     * {"type":"knock","to":..,"fromName":..,"seq":..,"dt":..}; the server fans it
     * out to the target's sockets as a `knock` event (surfaced via [events]).
     * [dt] is ms since the previous tap (0 for the first tap of a pattern).
     */
    fun sendKnock(to: String, fromName: String, seq: Int, dt: Int): Boolean =
        send(
            SignalEnvelope(
                type = "knock",
                to = to,
                fromName = fromName,
                seq = seq,
                dt = dt,
            )
        )

    private suspend fun connectLoop(currentGeneration: Long) {
        while (isCurrent(currentGeneration)) {
            val token = tokenStore.accessToken
            if (token.isNullOrEmpty()) {
                delay(2000)
                continue
            }
            val url = "${wsBaseUrl.trimEnd('/')}?token=$token"
            val request = Request.Builder().url(url).build()
            val opened = openSocket(request, currentGeneration)
            if (!isCurrent(currentGeneration)) break
            attempt = if (opened) 0 else attempt + 1
            val backoffMs = min(30_000.0, 500.0 * 2.0.pow(attempt.toDouble())).toLong()
            delay(backoffMs)
        }
    }

    /** Returns true once the socket successfully opened (so backoff can reset). */
    private suspend fun openSocket(request: Request, currentGeneration: Long): Boolean {
        var everOpened = false
        val closed = CompletableDeferred<Unit>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(currentGeneration)) {
                    webSocket.cancel()
                    if (!closed.isCompleted) closed.complete(Unit)
                    return
                }
                everOpened = true
                this@SignalingClient.webSocket = webSocket
                if (pendingSocket === webSocket) pendingSocket = null
                _connected.tryEmit(true)
                startHeartbeat(currentGeneration, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(currentGeneration) || this@SignalingClient.webSocket !== webSocket) return
                runCatching { json.decodeFromString(SignalEnvelope.serializer(), text) }
                    .onSuccess { _events.tryEmit(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@SignalingClient.webSocket === webSocket) {
                    this@SignalingClient.webSocket = null
                    heartbeatJob?.cancel()
                    _connected.tryEmit(false)
                }
                if (!closed.isCompleted) closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@SignalingClient.webSocket === webSocket) {
                    this@SignalingClient.webSocket = null
                    heartbeatJob?.cancel()
                    _connected.tryEmit(false)
                }
                if (!closed.isCompleted) closed.complete(Unit)
            }
        }

        val socket = client.newWebSocket(request, listener)
        pendingSocket = socket
        if (!isCurrent(currentGeneration)) socket.cancel()
        try {
            closed.await()
        } finally {
            if (pendingSocket === socket) pendingSocket = null
            if (!isCurrent(currentGeneration)) socket.cancel()
            if (webSocket === socket) {
                heartbeatJob?.cancel()
                webSocket = null
            }
        }
        return everOpened
    }

    private fun startHeartbeat(currentGeneration: Long, socket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isCurrent(currentGeneration) && webSocket === socket) {
                delay(25_000)
                if (isCurrent(currentGeneration) && webSocket === socket) {
                    val payload = json.encodeToString(
                        SignalEnvelope.serializer(),
                        SignalEnvelope(type = "heartbeat"),
                    )
                    socket.send(payload)
                }
            }
        }
    }

    private fun isCurrent(currentGeneration: Long): Boolean =
        running && generation.get() == currentGeneration && tokenStore.isLoggedIn
}
