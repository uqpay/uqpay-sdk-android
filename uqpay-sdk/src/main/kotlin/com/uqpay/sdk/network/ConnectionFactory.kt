package com.uqpay.sdk.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Opens and configures connections. Injected rather than called statically, so tests can
 * substitute a fake without a live socket.
 */
internal fun interface ConnectionFactory {
    @Throws(IOException::class)
    fun open(request: UQPayRequest): UQPayConnection
}

/**
 * Default factory over [HttpsURLConnection].
 *
 * Raw `HttpsURLConnection` is deliberate. A payment SDK is embedded in arbitrary
 * merchant apps, and dragging OkHttp or Retrofit in means version conflicts with the
 * host, a larger APK, and a wider attack surface. Both stripe-android and
 * airwallex-payment-android made the same call.
 */
internal class DefaultConnectionFactory(
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 30_000,
) : ConnectionFactory {

    override fun open(request: UQPayRequest): UQPayConnection {
        val url = URL(request.url)

        // HTTPS is enforced by type, not by convention: a cleartext URL cannot produce
        // an HttpsURLConnection, so it fails here rather than silently sending card
        // data in the clear.
        val connection = url.openConnection() as? HttpsURLConnection
            ?: throw IOException("UQPAY endpoints must be HTTPS.")

        return connection.runCatching {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            requestMethod = request.method.name
            useCaches = false
            instanceFollowRedirects = false

            setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            request.idempotencyKey?.let { setRequestProperty("x-idempotency-key", it) }

            if (request.method == HttpMethod.POST) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            UQPayConnection(this)
        }.getOrElse { t ->
            connection.disconnect()
            throw if (t is IOException) t else IOException("Could not open a connection.", t)
        }
    }
}

/**
 * Closeable wrapper that reads a connection into a [UQPayResponse].
 *
 * Reads `errorStream` for non-2xx: `inputStream` throws there, and the error body is the
 * part that tells us *why* a payment failed.
 */
internal class UQPayConnection(private val connection: HttpURLConnection) : AutoCloseable {

    @Throws(IOException::class)
    fun execute(body: String?): UQPayResponse {
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        val status = connection.responseCode
        val payload = runCatching {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()

        return UQPayResponse(
            statusCode = status,
            body = payload,
            traceId = connection.getHeaderField("x-request-id")
                ?: connection.getHeaderField("request-id")
                ?: connection.getHeaderField("x-b3-traceid"),
            retryAfterSeconds = connection.getHeaderField("retry-after")?.toLongOrNull(),
        )
    }

    override fun close() {
        runCatching { connection.disconnect() }
    }
}
