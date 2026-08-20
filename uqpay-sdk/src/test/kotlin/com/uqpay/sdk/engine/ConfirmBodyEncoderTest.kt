package com.uqpay.sdk.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-identical encoding is the precondition for replaying an idempotency key: a
 * gateway honours a reused key only for an unchanged body. These tests treat the encoded
 * string as a wire format, not as a formatting preference.
 */
class ConfirmBodyEncoderTest {

    @Serializable
    private data class BrowserInfoFixture(
        @SerialName("screen_width") val screenWidth: Int,
        @SerialName("os_type") val osType: String,
        @SerialName("language") val language: String,
    )

    // ---- Determinism across encodes and across input order ---------------------------

    @Test
    fun `the same body encodes identically twice`() {
        val body = confirmBody()
        assertEquals(ConfirmBodyEncoder.encode(body), ConfirmBodyEncoder.encode(body))
    }

    @Test
    fun `two differently ordered maps encode identically`() {
        // The retry path rebuilds the body rather than caching the string, and a map
        // assembled in a different order by a later refactor must not change the bytes
        // a pinned key replays.
        val forward = linkedMapOf<String, JsonElement>(
            "a_first" to JsonPrimitive("1"),
            "m_middle" to JsonPrimitive("2"),
            "z_last" to JsonPrimitive("3"),
        )
        val reversed = linkedMapOf<String, JsonElement>(
            "z_last" to JsonPrimitive("3"),
            "m_middle" to JsonPrimitive("2"),
            "a_first" to JsonPrimitive("1"),
        )

        assertEquals(ConfirmBodyEncoder.encode(forward), ConfirmBodyEncoder.encode(reversed))
        assertEquals(
            """{"a_first":"1","m_middle":"2","z_last":"3"}""",
            ConfirmBodyEncoder.encode(reversed),
        )
    }

    @Test
    fun `nested objects are sorted too`() {
        // The confirm body's payment_method and browser_info are objects; an unsorted
        // nested object breaks replay exactly as badly as an unsorted top level.
        val outerFirst = buildJsonObject {
            put("z_outer", "9")
            putJsonObjectEntries("browser_info", listOf("screen_width" to "390", "language" to "en-SG"))
        }
        val outerSecond = buildJsonObject {
            putJsonObjectEntries("browser_info", listOf("language" to "en-SG", "screen_width" to "390"))
            put("z_outer", "9")
        }

        assertEquals(ConfirmBodyEncoder.encode(outerFirst), ConfirmBodyEncoder.encode(outerSecond))
        assertEquals(
            """{"browser_info":{"language":"en-SG","screen_width":"390"},"z_outer":"9"}""",
            ConfirmBodyEncoder.encode(outerFirst),
        )
    }

    @Test
    fun `array order is preserved, never sorted`() {
        // An array is positional: reordering it would change meaning, not just bytes.
        val body = buildJsonObject {
            put(
                "types",
                buildJsonArray {
                    add(JsonPrimitive("card"))
                    add(JsonPrimitive("alipay"))
                    add(JsonPrimitive("grabpay"))
                },
            )
        }
        assertEquals("""{"types":["card","alipay","grabpay"]}""", ConfirmBodyEncoder.encode(body))
    }

    @Test
    fun `objects nested inside arrays are sorted`() {
        val body = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("z", "1")
                            put("a", "2")
                        },
                    )
                },
            )
        }
        assertEquals("""{"items":[{"a":"2","z":"1"}]}""", ConfirmBodyEncoder.encode(body))
    }

    @Test
    fun `deep nesting is sorted at every level`() {
        val body = buildJsonObject {
            put(
                "outer",
                buildJsonObject {
                    put(
                        "z_mid",
                        buildJsonObject {
                            put("z_inner", "1")
                            put("a_inner", "2")
                        },
                    )
                    put("a_mid", "3")
                },
            )
        }
        assertEquals(
            """{"outer":{"a_mid":"3","z_mid":{"a_inner":"2","z_inner":"1"}}}""",
            ConfirmBodyEncoder.encode(body),
        )
    }

    // ---- Shape guarantees ------------------------------------------------------------

    @Test
    fun `output is compact - no whitespace to drift`() {
        val encoded = ConfirmBodyEncoder.encode(confirmBody())
        assertEquals(-1, encoded.indexOf('\n'))
        assertEquals(-1, encoded.indexOf(' '))
    }

    @Test
    fun `an explicit null is encoded, not dropped`() {
        // A body that omits a key and a body that sends it as null are different bytes,
        // so whichever the caller built must survive to the wire unchanged.
        assertEquals(
            """{"ip_address":null}""",
            ConfirmBodyEncoder.encode(mapOf("ip_address" to JsonNull)),
        )
        assertNotEquals(
            ConfirmBodyEncoder.encode(mapOf("ip_address" to JsonNull)),
            ConfirmBodyEncoder.encode(emptyMap()),
        )
    }

    @Test
    fun `an empty body is a valid empty object`() {
        assertEquals("{}", ConfirmBodyEncoder.encode(emptyMap()))
    }

    @Test
    fun `numbers and booleans keep their json types`() {
        val body = buildJsonObject {
            put("screen_width", 390)
            put("java_enabled", false)
            put("amount", "8.98")
        }
        // The amount stays a string: it is a decimal money value in major units and
        // must not pass through a binary float on the way to the gateway.
        assertEquals(
            """{"amount":"8.98","java_enabled":false,"screen_width":390}""",
            ConfirmBodyEncoder.encode(body),
        )
    }

    @Test
    fun `a serializable body is sorted despite its declaration order`() {
        // Declaring the body as a data class must not be a way to opt out of the wire
        // contract — the fields below are declared width, os, language and come out
        // language, os, width.
        assertEquals(
            """{"language":"en-SG","os_type":"ANDROID","screen_width":390}""",
            ConfirmBodyEncoder.encode(
                BrowserInfoFixture.serializer(),
                BrowserInfoFixture(screenWidth = 390, osType = "ANDROID", language = "en-SG"),
            ),
        )
    }

    @Test
    fun `a map and an equivalent serializable value produce the same bytes`() {
        val fromMap = ConfirmBodyEncoder.encode(
            mapOf(
                "os_type" to JsonPrimitive("ANDROID"),
                "screen_width" to JsonPrimitive(390),
                "language" to JsonPrimitive("en-SG"),
            ),
        )
        val fromDto = ConfirmBodyEncoder.encode(
            BrowserInfoFixture.serializer(),
            BrowserInfoFixture(screenWidth = 390, osType = "ANDROID", language = "en-SG"),
        )
        assertEquals(fromMap, fromDto)
    }

    @Test
    fun `unicode field values survive a round trip through the encoder`() {
        // Cardholder names are free text; the replay must resend exactly what was sent.
        val body = buildJsonObject { put("cardholder_name", "Zoë Ω") }
        val encoded = ConfirmBodyEncoder.encode(body)
        // kotlinx.serialization writes non-ASCII as raw UTF-8, never as \uXXXX escapes. The
        // exact form is pinned because the idempotent replay must resend byte-identical
        // bodies: an encoder that switched to escaping would still "contain" the name yet
        // produce different bytes under the same key.
        assertEquals("{\"cardholder_name\":\"Zoë Ω\"}", encoded)
        assertEquals(encoded, ConfirmBodyEncoder.encode(body))
        val decoded = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("Zoë Ω", decoded.getValue("cardholder_name").jsonPrimitive.content)
        assertEquals(body, decoded)
    }

    // ---- Helpers ---------------------------------------------------------------------

    private fun confirmBody(): JsonObject = buildJsonObject {
        put("ip_address", "203.0.113.7")
        put(
            "payment_method",
            buildJsonObject {
                put("type", "card")
                put(
                    "card",
                    buildJsonObject {
                        put("expiry_year", "2030")
                        put("expiry_month", "12")
                    },
                )
            },
        )
        put(
            "browser_info",
            buildJsonObject {
                put("os_type", "ANDROID")
                put("language", "en-SG")
            },
        )
    }

    private fun JsonObjectBuilder.putJsonObjectEntries(
        key: String,
        entries: List<Pair<String, String>>,
    ) {
        put(key, JsonObject(entries.associate { it.first to JsonPrimitive(it.second) }))
    }
}
