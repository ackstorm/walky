package com.ackstorm.walky

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.log10
import kotlinx.coroutines.delay

private const val WAVE_BARS = 48
private const val WAVE_SAMPLE_MS = 50L

@Composable
fun RecordScreen(state: UiState, controller: Controller, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme

    var micDenied by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
    }
    LifecycleResumeEffect(Unit) {
        if (context.hasMic()) micDenied = false
        onPauseOrDispose {}
    }

    val recording = state as? UiState.Recording
    val cancelArmed = recording?.zone == Zone.CANCEL
    LaunchedEffect(cancelArmed) {
        if (cancelArmed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(recording != null) {
        levels.clear()
        if (recording == null) return@LaunchedEffect
        while (true) {
            levels.add(displayLevel(controller.amplitude()))
            if (levels.size > WAVE_BARS) levels.removeAt(0)
            delay(WAVE_SAMPLE_MS)
        }
    }

    val hint = stringResource(R.string.hold_to_talk)
    var message = hint
    var detail: String? = null
    when (state) {
        UiState.Unconfigured -> message = stringResource(R.string.unconfigured)
        is UiState.Idle -> when {
            micDenied -> detail = stringResource(R.string.mic_denied)
            state.notice != null -> {
                message = stringResource(state.notice.text())
                detail = state.notice.reason()?.let { stringResource(it) } ?: hint
            }
        }
        is UiState.Recording -> message = stringResource(
            when {
                cancelArmed -> R.string.release_to_discard
                state.returned -> R.string.release_to_send
                else -> R.string.slide_to_cancel
            },
        )
        UiState.Sending -> message = stringResource(R.string.sending)
        is UiState.Failed -> {
            message = failureText(state.result)
            detail = state.newDestination?.let { stringResource(R.string.new_destination, it) }
        }
    }
    val alert = cancelArmed || state is UiState.Failed

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onOpenSettings,
            enabled = state !is UiState.Recording && state !is UiState.Sending,
            modifier = Modifier.align(Alignment.End).padding(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.settings),
                tint = colors.onSurfaceVariant,
            )
        }

        val waveColor = if (cancelArmed) colors.error else colors.primary
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp)) {
            val slot = size.width / WAVE_BARS
            val bar = slot * 0.5f
            levels.forEachIndexed { i, level ->
                val h = (size.height * 0.8f * level).coerceAtLeast(bar)
                val age = (WAVE_BARS - levels.size + i) / WAVE_BARS.toFloat()
                drawRoundRect(
                    color = waveColor.copy(alpha = 0.25f + 0.75f * age),
                    topLeft = Offset((WAVE_BARS - levels.size + i) * slot, (size.height - h) / 2),
                    size = Size(bar, h),
                    cornerRadius = CornerRadius(bar / 2),
                )
            }
        }

        Box(Modifier.size(width = 200.dp, height = 160.dp), contentAlignment = Alignment.Center) {
            val pulse = rememberInfiniteTransition()
            val breath by pulse.animateFloat(1f, 1.04f, infiniteRepeatable(tween(1700), RepeatMode.Reverse))
            val turn by pulse.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))

            // Two halos that open on press and swell with the voice.
            val level = if (recording != null) levels.lastOrNull() ?: 0f else 0f
            val listening by animateFloatAsState(if (recording != null) 1f else 0f)
            val swell by animateFloatAsState(level, tween(120))
            val haloColor = if (cancelArmed) colors.errorContainer else colors.primaryContainer
            for ((reach, strength) in listOf(0.34f to 0.25f, 0.16f to 0.55f)) {
                Box(
                    Modifier
                        .size(112.dp)
                        .graphicsLayer {
                            val scale = 1f + listening * reach * (1f + 1.4f * swell)
                            scaleX = scale
                            scaleY = scale
                            alpha = listening * strength
                        }
                        .background(haloColor, CircleShape),
                )
            }

            // Ring: spins while sending, bursts outward once delivered.
            val burst = remember { Animatable(1f) }
            LaunchedEffect(state) {
                if (state == UiState.Idle(Notice.SENT)) {
                    burst.snapTo(0f)
                    burst.animateTo(1f, tween(700))
                }
            }
            val ringColor = colors.primary
            Canvas(Modifier.size(138.dp)) {
                val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                if (state is UiState.Sending) {
                    drawArc(ringColor, startAngle = turn, sweepAngle = 110f, useCenter = false, style = stroke)
                } else if (burst.value < 1f) {
                    drawCircle(
                        ringColor.copy(alpha = 1f - burst.value),
                        radius = size.minDimension / 2 * (1f + 0.4f * burst.value),
                        style = stroke,
                    )
                }
            }

            val sphereScale by animateFloatAsState(
                when {
                    recording != null -> 0.94f
                    state is UiState.Idle -> breath
                    else -> 1f
                },
            )
            val sphereColor by animateColorAsState(
                when {
                    cancelArmed -> colors.errorContainer
                    state is UiState.Sending -> colors.primaryContainer.copy(alpha = 0.6f)
                    state is UiState.Idle || state is UiState.Recording -> colors.primaryContainer
                    else -> colors.surfaceVariant
                },
            )
            val lift by animateDpAsState(if (state is UiState.Idle) 10.dp else 0.dp)
            Box(
                Modifier
                    .size(112.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = hint
                    }
                    // Before the scale, so the slide is measured in real dp.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!context.hasMic()) {
                                // The user presses again once the permission is granted.
                                askMic.launch(Manifest.permission.RECORD_AUDIO)
                                return@awaitEachGesture
                            }
                            controller.press()
                            var released = false
                            try {
                                while (true) {
                                    // Only the finger that started the recording controls it.
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: continue
                                    if (!change.pressed) {
                                        // Compose reports a system cancel as an up that is already consumed.
                                        if (!change.isConsumed) {
                                            released = true
                                            controller.release()
                                        }
                                        break
                                    }
                                    controller.drag((change.position.y - down.position.y).toDp().value)
                                }
                            } finally {
                                // The system took the gesture away.
                                if (!released) controller.interrupt()
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = sphereScale
                        scaleY = sphereScale
                    }
                    .shadow(lift, CircleShape, ambientColor = colors.primary, spotColor = colors.primary)
                    .clip(CircleShape)
                    .drawBehind {
                        // Lit from the upper left, so the disc reads as a sphere.
                        drawRect(
                            Brush.radialGradient(
                                listOf(lerp(sphereColor, Color.White, 0.4f), sphereColor),
                                center = Offset(size.width * 0.34f, size.height * 0.28f),
                                radius = size.width * 0.7f,
                            ),
                        )
                    },
            )

            // Where the slide ends: shown while recording, lit once the cancellation is armed.
            val targetAlpha by animateFloatAsState(if (recording != null) 1f else 0f)
            val targetScale by animateFloatAsState(if (cancelArmed) 1.2f else 1f)
            val targetColor = if (cancelArmed) colors.error else colors.outline
            val targetFill = if (cancelArmed) colors.errorContainer else colors.surface
            Canvas(
                Modifier
                    .offset(y = 86.dp)
                    .size(36.dp)
                    .graphicsLayer {
                        alpha = targetAlpha
                        scaleX = targetScale
                        scaleY = targetScale
                    },
            ) {
                drawCircle(targetFill)
                drawCircle(targetColor, style = Stroke(1.5.dp.toPx()))
                val arm = size.minDimension * 0.16f
                val cross = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(targetColor, center - Offset(arm, arm), center + Offset(arm, arm), cross.width, cross.cap)
                drawLine(targetColor, center + Offset(-arm, arm), center + Offset(arm, -arm), cross.width, cross.cap)
            }
        }

        // Room below the sphere so a 64 dp slide stays clear of the system gesture edge.
        Column(
            Modifier.height(196.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Crossfade(message) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (alert) colors.error else colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state is UiState.Unconfigured ->
                        Button(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
                    state is UiState.Failed -> {
                        Button(onClick = controller::retry) { Text(stringResource(R.string.retry)) }
                        OutlinedButton(onClick = controller::discard) { Text(stringResource(R.string.discard)) }
                    }
                    state is UiState.Idle && micDenied ->
                        OutlinedButton(onClick = { context.openAppSettings() }) {
                            Text(stringResource(R.string.open_system_settings))
                        }
                }
            }
        }
    }
}

/** Maps a linear microphone level onto 0..1 over a 45 dB range, the way loudness is heard. */
private fun displayLevel(linear: Float): Float =
    if (linear <= 0f) 0f else ((20f * log10(linear) + 45f) / 45f).coerceIn(0f, 1f)

private fun Notice.text(): Int = when (this) {
    Notice.SENT -> R.string.sent
    Notice.DISCARDED, Notice.TOO_SHORT, Notice.SILENT -> R.string.discarded
    Notice.MIC_FAILED -> R.string.mic_failed
    Notice.INTERRUPTED -> R.string.interrupted
}

/** Why a take was discarded without the user asking for it. */
private fun Notice.reason(): Int? = when (this) {
    Notice.TOO_SHORT -> R.string.too_short
    Notice.SILENT -> R.string.nothing_heard
    else -> null
}

@Composable
private fun failureText(result: UploadResult?): String = when (result) {
    is UploadResult.Http ->
        if (result.code in 300..399) stringResource(R.string.redirected, result.code)
        else stringResource(R.string.http_error, result.code)
    UploadResult.Offline -> stringResource(R.string.offline)
    UploadResult.Unconfirmed -> stringResource(R.string.unconfirmed)
    UploadResult.Tls -> stringResource(R.string.tls_failed)
    UploadResult.Delivered, null -> stringResource(R.string.pending)
}

private fun Context.hasMic(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}
