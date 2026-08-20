package com.uqpay.sdk.engine

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload's two jobs are its identity and its bytes, and both are money.
 *
 * An identity that is too *coarse* — two different payments producing one digest — makes
 * the second payment reuse the first's pinned idempotency key and, worse, be confirmed
 * against the first's intent id. An identity that is too *fine* stops a legitimate replay
 * from replaying. Bytes that are not reproducible make every replay a rejection.
 *
 * The card number used throughout is the universally documented test value `4242…4242`,
 * never a real one, and it appears here partly to prove it does **not** survive into the
 * digest input.
 */
class ConfirmPayloadTest {

    // ---- Fixtures --------------------------------------------------------------------

    private val fullBilling = ConfirmBilling(
        firstName = "Ada",
        lastName = "Lovelace",
        email = "ada@example.com",
        phoneNumber = "+6591234567",
        countryCode = "SG",
        state = "Central",
        city = "Singapore",
        street = "1 Raffles Place, #20-01",
        postcode = "048616",
    )

    private fun card(
        intentId: String = "int_alpha",
        cardNumber: String = "4242424242424242",
        cvc: String = "917",
        billing: ConfirmBilling = fullBilling,
        network: String = "visa",
        returnUrl: String? = null,
    ) = ConfirmPayload.Card(
        paymentIntentId = intentId,
        cardNumber = cardNumber,
        expiryMonth = "12",
        expiryYear = "2030",
        cvc = cvc,
        cardholderName = "Ada Lovelace",
        network = network,
        billing = billing,
        returnUrl = returnUrl,
    )

    private val frozenDevice = BrowserInfo(
        acceptHeader = "*/*",
        browser = BrowserDetails(
            javaEnabled = true,
            javascriptEnabled = true,
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8)",
            cookieEnabled = true,
            plugins = emptyList(),
            doNotTrack = false,
        ),
        deviceId = "device-pinned",
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
    )

    /** What a later process, after a rotation and a network change, would measure. */
    private val relaunchedDevice = frozenDevice.copy(screenHeight = 1080, screenWidth = 2400)

    // ---- The hard requirement: the intent id is in the identity -----------------------

    /**
     * **The single most important test in this file.**
     *
     * The registry keys pins on the digest alone and restores the record's *own* intent id.
     * The same customer paying the same amount with the same card twice — an ordinary
     * thing to do — produces two intents and two byte-identical sets of card details. If
     * those digested the same, the second payment would be confirmed under the first
     * payment's key **and against the first payment's intent id**. That is a mis-charge,
     * and it is not detectable anywhere downstream: the registry is only ever shown a
     * digest.
     */
    @Test
    fun `two intents with byte-identical card details digest differently`() {
        val first = card(intentId = "int_alpha")
        val second = card(intentId = "int_beta")

        assertEquals(
            "the fixture is only meaningful if everything but the intent id is identical",
            first.digestFields().drop(1),
            second.digestFields().drop(1),
        )
        assertNotEquals(first.digest(), second.digest())
    }

    /** Same rule, wallet side. The wallet identity is only two fields, so it has less to hide behind. */
    @Test
    fun `two intents paid through the same wallet digest differently`() {
        val first = ConfirmPayload.Wallet("int_alpha", "grabpay")
        val second = ConfirmPayload.Wallet("int_beta", "grabpay")

        assertNotEquals(first.digest(), second.digest())
    }

    /** One intent, two wallets: an Alipay screen must never replay a GrabPay attempt's key. */
    @Test
    fun `two wallets on one intent digest differently`() {
        assertNotEquals(
            ConfirmPayload.Wallet("int_alpha", "alipaycn").digest(),
            ConfirmPayload.Wallet("int_alpha", "grabpay").digest(),
        )
    }

    /** The intent id is at position zero, for every variant, by construction. */
    @Test
    fun `the intent id is the first identity field of every payload`() {
        assertEquals("int_alpha", card().digestFields().first())
        assertEquals("int_alpha", ConfirmPayload.Wallet("int_alpha", "paynow").digestFields().first())
    }

    // ---- Fixed arity, and the absent-versus-empty distinction -------------------------

    /**
     * Fifteen fields, in the order the shipped iOS SDK froze
     * (`PaymentCardViewController.swift:1280-1296`, *"Field order is part of the identity —
     * do not reorder"*).
     *
     * Asserted as a whole list rather than by length: a length check would pass a list that
     * had swapped `city` and `state`, and a swap orphans every pin already written to a
     * customer's device.
     */
    @Test
    fun `the card identity is the fifteen iOS fields in the iOS order`() {
        assertEquals(
            listOf(
                "int_alpha",
                "card",
                "424242:4242",
                "12",
                "2030",
                "Ada Lovelace",
                "Ada",
                "Lovelace",
                "ada@example.com",
                "+6591234567",
                "SG",
                "Central",
                "Singapore",
                "1 Raffles Place, #20-01",
                "048616",
            ),
            card().digestFields(),
        )
    }

    /** Two fields, matching iOS's `WalletQRConfirm.payloadDigest`. */
    @Test
    fun `the wallet identity is the intent id and the method type`() {
        assertEquals(
            listOf("int_alpha", "grabpay"),
            ConfirmPayload.Wallet("int_alpha", "grabpay").digestFields(),
        )
    }

    /**
     * A card with no billing details at all still produces fifteen fields.
     *
     * A shortened list is the collision `digest(listOf())` == `digest(listOf(""))` waiting
     * to happen: field *count* is not part of the identity, only order and boundaries are.
     */
    @Test
    fun `an empty billing block still produces fifteen identity fields`() {
        assertEquals(15, card(billing = ConfirmBilling()).digestFields().size)
        assertEquals(15, card().digestFields().size)
    }

    /**
     * **Absent is not empty**, and the difference is visible on the wire.
     *
     * A null optional is omitted from the body; an empty one is sent as `""`. Those are
     * different bytes under the same idempotency key, and a gateway rejects a reused key
     * whose body changed rather than replaying it. So they must digest differently — which
     * is why the placeholder for an absent value is U+0000 and not `""`.
     */
    @Test
    fun `an absent optional and an empty optional digest differently`() {
        val absent = card(billing = ConfirmBilling(state = null))
        val empty = card(billing = ConfirmBilling(state = ""))

        assertNotEquals(absent.digest(), empty.digest())
    }

    /** The wire fact the test above depends on. If this changes, that rule changes with it. */
    @Test
    fun `an absent optional is omitted from the body and an empty one is sent`() {
        val absent = card(billing = ConfirmBilling(state = null)).encodeBody(frozenDevice, "10.0.0.1")
        val empty = card(billing = ConfirmBilling(state = "")).encodeBody(frozenDevice, "10.0.0.1")

        assertFalse("a null optional writes no key at all", absent.contains("\"state\""))
        assertTrue("an empty optional writes an empty value", empty.contains("\"state\":\"\""))
        assertNotEquals(absent, empty)
    }

    /** An address with nothing in it is omitted entirely rather than sent as `{}`. */
    @Test
    fun `an empty address object is omitted rather than sent empty`() {
        val body = card(billing = ConfirmBilling(firstName = "Ada")).encodeBody(frozenDevice, null)

        assertFalse(body.contains("\"address\""))
        assertTrue(body.contains("\"first_name\":\"Ada\""))
    }

    /** A device with no readable interface still gets to try to pay. */
    @Test
    fun `a null ip address is omitted from the body`() {
        assertFalse(card().encodeBody(frozenDevice, null).contains("ip_address"))
        assertTrue(card().encodeBody(frozenDevice, "10.0.0.1").contains("\"ip_address\":\"10.0.0.1\""))
    }

    // ---- Every identity field actually participates -----------------------------------

    /**
     * Each of the fourteen editable identity fields changes the digest on its own.
     *
     * Written as a sweep rather than fourteen tests because the failure it guards against is
     * a field being dropped from the list, and a sweep cannot be partially forgotten. A
     * dropped field means the customer's correction is sent under the previous attempt's
     * key — which the gateway rejects, stranding a payment that was only ever a typo.
     */
    @Test
    fun `editing any identity field changes the digest`() {
        val baseline = card().digest()
        val edits: List<Pair<String, ConfirmPayload.Card>> = listOf(
            "intent id" to card(intentId = "int_other"),
            "card number BIN" to card(cardNumber = "5555424242424242"),
            "card number last four" to card(cardNumber = "4242424242421111"),
            "expiry month" to card().copy(expiryMonth = "01"),
            "expiry year" to card().copy(expiryYear = "2031"),
            "cardholder name" to card().copy(cardholderName = "A Lovelace"),
            "first name" to card(billing = fullBilling.copy(firstName = "Augusta")),
            "last name" to card(billing = fullBilling.copy(lastName = "Byron")),
            "email" to card(billing = fullBilling.copy(email = "ada@example.org")),
            "phone" to card(billing = fullBilling.copy(phoneNumber = "+6598765432")),
            "country" to card(billing = fullBilling.copy(countryCode = "MY")),
            "state" to card(billing = fullBilling.copy(state = "West")),
            "city" to card(billing = fullBilling.copy(city = "Jurong")),
            "street" to card(billing = fullBilling.copy(street = "2 Raffles Place")),
            "postcode" to card(billing = fullBilling.copy(postcode = "048617")),
        )

        edits.forEach { (what, edited) ->
            assertNotEquals("editing the $what must change the identity", baseline, edited.digest())
        }
        // Every edit is also distinct from every other one: no two corrections collide.
        assertEquals(edits.size, edits.map { it.second.digest() }.toSet().size)
    }

    /**
     * The accepted cost, asserted so it stays a decision rather than a surprise.
     *
     * The CVC and the middle digits of the PAN are excluded from the identity because a
     * digest is written to disk and nothing PAN- or CVC-derived may be at rest. The card
     * network is excluded to match iOS's frozen list, and is in any case a function of the
     * BIN, which is included.
     *
     * What that costs: an edit to only these replays the previous key with a changed body,
     * the gateway rejects it, the pin resolves on that definitive answer, and the next tap
     * mints fresh. One wasted round trip — never a double charge.
     */
    @Test
    fun `the cvc, the middle pan digits and the network are outside the identity`() {
        val baseline = card().digest()

        assertEquals("the CVC never enters an identity", baseline, card(cvc = "999").digest())
        assertEquals(
            "digits between the BIN and the last four are not in the identity",
            baseline,
            card(cardNumber = "4242429999994242").digest(),
        )
        assertEquals("the network follows the BIN", baseline, card(network = "mastercard").digest())
        assertEquals(
            "the 3-D Secure return URL is not in the identity",
            baseline,
            card(returnUrl = "https://merchant.example/return").digest(),
        )
    }

    // ---- Nothing card-derived leaves this class -------------------------------------

    /**
     * The bytes that are hashed are inspected directly, because a digest hides a full PAN
     * exactly as well as it hides everything else — a test that could only see the output
     * could not prove the input was safe.
     */
    @Test
    fun `the identity input never contains the full pan or the cvc`() {
        val canonical = ConfirmPayloadIdentity.canonicalString(card().digestFields())

        assertFalse("no full PAN", canonical.contains("4242424242424242"))
        assertFalse("no CVC", canonical.contains("917"))
        assertTrue("BIN and last four only", canonical.contains("424242:4242"))
    }

    /**
     * A `data class` synthesises a `toString` over every property, and this one holds a PAN
     * and a CVC. The override is what stops the first exception message that interpolates a
     * payload from putting a card number into a crash report.
     */
    @Test
    fun `rendering a card payload never reveals the pan or the cvc`() {
        val rendered = card().toString()

        assertFalse(rendered.contains("4242424242424242"))
        assertFalse(rendered.contains("4242"))
        assertFalse(rendered.contains("917"))
        assertTrue(rendered.contains("int_alpha"))
    }

    // ---- The bytes -------------------------------------------------------------------

    /**
     * The precondition for the whole replay ladder: the same logical body encodes to the
     * same bytes, every time. A gateway honours a reused idempotency key only for unchanged
     * content.
     */
    @Test
    fun `encoding the same payload twice is byte-identical`() {
        val payload = card()

        assertEquals(
            payload.encodeBody(frozenDevice, "10.0.0.1"),
            payload.encodeBody(frozenDevice, "10.0.0.1"),
        )
        assertEquals(
            "and so is encoding an equal payload built separately",
            card().encodeBody(frozenDevice, "10.0.0.1"),
            payload.encodeBody(frozenDevice, "10.0.0.1"),
        )
    }

    /**
     * Device values come from the **argument**, never from anything this object holds.
     *
     * They are frozen in the attempt at mint time precisely because they change on their
     * own — the screen dimensions swap on rotation — and a replay that re-measured them
     * would carry a changed body under an unchanged key.
     */
    @Test
    fun `the body is built from the device values it is handed`() {
        assertNotEquals(
            card().encodeBody(frozenDevice, "10.0.0.1"),
            card().encodeBody(relaunchedDevice, "10.0.0.1"),
        )
        assertNotEquals(
            card().encodeBody(frozenDevice, "10.0.0.1"),
            card().encodeBody(frozenDevice, "192.168.1.42"),
        )
    }

    /** Sorted keys, recursively, so a future field reorder cannot change a replay's bytes. */
    @Test
    fun `body keys are sorted at every depth`() {
        val body = card().encodeBody(frozenDevice, "10.0.0.1")

        assertTrue(
            "top level: browser_info, ip_address, payment_method",
            body.indexOf("\"browser_info\"") < body.indexOf("\"ip_address\"") &&
                body.indexOf("\"ip_address\"") < body.indexOf("\"payment_method\""),
        )
        assertTrue(
            "inside the card object: auto_capture before card_name before cvc",
            body.indexOf("\"auto_capture\"") < body.indexOf("\"card_name\"") &&
                body.indexOf("\"card_name\"") < body.indexOf("\"cvc\""),
        )
        assertTrue(
            "inside the address: city before country_code before postcode",
            body.indexOf("\"city\"") < body.indexOf("\"country_code\"") &&
                body.indexOf("\"country_code\"") < body.indexOf("\"postcode\""),
        )
    }

    /** The wire shape iOS posts, method field for method field. */
    @Test
    fun `the card body mirrors the iOS confirm request`() {
        val body = card().encodeBody(frozenDevice, "10.0.0.1")

        assertTrue(body.contains("\"type\":\"card\""))
        assertTrue(body.contains("\"card_number\":\"4242424242424242\""))
        assertTrue(body.contains("\"expiry_month\":\"12\""))
        assertTrue(body.contains("\"expiry_year\":\"2030\""))
        assertTrue(body.contains("\"cvc\":\"917\""))
        assertTrue(body.contains("\"network\":\"visa\""))
        assertTrue(body.contains("\"auto_capture\":true"))
        assertTrue(body.contains("\"authorization_type\":\"authorization\""))
        assertTrue(body.contains("\"three_ds_action\":\"enforce_3ds\""))
        assertTrue(body.contains("\"phone_number\":\"+6591234567\""))
        assertTrue(body.contains("\"country_code\":\"SG\""))
        assertTrue(body.contains("\"street\":\"1 Raffles Place, #20-01\""))
        assertTrue(body.contains("\"postcode\":\"048616\""))
        // iOS sends no `three_ds` on a first card confirm.
        assertFalse(body.contains("three_ds\":"))
    }

    /** 3-D Secure fields appear only once Slice 4 supplies a return URL. */
    @Test
    fun `the three ds object appears only with a return url`() {
        val body = card(returnUrl = "https://merchant.example/return").encodeBody(frozenDevice, null)

        assertTrue(body.contains("\"return_url\":\"https://merchant.example/return\""))
        // Empty rather than absent, matching iOS's ThreeDsRequest defaults.
        assertTrue(body.contains("\"acs_response\":\"\""))
        assertTrue(body.contains("\"device_data_collection_res\":\"\""))
        assertTrue(body.contains("\"ds_transaction_id\":\"\""))
    }

    /** A wallet nests its details under a key named for the method, exactly as iOS does. */
    @Test
    fun `a wallet body nests its details under the method type`() {
        val payload = ConfirmPayload.Wallet(
            paymentIntentId = "int_alpha",
            methodType = "grabpay",
            details = buildJsonObject {
                put("flow", JsonPrimitive("qrcode"))
                put("is_present", JsonPrimitive(false))
            },
        )

        val body = payload.encodeBody(frozenDevice, "10.0.0.1")

        assertTrue(body.contains("\"type\":\"grabpay\""))
        assertTrue(body.contains("\"grabpay\":{\"flow\":\"qrcode\",\"is_present\":false}"))
    }

    /** No details means no key, rather than an empty object the gateway has no rule for. */
    @Test
    fun `a wallet with no details sends only its type`() {
        val body = ConfirmPayload.Wallet("int_alpha", "paynow").encodeBody(frozenDevice, null)

        assertTrue(body.contains("\"payment_method\":{\"type\":\"paynow\"}"))
    }

    // --- forMethod: the detail object the gateway actually requires ------------------

    /**
     * A wallet confirm without `flow` is rejected by the live gateway with
     * `invalid_payment_method` / "invalid flow". This was shipped and only found on the
     * emulator: every other test in this file builds [ConfirmPayload.Wallet] with explicit
     * details, so the default empty object was never sent on a real request.
     */
    @Test
    fun `a wallet built by forMethod carries the flow the gateway requires`() {
        val body = ConfirmPayload.Wallet.forMethod("int_1", "grabpay").encodeBody(frozenDevice, "1.2.3.4")

        assertTrue("flow must be present", body.contains(""""flow":"qrcode""""))
        assertTrue("is_present must be present", body.contains(""""is_present":false"""))
        assertTrue("details nest under the method type", body.contains(""""grabpay":{"""))
        assertTrue("type is still sent", body.contains(""""type":"grabpay""""))
    }

    /** The default constructor still sends no details — forMethod is the supported path. */
    @Test
    fun `the bare constructor sends no details, which is why forMethod exists`() {
        val bare = ConfirmPayload.Wallet("int_1", "grabpay").encodeBody(frozenDevice, "1.2.3.4")
        assertFalse("bare wallet has no flow", bare.contains("flow"))
    }

    /**
     * Both Alipay variants nest their details under `alipay`, not under their own method
     * type, and carry a LOWERCASE `os_type` — the opposite convention to
     * `browser_info.mobile.os_type`, which the gateway requires uppercase. Two fields, one
     * name, opposite rules.
     */
    @Test
    fun `alipay variants nest under alipay and send a lowercase os type`() {
        listOf("alipaycn", "alipayhk").forEach { method ->
            val body = ConfirmPayload.Wallet.forMethod("int_1", method).encodeBody(frozenDevice, "1.2.3.4")
            assertTrue("$method nests under alipay", body.contains(""""alipay":{"""))
            assertFalse("$method must not nest under itself", body.contains(""""$method":{"""))
            assertTrue("$method keeps its own type", body.contains(""""type":"$method""""))
            assertTrue("$method sends lowercase os_type", body.contains(""""os_type":"android""""))
        }
    }

    /** Every other wallet nests under its own type and sends no os_type. */
    @Test
    fun `non-alipay wallets nest under their own type`() {
        listOf("grabpay", "paynow", "tng", "gcash", "wechatpay").forEach { method ->
            val body = ConfirmPayload.Wallet.forMethod("int_1", method).encodeBody(frozenDevice, "1.2.3.4")
            assertTrue("$method nests under itself", body.contains(""""$method":{"""))
            // Scoped to the LOWERCASE value on purpose: `browser_info.mobile.os_type` is
            // always present and is uppercase "ANDROID". A bare contains("os_type") would
            // match that and pass for the wrong reason — it did, on the first run.
            assertFalse(
                "$method must not send a wallet os_type",
                body.contains(""""os_type":"android""""),
            )
        }
    }

    /**
     * Details are constants of the flow, so they stay outside the identity — a replay must
     * reproduce them byte-identically without them contributing to the digest.
     */
    @Test
    fun `forMethod details do not change the payload identity`() {
        val bare = ConfirmPayload.Wallet("int_1", "grabpay")
        val built = ConfirmPayload.Wallet.forMethod("int_1", "grabpay")
        assertEquals(bare.digestFields(), built.digestFields())
    }
}
