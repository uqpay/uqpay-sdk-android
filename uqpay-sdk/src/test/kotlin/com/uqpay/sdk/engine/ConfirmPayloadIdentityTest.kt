package com.uqpay.sdk.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity function is a wire contract: these digests are written to disk and read
 * back by a later launch, possibly of a later SDK version. Every test here defends that
 * — a change that makes one of them fail has orphaned every pin in the field.
 *
 * Documented test PANs only (acceptance §4.3): no real card number appears in this file.
 */
class ConfirmPayloadIdentityTest {

    /** A documented UQPAY sandbox test card. Never a real PAN. */
    private val testPan = "4176660000000027"
    private val testCvc = "303"

    // ---- The committed vectors ------------------------------------------------------

    /**
     * The whole point of this file.
     *
     * These two hex strings were computed independently of the implementation (SHA-256 of
     * the fields joined by U+001F, UTF-8). They are the guard against a future
     * "optimisation" — a different separator, a different encoding, uppercase hex,
     * `hashCode()` — silently changing the algorithm. Any such change orphans every pin
     * persisted by every shipped version: the relaunched app would file the same payment
     * under a new identity, mint a fresh idempotency key, and re-send a confirm that may
     * already be processing. That is the double charge.
     *
     * If one of these fails, the correct response is to revert the change, not to update
     * the expectation.
     */
    @Test
    fun `committed digest vector for a plain field list`() {
        assertEquals(
            "89d8159c6a5bc0be396fa6ffd04a85b3a2afe9a1798f756e116a43a3e4fe56f6",
            ConfirmPayloadIdentity.digest(listOf("uqpay", "confirm", "v1")),
        )
    }

    /** The same guard for a realistically shaped card identity. */
    @Test
    fun `committed digest vector for a card-shaped field list`() {
        assertEquals(
            "a30578251063ae731c46dbbbe2ac4b633f3ba35598bc5a95dc2e4405d10551fe",
            ConfirmPayloadIdentity.digest(
                listOf("int_1", "card", "417666:0027", "12", "2030", "SG"),
            ),
        )
    }

    /**
     * **Cross-platform parity, locked permanently.** These two vectors are iOS's committed
     * expectations (`ConfirmPayloadIdentityTests.swift` in the iOS SDK) and were reproduced
     * independently in Python during the Slice 2 audit. The digest is the key into the shared
     * idempotency semantics both SDKs implement against the same gateway; if Android and iOS
     * ever disagree on these bytes, one of them has silently changed the canonical form.
     */
    @Test
    fun `iOS committed vectors digest identically on Android`() {
        assertEquals(
            "f04cdced9736a69da6103f08a4daaf8c485dd481217d218a1b4993c8c3968e13",
            ConfirmPayloadIdentity.digest(listOf("a", "b")),
        )
        assertEquals(
            "f54bf243bef884d9911df9fc3d7169731647ea074c43108dcbfe45686658f28e",
            ConfirmPayloadIdentity.digest(listOf("pi_123", "grabpay")),
        )
    }

    @Test
    fun `the separator is U+001F`() {
        // Pinned separately from the vectors so a change to it names itself in the
        // failure output instead of showing up as an unexplained hex mismatch.
        assertEquals('\u001F', ConfirmPayloadIdentity.FIELD_SEPARATOR)
        assertEquals(0x1F, ConfirmPayloadIdentity.FIELD_SEPARATOR.code)
    }

    // ---- Collision resistance across field boundaries --------------------------------

    @Test
    fun `re-partitioning the same characters does not collide`() {
        // With an empty separator these are the same bytes, and two different payments
        // would share one idempotency pin.
        assertNotEquals(
            ConfirmPayloadIdentity.digest(listOf("ab", "c")),
            ConfirmPayloadIdentity.digest(listOf("a", "bc")),
        )
    }

    @Test
    fun `field order is part of the identity`() {
        assertNotEquals(
            ConfirmPayloadIdentity.digest(listOf("int_1", "card")),
            ConfirmPayloadIdentity.digest(listOf("card", "int_1")),
        )
    }

    @Test
    fun `an added empty field changes the identity`() {
        // A trailing blank optional field is a different payload, not the same one.
        assertNotEquals(
            ConfirmPayloadIdentity.digest(listOf("int_1", "card")),
            ConfirmPayloadIdentity.digest(listOf("int_1", "card", "")),
        )
    }

    /**
     * The one collision the scheme has, pinned on purpose: an empty list and a list of one
     * empty string join to the same canonical string (`""`), so they digest identically. It
     * is not a bug to fix — `joinToString` cannot tell them apart — it is the reason the
     * fixed-arity rule and `ConfirmPayload.ABSENT_FIELD` (U+0000, never `""`) exist: callers
     * always pass every field, absent ones as the placeholder, so no real payload ever
     * digests as `[]` or `[""]`. If a refactor made these two differ, that would suggest the
     * canonical form changed and every committed vector above must be re-derived; if the
     * fixed-arity rule were then dropped as "unnecessary", this test is the reminder why not.
     */
    @Test
    fun `an empty list and a single empty field collide - the accepted root of the arity rule`() {
        assertEquals(
            ConfirmPayloadIdentity.canonicalString(emptyList()),
            ConfirmPayloadIdentity.canonicalString(listOf("")),
        )
        assertEquals(
            ConfirmPayloadIdentity.digest(emptyList()),
            ConfirmPayloadIdentity.digest(listOf("")),
        )
        // The placeholder is what keeps a real absent field out of that collision.
        assertNotEquals("", ConfirmPayload.ABSENT_FIELD)
        assertNotEquals(
            ConfirmPayloadIdentity.digest(listOf("int_1", "")),
            ConfirmPayloadIdentity.digest(listOf("int_1", ConfirmPayload.ABSENT_FIELD)),
        )
    }

    // ---- Determinism ----------------------------------------------------------------

    @Test
    fun `the same fields digest identically across calls`() {
        val fields = listOf("int_1", "card", ConfirmPayloadIdentity.cardNumberIdentity(testPan))
        assertEquals(
            ConfirmPayloadIdentity.digest(fields),
            ConfirmPayloadIdentity.digest(fields.toList()),
        )
    }

    @Test
    fun `a digest is 64 lowercase hex characters`() {
        // Shape matters: it is a map key and a persisted field. Lowercase is asserted
        // because String.format would render it per the default locale.
        val digest = ConfirmPayloadIdentity.digest(listOf("int_1"))
        assertEquals(64, digest.length)
        assertTrue(digest, digest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `non-ascii fields digest by their utf-8 bytes`() {
        // A cardholder name is free text. Pinned so a future charset default cannot move
        // the identity of a payment already pinned.
        assertEquals(
            "91005e31b81bd7e7aef85014b392afa2419e8c052bac4c8b33d38fab39d837b9",
            ConfirmPayloadIdentity.digest(listOf("Omega-Ω", "naive-ï")),
        )
    }

    // ---- Nothing card-derived reaches the hashed bytes -------------------------------

    @Test
    fun `the canonical string carries neither a full pan nor a cvc`() {
        // Asserted on the canonical string, not the digest: a digest hides a full PAN
        // exactly as well as it hides anything else, so testing the output would prove
        // nothing about the input. This is the bytes we actually hash and persist an
        // identity for.
        val canonical = ConfirmPayloadIdentity.canonicalString(
            listOf(
                "int_1",
                "card",
                ConfirmPayloadIdentity.cardNumberIdentity(testPan),
                "12",
                "2030",
            ),
        )

        assertFalse(canonical, canonical.contains(testPan))
        assertFalse(canonical, canonical.contains(testCvc))
        // Not even a fragment long enough to narrow the middle digits.
        assertFalse(canonical, canonical.contains(testPan.substring(0, 7)))
        assertTrue(canonical, canonical.contains("417666:0027"))
    }

    @Test
    fun `card identity keeps bin and last four only`() {
        assertEquals("417666:0027", ConfirmPayloadIdentity.cardNumberIdentity(testPan))
        // A 19-digit PAN (Maestro/UnionPay range) reduces the same way.
        assertEquals("417666:9019", ConfirmPayloadIdentity.cardNumberIdentity("4176661234567899019"))
    }

    @Test
    fun `card identity tolerates a part-typed number`() {
        // The form calls this while the customer is still typing; overlapping take and
        // takeLast is defined behaviour, not an edge case to reject.
        assertEquals("4176:4176", ConfirmPayloadIdentity.cardNumberIdentity("4176"))
        assertEquals(":", ConfirmPayloadIdentity.cardNumberIdentity(""))
    }

    @Test
    fun `changing only the middle digits reuses the identity - the accepted cost`() {
        // Documented in ConfirmPayloadIdentity's KDoc: the replay is rejected by the
        // server for a changed body, the pin resolves, the next tap mints a fresh key.
        // One wasted round trip, never a double charge. Pinned as a test so the trade
        // stays a decision rather than a surprise.
        assertEquals(
            ConfirmPayloadIdentity.cardNumberIdentity("4176660000000027"),
            ConfirmPayloadIdentity.cardNumberIdentity("4176669999990027"),
        )
    }

    @Test
    fun `changing the last four changes the identity`() {
        assertNotEquals(
            ConfirmPayloadIdentity.digest(
                listOf(ConfirmPayloadIdentity.cardNumberIdentity("4176660000000027")),
            ),
            ConfirmPayloadIdentity.digest(
                listOf(ConfirmPayloadIdentity.cardNumberIdentity("4176660000000035")),
            ),
        )
    }
}
