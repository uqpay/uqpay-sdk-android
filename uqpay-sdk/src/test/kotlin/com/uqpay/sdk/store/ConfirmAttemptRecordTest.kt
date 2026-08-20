package com.uqpay.sdk.store

import com.uqpay.sdk.engine.BrowserDetails
import com.uqpay.sdk.engine.BrowserInfo
import com.uqpay.sdk.engine.MobileInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * The persisted pin format is a promise made to a future SDK version.
 *
 * A customer can start a payment on v1.2, have Android kill the app mid-confirm, take a
 * store update to v1.3, and relaunch. The blob written before the update is the only thing
 * that stops the next tap minting a fresh idempotency key against a payment that may
 * already be authorising. Every test here defends that: **if one fails, the change that
 * broke it is wrong — the expectation is not to be updated to match.**
 *
 * No card number, CVC or real key appears in this file. The values are invented and
 * card-free by construction, which is also the point of `nothing card-derived is
 * representable at rest`.
 */
@OptIn(ExperimentalSerializationApi::class)
class ConfirmAttemptRecordTest {

    // ---- The committed v1 fixture ---------------------------------------------------

    /**
     * A literal v1 blob, written down by hand rather than generated from the current code.
     *
     * Generating it would make it agree with any future implementation automatically,
     * which is the one property it must not have. As a frozen string it fails the moment a
     * `@SerialName` is renamed, a field is dropped, or a type changes — including a rename
     * made inside `BrowserInfo` for wire reasons, which is exactly the cross-module
     * coupling this fixture exists to make loud.
     */
    private val v1Fixture = """
        {
          "identity_digest": "89d8159c6a5bc0be396fa6ffd04a85b3a2afe9a1798f756e116a43a3e4fe56f6",
          "key_value": "6f4c2a1e-8b3d-4f9a-9c0e-2d7b5a1f4e63",
          "payment_intent_id": "int_v1_fixture_0001",
          "browser_info": {
            "accept_header": "*/*",
            "browser": {
              "java_enabled": true,
              "javascript_enabled": true,
              "user_agent": "Mozilla/5.0 (Linux; Android 14; Pixel 8)",
              "cookie_enabled": true,
              "plugins": [],
              "do_not_track": false
            },
            "device_id": "a1b2c3d4e5f60718",
            "language": "en-US",
            "mobile": {
              "device_model": "Pixel 8",
              "os_type": "ANDROID",
              "os_version": "Android 14",
              "carrier": null
            },
            "screen_color_depth": 24,
            "screen_height": 2400,
            "screen_width": 1080,
            "timezone": "8",
            "touch_support": true,
            "hardware_concurrency": 8,
            "device_memory": 8,
            "fonts": null,
            "webgl_vendor": null,
            "webgl_renderer": null
          },
          "created_at": 1786924800000,
          "ip_address": "192.168.1.42"
        }
    """.trimIndent()

    /** The record the fixture must decode to, spelled out field by field. */
    private val fixtureRecord = PersistedConfirmAttempt(
        identityDigest = "89d8159c6a5bc0be396fa6ffd04a85b3a2afe9a1798f756e116a43a3e4fe56f6",
        keyValue = "6f4c2a1e-8b3d-4f9a-9c0e-2d7b5a1f4e63",
        paymentIntentId = "int_v1_fixture_0001",
        browserInfo = BrowserInfo(
            acceptHeader = "*/*",
            browser = BrowserDetails(
                javaEnabled = true,
                javascriptEnabled = true,
                userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8)",
                cookieEnabled = true,
                plugins = emptyList(),
                doNotTrack = false,
            ),
            deviceId = "a1b2c3d4e5f60718",
            language = "en-US",
            mobile = MobileInfo(
                deviceModel = "Pixel 8",
                osType = "ANDROID",
                osVersion = "Android 14",
                carrier = null,
            ),
            screenColorDepth = 24,
            screenHeight = 2400,
            screenWidth = 1080,
            timezone = "8",
            touchSupport = true,
            hardwareConcurrency = 8,
            deviceMemory = 8,
            fonts = null,
            webglVendor = null,
            webglRenderer = null,
        ),
        createdAt = 1_786_924_800_000L,
        ipAddress = "192.168.1.42",
    )

    private fun decode(json: String): PersistedConfirmAttempt =
        ConfirmAttemptJson.instance.decodeFromString(PersistedConfirmAttempt.serializer(), json)

    private fun encode(record: PersistedConfirmAttempt): String =
        ConfirmAttemptJson.instance.encodeToString(PersistedConfirmAttempt.serializer(), record)

    /** Every key in the document, as dotted paths, so nesting is part of the assertion. */
    private fun keyPaths(json: String): Set<String> {
        val root = ConfirmAttemptJson.instance.parseToJsonElement(json).jsonObject
        val out = sortedSetOf<String>()
        fun walk(obj: JsonObject, prefix: String) {
            for ((name, value) in obj) {
                val path = if (prefix.isEmpty()) name else "$prefix.$name"
                out.add(path)
                if (value is JsonObject) walk(value, path)
            }
        }
        walk(root, "")
        return out
    }

    // ---- The wire-format guarantee ---------------------------------------------------

    /**
     * The whole point of this file: a blob in the shape v1 writes must decode, field for
     * field, on a build that has never seen it before.
     */
    @Test
    fun `the committed v1 fixture decodes`() {
        assertEquals(fixtureRecord, decode(v1Fixture))
    }

    /**
     * The frozen key set. A new field is not forbidden — it is required to announce
     * itself here, where someone has to think about whether old readers survive it.
     *
     * The nested `browser_info.*` paths are the cross-module guard: `BrowserInfo` is owned
     * by the engine and shaped by the gateway's wire format, but it is also part of this
     * stored format. A rename made for a wire reason fails here, in a module test, instead
     * of failing on a customer's phone after an app update.
     */
    @Test
    fun `the v1 key set is frozen`() {
        val expected = setOf(
            "browser_info",
            "browser_info.accept_header",
            "browser_info.browser",
            "browser_info.browser.cookie_enabled",
            "browser_info.browser.do_not_track",
            "browser_info.browser.java_enabled",
            "browser_info.browser.javascript_enabled",
            "browser_info.browser.plugins",
            "browser_info.browser.user_agent",
            "browser_info.device_id",
            "browser_info.device_memory",
            "browser_info.fonts",
            "browser_info.hardware_concurrency",
            "browser_info.language",
            "browser_info.mobile",
            "browser_info.mobile.carrier",
            "browser_info.mobile.device_model",
            "browser_info.mobile.os_type",
            "browser_info.mobile.os_version",
            "browser_info.screen_color_depth",
            "browser_info.screen_height",
            "browser_info.screen_width",
            "browser_info.timezone",
            "browser_info.touch_support",
            "browser_info.webgl_renderer",
            "browser_info.webgl_vendor",
            "created_at",
            "identity_digest",
            "ip_address",
            "key_value",
            "payment_intent_id",
        )
        assertEquals("the committed fixture drifted from v1", expected, keyPaths(v1Fixture))
        assertEquals(
            "today's writer no longer produces the v1 shape",
            expected,
            keyPaths(encode(fixtureRecord)),
        )
    }

    /**
     * `encodeDefaults` and `explicitNulls` are load-bearing, not cosmetic: a future
     * version must be free to promote an optional field to required without stranding
     * blobs already on disk, and it can only do that if the blobs actually carry the key.
     */
    @Test
    fun `optional and null fields are written explicitly, not omitted`() {
        val json = encode(fixtureRecord.copy(ipAddress = null))
        val root = ConfirmAttemptJson.instance.parseToJsonElement(json).jsonObject

        assertTrue("ip_address was omitted rather than written as null", root.containsKey("ip_address"))
        assertEquals(kotlinx.serialization.json.JsonNull, root["ip_address"])
        // Defaulted fields nested inside BrowserInfo must be present for the same reason.
        val browser = root["browser_info"]!!.jsonObject
        assertTrue(browser.containsKey("fonts"))
        assertTrue(browser.containsKey("webgl_vendor"))
        assertTrue(browser.containsKey("webgl_renderer"))
    }

    // ---- Round trips -----------------------------------------------------------------

    @Test
    fun `encode then decode is lossless`() {
        assertEquals(fixtureRecord, decode(encode(fixtureRecord)))
    }

    @Test
    fun `encode then decode is lossless with a null ip address`() {
        val record = fixtureRecord.copy(ipAddress = null)
        val restored = decode(encode(record))
        assertEquals(record, restored)
        assertNull(restored.ipAddress)
    }

    /**
     * The projection both ways. This is the path a pin actually takes across process
     * death — live attempt, blob, live attempt — and it must lose nothing, because a
     * dropped device value means a replay whose body no longer matches the pinned key.
     */
    @Test
    fun `attempt survives the full store round trip unchanged`() {
        val attempt = ConfirmAttempt(
            key = "6f4c2a1e-8b3d-4f9a-9c0e-2d7b5a1f4e63",
            payloadDigest = fixtureRecord.identityDigest,
            paymentIntentId = "int_round_trip",
            browserInfo = fixtureRecord.browserInfo,
            ipAddress = "10.0.2.16",
            createdAt = 1_786_924_800_000L,
        )
        assertEquals(attempt, decode(encode(attempt.toPersisted())).toAttempt())
    }

    /**
     * The restored attempt carries the record's **own** intent id. If this ever reads from
     * current configuration or session state instead, a pin from one payment can be
     * replayed against another.
     */
    @Test
    fun `a restored attempt carries its own payment intent id`() {
        val restored = decode(v1Fixture).toAttempt()
        assertEquals("int_v1_fixture_0001", restored.paymentIntentId)
    }

    /** Frozen device values come back from storage, not from a fresh measurement. */
    @Test
    fun `a restored attempt carries the frozen device values`() {
        val restored = decode(v1Fixture).toAttempt()
        assertEquals(1080, restored.browserInfo.screenWidth)
        assertEquals(2400, restored.browserInfo.screenHeight)
        assertEquals("ANDROID", restored.browserInfo.mobile.osType)
        assertEquals("192.168.1.42", restored.ipAddress)
    }

    // ---- Forward compatibility -------------------------------------------------------

    /**
     * Simulates a blob written by a *newer* SDK that added fields this build has never
     * heard of. The older reader must still find the pin. The alternative — throwing —
     * means a downgrade, or a staged rollout, loses every pin on the device.
     */
    @Test
    fun `unknown top-level fields in a stored blob are tolerated`() {
        val fromTheFuture = v1Fixture.replaceFirst(
            "\"identity_digest\"",
            "\"settlement_hint_v2\": {\"nested\": [1, 2, 3]},\n  \"retry_generation\": 4,\n  \"identity_digest\"",
        )
        assertEquals(fixtureRecord, decode(fromTheFuture))
    }

    /** The same tolerance must hold inside the nested device objects. */
    @Test
    fun `unknown nested fields in a stored blob are tolerated`() {
        val fromTheFuture = v1Fixture
            .replaceFirst("\"accept_header\"", "\"biometric_class\": \"strong\",\n      \"accept_header\"")
            .replaceFirst("\"device_model\"", "\"foldable_state\": \"unfolded\",\n        \"device_model\"")
        assertEquals(fixtureRecord, decode(fromTheFuture))
    }

    /**
     * The other side of the coin. A blob that is damaged rather than merely unfamiliar
     * must fail loudly so the store can discard it wholesale and fall back to no pin.
     * Half-parsing a damaged pin is strictly worse: a wrong key is a rejected replay,
     * whereas no key is an honest fresh mint.
     */
    @Test
    fun `a truncated blob fails to decode rather than half-parsing`() {
        val thrown = runCatching { decode(v1Fixture.substring(0, v1Fixture.length / 2)) }.exceptionOrNull()
            ?: fail("a truncated blob decoded")
        // The decoder's own rejection, not any exception: "rejected everything" (an NPE, a
        // fixture bug) must not pass as "rejected the bad blob".
        assertTrue("expected SerializationException, got ${thrown::class}", thrown is SerializationException)
    }

    @Test
    fun `a blob missing a required field fails to decode`() {
        val damaged = v1Fixture.replaceFirst("\"key_value\"", "\"key_value_typo\"")
        val thrown = runCatching { decode(damaged) }.exceptionOrNull()
            ?: fail("a blob with no key_value decoded")
        assertTrue("expected MissingFieldException, got ${thrown::class}", thrown is MissingFieldException)
        assertTrue((thrown as MissingFieldException).missingFields.contains("key_value"))
    }

    /**
     * A wrong-typed value must not be silently coerced. A garbage `created_at` quietly
     * becoming `0` would read as a pin minted in 1970 and be dropped as expired — the pin
     * would vanish without anything looking broken.
     */
    @Test
    fun `a wrong-typed value is not silently coerced`() {
        val damaged = v1Fixture.replaceFirst("1786924800000", "\"not-a-timestamp\"")
        val thrown = runCatching { decode(damaged) }.exceptionOrNull()
            ?: fail("created_at was coerced instead of rejected")
        assertTrue("expected SerializationException, got ${thrown::class}", thrown is SerializationException)
        assertFalse("a type mismatch is not a missing field", thrown is MissingFieldException)
    }

    // ---- Idempotency keys are opaque -------------------------------------------------

    /**
     * The rule that protects money: a key comes back **exactly** as it went in.
     *
     * The server matches idempotency keys as opaque strings. A key that returns one
     * character different is a different key, and a different key against a payment that
     * may already be authorising is a double charge. So no re-casing, no trimming, no
     * `UUID.fromString` validation — including for keys that look wrong. A malformed-looking
     * key is still the key the gateway saw.
     */
    @Test
    fun `a restored key survives byte-identically whatever its shape`() {
        val shapes = listOf(
            "6F4C2A1E-8B3D-4F9A-9C0E-2D7B5A1F4E63",   // uppercase — UQPAY rejects these on mint…
            "6f4C2a1E-8b3D-4f9A-9c0E-2d7B5a1F4e63",   // …but a stored one is replayed as-is
            "{6f4c2a1e-8b3d-4f9a-9c0e-2d7b5a1f4e63}", // braced
            "6f4c2a1e8b3d4f9a9c0e2d7b5a1f4e63",       // undashed
            "  padded-key-with-spaces  ",             // whitespace is part of the key
            "not-a-uuid-at-all",                      // opaque, and that is allowed
            "ключ-‐0027-🔑",                 // non-ASCII survives UTF-8 encoding
            "",                                        // even empty: not ours to correct
        )

        for (shape in shapes) {
            val restored = decode(encode(fixtureRecord.copy(keyValue = shape))).toAttempt()
            assertEquals("key mutated through the store: <$shape>", shape, restored.key)
            assertEquals(shape.length, restored.key.length)
        }
    }

    /** The in-memory restore path must not normalise either — it hands back the same string. */
    @Test
    fun `restoring an attempt does not touch the key instance`() {
        val key = "6F4C2A1E-8B3D-4F9A-9C0E-2D7B5A1F4E63"
        assertSame(key, fixtureRecord.copy(keyValue = key).toAttempt().key)
    }

    /**
     * Fresh keys, by contrast, are minted lowercase — **UQPAY rejects an uppercase UUID.**
     * `java.util.UUID.toString()` is specified to emit lowercase hex, so this holds by
     * construction; the test pins it so nothing downstream "tidies" the format.
     */
    @Test
    fun `a freshly minted key is a lowercase uuid`() {
        repeat(50) {
            val key = ConfirmAttempt.newIdempotencyKey()
            assertEquals("a minted key was not lowercase: <$key>", key.lowercase(), key)
            assertEquals(36, key.length)
            // Parses as a UUID and prints back identically — no re-casing anywhere.
            assertEquals(key, UUID.fromString(key).toString())
        }
    }

    @Test
    fun `minting produces a distinct key each time`() {
        val keys = (1..200).map { ConfirmAttempt.newIdempotencyKey() }.toSet()
        assertEquals(200, keys.size)
    }

    @Test
    fun `mint freezes everything it is given and only invents the key`() {
        val attempt = ConfirmAttempt.mint(
            payloadDigest = fixtureRecord.identityDigest,
            paymentIntentId = "int_mint",
            browserInfo = fixtureRecord.browserInfo,
            ipAddress = null,
            createdAt = 1_786_924_800_000L,
        )
        assertEquals(fixtureRecord.identityDigest, attempt.payloadDigest)
        assertEquals("int_mint", attempt.paymentIntentId)
        assertSame(fixtureRecord.browserInfo, attempt.browserInfo)
        assertNull(attempt.ipAddress)
        assertEquals(1_786_924_800_000L, attempt.createdAt)
        assertEquals(36, attempt.key.length)
    }

    // ---- Nothing card-derived, nothing loggable --------------------------------------

    /**
     * Acceptance §4.3, asserted on the serialized form rather than on the source: the
     * at-rest record has no field that could hold a PAN, a CVC, an expiry or a token.
     *
     * Checked against the key set the type actually produces, so adding such a field —
     * however it is named in Kotlin — has to get past this test.
     */
    @Test
    fun `nothing card-derived is representable at rest`() {
        val forbidden = listOf(
            "pan", "card_number", "cardnumber", "number", "cvc", "cvv", "csc",
            "security_code", "expiry", "exp_month", "exp_year", "cardholder",
            "token", "secret", "api_key", "password", "email", "phone",
        )
        val paths = keyPaths(encode(fixtureRecord))
        for (path in paths) {
            val leaf = path.substringAfterLast('.')
            for (word in forbidden) {
                assertFalse(
                    "the persisted record grew a field that can hold card data: $path",
                    leaf == word || leaf.endsWith("_$word") || leaf.startsWith("${word}_"),
                )
            }
        }
    }

    /**
     * The idempotency key is a live credential for replaying a payment. A record or an
     * attempt that renders it in `toString()` puts it into every log line, exception
     * message and crash report that ever interpolates one.
     */
    @Test
    fun `toString never renders the idempotency key`() {
        val key = "6f4c2a1e-8b3d-4f9a-9c0e-2d7b5a1f4e63"
        val record = fixtureRecord.copy(keyValue = key)
        assertFalse(record.toString().contains(key))
        assertFalse(record.toAttempt().toString().contains(key))
    }

    /**
     * The digest is not card-derived, but it is still the identity of a specific payment;
     * a whole one in a log is more than a log needs.
     */
    @Test
    fun `toString does not render the full payload digest`() {
        assertFalse(fixtureRecord.toString().contains(fixtureRecord.identityDigest))
        assertTrue(fixtureRecord.toString().contains("int_v1_fixture_0001"))
    }

    // ---- Insertion order (WU-2.3/2.4 rely on it) -------------------------------------

    /**
     * The registry evicts oldest-first, which means list order is part of the stored
     * format too, not just each element. A list must come back in the order it went in.
     */
    @Test
    fun `a list of records round trips in insertion order`() {
        val records = (1..5).map { i ->
            fixtureRecord.copy(
                identityDigest = "digest-$i",
                keyValue = "key-$i",
                paymentIntentId = "int_$i",
                createdAt = 1_786_924_800_000L + i,
            )
        }
        val serializer = kotlinx.serialization.builtins.ListSerializer(
            PersistedConfirmAttempt.serializer(),
        )
        val json = ConfirmAttemptJson.instance.encodeToString(serializer, records)
        val restored = ConfirmAttemptJson.instance.decodeFromString(serializer, json)

        assertEquals(records, restored)
        assertEquals(listOf("int_1", "int_2", "int_3", "int_4", "int_5"), restored.map { it.paymentIntentId })
    }

    /** An empty store blob is a legitimate state, not corruption. */
    @Test
    fun `an empty list round trips`() {
        val serializer = kotlinx.serialization.builtins.ListSerializer(
            PersistedConfirmAttempt.serializer(),
        )
        val json = ConfirmAttemptJson.instance.encodeToString(serializer, emptyList())
        assertTrue(ConfirmAttemptJson.instance.decodeFromString(serializer, json).isEmpty())
    }

    // ---- Codec independence ----------------------------------------------------------

    /**
     * The stored format must not be downstream of the network layer's tuning. If a future
     * change points the store at `network.UQPayJson`, a debugging flip of `prettyPrint` or
     * a lenience change made for a gateway payload silently rewrites every pin on every
     * device. Pinned here as a behavioural difference rather than as a code-review note.
     */
    @Test
    fun `the store codec writes nulls that the network codec would drop`() {
        val json = encode(fixtureRecord.copy(ipAddress = null))
        assertTrue(json.contains("\"ip_address\":null"))
        assertFalse("the store blob must stay compact", json.contains("\n"))
    }

    /** Decoding does not depend on key order — a future writer may reorder freely. */
    @Test
    fun `key order in a stored blob does not matter`() {
        val reordered = buildString {
            append("{")
            append("\"ip_address\":\"192.168.1.42\",")
            append("\"created_at\":1786924800000,")
            append("\"payment_intent_id\":\"int_v1_fixture_0001\",")
            append("\"key_value\":\"6f4c2a1e-8b3d-4f9a-9c0e-2d7b5a1f4e63\",")
            append("\"identity_digest\":\"")
            append(fixtureRecord.identityDigest)
            append("\",")
            append("\"browser_info\":")
            append(
                ConfirmAttemptJson.instance.encodeToString(
                    BrowserInfo.serializer(),
                    fixtureRecord.browserInfo,
                ),
            )
            append("}")
        }
        assertEquals(fixtureRecord, decode(reordered))
    }

    /** Guards the assumption the fixture's `timezone` encodes as a string, not a number. */
    @Test
    fun `timezone stays a string at rest`() {
        val root = ConfirmAttemptJson.instance.parseToJsonElement(encode(fixtureRecord)).jsonObject
        val timezone = root["browser_info"]!!.jsonObject["timezone"]
        assertTrue(timezone is JsonPrimitive && timezone.isString)
    }
}
