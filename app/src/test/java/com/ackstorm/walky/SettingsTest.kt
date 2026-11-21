package com.ackstorm.walky

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    private fun ok(u: String, n: String = "", v: String = "") = (parseSettings(u, n, v) as SettingsResult.Ok).settings
    private fun bad(u: String, n: String = "", v: String = "") = (parseSettings(u, n, v) as SettingsResult.Bad).error

    @Test fun urlOnly() = assertEquals(Settings("https://example.com/audio"), ok("https://example.com/audio"))

    @Test fun trimsPastedInput() = assertEquals(
        Settings("https://example.com/a", "X-API-Key", "k1"),
        ok(" https://example.com/a\n", " X-API-Key ", "k1\n"),
    )

    @Test fun keepsPortPathQuery() =
        assertEquals("https://h.example:8443/a?b=c", ok("https://h.example:8443/a?b=c").url)

    @Test fun rejectsHttp() = assertEquals(SettingsError.URL_NOT_HTTPS, bad("http://example.com"))

    @Test fun rejectsNoHost() = assertEquals(SettingsError.URL_INVALID, bad("https:///audio"))

    @Test fun rejectsGarbage() = assertEquals(SettingsError.URL_INVALID, bad("not a url"))

    @Test fun rejectsEmptyUrl() = assertEquals(SettingsError.URL_INVALID, bad(""))

    @Test fun rejectsHalfHeader() {
        assertEquals(SettingsError.HEADER_INCOMPLETE, bad("https://e.com", "X-API-Key", ""))
        assertEquals(SettingsError.HEADER_INCOMPLETE, bad("https://e.com", "", "k1"))
    }

    @Test fun rejectsReservedNames() =
        listOf("content-type", "Content-Length", "HOST", "Transfer-Encoding", "connection").forEach {
            assertEquals(SettingsError.HEADER_NAME_RESERVED, bad("https://e.com", it, "v"))
        }

    @Test fun rejectsBadName() = assertEquals(SettingsError.HEADER_NAME_INVALID, bad("https://e.com", "X Key:", "v"))

    @Test fun rejectsControlCharInValue() =
        assertEquals(SettingsError.HEADER_VALUE_INVALID, bad("https://e.com", "X-K", "a\r\nb"))

    @Test fun labelHidesPathAndQuery() =
        assertEquals("h.example", destinationLabel("https://h.example:8443/x?token=abc"))
}
