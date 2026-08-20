package com.uqpay.sdk.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What is being confirmed: the payment method, its values, and the payment it belongs to.
 *
 * A payload knows two things and nothing else — how to describe itself as a **digest field
 * list** (its identity, which an idempotency pin is filed under) and how to encode itself
 * as a **confirm body** (the bytes that go on the wire). It owns no networking, no UI and
 * no retry policy; [ConfirmRunner] owns those.
 *
 * ### The intent id is the first field of every identity, always
 *
 * `ConfirmIdempotency` keys pins on the payload digest **alone**, and hands back the stored
 * record's own `paymentIntentId` when it restores one. If two *different* intents could
 * ever produce the same digest — the same customer paying the same amount with the same
 * card twice, which is an entirely ordinary thing to do — the second payment would be
 * confirmed **against the first intent's id, under the first intent's key**. That is a
 * mis-charge, and no amount of care inside the registry can prevent it: the registry is
 * only ever shown a digest.
 *
 * So [digestFields] is `final` and prepends [paymentIntentId] itself. A variant supplies
 * only [methodIdentityFields], and therefore *cannot* forget the intent id, reorder it
 * behind another field, or omit it for one method and not another. iOS reaches the same
 * position by convention — the intent id is written first in a hand-built array under the
 * comment *"Field order is part of the identity — do not reorder"* — and a convention is
 * exactly what a future edit is entitled to break.
 *
 * ### Fixed arity, and why the absent placeholder is not an empty string
 *
 * `ConfirmPayloadIdentity.digest` hashes fields joined by U+001F. Field **count** is not
 * part of that identity, only order and boundaries are, so a *variable-length* field list
 * collides: `digest(listOf())` and `digest(listOf(""))` are the same value. Every identity
 * built here is therefore fixed-arity — fifteen fields for a card, two for a wallet — with
 * a placeholder standing in for an absent optional value.
 *
 * That placeholder is [ABSENT_FIELD] (U+0000), **not** an empty string, and the difference
 * is load-bearing rather than fussy. An absent optional and an empty optional are different
 * bytes on the wire: [ConfirmBodyEncoder] omits a null property entirely and writes an
 * empty one as `"state":""`. Two payloads that encode differently must digest differently,
 * or the second one reuses the first one's pinned key against a changed body — which the
 * gateway rejects rather than replays. U+0000 cannot be typed into a form field, so it can
 * never be confused with a value a customer actually entered.
 *
 * (The execution plan's wording — "absent optional values must be empty-string
 * placeholders" — is wrong on this point and would collide the two cases. The arity half of
 * that rule is right and is honoured; the placeholder half is not.)
 *
 * ### What is deliberately outside the identity
 *
 * The CVC, the digits of the PAN between the BIN and the last four, the card network, the
 * capture and authorisation flags, and the 3-D Secure return URL are all in the **body**
 * but not in the **identity**. The first two are excluded because a digest is written to
 * disk and nothing PAN- or CVC-derived may be at rest (`ConfirmPayloadIdentity`); the rest
 * are excluded to match iOS's frozen field list byte-for-byte.
 *
 * The cost is the one WU-2.1 already recorded and accepted: an edit that changes **only**
 * excluded fields replays the previous key with a changed body, the gateway rejects it,
 * that rejection is a definitive answer, the pin resolves, and the next tap mints a fresh
 * key. One wasted round trip, never a double charge. The network is not a real exposure
 * anyway — it is derived from the BIN, which *is* in the identity.
 *
 * ### Card and CVC values live here and nowhere else
 *
 * A payload is read on **every** send and its values are never copied into the attempt, the
 * pin store, a log line, or an exception message. `ConfirmAttempt` freezes only the device
 * fingerprint and the IP, because those are the parts of a body that change on their own;
 * the card is re-read from this object instead. [Card.toString] is overridden for the same
 * reason — a data class's generated `toString` would print the PAN and the CVC into the
 * first crash report that included it.
 */
internal sealed class ConfirmPayload {

    /** The payment this payload confirms. Always the first field of [digestFields]. */
    abstract val paymentIntentId: String

    /** The wire `payment_method.type`, e.g. `card`, `alipaycn`, `grabpay`. */
    abstract val methodType: String

    /**
     * The identity fields **after** the intent id, fixed-arity, in an order that is frozen.
     *
     * Implementations must never return a list whose length varies with the data — see the
     * class KDoc on [ABSENT_FIELD].
     */
    protected abstract fun methodIdentityFields(): List<String>

    /** The wire `payment_method` object for this variant. */
    protected abstract fun methodObject(): JsonObject

    /**
     * The full identity field list: the intent id, then the method's own fields.
     *
     * Exposed rather than private so tests can assert on arity and on field order without
     * having to reverse a SHA-256 — a digest hides a missing intent id exactly as well as
     * it hides everything else.
     */
    fun digestFields(): List<String> = buildList {
        // Position zero, added here and not by any subclass. See the class KDoc.
        add(paymentIntentId)
        addAll(methodIdentityFields())
    }

    /** The pin identity for this payload. */
    fun digest(): String = ConfirmPayloadIdentity.digest(digestFields())

    /**
     * The confirm body, as canonical JSON.
     *
     * [browserInfo] and [ipAddress] come from the **attempt**, not from the device: they
     * were frozen when the key was minted and must be replayed verbatim, or a replay under
     * that key carries a changed body and is rejected. Everything else is read from this
     * payload right now, which is what keeps the card number and CVC out of anything
     * persistent.
     *
     * Encoded through [ConfirmBodyEncoder] so keys are sorted recursively and nulls are
     * omitted rather than written — the two properties a byte-identical replay depends on.
     */
    fun encodeBody(browserInfo: BrowserInfo, ipAddress: String?): String =
        ConfirmBodyEncoder.encode(
            ConfirmRequestBody.serializer(),
            ConfirmRequestBody(
                paymentMethod = methodObject(),
                browserInfo = browserInfo,
                ipAddress = ipAddress,
            ),
        )

    /**
     * A card payment.
     *
     * The form that collects these values is Slice 4's; this type models only what the
     * confirm needs, so the engine can be finished and tested before any UI exists.
     *
     * @property cardholderName the wire `card_name`. iOS composes it from the first and
     *   last name before building the request, and it is a *separate* identity field from
     *   either — a customer who corrects "Jon" to "John" changes two fields, and both are
     *   in the digest.
     * @property street the **combined** street line. iOS joins address line 1 and line 2
     *   with `", "` before it builds either the identity or the body, so the combining
     *   belongs to the form, not here — one string on the wire, one field in the identity.
     * @property network the card brand (`visa`, `mastercard`, …), lowercase. Not in the
     *   identity: it is a function of the BIN, which is.
     * @property returnUrl when non-null, emits the `three_ds` object. Null on a first card
     *   confirm, exactly as iOS sends it. The three companion 3-D Secure fields are sent as
     *   empty strings to match iOS's `ThreeDsRequest` defaults; whether the gateway wants
     *   them populated client-side is `api-contract.md` UNVERIFIED #3 and belongs to
     *   Slice 4's sandbox run.
     */
    data class Card(
        override val paymentIntentId: String,
        val cardNumber: String,
        val expiryMonth: String,
        val expiryYear: String,
        val cvc: String,
        val cardholderName: String,
        val network: String,
        val billing: ConfirmBilling = ConfirmBilling(),
        val autoCapture: Boolean = true,
        val authorizationType: String = "authorization",
        val threeDsAction: String = "enforce_3ds",
        val returnUrl: String? = null,
    ) : ConfirmPayload() {

        override val methodType: String get() = METHOD_TYPE

        /**
         * Fourteen fields, which with the intent id makes **fifteen**. The order is the
         * shipped iOS SDK's, field for field
         * (`PaymentCardViewController.swift:1280-1296`), and it is frozen: reordering it
         * orphans every pin already on a customer's device, because the digest changes
         * while the payment does not.
         */
        override fun methodIdentityFields(): List<String> = listOf(
            METHOD_TYPE,
            ConfirmPayloadIdentity.cardNumberIdentity(cardNumber),
            expiryMonth,
            expiryYear,
            cardholderName,
            identityOf(billing.firstName),
            identityOf(billing.lastName),
            identityOf(billing.email),
            identityOf(billing.phoneNumber),
            identityOf(billing.countryCode),
            identityOf(billing.state),
            identityOf(billing.city),
            identityOf(billing.street),
            identityOf(billing.postcode),
        )

        override fun methodObject(): JsonObject = buildJsonObject {
            put("type", METHOD_TYPE)
            put(
                "card",
                buildJsonObject {
                    put("card_name", cardholderName)
                    put("card_number", cardNumber)
                    put("expiry_month", expiryMonth)
                    put("expiry_year", expiryYear)
                    put("cvc", cvc)
                    put("network", network)
                    put("billing", billing.toJson())
                    put("auto_capture", autoCapture)
                    put("authorization_type", authorizationType)
                    put("three_ds_action", threeDsAction)
                    returnUrl?.let { put("three_ds", threeDsObject(it)) }
                },
            )
        }

        /**
         * Never renders the card.
         *
         * A `data class` synthesises a `toString` over every property, and this one holds a
         * PAN and a CVC. That string would reach a crash report the first time anything
         * interpolated a payload into an exception message — which is precisely the class of
         * leak this SDK's logging rules exist to prevent.
         */
        override fun toString(): String =
            "ConfirmPayload.Card(intent=$paymentIntentId, network=$network)"

        private fun threeDsObject(url: String): JsonObject = buildJsonObject {
            put("return_url", url)
            // Empty rather than omitted: iOS's `ThreeDsRequest` defaults these to "" and
            // sends them, and the known-working client's bytes are the reference.
            put("acs_response", "")
            put("device_data_collection_res", "")
            put("ds_transaction_id", "")
        }

        internal companion object {
            const val METHOD_TYPE: String = "card"
        }
    }

    /**
     * A wallet payment — Alipay, GrabPay, WeChat Pay, PayNow, and the QR wallets.
     *
     * The identity is `[paymentIntentId, methodType]` and nothing else, matching iOS's
     * `WalletQRConfirm.payloadDigest`. That is safe **only** because wallet details are not
     * customer-editable: every field in them is a constant of the flow (`flow: "qrcode"`,
     * `is_present`, the OS type). The protection that makes it safe is Slice 5's
     * one-confirm latch, keyed `intentId|methodType`, not the digest.
     *
     * **If Slice 5 ever puts a customer-editable value into [details]** — GrabPay's
     * optional `shopper_name` is the live candidate — it must join this field list, or an
     * edit will replay the previous key against a changed body and be rejected.
     *
     * @property details the method-specific object, nested under a key named for the
     *   method exactly as iOS nests it (`{"type":"grabpay","grabpay":{…}}`). Supplied by
     *   Slice 5; an empty object omits the key rather than sending `{}`.
     */
    data class Wallet(
        override val paymentIntentId: String,
        override val methodType: String,
        val details: JsonObject = JsonObject(emptyMap()),
        /**
         * The key the detail object nests under. Almost always [methodType] — but **not**
         * for the Alipay variants, which both nest under `alipay`. Getting this wrong is
         * indistinguishable from sending no details at all.
         */
        val detailsKey: String = methodType,
    ) : ConfirmPayload() {

        override fun methodIdentityFields(): List<String> = listOf(methodType)

        override fun methodObject(): JsonObject = buildJsonObject {
            put("type", methodType)
            if (details.isNotEmpty()) put(detailsKey, details)
        }

        internal companion object {

            /**
             * Builds the confirm payload for a wallet, with the detail object the gateway
             * requires.
             *
             * **A wallet confirm without `flow` is rejected** — `invalid_payment_method`
             * with the message `invalid flow`. Found on the emulator against the live
             * sandbox: every unit test had passed because each one constructed [Wallet]
             * with explicit details, so the default empty object was never exercised on a
             * real request. Mirrors the shipped iOS `WalletQRDescriptor`
             * (`WalletQRDescriptor.swift:122-258`), which sends `flow: "qrcode"` and
             * `is_present: false` for every wallet.
             *
             * `os_type` is **lowercase** here, unlike `browser_info.mobile.os_type` which
             * the gateway requires uppercase. Two fields, same name, opposite conventions —
             * iOS sends `"ios"` in this one and `"IOS"` in the other.
             *
             * Details are deliberately outside [methodIdentityFields]: every value here is a
             * constant of the flow, so it cannot change between a send and its replay. If a
             * merchant-editable field is ever added, it must join the identity.
             */
            fun forMethod(paymentIntentId: String, methodType: String): Wallet {
                val alipayVariant = methodType == "alipaycn" || methodType == "alipayhk"
                return Wallet(
                    paymentIntentId = paymentIntentId,
                    methodType = methodType,
                    details = buildJsonObject {
                        put("flow", "qrcode")
                        if (alipayVariant) put("os_type", "android")
                        put("is_present", false)
                    },
                    detailsKey = if (alipayVariant) "alipay" else methodType,
                )
            }
        }
    }

    internal companion object {

        /**
         * Stands in for an absent optional value in a digest field list. U+0000 NULL.
         *
         * Not an empty string, deliberately — see the class KDoc. An absent optional is
         * omitted from the encoded body and an empty one is written as `""`, so the two
         * produce different bytes under the same key and must produce different digests.
         * U+0000 cannot be entered into any form field, so it can never collide with a real
         * value, and it survives the UTF-8 encoding `ConfirmPayloadIdentity` applies.
         */
        const val ABSENT_FIELD: String = "\u0000"

        /** [value], or [ABSENT_FIELD] when there is no value at all. */
        fun identityOf(value: String?): String = value ?: ABSENT_FIELD
    }
}

/**
 * Billing details for a card confirm. Mirrors iOS `BillingDetails` + `Address`, whose
 * fields are every one optional.
 *
 * Null means *the customer gave no value* and the key is omitted from the body. An empty
 * string means *the customer left the field blank* and `""` is sent. The gateway can tell
 * those apart, so the identity has to as well — see [ConfirmPayload.ABSENT_FIELD].
 */
internal data class ConfirmBilling(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    val countryCode: String? = null,
    val state: String? = null,
    val city: String? = null,
    val street: String? = null,
    val postcode: String? = null,
) {
    /**
     * The wire `billing` object.
     *
     * Nulls are omitted here by hand rather than left to the encoder, because this object
     * is hand-built as a [JsonObject]: writing `JsonNull` would survive
     * [ConfirmBodyEncoder]'s `explicitNulls = false`, which only governs properties of
     * `@Serializable` classes. `address` itself is omitted when it would be empty, matching
     * a Swift optional that was never set.
     */
    fun toJson(): JsonObject = buildJsonObject {
        putIfPresent("first_name", firstName)
        putIfPresent("last_name", lastName)
        putIfPresent("email", email)
        putIfPresent("phone_number", phoneNumber)
        val address = addressJson()
        if (address.isNotEmpty()) put("address", address)
    }

    private fun addressJson(): JsonObject = buildJsonObject {
        putIfPresent("country_code", countryCode)
        putIfPresent("state", state)
        putIfPresent("city", city)
        putIfPresent("street", street)
        putIfPresent("postcode", postcode)
    }
}

/** Writes [key] only when [value] is present; a null is an absent key, never a JSON null. */
private fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    if (value != null) put(key, value)
}

/**
 * The confirm request envelope. Mirrors iOS `ConfirmPaymentIntentRequest`.
 *
 * `payment_method` is a [JsonObject] rather than a sealed hierarchy of typed models: the
 * gateway's shape is one optional field per payment method on a single object, and modelling
 * fifteen mutually-exclusive nullable properties in Kotlin buys nothing that the variant's
 * own [ConfirmPayload.methodObject] does not already give. `ip_address` is a nullable
 * property of a `@Serializable` class precisely so `explicitNulls = false` omits it — a
 * device with no readable interface still gets to try to pay.
 */
@Serializable
private data class ConfirmRequestBody(
    @SerialName("payment_method") val paymentMethod: JsonObject,
    @SerialName("browser_info") val browserInfo: BrowserInfo,
    @SerialName("ip_address") val ipAddress: String?,
)
