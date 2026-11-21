package com.ackstorm.walky

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakeStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private lateinit var store: TakeStore

    @Before fun setUp() {
        dir = tmp.newFolder("take")
        store = TakeStore(dir)
    }

    @Test fun emptyDirHasNoPending() = assertNull(store.pending())

    @Test fun keepMovesRecordingAndRemembersUrl() {
        store.recording.writeBytes(byteArrayOf(1, 2, 3))
        val take = store.keep("https://a.example/x")
        assertFalse(store.recording.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), take.file.readBytes())
        assertEquals(PendingTake(take.file, "https://a.example/x"), TakeStore(dir).pending())
    }

    @Test fun discardPendingRemovesBothFiles() {
        store.recording.writeBytes(byteArrayOf(1))
        store.keep("https://a.example/x")
        store.discardPending()
        assertNull(store.pending())
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun dropLeftoversDeletesUnfinishedRecordingOnly() {
        store.recording.writeBytes(byteArrayOf(1))
        store.keep("https://a.example/x")
        store.recording.writeBytes(byteArrayOf(2))
        store.dropLeftovers()
        assertFalse(store.recording.exists())
        assertNotNull(store.pending())
    }

    @Test fun audioWithoutUrlFileIsNotPending() {
        File(dir, "take.m4a").writeBytes(byteArrayOf(1))
        assertNull(store.pending())
        store.dropLeftovers()
        assertEquals(0, dir.list()!!.size)
    }
}
