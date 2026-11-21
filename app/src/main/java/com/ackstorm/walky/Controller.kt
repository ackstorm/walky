package com.ackstorm.walky

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downward travel from the touch-down point that arms the cancellation. */
const val CANCEL_AT_DP = 64f

/** Once armed, the thumb has to come back above this to send again. */
const val REARM_BELOW_DP = 48f

/** Shorter holds are accidental taps and are never sent. */
const val MIN_TAKE_MS = 300L

enum class Zone { SEND, CANCEL }

enum class Notice { SENT, DISCARDED, TOO_SHORT, MIC_FAILED, INTERRUPTED }

sealed interface UiState {
    data object Unconfigured : UiState
    data class Idle(val notice: Notice? = null) : UiState
    data class Recording(val zone: Zone = Zone.SEND, val returned: Boolean = false) : UiState
    data object Sending : UiState

    /** A finished take that was not delivered. [result] is null when it was found at startup. */
    data class Failed(val result: UploadResult?, val newDestination: String? = null) : UiState
}

interface Recorder {
    var onError: () -> Unit

    /** Throws if the microphone cannot be opened. */
    fun start(file: File)

    /** Releases the microphone. True if the file was finalized. */
    fun stop(): Boolean

    /** 0..1, 0 when idle. */
    fun amplitude(): Float
}

/** Every rule of the record-and-send gesture. Call it from one thread. */
class Controller(
    private val recorder: Recorder,
    private val uploader: Uploader,
    private val takes: TakeStore,
    private val loadSettings: () -> Settings?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow<UiState>(UiState.Idle())
    val state: StateFlow<UiState> = _state
    private var startedAt = 0L

    init {
        recorder.onError = ::interrupt
        takes.dropLeftovers()
        _state.value = takes.pending()?.let { failed(null, it) } ?: resting(null)
    }

    fun press() {
        if (_state.value !is UiState.Idle) return
        try {
            recorder.start(takes.recording)
        } catch (e: Exception) {
            takes.discardRecording()
            _state.value = UiState.Idle(Notice.MIC_FAILED)
            return
        }
        startedAt = now()
        _state.value = UiState.Recording()
    }

    fun drag(downDp: Float) {
        val recording = _state.value as? UiState.Recording ?: return
        if (recording.zone == Zone.SEND && downDp >= CANCEL_AT_DP) {
            _state.value = recording.copy(zone = Zone.CANCEL)
        } else if (recording.zone == Zone.CANCEL && downDp < REARM_BELOW_DP) {
            _state.value = UiState.Recording(Zone.SEND, returned = true)
        }
    }

    fun release() {
        val recording = _state.value as? UiState.Recording ?: return
        val finalized = recorder.stop()
        if (recording.zone == Zone.CANCEL) {
            takes.discardRecording()
            _state.value = UiState.Idle(Notice.DISCARDED)
            return
        }
        val settings = loadSettings()
        val valid = finalized && takes.recording.length() > 0 && now() - startedAt >= MIN_TAKE_MS
        if (!valid || settings == null) {
            takes.discardRecording()
            _state.value = UiState.Idle(Notice.TOO_SHORT)
            return
        }
        takes.keep(settings.url)
        send()
    }

    /** The system took the gesture or the microphone away: nothing is sent. */
    fun interrupt() {
        if (_state.value !is UiState.Recording) return
        recorder.stop()
        takes.discardRecording()
        _state.value = UiState.Idle(Notice.INTERRUPTED)
    }

    fun retry() {
        if (_state.value is UiState.Failed) send()
    }

    fun discard() {
        if (_state.value !is UiState.Failed) return
        takes.discardPending()
        _state.value = UiState.Idle(Notice.DISCARDED)
    }

    fun settingsChanged() {
        when (val current = _state.value) {
            is UiState.Unconfigured -> _state.value = resting(null)
            is UiState.Failed -> takes.pending()?.let { _state.value = failed(current.result, it) }
            else -> Unit
        }
    }

    fun amplitude(): Float = recorder.amplitude()

    private fun send() {
        val take = takes.pending() ?: return
        val settings = loadSettings() ?: return
        _state.value = UiState.Sending
        scope.launch {
            val result = withContext(io) { uploader.post(take.file, settings) }
            if (result == UploadResult.Delivered) {
                takes.discardPending()
                _state.value = UiState.Idle(Notice.SENT)
            } else {
                _state.value = failed(result, take)
            }
        }
    }

    private fun resting(notice: Notice?): UiState =
        if (loadSettings() == null) UiState.Unconfigured else UiState.Idle(notice)

    private fun failed(result: UploadResult?, take: PendingTake): UiState.Failed {
        val current = loadSettings()?.url
        val changed = current != null && current != take.url
        return UiState.Failed(result, if (changed) destinationLabel(current!!) else null)
    }
}
