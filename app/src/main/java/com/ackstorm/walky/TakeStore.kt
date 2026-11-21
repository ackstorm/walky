package com.ackstorm.walky

import java.io.File

data class PendingTake(val file: File, val url: String)

/** The file being recorded and the single finished take that waits for delivery. */
class TakeStore(dir: File) {
    val recording = File(dir, "recording.m4a")
    private val take = File(dir, "take.m4a")
    private val takeUrl = File(dir, "take.url")

    init {
        dir.mkdirs()
    }

    fun pending(): PendingTake? =
        if (take.exists() && takeUrl.exists()) PendingTake(take, takeUrl.readText()) else null

    /** The URL is written last: audio without it is not a pending take. */
    fun keep(url: String): PendingTake {
        check(recording.renameTo(take)) { "cannot keep the recording" }
        takeUrl.writeText(url)
        return PendingTake(take, url)
    }

    fun discardRecording() {
        recording.delete()
    }

    fun discardPending() {
        takeUrl.delete()
        take.delete()
    }

    /** Removes what a killed process left half done. */
    fun dropLeftovers() {
        recording.delete()
        if (!takeUrl.exists()) take.delete()
    }
}
