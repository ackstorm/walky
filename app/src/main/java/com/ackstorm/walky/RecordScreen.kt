package com.ackstorm.walky

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
            levels.add(controller.amplitude())
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
                detail = hint
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
        TextButton(
            onClick = onOpenSettings,
            enabled = state !is UiState.Recording && state !is UiState.Sending,
            modifier = Modifier.align(Alignment.End).padding(8.dp),
        ) {
            Text(stringResource(R.string.settings), color = colors.onSurfaceVariant)
        }

        val waveColor = if (cancelArmed) colors.error else colors.primary
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp)) {
            val slot = size.width / WAVE_BARS
            val bar = slot * 0.5f
            levels.forEachIndexed { i, level ->
                val h = (size.height * 0.8f * level).coerceAtLeast(bar)
                drawRoundRect(
                    color = waveColor,
                    topLeft = Offset((WAVE_BARS - levels.size + i) * slot, (size.height - h) / 2),
                    size = Size(bar, h),
                    cornerRadius = CornerRadius(bar / 2),
                )
            }
        }

        Box(
            Modifier
                .size(112.dp)
                .scale(if (recording != null) 0.94f else 1f)
                .clip(CircleShape)
                .background(
                    when {
                        cancelArmed -> colors.errorContainer
                        state is UiState.Idle || state is UiState.Recording -> colors.primaryContainer
                        else -> colors.surfaceVariant
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = hint
                }
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
                },
        )

        // Room below the sphere so a 64 dp slide stays clear of the system gesture edge.
        Column(
            Modifier.height(176.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.titleMedium,
                color = if (alert) colors.error else colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
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
                        if (state.result.needsSettings()) {
                            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
                        }
                    }
                    state is UiState.Idle && micDenied ->
                        OutlinedButton(onClick = { context.openAppSettings() }) {
                            Text(stringResource(R.string.open_system_settings))
                        }
                    else -> Spacer(Modifier)
                }
            }
        }
    }
}

private fun Notice.text(): Int = when (this) {
    Notice.SENT -> R.string.sent
    Notice.DISCARDED -> R.string.discarded
    Notice.TOO_SHORT -> R.string.too_short
    Notice.MIC_FAILED -> R.string.mic_failed
    Notice.INTERRUPTED -> R.string.interrupted
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

/** A wrong URL (redirect) or a rejected header (401, 403) is fixed in settings. */
private fun UploadResult?.needsSettings(): Boolean =
    this is UploadResult.Http && (code in 300..399 || code == 401 || code == 403)

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
