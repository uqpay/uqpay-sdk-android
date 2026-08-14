package com.uqpay.sdk.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Test doubles for the connection layer.
 *
 * [ConnectionFactory] hands back a concrete [UQPayConnection] rather than an interface,
 * so a fake has to reach one level lower and stand in for the [HttpURLConnection]
 * itself. That is the only way to exercise [DefaultUQPayNetworkClient] without a socket.
 */
internal class FakeReply(
    val status: Int = 200,
    val body: String? = "{}",
    val headers: Map<String, String> = emptyMap(),
    val throwable: Throwable? = null,
) {
    companion object {
        fun failing(t: Throwable) = FakeReply(throwable = t)

        fun retryAfter(status: Int, seconds: String) =
            FakeReply(status = status, headers = mapOf("retry-after" to seconds))
    }
}

/**
 * Replays a scripted list of replies, repeating the last one once the script runs out,
 * and records every request it was asked to open.
 */
internal class FakeConnectionFactory(private vararg val script: FakeReply) : ConnectionFactory {

    val requests: MutableList<UQPayRequest> = mutableListOf()
    val bodiesWritten: MutableList<String?> = mutableListOf()

    val callCount: Int get() = requests.size

    override fun open(request: UQPayRequest): UQPayConnection {
        val reply = script[minOf(requests.size, script.size - 1)]
        requests += request
        reply.throwable?.let { throw it }
        val connection = FakeHttpConnection(reply, bodiesWritten)
        return UQPayConnection(connection)
    }
}

private class FakeHttpConnection(
    private val reply: FakeReply,
    private val bodiesWritten: MutableList<String?>,
) : HttpURLConnection(URL("https://api-sandbox.uqpaytech.com/api/v2/payment_intents/PI_1")) {

    private val written = object : ByteArrayOutputStream() {
        override fun close() {
            bodiesWritten += toString(Charsets.UTF_8.name())
            super.close()
        }
    }

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = reply.status

    override fun getOutputStream(): OutputStream = written

    override fun getInputStream(): InputStream =
        ByteArrayInputStream(reply.body.orEmpty().toByteArray(Charsets.UTF_8))

    override fun getErrorStream(): InputStream? =
        reply.body?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) }

    override fun getHeaderField(name: String): String? = reply.headers[name.lowercase()]
}
