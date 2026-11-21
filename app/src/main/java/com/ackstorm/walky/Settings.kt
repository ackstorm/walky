package com.ackstorm.walky

import java.net.URI
import java.net.URISyntaxException

data class Settings(val url: String, val headerName: String = "", val headerValue: String = "")

enum class SettingsError {
    URL_INVALID,
    URL_NOT_HTTPS,
    HEADER_INCOMPLETE,
    HEADER_NAME_INVALID,
    HEADER_NAME_RESERVED,
    HEADER_VALUE_INVALID,
}

sealed interface SettingsResult {
    data class Ok(val settings: Settings) : SettingsResult
    data class Bad(val error: SettingsError) : SettingsResult
}

// The app sets these itself; the user header must not replace them.
private val RESERVED_HEADERS = setOf("content-type", "content-length", "host", "transfer-encoding", "connection")

// RFC 9110 token.
private val HEADER_NAME = Regex("""^[!#$%&'*+.^_`|~0-9A-Za-z-]+$""")

fun parseSettings(url: String, headerName: String, headerValue: String): SettingsResult {
    val cleanUrl = url.trim()
    val name = headerName.trim()
    val value = headerValue.trim()

    val uri = try {
        URI(cleanUrl)
    } catch (e: URISyntaxException) {
        return SettingsResult.Bad(SettingsError.URL_INVALID)
    }
    if (uri.host.isNullOrEmpty()) return SettingsResult.Bad(SettingsError.URL_INVALID)
    if (!uri.scheme.equals("https", ignoreCase = true)) return SettingsResult.Bad(SettingsError.URL_NOT_HTTPS)

    if (name.isEmpty() != value.isEmpty()) return SettingsResult.Bad(SettingsError.HEADER_INCOMPLETE)
    if (name.isNotEmpty()) {
        if (!HEADER_NAME.matches(name)) return SettingsResult.Bad(SettingsError.HEADER_NAME_INVALID)
        if (name.lowercase() in RESERVED_HEADERS) return SettingsResult.Bad(SettingsError.HEADER_NAME_RESERVED)
        if (value.any { (it < ' ' && it != '\t') || it == '\u007f' }) {
            return SettingsResult.Bad(SettingsError.HEADER_VALUE_INVALID)
        }
    }
    return SettingsResult.Ok(Settings(cleanUrl, name, value))
}

/** Host only: the path and query of a destination can carry a token. */
fun destinationLabel(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
