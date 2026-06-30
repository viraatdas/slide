package ai.exla.slide.knock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.exla.slide.ui.components.quietClickable
import ai.exla.slide.ui.theme.SlideColors

/**
 * Lightweight incoming-knock overlay (NOT the full incoming-call screen). Sits
 * at the top of the screen above everything else, plays sound + haptic on each
 * tap and visibly pulses per incoming tap. Identified legacy knocks can offer
 * actions; anonymous privacy-preserving taps remain display-only. It auto-
 * dismisses ~2.5s after the last tap (handled by [KnockViewModel]).
 */
@Composable
fun IncomingKnockBanner(
    knock: IncomingKnock?,
    onKnockBack: (() -> Unit)?,
    onCall: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val effects = remember { KnockEffects(context) }
    val scale = remember { Animatable(1f) }

    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { effects.release() } }

    // Re-trigger feedback + pulse whenever a new tap arrives (pulse increments).
    LaunchedEffect(knock?.fromUserId, knock?.pulse) {
        if (knock != null && knock.pulse > 0) {
            effects.play()
            scale.snapTo(1.06f)
            scale.animateTo(1f, tween(durationMillis = 220))
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(top = 12.dp, start = 12.dp, end = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = knock != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val k = knock ?: return@AnimatedVisibility
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(scale.value)
                    .background(SlideColors.Bg, RoundedCornerShape(18.dp))
                    .border(BorderStroke(1.dp, SlideColors.Hairline), RoundedCornerShape(18.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(44.dp).background(SlideColors.Ink, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✊", fontSize = 22.sp, color = SlideColors.Bg)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${k.displayName} is knocking",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SlideColors.Ink,
                        )
                        Text(
                            text = if (onKnockBack != null || onCall != null) {
                                "Knock back or pick up"
                            } else {
                                "Listen for the rhythm"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = SlideColors.InkSecondary,
                        )
                    }
                    Text(
                        text = "✕",
                        fontSize = 16.sp,
                        color = SlideColors.InkSecondary,
                        modifier = Modifier
                            .quietClickable(onDismiss)
                            .padding(4.dp),
                    )
                }
                if (onKnockBack != null || onCall != null) {
                    Spacer(Modifier.height(14.dp))
                    Row {
                        if (onKnockBack != null) {
                            BannerAction(
                                label = "Knock back",
                                filled = false,
                                onClick = onKnockBack,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (onKnockBack != null && onCall != null) {
                            Spacer(Modifier.width(10.dp))
                        }
                        if (onCall != null) {
                            BannerAction(
                                label = "Call",
                                filled = true,
                                onClick = onCall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerAction(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(
                if (filled) SlideColors.Accent else SlideColors.Bg,
                RoundedCornerShape(12.dp),
            )
            .then(
                if (filled) Modifier
                else Modifier.border(BorderStroke(1.dp, SlideColors.Hairline), RoundedCornerShape(12.dp))
            )
            .quietClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) SlideColors.Bg else SlideColors.Ink,
            textAlign = TextAlign.Center,
        )
    }
}
