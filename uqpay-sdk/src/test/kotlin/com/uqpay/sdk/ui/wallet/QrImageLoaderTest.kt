package com.uqpay.sdk.ui.wallet

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The QR image fetch, disaster first.
 *
 * The load-bearing assertion in this file is `useCaches == false`. A cache hit is invisible
 * from the outside — the loader returns a perfectly good `Bitmap` either way — so the only
 * place the rule can be checked is the connection the loader configured. The live gateway
 * makes this concrete: `…/api/v2/payment/qr?data=…` answers with
 * `cache-control: public, max-age=300`, so an HTTP cache is *invited* to hold a QR for five
 * minutes, and a merchant app is entitled to install one process-wide.
 *
 * No real QR payload appears in this file; the fake URLs carry a placeholder.
 */
@RunWith(RobolectricTestRunner::class)
class QrImageLoaderTest {

    // ---- the rule that cannot be observed any other way --------------------------------

    @Test
    fun `caches are disabled on the connection`() = runTest {
        val connection = FakeConnection(body = PNG_BYTES)
        loader(connection).load(URL_OK)

        assertFalse("a cached QR may be expired or superseded", connection.useCaches)
    }

    @Test
    fun `timeouts are set so a hung QR host cannot hold the screen forever`() = runTest {
        val connection = FakeConnection(body = PNG_BYTES)
        loader(connection).load(URL_OK)

        assertEquals(QrImageLoader.CONNECT_TIMEOUT_MILLIS, connection.connectTimeoutMillis)
        assertEquals(QrImageLoader.READ_TIMEOUT_MILLIS, connection.readTimeoutMillis)
    }

    @Test
    fun `the connection is closed on success and on failure`() = runTest {
        val ok = FakeConnection(body = PNG_BYTES)
        loader(ok).load(URL_OK)
        assertTrue(ok.closed)

        val bad = FakeConnection(status = 500, body = ByteArray(0))
        loader(bad).load(URL_OK)
        assertTrue(bad.closed)
    }

    // ---- HTTPS ---------------------------------------------------------------------------

    @Test
    fun `a cleartext QR URL is refused without opening a connection`() = runTest {
        var opened = false
        val result = QrImageLoader(
            connectionFactory = { opened = true; FakeConnection(body = PNG_BYTES) },
            ioDispatcher = Dispatchers.Unconfined,
            decode = { BITMAP },
        ).load("http://example.invalid/qr.png")

        assertEquals(QrImageResult.Failed("non-https url"), result)
        assertFalse("a downgraded QR URL must never be fetched", opened)
    }

    @Test
    fun `a malformed URL fails instead of throwing`() = runTest {
        assertEquals(
            QrImageResult.Failed("malformed url"),
            loader(FakeConnection(body = PNG_BYTES)).load("not a url at all"),
        )
    }

    // ---- failure is retryable, and says nothing it should not --------------------------

    @Test
    fun `a non-2xx response fails with the status and is retryable`() = runTest {
        val result = loader(FakeConnection(status = 503, body = ByteArray(0))).load(URL_OK)
        assertEquals(QrImageResult.Failed("http 503"), result)
    }

    @Test
    fun `an empty body fails rather than decoding to a blank square`() = runTest {
        assertEquals(
            QrImageResult.Failed("empty body"),
            loader(FakeConnection(body = ByteArray(0))).load(URL_OK),
        )
    }

    @Test
    fun `bytes that are not an image fail as undecodable`() = runTest {
        val result = QrImageLoader(
            connectionFactory = { FakeConnection(body = "<html>nope</html>".toByteArray()) },
            ioDispatcher = Dispatchers.Unconfined,
            decode = { null },
        ).load(URL_OK)

        assertEquals(QrImageResult.Failed("undecodable image"), result)
    }

    @Test
    fun `a thrown IOException becomes a failure, never an escaping exception`() = runTest {
        val result = QrImageLoader(
            connectionFactory = { throw IOException("connect timed out to $URL_OK") },
            ioDispatcher = Dispatchers.Unconfined,
            decode = { BITMAP },
        ).load(URL_OK)

        assertEquals(QrImageResult.Failed("IOException"), result)
    }

    /**
     * The QR URL embeds the EMVCo payment payload as a query parameter, and exception
     * messages routinely quote the URL. Only the exception's *type* may survive into a
     * diagnostic string.
     */
    @Test
    fun `a failure reason never carries the QR payload`() = runTest {
        val results = listOf(
            QrImageLoader(
                connectionFactory = { throw IOException("failed to connect to $URL_OK") },
                ioDispatcher = Dispatchers.Unconfined,
                decode = { BITMAP },
            ).load(URL_OK),
            loader(FakeConnection(status = 404, body = ByteArray(0))).load(URL_OK),
        )

        results.forEach { result ->
            val reason = (result as QrImageResult.Failed).reason
            assertFalse(reason, reason.contains("PLACEHOLDER"))
            assertFalse(reason, reason.contains("payment/qr"))
        }
    }

    /** A hostile or misrouted response must not be decoded into an out-of-memory crash. */
    @Test
    fun `an oversized response is refused rather than buffered`() = runTest {
        val result = QrImageLoader(
            connectionFactory = { FakeConnection(body = ByteArray(64 * 1024)) },
            ioDispatcher = Dispatchers.Unconfined,
            maxBytes = 1024,
            decode = { BITMAP },
        ).load(URL_OK)

        assertEquals(QrImageResult.Failed("IOException"), result)
    }

    // ---- the happy path --------------------------------------------------------------

    @Test
    fun `a PNG loads`() = runTest {
        val result = loader(FakeConnection(body = PNG_BYTES)).load(URL_OK)
        assertTrue(result is QrImageResult.Loaded)
    }

    /**
     * Retry is a fresh fetch, not a replay of the first answer — a QR that failed once and a
     * QR that succeeded on the second try must both come from the wire.
     */
    @Test
    fun `each load opens its own connection`() = runTest {
        var opened = 0
        val subject = QrImageLoader(
            connectionFactory = { opened++; FakeConnection(body = PNG_BYTES) },
            ioDispatcher = Dispatchers.Unconfined,
            decode = { BITMAP },
        )
        subject.load(URL_OK)
        subject.load(URL_OK)

        assertEquals(2, opened)
    }

    // ---- fakes -------------------------------------------------------------------------

    private fun loader(connection: FakeConnection) = QrImageLoader(
        connectionFactory = { connection },
        ioDispatcher = Dispatchers.Unconfined,
        decode = { BITMAP },
    )

    private class FakeConnection(
        private val status: Int = 200,
        private val body: ByteArray,
    ) : QrConnection {
        // Defaults to true, exactly as HttpURLConnection does: the test must observe the
        // loader turning it off, not a fake that was born with it off.
        override var useCaches: Boolean = true
        override var connectTimeoutMillis: Int = 0
        override var readTimeoutMillis: Int = 0
        var closed = false

        override fun responseCode(): Int = status
        override fun body(): InputStream = ByteArrayInputStream(body)
        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val URL_OK = "https://api-sandbox.invalid/api/v2/payment/qr?data=PLACEHOLDER"

        /** Not a real image; `decode` is injected, so the bytes only have to be non-empty. */
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        val BITMAP: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
}
