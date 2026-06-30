package ai.exla.slide.ui.incall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.exla.slide.call.CallConnectionState
import ai.exla.slide.call.CallUiState
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import ai.exla.slide.ui.components.AvatarCircle
import ai.exla.slide.ui.components.CircleIconButton
import ai.exla.slide.ui.theme.SlideColors
import ai.exla.slide.knock.KnockEffects
import ai.exla.slide.util.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun InCallScreen(
    vm: InCallViewModel,
    onKnockTap: (() -> Unit)? = null,
    onMediaTerminated: () -> Unit,
    onEnded: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.connection) {
        if (state.connection == CallConnectionState.Ended ||
            state.connection == CallConnectionState.Failed
        ) {
            onMediaTerminated()
        }
    }

    // Chrome auto-hides after a few seconds; tap to reveal.
    var chromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(chromeVisible, state.connection) {
        if (chromeVisible && state.connection == CallConnectionState.Connected) {
            delay(4000)
            chromeVisible = false
        }
    }

    val isVideoCall = !state.audioOnly
    val onVideoSurface = isVideoCall && state.remoteVideoActive

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isVideoCall) SlideColors.Ink else SlideColors.Bg)
            .pointerInput(Unit) {
                detectTapGestures { chromeVisible = !chromeVisible }
            },
    ) {
        if (!isVideoCall) {
            if (state.ringStyle == "knock" &&
                !state.remoteParticipantPresent &&
                onKnockTap != null
            ) {
                KnockWaitingStage(state, onKnockTap)
            } else {
                AudioOnlyStage(state)
            }
        } else if (!onVideoSurface) {
            VideoConnectingStage(state)
            DraggableSelfView(room = vm.room(), track = vm.localTrack())
        } else {
            // Full-bleed remote video behind the chrome + a draggable self-view.
            LiveKitVideoView(
                room = vm.room(),
                track = vm.remoteTrack(),
                mirror = false,
                modifier = Modifier.fillMaxSize(),
            )
            DraggableSelfView(room = vm.room(), track = vm.localTrack())
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopChrome(state, isVideoCall)
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ControlBar(
                state = state,
                isVideoCall = isVideoCall,
                onMute = { vm.toggleMic() },
                onFlip = { vm.flipCamera() },
                onVideo = { vm.toggleCamera() },
                onEnd = { vm.end(onEnded) },
            )
        }
    }
}

@Composable
private fun KnockWaitingStage(state: CallUiState, onKnockTap: () -> Unit) {
    val context = LocalContext.current
    val effects = remember { KnockEffects(context) }
    DisposableEffect(effects) { onDispose { effects.release() } }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            state.peer?.displayName ?: state.peer?.phone ?: "Slide",
            color = SlideColors.Ink,
            fontWeight = FontWeight.Light,
            fontSize = 28.sp,
        )
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .size(176.dp)
                .background(SlideColors.Ink, CircleShape)
                .clickable {
                    effects.play()
                    onKnockTap()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("✊", fontSize = 68.sp, color = SlideColors.Bg)
        }
        Spacer(Modifier.height(20.dp))
        Text("Tap to keep knocking", color = SlideColors.InkSecondary, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun AudioOnlyStage(state: CallUiState) {
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        AvatarCircle(name = state.peer?.displayName, size = 120.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            state.peer?.displayName ?: "Calling…",
            color = SlideColors.Ink,
            fontWeight = FontWeight.Light,
            fontSize = 28.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(statusText(state), color = SlideColors.InkSecondary, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun VideoConnectingStage(state: CallUiState) {
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        AvatarCircle(
            name = state.peer?.displayName,
            size = 120.dp,
            backgroundColor = SlideColors.OnVideo.copy(alpha = 0.1f),
            textColor = SlideColors.OnVideo,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            state.peer?.displayName ?: "Calling…",
            color = SlideColors.OnVideo,
            fontWeight = FontWeight.Light,
            fontSize = 28.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(videoWaitingText(state), color = SlideColors.OnVideo.copy(alpha = 0.7f), fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun TopChrome(state: CallUiState, isVideoCall: Boolean) {
    val tint = if (isVideoCall) SlideColors.OnVideo else SlideColors.Ink
    Column(
        modifier = Modifier.fillMaxWidth().systemBarsPadding().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            state.peer?.displayName ?: "",
            color = tint,
            fontWeight = FontWeight.Light,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(statusText(state), color = tint.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}

@Composable
private fun ControlBar(
    state: CallUiState,
    isVideoCall: Boolean,
    onMute: () -> Unit,
    onFlip: () -> Unit,
    onVideo: () -> Unit,
    onEnd: () -> Unit,
) {
    val tint = if (isVideoCall) SlideColors.OnVideo else SlideColors.Ink

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = if (state.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = "Mute",
            onClick = onMute,
            diameter = 56.dp,
            color = tint,
        )
        if (isVideoCall) {
            CircleIconButton(
                icon = Icons.Outlined.Cameraswitch,
                contentDescription = "Flip camera",
                onClick = onFlip,
                diameter = 56.dp,
                color = tint,
            )
            CircleIconButton(
                icon = if (state.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                contentDescription = "Camera on/off",
                onClick = onVideo,
                diameter = 56.dp,
                color = tint,
            )
        }
        CircleIconButton(
            icon = Icons.Outlined.CallEnd,
            contentDescription = "End call",
            onClick = onEnd,
            diameter = 64.dp,
            filled = true,
            color = SlideColors.Danger,
        )
    }
}

/** Rounded, draggable local self-view in the corner. */
@Composable
private fun DraggableSelfView(room: Room?, track: VideoTrack?) {
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .systemBarsPadding()
            .padding(16.dp)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(108.dp, 160.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SlideColors.InkSecondary.copy(alpha = 0.4f))
            .pointerInput(Unit) {
                // Self-contained drag using only the ui-layer pointer API.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            val delta = change.position - change.previousPosition
                            offset += delta
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (room != null && track != null) {
            LiveKitVideoView(room = room, track = track, mirror = true, modifier = Modifier.fillMaxSize())
        } else {
            AvatarCircle(name = "Me", size = 56.dp, backgroundColor = SlideColors.Bg)
        }
    }
}

/**
 * Renders a LiveKit [VideoTrack] via a [TextureViewRenderer] inside an
 * [AndroidView]. Keyed on the track so a new renderer is built when the track
 * changes; the old one is detached + released on disposal.
 */
@Composable
private fun LiveKitVideoView(
    room: Room?,
    track: VideoTrack?,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    if (room == null || track == null) {
        Box(modifier.background(SlideColors.Ink))
        return
    }
    key(track) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextureViewRenderer(ctx).also { view ->
                    room.initVideoRenderer(view)
                    view.setMirror(mirror)
                    track.addRenderer(view)
                }
            },
            onRelease = { view ->
                track.removeRenderer(view)
                view.release()
            },
        )
    }
}

private fun statusText(state: CallUiState): String = when (state.connection) {
    CallConnectionState.Connecting -> if (state.ringStyle == "knock") "Knocking…" else "Connecting…"
    CallConnectionState.Ringing -> if (state.ringStyle == "knock") "Knocking…" else "Ringing…"
    CallConnectionState.Connected -> formatDuration(state.durationSec)
    CallConnectionState.Ended -> "Call ended"
    CallConnectionState.Failed -> "Call failed"
    CallConnectionState.Idle -> if (state.ringStyle == "knock") "Knocking…" else "Calling…"
}

private fun videoWaitingText(state: CallUiState): String {
    if (state.connection == CallConnectionState.Connected && !state.remoteVideoActive) {
        return "Waiting for video"
    }
    return statusText(state)
}
