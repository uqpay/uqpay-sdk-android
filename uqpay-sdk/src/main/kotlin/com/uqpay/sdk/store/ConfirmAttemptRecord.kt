package com.uqpay.sdk.store

import com.uqpay.sdk.engine.BrowserInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * One confirm attempt: the idempotency key the gateway will match on, the payload identity
 * it belongs to, and the device values frozen with it.
 *
 * ### Why an attempt is a value, and why it is frozen
 *
 * An attempt exists so that a second send of the *same* payment reuses the *same*
 * `x-idempotency-key` and the *same* bytes. The gateway honours a reused key only when the
 * body is unchanged; a reused key with a changed body is rejected outright. That makes
 * every field below a promise: whatever was captured when the key was minted must come
 * back verbatim on every replay, including a replay in a later process after the app was
 * killed mid-confirm.
 *
 * [browserInfo] and [ipAddress] are the parts of a confirm body that change **on their
 * own**, with no customer action — the IP flips leaving Wi-Fi for cellular, the screen
 * dimensions swap on rotation. They are therefore snapshot here at mint time and never
 * re-measured. See `com.uqpay.sdk.engine.BrowserInfo` for the long form of that argument.
 *
 * ### What is deliberately absent
 *
 * The card number and the CVC are **not here and never will be**. They are re-read from
 * the form on every send. An attempt is written to disk, and nothing card-derived is
 * allowed at rest — [payloadDigest] is already reduced to a SHA-256 over BIN-plus-last-4
 * identity fields with the CVC excluded (`ConfirmPayloadIdentity`). If a future change
 * wants to add a field here, the question to answer first is "would I be comfortable
 * reading this out of an unencrypted app-private file?"
 *
 * @property key the `x-idempotency-key` value, an opaque string as far as the server is
 *   concerned. See [newIdempotencyKey] for how a fresh one is minted and
 *   [PersistedConfirmAttempt.toAttempt] for why a restored one is never touched.
 * @property payloadDigest the identity this attempt is pinned under — the output of
 *   `ConfirmPayloadIdentity.digest`, not a `hashCode()`.
 * @property paymentIntentId **carried by the attempt itself, deliberately.** A pin
 *   restored after relaunch must know which payment it belongs to without trusting
 *   whatever the current configuration or session happens to hold at restore time; those
 *   are mutable and, after process death, are whatever the *new* launch put there. Reading
 *   the intent id from a shared holder at restore time would let a pin from payment A be
 *   replayed against payment B. iOS carries it on the attempt for exactly this reason.
 * @property ipAddress the device IP, a **sibling** of [browserInfo] rather than a field
 *   inside it, because that is the shape the confirm request takes: `payment_method`,
 *   `browser_info` and `ip_address` are siblings on the request body. Nullable because a
 *   device with no usable interface still gets to try to pay.
 * @property createdAt epoch milliseconds at mint time. The server's idempotency window is
 *   24h; a pin older than that is dead weight and is dropped when the registry reads it.
 *   Epoch millis and not elapsed-realtime: this value must survive a reboot, and it is
 *   only ever compared against a TTL that the record cap independently backstops, so a
 *   user-settable wall clock cannot turn it into a correctness problem on its own.
 */
internal data class ConfirmAttempt(
    val key: String,
    val payloadDigest: String,
    val paymentIntentId: String,
    val browserInfo: BrowserInfo,
    val ipAddress: String?,
    val createdAt: Long,
) {
    /**
     * Never render an attempt in full. The key is a live credential for replaying a
     * payment and has no business in a log line, a crash report, or an exception message.
     */
    override fun toString(): String =
        "ConfirmAttempt(intent=$paymentIntentId, digest=${payloadDigest.take(8)}…)"

    internal companion object {

        /**
         * Mints a brand-new attempt with a fresh key.
         *
         * Only for a payload that has no live pin. Deciding *whether* a payload has one is
         * the registry's job; this is only the minting half, kept next to the record so
         * the key format is defined in exactly one place.
         */
        fun mint(
            payloadDigest: String,
            paymentIntentId: String,
            browserInfo: BrowserInfo,
            ipAddress: String?,
            createdAt: Long,
        ): ConfirmAttempt = ConfirmAttempt(
            key = newIdempotencyKey(),
            payloadDigest = payloadDigest,
            paymentIntentId = paymentIntentId,
            browserInfo = browserInfo,
            ipAddress = ipAddress,
            createdAt = createdAt,
        )

        /**
         * A fresh `x-idempotency-key`.
         *
         * `UUID.randomUUID().toString()` is **already lowercase on the JVM** —
         * `java.util.UUID.toString()` is specified to emit lowercase hex — and that is
         * load-bearing, not incidental: **UQPAY rejects an uppercase UUID.** Nothing here
         * or anywhere downstream may `uppercase()`, `lowercase()`, normalise, re-format or
         * `UUID.fromString(...)`-round-trip a key. A key is an opaque string; see
         * [PersistedConfirmAttempt.toAttempt].
         */
        fun newIdempotencyKey(): String = UUID.randomUUID().toString()
    }
}

/**
 * A pending attempt in its at-rest form — the thing that survives process death.
 *
 * ### This is a stored format, not a wire format
 *
 * The distinction matters and is easy to get backwards. `ConfirmBodyEncoder` is tuned to
 * match the iOS SDK byte-for-byte because its output goes **on the wire** to a gateway
 * that validates it. This type goes nowhere: it is written to app-private storage by this
 * SDK and read back by this SDK. It has no counterparty, so iOS parity buys nothing here
 * — the `@SerialName`s below are intentionally this codebase's snake_case rather than
 * iOS's Swift-default camelCase.
 *
 * What it does have is a **version-durability** requirement, which is stricter than parity
 * in the one direction that matters: a blob written by *this* SDK version must still
 * decode in *every future* version, and a blob written by a future version must not make
 * this version's reader throw. A merchant's customer can install v1.2, start a payment,
 * have the app killed, take the v1.3 update from the store, and relaunch — the pin written
 * before the update is the only thing standing between them and a second charge. So:
 *
 * - **The `@SerialName`s are frozen.** They are the format. Renaming one silently orphans
 *   every pin in the field: the record fails to decode, the registry sees no pin, the next
 *   tap mints a fresh key against a payment that may already be authorising. A committed
 *   literal-JSON fixture in `ConfirmAttemptRecordTest` is the guard. If that test fails,
 *   the change is wrong — the fixture is not to be updated to match it.
 * - **The reader tolerates unknown keys** ([ConfirmAttemptJson]), so a field added by a
 *   later version does not break an earlier one.
 * - **The writer emits every field explicitly**, defaults and nulls included, so that a
 *   future version is free to make a currently-optional field required without stranding
 *   the blobs already on disk.
 * - **Version identity lives in the storage key** (`com.uqpay.sdk.confirm-pins.v1`), not
 *   in a field here. One marker, not two that can disagree. A change that genuinely cannot
 *   keep decoding v1 blobs takes a new storage key; it does not mutate this one in place.
 *
 * ### On reusing [BrowserInfo] directly
 *
 * The execution plan sketched a separate `BrowserInfoRecord` mirror. It is not built,
 * deliberately. The whole purpose of freezing device values is that a replay re-encodes
 * **byte-identical** JSON; a hand-written mapper between two parallel 15-field structures
 * is precisely the place where a newly added field gets forgotten, and the symptom of that
 * bug is a replay the gateway rejects — the failure this design exists to prevent.
 * Structural reuse makes the store round trip lossless by construction instead of by
 * vigilance.
 *
 * The cost is real and is paid explicitly: [BrowserInfo] now serves two masters, a wire
 * format and a stored format, whose evolution pressures can diverge. That coupling is made
 * **loud rather than silent** by the committed fixture, which pins every nested
 * `browser_info` key — a rename made for wire reasons fails this module's tests, where it
 * can be thought about, rather than failing on a customer's phone after an app update.
 *
 * @property identityDigest the payload identity; the map key the registry files pins under.
 * @property keyValue the idempotency key, stored verbatim and restored verbatim.
 * @property ipAddress defaulted so that a blob predating the field would still decode; the
 *   writer nonetheless always emits it. Tolerant reader, explicit writer.
 */
@Serializable
internal data class PersistedConfirmAttempt(
    @SerialName("identity_digest") val identityDigest: String,
    @SerialName("key_value") val keyValue: String,
    @SerialName("payment_intent_id") val paymentIntentId: String,
    @SerialName("browser_info") val browserInfo: BrowserInfo,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("ip_address") val ipAddress: String? = null,
) {
    /** Never render a record in full; see [ConfirmAttempt.toString]. */
    override fun toString(): String =
        "PersistedConfirmAttempt(intent=$paymentIntentId, digest=${identityDigest.take(8)}…)"
}

/** The at-rest projection of a live attempt. Total, lossless, and boring by design. */
internal fun ConfirmAttempt.toPersisted(): PersistedConfirmAttempt = PersistedConfirmAttempt(
    identityDigest = payloadDigest,
    keyValue = key,
    paymentIntentId = paymentIntentId,
    browserInfo = browserInfo,
    createdAt = createdAt,
    ipAddress = ipAddress,
)

/**
 * Rehydrates a stored record into a live attempt.
 *
 * **The key round-trips byte-identically.** No re-casing, no trimming, no
 * `UUID.fromString` validation, no canonicalisation of any kind — and this is a rule about
 * correctness, not tidiness. The server matches idempotency keys as **opaque strings**: a
 * key that comes back one character different from the one already in flight is a
 * different key, and a different key against a payment that may already be authorising is
 * a double charge. A key that looks malformed is still the key the gateway saw; "fixing"
 * it can only make the replay miss. iOS carries a dedicated restoring initialiser for
 * exactly this reason, to keep the restore path away from any minting-time normalisation.
 *
 * The frozen device values and the record's **own** [PersistedConfirmAttempt.paymentIntentId]
 * come back with it — nothing is re-measured and nothing is read from current state.
 */
internal fun PersistedConfirmAttempt.toAttempt(): ConfirmAttempt = ConfirmAttempt(
    key = keyValue,
    payloadDigest = identityDigest,
    paymentIntentId = paymentIntentId,
    browserInfo = browserInfo,
    ipAddress = ipAddress,
    createdAt = createdAt,
)

/**
 * The JSON codec for the persisted pin blob. Every setting is a durability decision.
 *
 * Deliberately **not** `network.UQPayJson`. That instance is tuned for decoding untrusted
 * gateway responses and is legitimately re-tunable by the network layer's owner:
 * `isLenient` and `coerceInputValues` exist there so a surprising payload degrades instead
 * of throwing, and `explicitNulls = false` changes which keys get written at all. Any of
 * those flipped for a network reason would change the bytes of every pin on every device
 * — a storage format must not be downstream of someone else's debugging convenience.
 *
 * The values below are pinned explicitly even where they match the kotlinx defaults, so a
 * later reader sees intent rather than absence:
 *
 * - `ignoreUnknownKeys = true` — the forward-compatibility half. A blob written by a newer
 *   SDK version, containing a field this version has never heard of, still decodes.
 * - `encodeDefaults = true` — the backward-compatibility half. Writing defaults and nulls
 *   explicitly means a future version may promote an optional field to required without
 *   stranding blobs already on disk.
 * - `explicitNulls = true` — same reason; an absent key and a null key must not be the
 *   same thing to a future reader.
 * - `isLenient = false` — a corrupt blob must fail loudly so the store can discard it
 *   wholesale. Half-parsing a damaged pin is worse than having no pin: a wrong key is a
 *   failed replay, no key is a fresh mint.
 * - `coerceInputValues = false` — never silently substitute a default for a wrong-typed
 *   value. A `created_at` that arrives as garbage must not quietly become `0`, which would
 *   read as a pin from 1970 and be dropped as expired.
 * - `prettyPrint = false` — size, and no reason to invite a debugging flip.
 */
internal object ConfirmAttemptJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        isLenient = false
        coerceInputValues = false
        prettyPrint = false
    }
}
