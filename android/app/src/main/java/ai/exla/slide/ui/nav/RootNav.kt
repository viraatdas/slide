package ai.exla.slide.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import ai.exla.slide.messaging.PushTokens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.exla.slide.AppContainer
import ai.exla.slide.IncomingCallLaunch
import ai.exla.slide.call.CallPeer
import ai.exla.slide.call.CallConnectionState
import ai.exla.slide.knock.IncomingKnockBanner
import ai.exla.slide.knock.KnockPad
import ai.exla.slide.knock.KnockViewModel
import ai.exla.slide.messaging.IncomingCallNotifier
import ai.exla.slide.messaging.IncomingCallPayload
import ai.exla.slide.messaging.CallResolutionKind
import ai.exla.slide.ui.VmFactory
import ai.exla.slide.ui.components.PrimaryButton
import ai.exla.slide.ui.components.SecondaryButton
import ai.exla.slide.ui.theme.SlideColors
import ai.exla.slide.ui.incall.InCallScreen
import ai.exla.slide.ui.incall.InCallViewModel
import ai.exla.slide.ui.incall.IncomingCallScreen
import ai.exla.slide.ui.onboarding.AuthStep
import ai.exla.slide.ui.onboarding.AuthViewModel
import ai.exla.slide.ui.onboarding.CodeScreen
import ai.exla.slide.ui.onboarding.NameScreen
import ai.exla.slide.ui.onboarding.PhoneScreen
import ai.exla.slide.ui.onboarding.WelcomeScreen
import kotlinx.coroutines.launch

/** Top-level app state: which major surface is showing. */
private sealed interface RootScreen {
    data object Auth : RootScreen
    data object Main : RootScreen
    data class Incoming(
        val callId: String,
        val peer: CallPeer,
        val videoEnabled: Boolean,
        val ringStyle: String = "call",
    ) : RootScreen
    data class InCall(
        val peer: CallPeer,
        val incomingCallId: String? = null,
        val videoEnabled: Boolean = true,
        val ringStyle: String = "call",
        val activeCallId: String? = incomingCallId,
        val mediaReady: Boolean = false,
        val needsSessionStart: Boolean = true,
    ) : RootScreen
    data class Knock(val peer: CallPeer) : RootScreen
    data object MediaPermissionRequired : RootScreen
    data object CallStartFailed : RootScreen
}

@Composable
fun SlideAppRoot(
    container: AppContainer,
    incomingLaunch: IncomingCallLaunch? = null,
    onIncomingLaunchConsumed: () -> Unit = {},
    onIncomingCallFinished: () -> Unit = {},
) {
    val context = LocalContext.current
    val factory = remember(container) { VmFactory(container) }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(initialRootScreen(container)) }
    var pendingPermissionCall by remember { mutableStateOf<RootScreen.InCall?>(null) }

    // Request POST_NOTIFICATIONS (API 33+) so the full-screen incoming-call
    // notification can ring. Fire-and-forget; the result is informational.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — no-op, push simply won't ring without it */ }

    val mediaPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) mediaResult@{
        val pending = pendingPermissionCall ?: return@mediaResult
        pendingPermissionCall = null
        if (pending.incomingCallId?.let(container.callEventCoordinator::isResolved) == true) {
            screen = RootScreen.Main
            onIncomingCallFinished()
            return@mediaResult
        }
        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            pending.incomingCallId?.let { callId ->
                container.callEventCoordinator.resolve(callId, CallResolutionKind.Declined)
                scope.launch { container.repository.declineCall(callId) }
            }
            screen = RootScreen.MediaPermissionRequired
            onIncomingCallFinished()
        } else {
            // Camera denial degrades a video invitation to audio; microphone
            // denial cannot produce a call and is handled above.
            val ready = pending.copy(
                videoEnabled = pending.videoEnabled && cameraGranted,
                mediaReady = true,
            )
            val prepared = container.callService.prepare(
                peer = ready.peer,
                isIncoming = ready.incomingCallId != null,
                videoEnabled = ready.videoEnabled,
                ringStyle = ready.ringStyle,
                callId = ready.incomingCallId,
            )
            if (prepared) {
                val invitationCurrent = ready.incomingCallId?.let {
                    container.callEventCoordinator.consumeInvitation(it)
                } ?: true
                if (invitationCurrent) {
                    screen = ready
                } else {
                    container.callService.end()
                    screen = RootScreen.Main
                    onIncomingCallFinished()
                }
            } else {
                ready.incomingCallId?.let { callId ->
                    container.callEventCoordinator.resolve(
                        callId,
                        CallResolutionKind.Declined,
                    )
                    scope.launch { container.repository.declineCall(callId) }
                }
                screen = RootScreen.CallStartFailed
                onIncomingCallFinished()
            }
        }
    }

    fun beginCall(target: RootScreen.InCall) {
        // Silence the ringing notification immediately, but retain pending
        // invitation state until permissions and the foreground service are
        // ready. That state recovers a permission-dialog configuration change.
        target.incomingCallId?.let { IncomingCallNotifier.dismiss(context, it) }
        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (micGranted && (!target.videoEnabled || cameraGranted)) {
            val ready = target.copy(mediaReady = true)
            val prepared = container.callService.prepare(
                peer = ready.peer,
                isIncoming = ready.incomingCallId != null,
                videoEnabled = ready.videoEnabled,
                ringStyle = ready.ringStyle,
                callId = ready.incomingCallId,
            )
            if (prepared) {
                val invitationCurrent = ready.incomingCallId?.let {
                    container.callEventCoordinator.consumeInvitation(it)
                } ?: true
                if (invitationCurrent) {
                    screen = ready
                } else {
                    container.callService.end()
                    screen = RootScreen.Main
                    onIncomingCallFinished()
                }
            } else {
                ready.incomingCallId?.let { callId ->
                    container.callEventCoordinator.resolve(
                        callId,
                        CallResolutionKind.Declined,
                    )
                    scope.launch { container.repository.declineCall(callId) }
                }
                screen = RootScreen.CallStartFailed
                onIncomingCallFinished()
            }
            return
        }
        pendingPermissionCall = target
        val permissions = buildList {
            if (!micGranted) add(Manifest.permission.RECORD_AUDIO)
            if (target.videoEnabled && !cameraGranted) add(Manifest.permission.CAMERA)
        }.toTypedArray()
        mediaPermission.launch(permissions)
    }

    // Once signed in: ask for notifications (33+) and register the FCM token.
    // Both are no-ops if not applicable (pre-33 / Firebase not configured yet).
    LaunchedEffect(screen !is RootScreen.Auth) {
        if (screen is RootScreen.Auth) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        PushTokens.registerCurrentToken(context, container.repository)
    }

    // Shared, app-scoped knock VM: drives the incoming-knock banner overlay and
    // outgoing taps from the knock pad. Lives above the screen `when` so the
    // banner can float over any surface.
    val knockVm: KnockViewModel = viewModel(factory = factory)
    val inCallVm: InCallViewModel = viewModel(factory = factory)
    val incomingKnock by knockVm.incoming.collectAsStateWithLifecycle()

    LaunchedEffect(screen is RootScreen.Auth) {
        if (screen is RootScreen.Auth) {
            container.signalingClient.disconnect()
            return@LaunchedEffect
        }

        container.signalingClient.connect()
    }

    LaunchedEffect(container.callEventCoordinator) {
        container.callEventCoordinator.invitations.collect { payload ->
            if (payload == null) {
                if (screen is RootScreen.Incoming) {
                    screen = RootScreen.Main
                    onIncomingCallFinished()
                }
                return@collect
            }
            // Invitation and terminal events are buffered across Activity
            // recreation. A terminal event may have won while collectors were
            // absent, so never surface its older queued invitation afterward.
            if (container.callEventCoordinator.isResolved(payload.callId)) {
                return@collect
            }
            // A raw WebSocket tap can precede the durable invitation. Once the
            // real invite exists its full incoming-call surface owns the UI.
            knockVm.dismissIncoming()
            val incoming = payload.toIncomingScreen()
            when (val current = screen) {
                is RootScreen.Incoming -> {
                    if (current.callId != incoming.callId) {
                        scope.launch { container.repository.declineCall(incoming.callId) }
                    }
                }
                is RootScreen.InCall -> {
                    val currentId = current.activeCallId ?: current.incomingCallId
                    if (currentId != incoming.callId) {
                        scope.launch { container.repository.declineCall(incoming.callId) }
                    }
                }
                RootScreen.Auth -> Unit
                else -> screen = incoming
            }
        }
    }

    // FCM, notification actions/timeouts, and WebSocket terminal events all
    // converge here so the local Incoming UI cannot outlive its invitation.
    LaunchedEffect(container.callEventCoordinator) {
        container.callEventCoordinator.resolutions.collect { resolution ->
            when (val current = screen) {
                is RootScreen.Incoming -> {
                    if (current.callId == resolution.callId) {
                        screen = RootScreen.Main
                        onIncomingCallFinished()
                    }
                }
                is RootScreen.InCall -> {
                    val matches = current.activeCallId == resolution.callId ||
                        current.incomingCallId == resolution.callId
                    if (matches && resolution.kind != CallResolutionKind.AcceptedElsewhere) {
                        inCallVm.remoteEnded(resolution.callId)
                        screen = RootScreen.Main
                        onIncomingCallFinished()
                    }
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(container.callEventCoordinator) {
        container.callEventCoordinator.localEnds.collect { callId ->
            val current = screen
            if (current is RootScreen.InCall) {
                val matches = callId == null || current.activeCallId == callId ||
                    current.incomingCallId == callId
                if (matches) {
                    inCallVm.end {
                        screen = RootScreen.Main
                        onIncomingCallFinished()
                    }
                }
            }
        }
    }

    LaunchedEffect(incomingLaunch?.nonce) {
        val launch = incomingLaunch ?: return@LaunchedEffect
        onIncomingLaunchConsumed()
        val payload = launch.payload
        // Notification launches race the raw knock collector in the same way
        // as foreground delivery; always prefer the durable invitation.
        knockVm.dismissIncoming()
        if (!container.tokenStore.isLoggedIn ||
            payload.isStale() ||
            container.callEventCoordinator.isResolved(payload.callId)
        ) {
            IncomingCallNotifier.dismiss(context, payload.callId)
            if (container.tokenStore.isLoggedIn &&
                !container.callEventCoordinator.isResolved(payload.callId)
            ) {
                container.callEventCoordinator.resolve(
                    payload.callId,
                    CallResolutionKind.Declined,
                )
                scope.launch { container.repository.declineCall(payload.callId) }
            }
            onIncomingCallFinished()
            return@LaunchedEffect
        }

        if (!container.callEventCoordinator.stageInvitation(payload)) {
            scope.launch { container.repository.declineCall(payload.callId) }
            onIncomingCallFinished()
            return@LaunchedEffect
        }

        val target = payload.toIncomingScreen()
        when (val current = screen) {
            is RootScreen.InCall -> {
                val currentId = current.activeCallId ?: current.incomingCallId
                if (currentId != target.callId) {
                    scope.launch { container.repository.declineCall(target.callId) }
                }
            }
            is RootScreen.Incoming -> {
                if (current.callId != target.callId) {
                    scope.launch { container.repository.declineCall(target.callId) }
                }
                else if (launch.autoAccept) {
                    beginCall(target.toInCall())
                }
            }
            RootScreen.Auth -> {
                container.callEventCoordinator.resolve(
                    target.callId,
                    CallResolutionKind.Declined,
                )
                scope.launch { container.repository.declineCall(target.callId) }
                onIncomingCallFinished()
            }
            else -> {
                // stageInvitation drives the durable Incoming surface. Avoid
                // writing a one-shot screen value here after a racing terminal
                // event has already cleared the pending state.
                if (launch.autoAccept) beginCall(target.toInCall())
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    when (val current = screen) {
        RootScreen.Auth -> AuthFlow(
            container = container,
            onAuthenticated = { screen = RootScreen.Main },
        )

        RootScreen.Main -> {
            MainShell(
                container = container,
                onStartCall = { peer, videoEnabled ->
                    beginCall(RootScreen.InCall(peer, videoEnabled = videoEnabled))
                },
                onStartKnock = { peer -> screen = RootScreen.Knock(peer) },
                onLoggedOut = {
                    PushTokens.deleteCurrentToken(context)
                    container.signalingClient.disconnect()
                    IncomingCallNotifier.dismiss(context)
                    container.callService.end()
                    screen = RootScreen.Auth
                    onIncomingCallFinished()
                },
            )
        }

        is RootScreen.Incoming -> {
            fun declineIncoming() {
                container.callEventCoordinator.resolve(
                    current.callId,
                    CallResolutionKind.Declined,
                )
                screen = RootScreen.Main
                onIncomingCallFinished()
                scope.launch {
                    container.repository.declineCall(current.callId)
                }
            }

            BackHandler { declineIncoming() }

            IncomingCallScreen(
                peer = current.peer,
                videoEnabled = current.videoEnabled,
                isKnock = current.ringStyle == "knock",
                onAccept = {
                    beginCall(current.toInCall())
                },
                onDecline = { declineIncoming() },
            )
        }

        is RootScreen.InCall -> {
            val vm = inCallVm
            val callState by vm.state.collectAsStateWithLifecycle()
            fun finishCallUi() {
                screen = RootScreen.Main
                onIncomingCallFinished()
            }
            LaunchedEffect(callState.callId, callState.connection) {
                val id = callState.callId
                if (id != null &&
                    (callState.connection == CallConnectionState.Connecting ||
                        callState.connection == CallConnectionState.Ringing ||
                        callState.connection == CallConnectionState.Connected) &&
                    current.activeCallId != id
                ) {
                    screen = current.copy(activeCallId = id)
                }
            }
            LaunchedEffect(
                current.peer.userId,
                current.incomingCallId,
                current.videoEnabled,
                current.ringStyle,
                current.mediaReady,
                current.needsSessionStart,
            ) {
                if (!current.mediaReady || !current.needsSessionStart) return@LaunchedEffect
                if (current.incomingCallId != null) {
                    vm.acceptCall(
                        current.incomingCallId,
                        current.peer,
                        current.videoEnabled,
                        current.ringStyle,
                    )
                } else {
                    // Mock service renders immediately; real impl performs POST /calls.
                    vm.placeCall(current.peer, current.videoEnabled, current.ringStyle)
                }
                screen = current.copy(needsSessionStart = false)
            }
            InCallScreen(
                vm = vm,
                onKnockTap = if (current.ringStyle == "knock" && current.incomingCallId == null) {
                    { knockVm.tap(current.peer.userId) }
                } else null,
                onMediaTerminated = { vm.handleMediaTerminated(::finishCallUi) },
                onEnded = ::finishCallUi,
            )
        }

        is RootScreen.Knock -> {
            KnockPad(
                peer = current.peer,
                onKnock = {
                    knockVm.startPattern()
                    knockVm.tap(current.peer.userId)
                    beginCall(RootScreen.InCall(
                        current.peer,
                        videoEnabled = false,
                        ringStyle = "knock",
                    ))
                },
                onDone = { screen = RootScreen.Main },
            )
        }

        RootScreen.MediaPermissionRequired -> MediaPermissionRequiredScreen(
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
                screen = RootScreen.Main
            },
            onCancel = { screen = RootScreen.Main },
        )

        RootScreen.CallStartFailed -> CallStartFailedScreen(
            onDone = { screen = RootScreen.Main },
        )
    }

        // A raw knock must never cover the durable accept/decline surface.
        val showBanner = screen !is RootScreen.Auth &&
            screen !is RootScreen.Incoming &&
            screen !is RootScreen.InCall
        if (showBanner) {
            val canRespond = incomingKnock?.canRespond == true
            val knockBackAction: (() -> Unit)? = if (canRespond) {
                { knockVm.knockBack() }
            } else {
                null
            }
            val callAction: (() -> Unit)? = if (canRespond) {
                {
                    val peer = incomingKnock?.toPeer()
                    knockVm.dismissIncoming()
                    if (peer != null) beginCall(RootScreen.InCall(peer, videoEnabled = false))
                }
            } else {
                null
            }
            IncomingKnockBanner(
                knock = incomingKnock,
                onKnockBack = knockBackAction,
                onCall = callAction,
                onDismiss = { knockVm.dismissIncoming() },
            )
        }

    }
}

@Composable
private fun AuthFlow(container: AppContainer, onAuthenticated: () -> Unit) {
    val factory = remember(container) { VmFactory(container) }
    val vm: AuthViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()

    when (state.step) {
        AuthStep.Welcome -> WelcomeScreen(onGetStarted = vm::goToPhone)
        AuthStep.Phone -> PhoneScreen(vm)
        AuthStep.Code -> CodeScreen(vm, onAuthenticated = { onAuthenticated() })
        AuthStep.Name -> NameScreen(vm, onDone = onAuthenticated)
        AuthStep.Done -> onAuthenticated()
    }
}

@Composable
private fun MediaPermissionRequiredScreen(
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Microphone access is required",
            style = MaterialTheme.typography.headlineSmall,
            color = SlideColors.Ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Slide cannot place or answer a call without your microphone. You can enable it in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = SlideColors.InkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(text = "Open Settings", onClick = onOpenSettings)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(text = "Not now", onClick = onCancel)
    }
}

@Composable
private fun CallStartFailedScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Couldn't start the call",
            style = MaterialTheme.typography.headlineSmall,
            color = SlideColors.Ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Android couldn't start the protected call service. Check system restrictions and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = SlideColors.InkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(text = "Done", onClick = onDone)
    }
}

private fun IncomingCallPayload.toIncomingScreen(): RootScreen.Incoming {
    return RootScreen.Incoming(
        callId = callId,
        peer = toPeer(),
        videoEnabled = videoEnabled,
        ringStyle = ringStyle,
    )
}

private fun RootScreen.Incoming.toInCall() = RootScreen.InCall(
    peer = peer,
    incomingCallId = callId,
    videoEnabled = videoEnabled,
    ringStyle = ringStyle,
)

private fun initialRootScreen(container: AppContainer): RootScreen {
    if (!container.tokenStore.isLoggedIn) return RootScreen.Auth
    val active = container.callService.state.value
    val peer = active.peer
    return if (peer != null &&
        (active.connection == CallConnectionState.Connecting ||
            active.connection == CallConnectionState.Ringing ||
            active.connection == CallConnectionState.Connected)
    ) {
        RootScreen.InCall(
            peer = peer,
            incomingCallId = if (active.isIncoming) active.callId else null,
            videoEnabled = !active.audioOnly,
            ringStyle = active.ringStyle,
            activeCallId = active.callId,
            mediaReady = true,
            needsSessionStart = false,
        )
    } else {
        RootScreen.Main
    }
}
