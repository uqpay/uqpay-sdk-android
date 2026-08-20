package com.uqpay.sdk.engine

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The encoder for every confirm body. Canonical JSON: keys sorted, recursively.
 *
 * A replay of an unresolved attempt resends the **same** `x-idempotency-key`, and an
 * idempotent gateway honours a reused key only for byte-identical content — a changed
 * body under a reused key is rejected, not replayed. So "the same logical body encodes to
 * the same bytes" is not tidiness, it is the precondition for the entire retry ladder.
 *
 * Sorted keys rather than declaration order, deliberately. kotlinx.serialization *is*
 * declaration-order deterministic within a single binary, which is enough for a retry
 * inside one session and not enough for the case that matters: a pin persisted by SDK
 * 1.2 and replayed after the app updates to 1.3, where a field was reordered or inserted
 * in between. Declaration order is an implementation detail that a future refactor is
 * entitled to change; sorted keys is a contract that a future refactor cannot break by
 * accident. iOS reached the same conclusion and encodes with `.sortedKeys`.
 *
 * Sorting is recursive because nesting is where the guarantee would quietly leak — the
 * confirm body's `payment_method` and `browser_info` are objects, and an unsorted nested
 * object is exactly as fatal as an unsorted top level. Array order is preserved: an array
 * is ordered by definition, and reordering one would change meaning, not just bytes.
 *
 * The [Json] instance is owned here rather than shared with `network.UQPayJson`. That one
 * is tuned for *decoding* server payloads and may legitimately be re-tuned; this one's
 * output is a wire commitment, and it must not be possible to change the bytes a
 * persisted pin replays by adjusting a parser setting somewhere else.
 */
internal object ConfirmBodyEncoder {

    /**
     * Tuned to reproduce Swift's synthesized `Codable` output exactly, because the shipped
     * iOS SDK is the only client verified to work against this gateway.
     *
     * - `explicitNulls = false` — a null property is **omitted**, not sent as `null`.
     *   Swift's synthesized `encode(to:)` uses `encodeIfPresent` for optionals, so iOS omits
     *   `fonts`, `webgl_vendor`, and `webgl_renderer`. Sending explicit nulls where the
     *   known-working client sends nothing is an unforced divergence on a payment confirm.
     * - `encodeDefaults = true` — a non-null property with a default **is** sent. This also
     *   matches Swift, where `touch_support`, `cookie_enabled`, `plugins`, and
     *   `do_not_track` reach the wire from their struct defaults.
     *
     * Both settings are part of the wire commitment: changing either changes the bytes that
     * every persisted idempotency pin replays, and a replayed key with a changed body is
     * rejected by the gateway rather than honoured. Do not adjust these to make a test pass.
     */
    private val json: Json = Json {
        prettyPrint = false
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Encodes [body] as canonical JSON.
     *
     * Accepts any `Map<String, JsonElement>` — `JsonObject` is one, so a body built with
     * `buildJsonObject { … }` passes straight through, and so does a plain map assembled
     * field by field. The input's own iteration order is irrelevant by construction:
     * two maps holding the same entries in different orders produce the identical string.
     */
    fun encode(body: Map<String, JsonElement>): String =
        json.encodeToString(JsonObject.serializer(), sortedObject(body))

    /**
     * Encodes a `@Serializable` value as canonical JSON.
     *
     * The value is turned into a tree first and sorted on the way out, so a DTO-shaped
     * confirm body gets the same guarantee as a hand-built one — declaring the body as a
     * data class must not be a way to opt out of the wire contract.
     */
    fun <T> encode(serializer: SerializationStrategy<T>, value: T): String =
        encode(json.encodeToJsonElement(serializer, value).jsonObject)

    /** [element] with every object key sorted, at every depth. */
    private fun canonicalise(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> sortedObject(element)
        // Positional, so order is meaning: sort the contents, never the elements.
        is JsonArray -> JsonArray(element.map(::canonicalise))
        else -> element
    }

    /**
     * Sorted by the natural ordering of the key strings — UTF-16 code unit order, which
     * is what every other implementation of "sorted keys" means and, for the ASCII field
     * names UQPAY uses, is unambiguous and locale-independent. A locale-aware collator
     * here would make the bytes depend on the customer's device settings.
     */
    private fun sortedObject(source: Map<String, JsonElement>): JsonObject {
        val sorted = LinkedHashMap<String, JsonElement>(source.size)
        source.keys.sorted().forEach { key ->
            sorted[key] = canonicalise(source.getValue(key))
        }
        return JsonObject(sorted)
    }
}
