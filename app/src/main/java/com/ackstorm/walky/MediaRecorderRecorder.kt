package com.ackstorm.walky

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Mono AAC-LC in an MPEG-4 container. */
class MediaRecorderRecorder(private val context: Context) : Recorder {
    override var onError: () -> Unit = {}
    private var recorder: MediaRecorder? = null

    override fun start(file: File) {
        val r = MediaRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(file)
            r.setOnErrorListener { _, _, _ -> onError() }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // MediaRecorder.stop() throws when no audio frame was written.
            false
        } finally {
            r.release()
        }
    }

    override fun amplitude(): Float = (recorder?.maxAmplitude ?: 0) / 32767f
}
