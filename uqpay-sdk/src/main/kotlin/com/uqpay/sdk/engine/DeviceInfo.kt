package com.uqpay.sdk.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * The device fingerprint sent as `browser_info` on every confirm.
 *
 * ### Why this is a frozen value type
 *
 * These values are captured **once, when an idempotency key is minted**, and replayed
 * byte-identically with that key for as long as the attempt is unresolved. They are the
 * only parts of a confirm body that change *on their own*, with no customer action: the
 * IP flips the moment the phone leaves Wi-Fi for cellular, and `screen_width` /
 * `screen_height` swap the moment the customer rotates the handset. Re-reading them per
 * send would produce a different body under the same key — and the gateway **rejects** a
 * reused idempotency key whose payload changed, it does not honour it. The replay would
 * fail, and the customer's second tap would mint a fresh key against a payment that may
 * already be authorising. Freezing removes the whole failure mode.
 *
 * So: no [Context] is retained here, nothing is lazily re-read, and nothing is measured
 * at send time. This is a plain value, snapshot at pin time, `@Serializable` so it can be
 * persisted alongside the attempt record and restored verbatim after process death.
 *
 * ### Wire contract
 *
 * The field names, JSON keys and value encodings are matched field-for-field to the
 * shipped iOS SDK's `BrowserInfo`
 * (`UqpayPayments/UqpayPayments/Models/PaymentRequests.swift:811`), because both SDKs
 * post to the same endpoint and a divergence here is a 400 the other platform does not
 * see. `@SerialName`s are therefore frozen: they are a wire format, not naming taste.
 *
 * Nothing is fabricated. The iOS SDK shipped hardcoded `1920x1080`, `"iOS 14.5"` and
 * `lat/lon 0` defaults and had to remove them all — canned device data misdescribes
 * nearly every real customer and actively poisons 3DS risk scoring, which is worse than
 * sending nothing. Fields Android cannot measure are omitted, never invented.
 *
 * `location` is deliberately **absent from the type entirely**. The SDK never asks for a
 * location fix, and the API rejects `lat`/`lon` of `"0"`; a nullable field would only be
 * an invitation to fill it in later.
 *
 * @property acceptHeader the wildcard accept header, matching iOS byte-for-byte. This is
 *   a native app, not a browser; the 3DS field exists for browser integrations and an
 *   invented `text/html,...` string would claim capabilities we do not have.
 * @property deviceId a pseudonymous, per-app device identifier — never a hardware serial.
 * @property timezone the current UTC offset **in whole hours**, as a string. See
 *   [DeviceInfo.timezoneOffsetHours] for why hours and not minutes.
 * @property screenColorDepth fixed at 24, matching iOS. Android exposes no honest
 *   per-display value here and the field is required.
 * @property fonts always null on Android — an installed-font list is a browser
 *   fingerprinting signal with no native equivalent, and enumerating one would be
 *   inventing data. Declared only so the wire shape matches iOS.
 * @property webglVendor always null on Android; see [fonts].
 * @property webglRenderer always null on Android; see [fonts].
 */
@Serializable
internal data class BrowserInfo(
    @SerialName("accept_header") val acceptHeader: String,
    @SerialName("browser") val browser: BrowserDetails,
    @SerialName("device_id") val deviceId: String,
    @SerialName("language") val language: String,
    @SerialName("mobile") val mobile: MobileInfo,
    @SerialName("screen_color_depth") val screenColorDepth: Int,
    @SerialName("screen_height") val screenHeight: Int,
    @SerialName("screen_width") val screenWidth: Int,
    @SerialName("timezone") val timezone: String,
    @SerialName("touch_support") val touchSupport: Boolean?,
    @SerialName("hardware_concurrency") val hardwareConcurrency: Int?,
    @SerialName("device_memory") val deviceMemory: Int?,
    @SerialName("fonts") val fonts: List<String>? = null,
    @SerialName("webgl_vendor") val webglVendor: String? = null,
    @SerialName("webgl_renderer") val webglRenderer: String? = null,
)

/**
 * The `browser` sub-object of [BrowserInfo]. Mirrors iOS `BrowserDetails`
 * (`PaymentRequests.swift:889`).
 *
 * @property userAgent must describe the same device as [MobileInfo.deviceModel]. A risk
 *   engine that sees a Pixel model behind an iPhone user agent reads it as spoofing, and
 *   the iOS SDK shipped exactly that bug — one canned `iPhone OS 14_5` string for every
 *   device, on every OS version.
 */
@Serializable
internal data class BrowserDetails(
    @SerialName("java_enabled") val javaEnabled: Boolean,
    @SerialName("javascript_enabled") val javascriptEnabled: Boolean,
    @SerialName("user_agent") val userAgent: String,
    @SerialName("cookie_enabled") val cookieEnabled: Boolean?,
    @SerialName("plugins") val plugins: List<String>?,
    @SerialName("do_not_track") val doNotTrack: Boolean?,
)

/**
 * The `mobile` sub-object of [BrowserInfo]. Mirrors iOS `MobileInfo`
 * (`PaymentRequests.swift:941`).
 *
 * @property osType spelled in **capitals**. The iOS source carries the comment that the
 *   confirm endpoint validates this strictly and rejects lowercase with a 400; iOS sends
 *   `"IOS"`, so we send `"ANDROID"`. This is not a style choice and must not be
 *   "normalised" by a future change.
 * @property carrier always null. Reading the network operator name means touching
 *   `TelephonyManager` for a value the risk engine does not require, so it is omitted —
 *   iOS omits it too, and the field explicitly must not carry a placeholder.
 */
@Serializable
internal data class MobileInfo(
    @SerialName("device_model") val deviceModel: String,
    @SerialName("os_type") val osType: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("carrier") val carrier: String? = null,
)

/**
 * One interface-scoped address, so the IP selection rule in
 * [DeviceInfo.selectDeviceIpAddress] can be tested without a live network stack.
 *
 * [NetworkInterface] cannot be constructed in a unit test, which would otherwise leave
 * the preference order — the part with actual behaviour — permanently untested.
 */
internal data class DeviceIpCandidate(
    val interfaceName: String,
    val address: InetAddress,
)

/**
 * Captures the frozen device values for a confirm attempt.
 *
 * Everything here is measured from the running device at the moment of capture and then
 * never touched again; see [BrowserInfo] for why that matters to idempotent replay.
 */
internal object DeviceInfo {

    /** The wildcard accept header iOS sends for a native app; see [BrowserInfo]. */
    private const val ACCEPT_HEADER = "*/*"

    /** See [MobileInfo.osType] — capitals are a server requirement, not a convention. */
    internal const val OS_TYPE = "ANDROID"

    /** Fixed, matching iOS. Android exposes no honest per-display value. */
    private const val SCREEN_COLOR_DEPTH = 24

    /** Used when the device locale reports no language at all. */
    private const val FALLBACK_LANGUAGE = "en-US"

    private const val BYTES_PER_GIB = 1_073_741_824L

    /**
     * Snapshots the device fingerprint. Call once per attempt, at pin time.
     *
     * The [context] is used and discarded — no reference to it survives in the returned
     * value, which must be safe to persist and to hold for the 24h idempotency window.
     */
    fun currentDevice(context: Context): BrowserInfo {
        val appContext = context.applicationContext ?: context
        val osRelease = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }
            ?: Build.VERSION.SDK_INT.toString()
        val model = Build.MODEL?.takeIf { it.isNotBlank() }
            ?: Build.DEVICE?.takeIf { it.isNotBlank() }
            ?: "Android"

        // The platform token real Android browsers and WebViews use, carrying the same
        // OS version and model reported in `mobile` — see [BrowserDetails.userAgent].
        val userAgent = "Mozilla/5.0 (Linux; Android $osRelease; $model)"

        val metrics = appContext.resources.displayMetrics

        return BrowserInfo(
            acceptHeader = ACCEPT_HEADER,
            browser = BrowserDetails(
                javaEnabled = true,
                javascriptEnabled = true,
                userAgent = userAgent,
                cookieEnabled = true,
                plugins = emptyList(),
                doNotTrack = false,
            ),
            deviceId = deviceId(appContext),
            language = currentLanguageTag(appContext),
            mobile = MobileInfo(
                deviceModel = model,
                osType = OS_TYPE,
                osVersion = "Android $osRelease",
            ),
            screenColorDepth = SCREEN_COLOR_DEPTH,
            screenHeight = metrics.heightPixels,
            screenWidth = metrics.widthPixels,
            timezone = timezoneOffsetHours().toString(),
            touchSupport = true,
            hardwareConcurrency = Runtime.getRuntime().availableProcessors(),
            deviceMemory = deviceMemoryGigabytes(appContext),
        )
    }

    /**
     * The current UTC offset in **whole hours**, truncated toward zero.
     *
     * Hours, not minutes: UQPAY's 3DS integration guide samples this field as
     * `"timezone": "-2"`, and the iOS SDK encodes `secondsFromGMT() / 3600`
     * (`DeviceBrowserInfo.swift:66`). An EMV 3DS `browserTZ` would be minutes — sending
     * minutes here was tried and rejected as guesswork.
     *
     * **Half-hour and quarter-hour zones cannot be expressed in the documented shape and
     * truncate toward zero**: Asia/Colombo and Asia/Kolkata (UTC+5:30) report `5`,
     * Asia/Kathmandu (UTC+5:45) reports `5`, America/St_Johns (UTC−3:30) reports `-3`.
     * Kotlin's integer division truncates toward zero exactly as Swift's does, so the two
     * SDKs agree digit-for-digit. Do not "fix" this by rounding — the value is only
     * meaningful to the risk engine if both platforms encode it identically.
     *
     * Uses the offset *now*, not the raw offset, so a device in summer time reports the
     * offset actually in force.
     */
    fun timezoneOffsetHours(
        timeZone: TimeZone = TimeZone.getDefault(),
        atEpochMillis: Long = System.currentTimeMillis(),
    ): Int = timeZone.getOffset(atEpochMillis) / 3_600_000

    /**
     * The device's own IP address, or null when none can be read.
     *
     * The confirm API needs this for 3DS risk assessment, and — the reason it is not
     * optional in practice — **UQPAY produces no wallet QR without it**. A missing
     * `ip_address` is not a degraded payment, it is no payment.
     *
     * This is the address of a local interface. Behind NAT that is a private address,
     * which is still the honest client-side value; the SDK never substitutes a public
     * address, because wrong data is worse for risk scoring than private data.
     *
     * Never throws. [NetworkInterface.getNetworkInterfaces] returns null on a device with
     * no interfaces up and throws `SocketException` on others, and neither is a reason to
     * fail a payment — the caller gets null and decides.
     *
     * @param interfaces overridable purely so the no-interface and throwing cases are
     *   testable; production always uses the platform enumeration.
     */
    fun currentIpAddress(
        interfaces: () -> Enumeration<NetworkInterface>? = { NetworkInterface.getNetworkInterfaces() },
    ): String? = runCatching {
        val enumeration = interfaces() ?: return@runCatching null
        val candidates = buildList {
            for (nic in enumeration) {
                val name = nic.name.orEmpty()
                for (address in nic.inetAddresses) {
                    add(DeviceIpCandidate(name, address))
                }
            }
        }
        selectDeviceIpAddress(candidates)
    }.getOrNull()

    /**
     * Picks the address to report, from every address on every interface.
     *
     * Loopback, link-local (`fe80::`, `169.254.x.x`) and wildcard addresses are dropped
     * first: they identify no device to a risk engine. Of what remains, IPv4 wins over
     * IPv6 — the field is a string the gateway parses, and IPv4 is what every reference
     * integration sends. Wi-Fi is preferred over cellular, mirroring iOS's `en0` then
     * `pdp_ip0` order, because that is the interface actually carrying the confirm.
     *
     * IPv6 scope suffixes (`fe80::1%wlan0`) are stripped; a `%` in the value would be
     * meaningless to the gateway.
     */
    fun selectDeviceIpAddress(candidates: List<DeviceIpCandidate>): String? {
        val usable = candidates.filterNot {
            it.address.isLoopbackAddress || it.address.isLinkLocalAddress || it.address.isAnyLocalAddress
        }
        if (usable.isEmpty()) return null

        fun rank(entry: DeviceIpCandidate): Int = when {
            entry.interfaceName.startsWith("wlan") -> 0
            entry.interfaceName.startsWith("eth") -> 1
            // Cellular interface names are vendor-specific; these three cover the ones
            // Android devices actually ship with.
            entry.interfaceName.startsWith("rmnet") ||
                entry.interfaceName.startsWith("ccmni") ||
                entry.interfaceName.startsWith("pdp") -> 2
            else -> 3
        }

        val ipv4 = usable.filter { it.address is Inet4Address }
        val chosen = ipv4.minByOrNull(::rank) ?: usable.minByOrNull(::rank)

        return chosen?.address?.hostAddress?.substringBefore('%')
    }

    /**
     * A pseudonymous device identifier, matching what iOS sends from
     * `identifierForVendor`.
     *
     * `Settings.Secure.ANDROID_ID` is the platform's app-scoped analogue: it is derived
     * per signing key and per user, resets on factory reset, and is **not** a hardware
     * serial, IMEI or advertising id — none of which this SDK reads. When it is
     * unavailable the value falls back to a random UUID for this capture, exactly as iOS
     * does; an attempt with an unstable device id is far better than a payment that
     * cannot be confirmed.
     */
    @Suppress("HardwareIds")
    // Lint's HardwareIds check exists to stop SDKs shipping non-resettable, cross-app
    // hardware identifiers (IMEI, serial, MAC). This is none of those. The gateway requires
    // a stable device id for risk scoring — an attempt without one is scored as a brand-new
    // device on every launch and is more likely to be declined — and ANDROID_ID is the
    // platform's own answer for that: scoped per app-signing-key and per user since API 26,
    // reset by a factory reset, and not readable by any other app. It is the direct analogue
    // of the `identifierForVendor` the shipped iOS SDK sends. It is transmitted to UQPAY and
    // frozen with a confirm attempt; it is never logged. No advertising id, serial number,
    // IMEI, MAC address or phone number is read anywhere in this SDK.
    private fun deviceId(context: Context): String =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    /**
     * A plain BCP 47 tag such as `en-US`.
     *
     * Built from language and country rather than from `Locale.toLanguageTag()` because a
     * device with a region override produces extension subtags (`en-US-u-rg-myzzzz`), and
     * the API rejects those outright with "language is invalid". iOS hit the same wall and
     * reduces its locale identifier the same way.
     *
     * Read from the app's configuration rather than `Locale.getDefault()` so a merchant
     * app that pins its own locale reports the language the customer is actually seeing.
     */
    private fun currentLanguageTag(context: Context): String {
        val locale = runCatching {
            context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)
        }.getOrNull() ?: Locale.getDefault()

        val language = locale.language.takeIf { it.isNotBlank() } ?: return FALLBACK_LANGUAGE
        val country = locale.country
        return if (country.isNotBlank()) "$language-$country" else language
    }

    /**
     * Physical RAM in whole gibibytes, floored at 1 — the same shape iOS derives from
     * `physicalMemory`. Never fails a capture: an unreadable `ActivityManager` yields the
     * floor rather than an exception on the confirm path.
     */
    private fun deviceMemoryGigabytes(context: Context): Int = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@runCatching 1
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        maxOf(1, (info.totalMem / BYTES_PER_GIB).toInt())
    }.getOrDefault(1)
}
