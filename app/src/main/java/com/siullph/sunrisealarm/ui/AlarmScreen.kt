package com.siullph.sunrisealarm.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.siullph.sunrisealarm.R
import com.siullph.sunrisealarm.ScreenSunrise
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What sits under the sunrise light: for this app's own alarms, a clock and a slider to
 * dismiss or snooze. Hidden while the light is on, and for linked sunrises (where the
 * Clock app's own alarm screen is underneath instead).
 */
@Composable
fun AlarmScreen(sunrise: ScreenSunrise?, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val visible = sunrise != null && sunrise.alarmScreen && !sunrise.lightOn
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(500)) +
                slideInVertically(tween(700, easing = FastOutSlowInEasing)) { it / 8 },
            exit = fadeOut(tween(250)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Icon(
                    painterResource(R.drawable.ic_sunrise),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Clock()
                Text(
                    "Sunrise alarm",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1.3f))
                AlarmSlider(onSnooze = onSnooze, onDismiss = onDismiss)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Slide right to dismiss, left to snooze",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    Text(
        DateFormat.getTimeFormat(context).format(Date(now)),
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private val KNOB = 76.dp

/**
 * A knob in the middle of a pill: drag it right to dismiss or left to snooze. Let go short
 * of the end and it springs back.
 */
@Composable
fun AlarmSlider(onSnooze: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var dragX by remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val breathing = rememberInfiniteTransition(label = "knob")
    val pulse by breathing.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(KNOB + 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val reach = with(density) { ((maxWidth - KNOB - 16.dp) / 2).toPx() }
        val currentReach by rememberUpdatedState(reach)
        val pull = if (reach > 0f) (dragX / reach).coerceIn(-1f, 1f) else 0f

        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Snooze",
                style = MaterialTheme.typography.labelLarge,
                color = lerp(MaterialTheme.colorScheme.onSurfaceVariant, tertiary, (-pull).coerceAtLeast(0f)),
            )
            Text(
                "Dismiss",
                style = MaterialTheme.typography.labelLarge,
                color = lerp(MaterialTheme.colorScheme.onSurfaceVariant, primary, pull.coerceAtLeast(0f)),
            )
        }
        run {
            val scale = if (dragX == 0f && !done) pulse else 1f + 0.06f * abs(pull)
            Box(
                modifier = Modifier
                    .offset { IntOffset(dragX.roundToInt(), 0) }
                    .size(KNOB)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(lerp(primary, tertiary, (-pull).coerceAtLeast(0f)))
                    .draggable(
                        orientation = Orientation.Horizontal,
                        enabled = !done,
                        state = rememberDraggableState { delta ->
                            dragX = (dragX + delta).coerceIn(-currentReach, currentReach)
                        },
                        onDragStopped = { velocity ->
                            val r = currentReach
                            val target = when {
                                dragX > r * 0.7f || (velocity > FLING && dragX > r * 0.25f) -> r
                                dragX < -r * 0.7f || (velocity < -FLING && dragX < -r * 0.25f) -> -r
                                else -> 0f
                            }
                            val spec = if (target == 0f) {
                                spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                            } else {
                                spring<Float>(stiffness = Spring.StiffnessMedium)
                            }
                            if (target != 0f) done = true
                            animate(dragX, target, velocity, spec) { value, _ -> dragX = value }
                            when {
                                target > 0f -> onDismiss()
                                target < 0f -> onSnooze()
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_sunrise),
                    contentDescription = "Slide to dismiss or snooze",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/** Pixels per second; a flick this fast counts even from a shorter drag. */
private const val FLING = 1_500f
