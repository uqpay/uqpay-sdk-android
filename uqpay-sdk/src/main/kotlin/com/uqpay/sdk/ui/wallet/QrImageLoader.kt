package com.uqpay.sdk.ui.wallet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The connection seam. Injected rather than opened statically so a test can assert on how
 * the connection was configured — which is the only way `useCaches = false` can be proved,
 * since a cache hit is invisible from the outside.
 */
internal fun interface QrConnectionFactory {
    @Throws(IOException::class)
    fun open(url: URL): QrConnection
}

/**
 * The subset of `HttpsURLConnection` the loader uses, as an interface so it can be faked.
 *
 * `useCaches` is a read/write property rather than a constructor argument on purpose: the
 * test that matters asserts on the value the loader *set*.
 */
internal interface QrConnection : AutoCloseable {
    var useCaches: Boolean
    var connectTimeoutMillis: Int
    var readTimeoutMillis: Int

    @Throws(IOException::class)
    fun responseCode(): Int

    @Throws(IOException::class)
    fun body(): InputStream?
}

/** How a QR image fetch ended. */
internal sealed class QrImageResult {

    /** The bytes arrived and decoded. */
    data class Loaded(val bitmap: Bitmap) : QrImageResult()

    /**
     * The QR could not be shown. **Always retryable** from the customer's point of view: the
     * payment attempt is untouched by a failed image download, so offering "Retry" costs
     * nothing and refusing to offer it strands a customer on a blank square.
     *
     * @property reason a short, non-identifying description for diagnostics. Never the URL —
     *   the QR URL embeds the EMVCo payment payload as a query parameter, which is payment
     *   data and must not reach a log.
     */
    data class Failed(val reason: String) : QrImageResult()
}

/**
 * Downloads the QR image a wallet confirm issued and decodes it to a [Bitmap].
 *
 * ### No image library, deliberately
 *
 * Coil and Glide both solve this in one line and both are the wrong trade for a payment SDK:
 * a merchant app that already ships a different version of either gets a dependency conflict
 * out of a screen that downloads one 1 KB PNG. The whole job here is a `GET`, a byte array,
 * and `BitmapFactory` — the same reasoning that keeps the network layer on raw
 * `HttpsURLConnection`.
 *
 * ### `useCaches = false` is load-bearing, and the gateway proves it
 *
 * The QR URL identifies **one payment attempt**: it is
 * `…/api/v2/payment/qr?data=<EMVCo payload>`, and the payload carries a per-attempt nonce.
 * Verified live on 2026-08-18, that endpoint answers with `cache-control: public,
 * max-age=300`. So an HTTP cache — and a merchant app is entitled to install one process-wide
 * with `HttpResponseCache.install`, which `HttpsURLConnection` silently honours — is
 * explicitly invited by the server to hold that image for five minutes.
 *
 * A cached QR is a QR that may already have expired or already have been superseded. Showing
 * one asks the customer to scan a code that will be refused, or worse, to pay into an attempt
 * that is no longer the one being watched. `setUseCaches(false)` is one line and it is the
 * difference; it is asserted by a test for exactly that reason.
 *
 * ### HTTPS only
 *
 * The cast to `HttpsURLConnection` is the enforcement, mirroring the network layer: an
 * `http://` URL cannot produce one, so a downgraded QR URL fails here rather than being
 * fetched in the clear where it could be swapped for an attacker's code.
 *
 * @param maxBytes a hard ceiling on what will be read into memory. The gateway's QR is
 *   around 1 KB; a megabyte is four orders of magnitude of headroom and still stops a
 *   hostile or misrouted response from being decoded into an out-of-memory crash on a
 *   low-end device.
 */
internal class QrImageLoader(
    private val connectionFactory: QrConnectionFactory = DefaultQrConnectionFactory(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val decode: (ByteArray) -> Bitmap? = { BitmapFactory.decodeByteArray(it, 0, it.size) },
) {

    /**
     * Fetches and decodes [url]. Never throws: every failure becomes [QrImageResult.Failed],
     * because a QR that will not load is a screen problem the customer can retry, never a
     * reason to fail a payment that may already be in flight.
     */
    suspend fun load(url: String): QrImageResult = withContext(ioDispatcher) {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: return@withContext QrImageResult.Failed("malformed url")
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            return@withContext QrImageResult.Failed("non-https url")
        }

        runCatching {
            connectionFactory.open(parsed).use { connection ->
                // The single most important line in this file. See the class KDoc.
                connection.useCaches = false
                connection.connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                connection.readTimeoutMillis = READ_TIMEOUT_MILLIS

                val status = connection.responseCode()
                if (status !in 200..299) return@use QrImageResult.Failed("http $status")

                val bytes = connection.body()?.use { readBounded(it) }
                    ?: return@use QrImageResult.Failed("empty body")
                if (bytes.isEmpty()) return@use QrImageResult.Failed("empty body")

                decode(bytes)?.let(QrImageResult::Loaded) ?: QrImageResult.Failed("undecodable image")
            }
        }.getOrElse { t ->
            // The exception's own message can quote the URL, which embeds the payment
            // payload; only the type is safe to keep.
            QrImageResult.Failed(t::class.java.simpleName)
        }
    }

    /**
     * Reads at most [maxBytes], then one byte more — so an oversized response is *detected*
     * rather than silently truncated into an undecodable image that would be reported as a
     * corrupt QR instead of an oversized one.
     */
    private fun readBounded(stream: InputStream): ByteArray {
        val buffer = ByteArray(BUFFER_BYTES)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) throw IOException("QR image exceeded $maxBytes bytes")
        }
        return out.toByteArray()
    }

    internal companion object {
        const val DEFAULT_MAX_BYTES: Int = 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS: Int = 15_000
        const val READ_TIMEOUT_MILLIS: Int = 15_000
        private const val BUFFER_BYTES = 8 * 1024
    }
}

/** Opens a real TLS connection. The only place in this file that touches the network. */
internal class DefaultQrConnectionFactory : QrConnectionFactory {

    override fun open(url: URL): QrConnection {
        val connection = url.openConnection() as? HttpsURLConnection
            ?: throw IOException("QR images must be fetched over HTTPS.")
        return RealQrConnection(connection)
    }

    private class RealQrConnection(private val connection: HttpsURLConnection) : QrConnection {

        override var useCaches: Boolean
            get() = connection.useCaches
            set(value) {
                connection.useCaches = value
            }

        override var connectTimeoutMillis: Int
            get() = connection.connectTimeout
            set(value) {
                connection.connectTimeout = value
            }

        override var readTimeoutMillis: Int
            get() = connection.readTimeout
            set(value) {
                connection.readTimeout = value
            }

        override fun responseCode(): Int = connection.responseCode

        override fun body(): InputStream? =
            if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream

        override fun close() {
            runCatching { connection.disconnect() }
        }
    }
}
