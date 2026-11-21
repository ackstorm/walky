package com.ackstorm.walky

import java.io.File
import java.io.IOException
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException

sealed interface UploadResult {
    data object Delivered : UploadResult

    /** Any status outside 2xx, redirects included. */
    data class Http(val code: Int) : UploadResult

    /** Failed before a connection existed: nothing was sent. */
    data object Offline : UploadResult

    /** Failed after connecting: the server may have the audio. */
    data object Unconfirmed : UploadResult

    data object Tls : UploadResult
}

fun interface Uploader {
    /** Blocking. Sends exactly one request. */
    fun post(file: File, settings: Settings): UploadResult
}

class HttpUploader(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : Uploader {
    override fun post(file: File, settings: Settings): UploadResult {
        val conn = URL(settings.url).openConnection() as HttpURLConnection
        var connected = false
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setFixedLengthStreamingMode(file.length())
            if (settings.headerName.isNotEmpty()) conn.setRequestProperty(settings.headerName, settings.headerValue)
            conn.setRequestProperty("Content-Type", "audio/mp4")
            conn.connect()
            connected = true
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val code = conn.responseCode
            if (code in 200..299) UploadResult.Delivered else UploadResult.Http(code)
        } catch (e: IOException) {
            classify(e, connected)
        } finally {
            conn.disconnect()
        }
    }
}

internal fun classify(e: IOException, connected: Boolean): UploadResult = when {
    // Thrown instead of a status when the server asks to resend a streamed body (auth challenge, 307).
    e is HttpRetryException -> UploadResult.Http(e.responseCode())
    e is SSLException -> UploadResult.Tls
    !connected -> UploadResult.Offline
    else -> UploadResult.Unconfirmed
}
