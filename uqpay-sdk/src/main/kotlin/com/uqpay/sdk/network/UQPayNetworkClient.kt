package com.uqpay.sdk.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * The single seam between the SDK and the network. Everything above this is testable
 * without a socket.
 */
internal fun interface UQPayNetworkClient {
    suspend fun execute(request: UQPayRequest): UQPayResponse
}

/** Backoff schedule, injected so tests do not wait in real time. */
internal fun interface RetryDelaySupplier {
    /** Delay before attempt [attempt] (1-based, so attempt 1 is the first retry). */
    fun delayMillis(attempt: Int): Long
}

/**
 * Exponential backoff with jitter: 2s, 4s, 8s, each ±25%.
 *
 * The jitter matters. Without it, every device that hit the same outage retries in
 * lockstep and hammers the gateway the moment it recovers.
 */
internal class ExponentialBackoff(
    private val baseMillis: Long = 2_000L,
    private val random: Random = Random.Default,
) : RetryDelaySupplier {
    override fun delayMillis(attempt: Int): Long {
        val exponential = baseMillis shl (attempt - 1).coerceIn(0, 6)
        val jitter = (exponential * 0.25).toLong()
        return exponential - jitter + random.nextLong(0, 2 * jitter + 1)
    }
}

/**
 * Executes requests, retrying only where it is provably safe to do so.
 *
 * @property maxRetries retries *after* the first attempt.
 */
internal class DefaultUQPayNetworkClient(
    private val connectionFactory: ConnectionFactory = DefaultConnectionFactory(),
    private val retryDelaySupplier: RetryDelaySupplier = ExponentialBackoff(),
    private val logger: UQPayLogger = UQPayLogger.Noop,
    private val workContext: CoroutineContext,
    private val maxRetries: Int = 3,
) : UQPayNetworkClient {

    override suspend fun execute(request: UQPayRequest): UQPayResponse =
        withContext(workContext) {
            var attempt = 0
            while (true) {
                val response = try {
                    sendOnce(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (!canRetry(request, attempt)) throw e.asApiException()
                    logger.debug(
                        "Retrying ${request.method} ${redactUrl(request.url)} " +
                            "after a transport failure",
                    )
                    delay(retryDelaySupplier.delayMillis(++attempt))
                    continue
                }

                if (!shouldRetry(response.statusCode) || !canRetry(request, attempt)) {
                    return@withContext response
                }

                logger.debug(
                    "Retrying ${request.method} ${redactUrl(request.url)} " +
                        "after HTTP ${response.statusCode}",
                )
                delay(delayFor(response, ++attempt))
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }

    private fun sendOnce(request: UQPayRequest): UQPayResponse =
        connectionFactory.open(request).use { it.execute(request.body) }

    /**
     * Retry only when the request itself is safe to repeat. A mutating call without an
     * idempotency key is never retried: resending it could charge the customer twice.
     */
    private fun canRetry(request: UQPayRequest, attempt: Int): Boolean =
        request.isRetrySafe && attempt < maxRetries

    private fun shouldRetry(status: Int): Boolean = status == 429 || status >= 500

    /**
     * Honour `Retry-After` when the gateway sends one — **up to [MAX_RETRY_AFTER_MILLIS]**.
     *
     * The header is more informed than our backoff, so it wins inside that bound. It is not
     * trusted beyond it: the value is an integer of seconds from a response we are already
     * treating as degraded, and a `Retry-After: 999999` parks the coroutine for eleven days,
     * three times over. Nothing above this layer can interrupt that — a poll attempt would
     * simply never return, and a confirm's replay ladder would never reach its next rung — so
     * the customer sits on a spinner with a live payment and no way to learn its fate.
     *
     * The ceiling is the top rung of the confirm replay ladder (10s). A wait longer than the
     * whole ladder cannot help the request it is delaying: by then the caller's own budget —
     * the ladder, or the poller's per-attempt ceiling — is what decides the payment.
     */
    private fun delayFor(response: UQPayResponse, attempt: Int): Long =
        response.retryAfterSeconds?.takeIf { it > 0 }
            // Clamped in seconds, before the multiply: `Long.MAX_VALUE / 1000` seconds is a
            // header a gateway can send, and multiplying it first overflows to a *negative*
            // delay — an immediate retry storm, which is the opposite of what it asked for.
            ?.coerceAtMost(MAX_RETRY_AFTER_MILLIS / 1_000L)
            ?.times(1_000L)
            ?: retryDelaySupplier.delayMillis(attempt)

    internal companion object {

        /** The bound on an honoured `Retry-After`. See [delayFor]. */
        const val MAX_RETRY_AFTER_MILLIS: Long = 10_000L
    }
}

/** Maps transport-level throwables onto the internal hierarchy. Nothing escapes the type. */
internal fun Throwable.asApiException(): UQPayApiException = when (this) {
    is UQPayApiException -> this
    is InterruptedIOException -> UQPayApiException.TimedOut(this)
    is IOException -> UQPayApiException.TransportFailure(this)
    else -> UQPayApiException.TransportFailure(IOException(this))
}
