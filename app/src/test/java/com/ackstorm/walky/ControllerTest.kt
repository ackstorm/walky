package com.ackstorm.walky

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControllerTest {
    private class FakeRecorder : Recorder {
        override var onError: () -> Unit = {}
        var failStart = false
        var finalizes = true
        var starts = 0
        var stops = 0

        override fun start(file: File) {
            if (failStart) error("microphone busy")
            starts++
            file.writeBytes(byteArrayOf(1, 2, 3))
        }

        override fun stop(): Boolean {
            stops++
            return finalizes
        }

        override fun amplitude() = 0f
    }

    private class FakeUploader : Uploader {
        class Post(val bytes: ByteArray, val settings: Settings)

        val posts = mutableListOf<Post>()
        val results = ArrayDeque<UploadResult>()

        override fun post(file: File, settings: Settings): UploadResult {
            posts += Post(file.readBytes(), settings)
            return results.removeFirstOrNull() ?: UploadResult.Delivered
        }
    }

    @get:Rule val tmp = TemporaryFolder()
    private val recorder = FakeRecorder()
    private val uploader = FakeUploader()
    private var settings: Settings? = Settings("https://a.example/audio", "X-API-Key", "k1")
    private var clock = 0L
    private lateinit var dir: File
    private lateinit var takes: TakeStore

    @Before fun setUp() {
        dir = tmp.newFolder("take")
        takes = TakeStore(dir)
    }

    private fun TestScope.controller() =
        Controller(recorder, uploader, TakeStore(dir), { settings }, this, StandardTestDispatcher(testScheduler), { clock })

    private fun Controller.hold(ms: Long) {
        press()
        clock += ms
    }

    private fun TestScope.failedController(result: UploadResult = UploadResult.Http(500)): Controller {
        uploader.results += result
        val c = controller()
        c.hold(1000)
        c.release()
        advanceUntilIdle()
        assertEquals(UiState.Failed(result), c.state.value)
        return c
    }

    @Test fun noSettingsIsUnconfiguredAndPressIsIgnored() = runTest {
        settings = null
        val c = controller()
        assertEquals(UiState.Unconfigured, c.state.value)
        c.press()
        assertEquals(0, recorder.starts)
        settings = Settings("https://a.example/audio")
        c.settingsChanged()
        assertEquals(UiState.Idle(), c.state.value)
    }

    @Test fun releaseSendsOnce() = runTest {
        val c = controller()
        c.hold(1000)
        assertEquals(UiState.Recording(), c.state.value)
        c.release()
        assertEquals(UiState.Sending, c.state.value)
        advanceUntilIdle()
        assertEquals(UiState.Idle(Notice.SENT), c.state.value)
        assertEquals(1, uploader.posts.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), uploader.posts[0].bytes)
        assertEquals(settings, uploader.posts[0].settings)
        assertNull(takes.pending())
    }

    @Test fun cancelZoneDiscardsWithZeroPosts() = runTest {
        val c = controller()
        c.hold(1000)
        c.drag(64f)
        assertEquals(UiState.Recording(Zone.CANCEL), c.state.value)
        c.release()
        advanceUntilIdle()
        assertEquals(UiState.Idle(Notice.DISCARDED), c.state.value)
        assertEquals(0, uploader.posts.size)
        assertFalse(takes.recording.exists())
        assertEquals(1, recorder.stops)
    }

    @Test fun hysteresisBetween48And64() = runTest {
        val c = controller()
        c.hold(1000)
        c.drag(63f)
        assertEquals(UiState.Recording(Zone.SEND), c.state.value)
        c.drag(64f)
        assertEquals(UiState.Recording(Zone.CANCEL), c.state.value)
        c.drag(48f)
        assertEquals(UiState.Recording(Zone.CANCEL), c.state.value)
        c.drag(47.9f)
        assertEquals(UiState.Recording(Zone.SEND, returned = true), c.state.value)
    }

    @Test fun returningToSphereSendsTheWholeTake() = runTest {
        val c = controller()
        c.hold(1000)
        c.drag(80f)
        c.drag(0f)
        c.release()
        advanceUntilIdle()
        assertEquals(1, uploader.posts.size)
        assertEquals(UiState.Idle(Notice.SENT), c.state.value)
    }

    @Test fun tapIsTooShort() = runTest {
        val c = controller()
        c.hold(299)
        c.release()
        advanceUntilIdle()
        assertEquals(UiState.Idle(Notice.TOO_SHORT), c.state.value)
        assertEquals(0, uploader.posts.size)
        assertFalse(takes.recording.exists())
    }

    @Test fun exactlyMinimumIsSent() = runTest {
        val c = controller()
        c.hold(300)
        c.release()
        advanceUntilIdle()
        assertEquals(1, uploader.posts.size)
    }

    @Test fun unfinalizedTakeIsTooShort() = runTest {
        recorder.finalizes = false
        val c = controller()
        c.hold(1000)
        c.release()
        advanceUntilIdle()
        assertEquals(UiState.Idle(Notice.TOO_SHORT), c.state.value)
        assertEquals(0, uploader.posts.size)
    }

    @Test fun micFailure() = runTest {
        recorder.failStart = true
        val c = controller()
        c.press()
        assertEquals(UiState.Idle(Notice.MIC_FAILED), c.state.value)
        assertFalse(takes.recording.exists())
    }

    @Test fun interruptDiscardsWithZeroPosts() = runTest {
        val c = controller()
        c.hold(1000)
        c.interrupt()
        advanceUntilIdle()
        assertEquals(UiState.Idle(Notice.INTERRUPTED), c.state.value)
        assertEquals(1, recorder.stops)
        assertEquals(0, uploader.posts.size)
        assertFalse(takes.recording.exists())
        c.release()
        assertEquals(UiState.Idle(Notice.INTERRUPTED), c.state.value)
    }

    @Test fun recorderErrorInterrupts() = runTest {
        val c = controller()
        c.hold(1000)
        recorder.onError()
        assertEquals(UiState.Idle(Notice.INTERRUPTED), c.state.value)
    }

    @Test fun pressWhileSendingIsIgnored() = runTest {
        val c = controller()
        c.hold(1000)
        c.release()
        c.press()
        assertEquals(UiState.Sending, c.state.value)
        assertEquals(1, recorder.starts)
    }

    @Test fun serverErrorKeepsTakeAndRetrySendsSameBytes() = runTest {
        val c = failedController()
        c.retry()
        advanceUntilIdle()
        assertEquals(UiState.Idle(Notice.SENT), c.state.value)
        assertEquals(2, uploader.posts.size)
        assertArrayEquals(uploader.posts[0].bytes, uploader.posts[1].bytes)
        assertNull(takes.pending())
    }

    @Test fun doubleRetryPostsOnce() = runTest {
        val c = failedController()
        c.retry()
        c.retry()
        advanceUntilIdle()
        assertEquals(2, uploader.posts.size)
    }

    @Test fun pressWhileFailedIsIgnored() = runTest {
        val c = failedController()
        c.press()
        assertEquals(1, recorder.starts)
    }

    @Test fun discardFromFailedDeletesTake() = runTest {
        val c = failedController()
        c.discard()
        assertEquals(UiState.Idle(Notice.DISCARDED), c.state.value)
        assertNull(takes.pending())
    }

    @Test fun pendingTakeSurvivesRestart() = runTest {
        failedController()
        val reopened = controller()
        assertEquals(UiState.Failed(null), reopened.state.value)
        reopened.retry()
        advanceUntilIdle()
        assertEquals(UiState.Idle(Notice.SENT), reopened.state.value)
        assertArrayEquals(uploader.posts[0].bytes, uploader.posts[1].bytes)
    }

    @Test fun changedDestinationIsShownAndUsed() = runTest {
        val c = failedController()
        settings = Settings("https://b.example/x?t=1")
        c.settingsChanged()
        assertEquals(UiState.Failed(UploadResult.Http(500), "b.example"), c.state.value)
        c.retry()
        advanceUntilIdle()
        assertEquals("https://b.example/x?t=1", uploader.posts[1].settings.url)
    }

    @Test fun leftoverRecordingIsDroppedAtStartup() = runTest {
        takes.recording.writeBytes(byteArrayOf(9))
        val c = controller()
        assertFalse(takes.recording.exists())
        assertEquals(UiState.Idle(), c.state.value)
    }
}
