package com.uqpay.sdk.engine

import java.security.MessageDigest

/**
 * The stable identity of a confirm payload — the key an idempotency pin is filed under.
 *
 * **Never `hashCode()`.** Kotlin's `hashCode()` is process-seeded for the types that
 * matter here, so its values cannot outlive a launch — and an idempotency pin *must*,
 * because a pin lost to process death is exactly the double-charge window. iOS shipped
 * this bug with Swift's `Hasher` and had to fix it; we start on the other side of it.
 * These digests are written at rest and compared across SDK versions, so the algorithm
 * below is a wire contract, pinned by a committed test vector in `ConfirmPayloadIdentityTest`.
 *
 * The digest is SHA-256 over the identity fields joined with [FIELD_SEPARATOR] (U+001F
 * UNIT SEPARATOR), a character no form field can contain. That choice is load-bearing
 * rather than decorative: with an empty separator `["ab","c"]` and `["a","bc"]` produce
 * the same bytes, and two different payments would share one pin.
 *
 * ### What is deliberately excluded, and what that costs
 *
 * The CVC never enters an identity, and the card number is reduced to BIN-plus-last-4 by
 * [cardNumberIdentity]. Nothing persisted may be derivable back to a full PAN or a CVC,
 * and these digests are persisted.
 *
 * The accepted cost, recorded honestly: an edit that changes **only** excluded fields —
 * the CVC, or a PAN digit between the BIN and the last four — produces the same identity,
 * so the next tap replays the previous attempt's key with a changed body. The server
 * rejects a reused key whose payload changed, that rejection is a definitive answer, the
 * pin resolves, and the tap after that mints a fresh key. **One wasted round trip, never
 * a double charge.** The reverse trade — including the CVC so the identity is exact —
 * would put card-derived material in a digest we write to disk, which is not a trade this
 * SDK is willing to make.
 */
internal object ConfirmPayloadIdentity {

    /**
     * U+001F UNIT SEPARATOR. Chosen because it cannot appear in any value we build an
     * identity from — card numbers, expiry parts, intent ids, method types — so field
     * boundaries are unambiguous and adjacent fields cannot be re-partitioned into a
     * colliding pair.
     */
    const val FIELD_SEPARATOR: Char = '\u001F'

    /**
     * Lowercase hex of the SHA-256 over [fields] joined by [FIELD_SEPARATOR], UTF-8.
     *
     * Callers pass identity fields only — see the class note on exclusions. Two payloads
     * that must be treated as the same payment produce the same string here, and nothing
     * else does.
     */
    fun digest(fields: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonicalString(fields).toByteArray(Charsets.UTF_8))
        return bytes.toLowercaseHex()
    }

    /**
     * Exactly the string [digest] hashes.
     *
     * Exposed so tests can assert on the *bytes that are hashed* rather than on a digest
     * that would hide a full PAN just as effectively as it hides everything else. A test
     * that can only see the output cannot prove the input was safe.
     */
    fun canonicalString(fields: List<String>): String = fields.joinToString(FIELD_SEPARATOR.toString())

    /**
     * `BIN:last4` — the only form of a card number allowed into an identity.
     *
     * Six leading digits plus four trailing ones distinguish a corrected card number from
     * the original without the digest being brute-forceable back to a PAN. Short inputs
     * (an in-progress form field) are not rejected: [take] and [takeLast] simply overlap,
     * which yields a stable string for a value that was never a real card number anyway.
     */
    fun cardNumberIdentity(pan: String): String = "${pan.take(6)}:${pan.takeLast(4)}"

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /**
     * Hex by table rather than `String.format("%02x", …)`: `format` uses the default
     * locale, and locales with non-ASCII digit shapes would silently produce a different
     * digest string on some devices — orphaning every pin those devices had written.
     */
    private fun ByteArray.toLowercaseHex(): String {
        val out = CharArray(size * 2)
        forEachIndexed { i, byte ->
            val v = byte.toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }
}
