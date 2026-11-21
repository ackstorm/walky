package com.ackstorm.walky

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpRetryException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpUploaderTest {
    private class Seen(val method: String, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String) = headers[name.lowercase()]
    }

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var file: File
    private val requests = CopyOnWriteArrayList<Seen>()
    private val otherRequests = CopyOnWriteArrayList<Seen>()
    private val url get() = "http://127.0.0.1:${server.address.port}/audio"
    private val otherUrl get() = "http://127.0.0.1:${server.address.port}/other"

    @Before fun setUp() {
        file = tmp.newFile("take.m4a").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/other") { record(it, otherRequests); reply(it, 200) }
        server.start()
    }

    @After fun tearDown() = server.stop(0)

    private fun record(exchange: HttpExchange, into: MutableList<Seen>) {
        val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
        into += Seen(exchange.requestMethod, headers, exchange.requestBody.readBytes())
    }

    private fun reply(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun serve(status: Int, location: String? = null, delayMs: Long = 0) {
        server.createContext("/audio") {
            record(it, requests)
            Thread.sleep(delayMs)
            if (location != null) it.responseHeaders.add("Location", location)
            reply(it, status)
        }
    }

    private fun post(settings: Settings) = HttpUploader().post(file, settings)

    @Test fun postsFileBytesWithContentTypeAndHeader() {
        serve(204)
        assertEquals(UploadResult.Delivered, post(Settings(url, "X-API-Key", "k1")))
        assertEquals(1, requests.size)
        assertEquals("POST", requests[0].method)
        assertEquals("audio/mp4", requests[0].header("Content-Type"))
        assertEquals("k1", requests[0].header("X-API-Key"))
        assertArrayEquals(file.readBytes(), requests[0].body)
    }

    @Test fun omitsHeaderWhenNotConfigured() {
        serve(200)
        assertEquals(UploadResult.Delivered, post(Settings(url)))
        assertNull(requests[0].header("X-API-Key"))
    }

    @Test fun serverErrorKeepsCode() {
        serve(500)
        assertEquals(UploadResult.Http(500), post(Settings(url)))
    }

    @Test fun unauthorizedKeepsCode() {
        serve(401)
        assertEquals(UploadResult.Http(401), post(Settings(url)))
        assertEquals(1, requests.size)
    }

    @Test fun redirectIsNotFollowed() {
        serve(302, location = otherUrl)
        assertEquals(UploadResult.Http(302), post(Settings(url, "X-API-Key", "k1")))
        assertEquals(1, requests.size)
        assertEquals(0, otherRequests.size)
    }

    @Test fun closedPortIsOffline() {
        val closed = url
        server.stop(0)
        assertEquals(UploadResult.Offline, post(Settings(closed)))
    }

    @Test fun silentServerIsUnconfirmed() {
        serve(204, delayMs = 1_000)
        assertEquals(UploadResult.Unconfirmed, HttpUploader(readTimeoutMs = 200).post(file, Settings(url)))
    }

    @Test fun classifyMapsTlsAndDns() {
        assertEquals(UploadResult.Tls, classify(SSLHandshakeException("bad cert"), connected = false))
        assertEquals(UploadResult.Offline, classify(UnknownHostException("h"), connected = false))
        assertEquals(UploadResult.Unconfirmed, classify(SocketTimeoutException(), connected = true))
        assertEquals(UploadResult.Http(307), classify(HttpRetryException("streamed body", 307), connected = true))
    }
}
