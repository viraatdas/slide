package ai.exla.slide.ui.incall

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.exla.slide.call.CallPeer
import ai.exla.slide.ui.components.AvatarCircle
import ai.exla.slide.ui.components.CircleIconButton
import ai.exla.slide.ui.theme.SlideColors
import kotlin.math.roundToInt

/**
 * Full-white incoming-call screen: large avatar + name, with a gentle pulse
 * (1.0 → 1.04). Black Accept, red Decline. (AGENTS.md)
 */
@Composable
fun IncomingCallScreen(
    peer: CallPeer,
    videoEnabled: Boolean = true,
    isKnock: Boolean = false,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val displayName = if (isKnock) "Someone" else peer.displayName ?: peer.phone ?: "Slide"
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlideColors.Bg)
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        AvatarCircle(
            name = displayName,
            size = 128.dp,
            modifier = Modifier.scale(pulse),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            displayName,
            color = SlideColors.Ink,
            fontWeight = FontWeight.Light,
            fontSize = 30.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                isKnock -> "is knocking — answer to find out who"
                videoEnabled -> "Incoming video call"
                else -> "Incoming call"
            },
            color = SlideColors.InkSecondary,
            fontSize = 15.sp,
        )
        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SlideToAnswer(videoEnabled = videoEnabled, isKnock = isKnock, onComplete = onAccept)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircleIconButton(
                    icon = Icons.Outlined.CallEnd,
                    contentDescription = "Decline",
                    onClick = onDecline,
                    diameter = 72.dp,
                    filled = true,
                    color = SlideColors.Danger,
                )
                Spacer(Modifier.height(10.dp))
                Text("Decline", color = SlideColors.InkSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SlideToAnswer(videoEnabled: Boolean, isKnock: Boolean, onComplete: () -> Unit) {
    val density = LocalDensity.current
    var completed by androidx.compose.runtime.remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(SlideColors.SurfaceMuted)
            .border(1.dp, SlideColors.Hairline, RoundedCornerShape(34.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        val knobSize = 60.dp
        val inset = 4.dp
        val maxOffset = with(density) { (maxWidth - knobSize - inset * 2).toPx().coerceAtLeast(0f) }
        var rawOffset by androidx.compose.runtime.remember(maxOffset) { mutableFloatStateOf(0f) }
        val animatedOffset by animateFloatAsState(
            targetValue = rawOffset,
            animationSpec = tween(180),
            label = "answerSlide",
        )

        Text(
            when {
                isKnock -> "Slide to pick up"
                videoEnabled -> "Slide for video"
                else -> "Slide to answer"
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 72.dp),
            color = SlideColors.InkSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
        )

        Box(
            modifier = Modifier
                .offset {
                    val insetPx = with(density) { inset.toPx() }
                    IntOffset((animatedOffset + insetPx).roundToInt(), 0)
                }
                .size(knobSize)
                .clip(CircleShape)
                .background(SlideColors.Ink)
                .pointerInput(maxOffset, completed) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            if (!completed) {
                                change.consume()
                                rawOffset = (rawOffset + dragAmount.x).coerceIn(0f, maxOffset)
                            }
                        },
                        onDragEnd = {
                            if (!completed) {
                                if (rawOffset >= maxOffset * 0.72f) {
                                    completed = true
                                    rawOffset = maxOffset
                                    onComplete()
                                } else {
                                    rawOffset = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            if (!completed) rawOffset = 0f
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (videoEnabled) Icons.Outlined.Videocam else Icons.Outlined.Phone,
                contentDescription = null,
                tint = SlideColors.Bg,
                modifier = Modifier.size(25.dp),
            )
        }
    }
}
