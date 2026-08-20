package com.uqpay.sdk.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.Enumeration
import java.util.TimeZone

/**
 * The device fingerprint must describe the real device, and must keep describing the
 * same one for as long as an idempotency key is unresolved.
 *
 * Two classes of defect are pinned here. The first is fabricated data — the iOS SDK
 * shipped `1920x1080`, `"iOS 14.5"` and lowercase `os_type` and had to remove all three,
 * the last of which was a hard 400 from the confirm endpoint. The second is drift: these
 * values change with no customer action, which is precisely why they are frozen with the
 * attempt rather than re-read per send.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceInfoTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Matches the production canonical encoder's posture: nulls are omitted, not sent. */
    private val json = Json { encodeDefaults = false }

    // --- The wire contract iOS proved with a 400 -------------------------------------

    /**
     * The confirm endpoint validates `os_type` strictly and rejects lowercase. iOS sends
     * `"IOS"`; there is no reason to believe `"android"` would fare better than `"ios"`
     * did.
     */
    @Test
    fun `os type is uppercase as the API requires`() {
        val info = DeviceInfo.currentDevice(context)

        assertEquals("ANDROID", info.mobile.osType)
        assertEquals("ANDROID", DeviceInfo.OS_TYPE)
    }

    /**
     * Hours, not minutes: the 3DS integration guide samples `"timezone": "-2"`, and iOS
     * encodes `secondsFromGMT() / 3600`.
     */
    @Test
    fun `timezone is whole hours for a whole-hour zone`() {
        assertEquals(9, hoursIn("Asia/Tokyo"))
        assertEquals(0, hoursIn("UTC"))
        assertEquals(-5, hoursIn("America/Panama"))
    }

    /**
     * Half-hour and quarter-hour zones cannot be expressed in the documented shape and
     * **truncate toward zero** — the same result Swift's integer division produces, which
     * is the whole point: both SDKs must encode a Colombo customer identically.
     *
     * If someone "fixes" this to round, UTC+5:30 becomes `6` on Android and stays `5` on
     * iOS, and the two platforms start describing the same customer differently.
     */
    @Test
    fun `timezone truncates toward zero for half-hour zones`() {
        assertEquals(5, hoursIn("Asia/Colombo"))   // UTC+5:30
        assertEquals(5, hoursIn("Asia/Kolkata"))   // UTC+5:30
        assertEquals(5, hoursIn("Asia/Kathmandu")) // UTC+5:45
        // Negative offsets truncate toward zero too, not downward: -3:30 is -3, not -4.
        assertEquals(-3, hoursIn("America/St_Johns"))
    }

    /** The captured value is a string, and it is the offset actually in force. */
    @Test
    fun `captured timezone is the offset in force rendered as a string`() {
        val expected = DeviceInfo.timezoneOffsetHours().toString()

        assertEquals(expected, DeviceInfo.currentDevice(context).timezone)
    }

    /** A canned user agent for the wrong OS reads as spoofing to a risk engine. */
    @Test
    fun `user agent describes an Android device and agrees with the model`() {
        val info = DeviceInfo.currentDevice(context)

        assertTrue(info.browser.userAgent.contains("Android"))
        assertTrue(info.browser.userAgent.contains(info.mobile.deviceModel))
        // Regression: iOS shipped one hardcoded "iPhone OS 14_5" for every device.
        assertFalse(info.browser.userAgent.contains("iPhone"))
        assertFalse(info.browser.userAgent.contains("14_5"))
    }

    /** Regression guard for the fabricated defaults iOS had to delete. */
    @Test
    fun `nothing is fabricated`() {
        val info = DeviceInfo.currentDevice(context)

        assertFalse(info.screenWidth == 1920 && info.screenHeight == 1080)
        assertTrue(info.mobile.osVersion.startsWith("Android "))
        assertFalse(info.mobile.osVersion.contains("14.5"))
        assertTrue(info.deviceId.isNotBlank())
        assertTrue(info.language.isNotBlank())
        assertEquals(24, info.screenColorDepth)
        assertTrue((info.hardwareConcurrency ?: 0) >= 1)
        assertTrue((info.deviceMemory ?: 0) >= 1)
    }

    /** A plain BCP 47 tag: the API rejects extension subtags with "language is invalid". */
    @Test
    fun `language is a plain BCP 47 tag with no extension subtags`() {
        val language = DeviceInfo.currentDevice(context).language

        assertFalse(language.contains("@"))
        assertFalse(language.contains("_"))
        assertFalse(language.contains("-u-"))
        assertTrue(language.matches(Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,4})?")))
    }

    // --- Why the values are frozen ---------------------------------------------------

    /**
     * **This is the test that justifies the whole design.**
     *
     * Rotating the handset changes `screen_width` / `screen_height` with no customer
     * action at all. If the confirm body were rebuilt from a live read on every send, a
     * replay after rotation would carry a *different* body under the *same* idempotency
     * key — and the gateway rejects a reused key whose payload changed rather than
     * honouring it. The replay fails, the pin stays, and the next tap mints a fresh key
     * against a payment that may already be authorising.
     *
     * Freezing [BrowserInfo] with the attempt is what makes that impossible. The
     * end-to-end assertion lives in the confirm runner's tests; this one proves the
     * premise: the values really do move underneath us.
     */
    @Test
    fun `rotation changes the screen metrics - which is why they are frozen`() {
        val portrait = DeviceInfo.currentDevice(context)

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        metrics.widthPixels = height
        metrics.heightPixels = width
        try {
            val landscape = DeviceInfo.currentDevice(context)

            assertNotEquals(portrait.screenWidth, landscape.screenWidth)
            assertNotEquals(portrait.screenHeight, landscape.screenHeight)
            assertEquals(portrait.screenWidth, landscape.screenHeight)
            assertEquals(portrait.screenHeight, landscape.screenWidth)
            // A frozen snapshot taken before the rotation is untouched by it.
            assertEquals(width, portrait.screenWidth)
        } finally {
            metrics.widthPixels = width
            metrics.heightPixels = height
        }
    }

    /** A snapshot holds no Context, so it is safe to persist and to hold for 24h. */
    @Test
    fun `a snapshot is a value and survives serialization round trip`() {
        val info = DeviceInfo.currentDevice(context)

        val restored = json.decodeFromString(BrowserInfo.serializer(), json.encodeToString(BrowserInfo.serializer(), info))

        assertEquals(info, restored)
    }

    // --- IP address ------------------------------------------------------------------

    /**
     * The API produces no wallet QR without `ip_address`, so this lookup runs on every
     * confirm — and it must never be the thing that fails one. A device with no
     * interfaces up returns null from the platform enumeration; others throw.
     */
    @Test
    fun `ip lookup returns null rather than throwing when no interface is available`() {
        assertNull(DeviceInfo.currentIpAddress { null })
        assertNull(DeviceInfo.currentIpAddress { emptyInterfaces() })
        assertNull(DeviceInfo.currentIpAddress { throw SocketException("network is down") })
        assertNull(DeviceInfo.currentIpAddress { error("anything at all") })
    }

    /** Loopback identifies no device to a risk engine; it is not a usable answer. */
    @Test
    fun `loopback and link-local addresses are never reported`() {
        val selected = DeviceInfo.selectDeviceIpAddress(
            listOf(
                address("lo", "127.0.0.1"),
                address("lo", "::1"),
                address("wlan0", "169.254.11.4"),
                address("wlan0", "fe80::1"),
            ),
        )

        assertNull(selected)
    }

    /** IPv4 first, Wi-Fi before cellular — mirroring iOS's `en0` then `pdp_ip0` order. */
    @Test
    fun `prefers a non-loopback IPv4 address on the wifi interface`() {
        val selected = DeviceInfo.selectDeviceIpAddress(
            listOf(
                address("lo", "127.0.0.1"),
                address("rmnet_data0", "10.4.5.6"),
                address("wlan0", "2001:db8::1"),
                address("wlan0", "192.168.1.42"),
            ),
        )

        assertEquals("192.168.1.42", selected)
    }

    /** With no IPv4 anywhere, an IPv6 address is still better than no address at all. */
    @Test
    fun `falls back to IPv6 and strips the scope suffix`() {
        val selected = DeviceInfo.selectDeviceIpAddress(
            listOf(address("wlan0", "2001:db8::1")),
        )

        assertNotNull(selected)
        assertFalse(selected!!.contains("%"))
        assertFalse(selected.contains("fe80"))
    }

    /** The real platform call must not throw on this machine either. */
    @Test
    fun `real interface lookup never throws`() {
        DeviceInfo.currentIpAddress()
    }

    // --- Privacy ---------------------------------------------------------------------

    /**
     * The snapshot is written to disk as part of a persisted confirm attempt, so its
     * contents are audited: coarse device metrics only. No card data (there is none here
     * by construction), and no location — the field is absent from the type entirely,
     * because the SDK never takes a fix and the API rejects `lat`/`lon` of `"0"`.
     */
    @Test
    fun `serialized form carries nothing beyond coarse device metrics`() {
        // Encoded through the PRODUCTION encoder, not a local Json instance. A local
        // instance would assert this type's shape under a posture the SDK does not
        // actually use — the test would pass while the wire bytes diverged from iOS.
        val encoded = ConfirmBodyEncoder.encode(
            BrowserInfo.serializer(),
            DeviceInfo.currentDevice(context),
        )
        val keys = (json.parseToJsonElement(encoded) as JsonObject).keys

        assertEquals(
            setOf(
                "accept_header", "browser", "device_id", "language", "mobile",
                "screen_color_depth", "screen_height", "screen_width", "timezone",
                "touch_support", "hardware_concurrency", "device_memory",
            ),
            keys,
        )

        // Location is not merely null — it does not exist.
        listOf("location", "lat", "lon", "accuracy", "latitude", "longitude").forEach {
            assertFalse("`$it` must never appear in browser_info", encoded.contains(it))
        }
        // Nothing card-derived, nothing that identifies a person, no telephony read.
        listOf(
            "card", "number", "cvc", "cvv", "expiry", "pan", "token", "secret",
            "email", "phone", "msisdn", "carrier", "imei", "serial", "advertising",
        ).forEach {
            assertFalse("`$it` must never appear in browser_info", encoded.contains(it))
        }
        assertNull(DeviceInfo.currentDevice(context).mobile.carrier)
    }

    // --- helpers ---------------------------------------------------------------------

    private fun hoursIn(zoneId: String): Int =
        DeviceInfo.timezoneOffsetHours(
            timeZone = TimeZone.getTimeZone(zoneId),
            // A fixed January instant, so a DST transition cannot move an assertion.
            atEpochMillis = 1_767_225_600_000L, // 2026-01-01T00:00:00Z
        )

    private fun address(interfaceName: String, literal: String) =
        DeviceIpCandidate(interfaceName, InetAddress.getByName(literal))

    private fun emptyInterfaces(): Enumeration<NetworkInterface> =
        Collections.enumeration(emptyList())
}
