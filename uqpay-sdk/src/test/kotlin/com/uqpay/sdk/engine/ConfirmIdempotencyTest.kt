package com.uqpay.sdk.engine

import com.uqpay.sdk.network.ApiErrorBody
import com.uqpay.sdk.network.UQPayApiException
import com.uqpay.sdk.store.ConfirmAttemptJson
import com.uqpay.sdk.store.ConfirmAttemptStore
import com.uqpay.sdk.store.PersistedConfirmAttempt
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Every test here is a disaster simulation.
 *
 * The registry under test is the SDK's only defence against charging a customer twice, and
 * none of the situations it defends against are ordinary: the app is killed mid-confirm,
 * the network drops after the bytes went out, a superseded request finishes late, the
 * device clock is wrong, storage is broken, two taps race on two threads. The happy path —
 * confirm, get an answer, resolve — is the one case that cannot lose anyone money, and it
 * is covered here almost incidentally.
 *
 * **If one of these fails, the change that broke it is what is wrong.** The expectations
 * are not to be relaxed to match new behaviour without an explicit decision recorded in
 * the execution plan.
 *
 * No real card number, CVC, idempotency key or API key appears in this file. The PAN used
 * in [noCardDataReachesTheStore] is the universally documented test value, and it is used
 * precisely to prove it does not survive as far as storage.
 */
class ConfirmIdempotencyTest {

    // ---- Fixtures ---------------------------------------------------------------------

    /**
     * A fixed instant with no special properties, used as "now" everywhere. Tests move the
     * clock relative to it explicitly, so no test depends on the real wall clock.
     */
    private val baseNow = 1_786_924_800_000L

    private var clockMillis = baseNow

    /** Bumped so a test can prove which capture a value came from. */
    private val deviceCaptureCount = AtomicInteger(0)

    private fun browserInfo(marker: String) = BrowserInfo(
        acceptHeader = "*/*",
        browser = BrowserDetails(
            javaEnabled = true,
            javascriptEnabled = true,
            userAgent = "Mozilla/5.0 (Linux; Android 14; $marker)",
            cookieEnabled = true,
            plugins = emptyList(),
            doNotTrack = false,
        ),
        deviceId = "device-$marker",
        language = "en-US",
        mobile = MobileInfo(
            deviceModel = marker,
            osType = "ANDROID",
            osVersion = "Android 14",
            carrier = null,
        ),
        screenColorDepth = 24,
        screenHeight = if (marker == "rotated") 1080 else 2400,
        screenWidth = if (marker == "rotated") 2400 else 1080,
        timezone = "8",
        touchSupport = true,
        hardwareConcurrency = 8,
        deviceMemory = 8,
    )

    /** The device values the *first* process captures. */
    private val pinnedDevice = browserInfo("Pixel 8")
    private val pinnedIp = "192.168.1.42"

    /** What a *later* process would measure — deliberately different in every dimension. */
    private val relaunchedDevice = browserInfo("rotated")
    private val relaunchedIp = "10.20.30.40"

    private var currentDevice = pinnedDevice
    private var currentIp: String? = pinnedIp

    private val store = FakeConfirmAttemptStore()

    private fun registry(
        target: ConfirmAttemptStore = store,
    ) = ConfirmIdempotency(
        store = target,
        browserInfo = {
            deviceCaptureCount.incrementAndGet()
            currentDevice
        },
        ipAddress = { currentIp },
        now = { clockMillis },
    )

    @Before
    fun setUp() {
        // Static registry state outlives a test method; without this, one test's pin is
        // another test's mysterious pass.
        ConfirmIdempotency.clearInMemoryOnlyForTest()
    }

    @After
    fun tearDown() {
        ConfirmIdempotency.clearInMemoryOnlyForTest()
    }

    // ---- Process death ----------------------------------------------------------------

    /**
     * The single most important test in this module.
     *
     * Android killed the app between the pin and the answer. The relaunched process asks
     * for the same payload and must get back **the same key**, **the values frozen with
     * it**, and **the intent id the record itself carries** — because the gateway honours a
     * replayed key only for a byte-identical body, and because whatever the new launch's
     * configuration holds is not evidence of which payment this pin belongs to.
     *
     * Asserted field by field rather than with one equality check: `ConfirmAttempt` is a
     * data class, and an equality assertion would pass even if a field were being
     * re-derived from current state and happened to agree.
     */
    @Test
    fun processDeathMidConfirmReplaysTheSameAttempt() {
        val pinned = registry().attempt("digest-card", "int_original")

        // The process dies. The map is gone; the blob on disk is not.
        ConfirmIdempotency.clearInMemoryOnlyForTest()

        // The new launch measures a different device and knows a different intent.
        currentDevice = relaunchedDevice
        currentIp = relaunchedIp
        clockMillis = baseNow + 60_000

        val restored = registry().attempt("digest-card", "int_from_the_new_launch")

        assertEquals("the key must replay verbatim", pinned.key, restored.key)
        assertEquals("digest-card", restored.payloadDigest)
        assertEquals(
            "the record's own intent id wins, never the caller's current one",
            "int_original",
            restored.paymentIntentId,
        )
        assertEquals("frozen device values come back", pinnedDevice, restored.browserInfo)
        assertEquals("Pixel 8", restored.browserInfo.mobile.deviceModel)
        assertEquals(2400, restored.browserInfo.screenHeight)
        assertEquals(1080, restored.browserInfo.screenWidth)
        assertEquals("frozen IP comes back", pinnedIp, restored.ipAddress)
        assertEquals("mint time is not reset by a restore", pinned.createdAt, restored.createdAt)
    }

    /** A restore must not re-measure the device: doing so is how a replay body drifts. */
    @Test
    fun restoreDoesNotCaptureCurrentDeviceValues() {
        registry().attempt("digest-card", "int_1")
        ConfirmIdempotency.clearInMemoryOnlyForTest()
        deviceCaptureCount.set(0)

        registry().attempt("digest-card", "int_1")

        assertEquals("no device capture on a restore", 0, deviceCaptureCount.get())
    }

    /** A restore is a read, not a write: it must not disturb store order or the cap. */
    @Test
    fun restoreDoesNotRewriteTheStore() {
        registry().attempt("digest-card", "int_1")
        ConfirmIdempotency.clearInMemoryOnlyForTest()
        val savesBefore = store.saves.size

        registry().attempt("digest-card", "int_1")

        assertEquals(savesBefore, store.saves.size)
    }

    // ---- Pin identity -----------------------------------------------------------------

    /** The same payload, unresolved, is one attempt however many times it is asked for. */
    @Test
    fun samePayloadReusesTheKeyWhileUnresolved() {
        val registry = registry()

        val first = registry.attempt("digest-card", "int_1")
        val second = registry.attempt("digest-card", "int_1")
        // A different instance: the invariant belongs to the payment, not to the object.
        val third = registry().attempt("digest-card", "int_1")

        assertEquals(first.key, second.key)
        assertEquals(first.key, third.key)
        assertEquals("only one mint means only one device capture", 1, deviceCaptureCount.get())
    }

    /** An edited payload is a different payment and must not inherit the old key. */
    @Test
    fun editedPayloadGetsAFreshKey() {
        val registry = registry()

        val first = registry.attempt("digest-card-v1", "int_1")
        val second = registry.attempt("digest-card-v2", "int_1")

        assertNotEquals(first.key, second.key)
        assertEquals(2, store.records.size)
    }

    /** A memory hit must not consult storage at all — memory is the session's truth. */
    @Test
    fun memoryHitDoesNotTouchTheStore() {
        val registry = registry()
        registry.attempt("digest-card", "int_1")
        store.loadCount = 0

        registry.attempt("digest-card", "int_1")

        assertEquals(0, store.loadCount)
    }

    /**
     * The key format is WU-2.2's, not this module's: a lowercase UUID, which is what UQPAY
     * accepts. A local `UUID.randomUUID()` here would be invisible until a `uppercase()`
     * crept in somewhere and the gateway started answering `invalid idempotency key format`.
     */
    @Test
    fun mintedKeysAreLowercaseUuids() {
        val key = registry().attempt("digest-card", "int_1").key

        assertTrue(
            "expected a lowercase UUID, got a $key-shaped thing",
            key.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
        )
    }

    /**
     * A restored key is an opaque string and is never normalised. A key that comes back one
     * character different is a different key — and a different key against a payment that
     * may already be authorising is a second charge.
     */
    @Test
    fun restoredKeysAreNeverReformatted() {
        val oddKey = "  6F4C2A1E-8B3D-4F9A-9C0E-2D7B5A1F4E63  "
        store.records = listOf(record("digest-card", oddKey, "int_1", baseNow - 1_000))

        val restored = registry().attempt("digest-card", "int_1")

        assertEquals(oddKey, restored.key)
    }

    // ---- Time to live -----------------------------------------------------------------

    /** Past the gateway's replay window a pin protects nobody, so it is not restored. */
    @Test
    fun expiredPinIsNotRestored() {
        val pinned = registry().attempt("digest-card", "int_1")
        ConfirmIdempotency.clearInMemoryOnlyForTest()

        clockMillis = baseNow + ConfirmIdempotency.TIME_TO_LIVE_MILLIS + 1

        val fresh = registry().attempt("digest-card", "int_1")

        assertNotEquals(pinned.key, fresh.key)
        assertEquals("an expired pin means a real mint, not a silent restore", 2, deviceCaptureCount.get())
    }

    /** The boundary itself is still inside the window. */
    @Test
    fun pinExactlyAtTheTtlBoundaryIsStillRestored() {
        val pinned = registry().attempt("digest-card", "int_1")
        ConfirmIdempotency.clearInMemoryOnlyForTest()

        clockMillis = baseNow + ConfirmIdempotency.TIME_TO_LIVE_MILLIS

        assertEquals(pinned.key, registry().attempt("digest-card", "int_1").key)
    }

    /**
     * The wall clock moved *backwards* between the pin and the relaunch (user-settable clock,
     * NTP correction), so the stored record's `createdAt` is now in the future and its age is
     * negative. That record is still live protection for a payment that may be authorising,
     * so it MUST be restored: the TTL filter is `age <= TTL`, not `age in 0..TTL`. The record
     * cap — not the TTL — is the defence against a tampered clock (see `liveRecords`).
     */
    @Test
    fun futureDatedPinIsRestoredWhenTheClockRollsBack() {
        val pinned = registry().attempt("digest-card", "int_1")
        ConfirmIdempotency.clearInMemoryOnlyForTest()

        clockMillis = baseNow - 60L * 60 * 1000 // T − 1h: the record is now "from the future"

        val restored = registry().attempt("digest-card", "int_1")

        assertEquals("a future-dated pin is live protection and must be restored", pinned.key, restored.key)
        assertEquals("restore, not mint: device must not be re-captured", 1, deviceCaptureCount.get())
    }

    /**
     * The TTL is in **milliseconds**. iOS's constant is in seconds; carrying it across
     * would leave the registry restoring 27-year-old pins with no visible symptom.
     */
    @Test
    fun ttlIsTwentyFourHoursInMilliseconds() {
        assertEquals(24L * 60 * 60 * 1000, ConfirmIdempotency.TIME_TO_LIVE_MILLIS)
    }

    /** Expiry is enforced on read, so an aged-out record is also dropped on the next write. */
    @Test
    fun expiredRecordsArePrunedWhenTheNextPinIsWritten() {
        store.records = listOf(
            record("digest-ancient", "key-ancient", "int_old", baseNow - 2 * ConfirmIdempotency.TIME_TO_LIVE_MILLIS),
            record("digest-recent", "key-recent", "int_recent", baseNow - 1_000),
        )

        registry().attempt("digest-new", "int_new")

        assertEquals(
            listOf("digest-recent", "digest-new"),
            store.records.map { it.identityDigest },
        )
    }

    // ---- The record cap ---------------------------------------------------------------

    /**
     * Eviction is by **list position**, not by timestamp — and this test is built so that
     * it can tell the difference.
     *
     * The seeded fixtures are unsorted in every dimension: the record that is first in the
     * list has the *newest* timestamp, the oldest timestamp sits in the middle, and the
     * digests and intent ids are not in any order either. A registry that sorted by
     * `createdAt` — or a store that silently sorted — would evict a different record and
     * fail here, which an ascending `int_1 … int_16` fixture set could never detect.
     *
     * The cap matters because it is the one part of this design that keeps working when the
     * wall clock does not.
     */
    @Test
    fun capEvictsByListOrderNotByTimestamp() {
        store.records = scrambledFullStore()
        val firstInList = store.records.first()
        val oldestByTimestamp = store.records.minByOrNull { it.createdAt }!!

        assertNotEquals(
            "fixture bug: the test cannot distinguish the two orders",
            firstInList.identityDigest,
            oldestByTimestamp.identityDigest,
        )

        registry().attempt("digest-seventeenth", "int_seventeen")

        val digests = store.records.map { it.identityDigest }
        assertEquals(ConfirmIdempotency.MAX_PERSISTED_ATTEMPTS, digests.size)
        assertFalse("the head of the list is evicted", digests.contains(firstInList.identityDigest))
        assertTrue(
            "the oldest timestamp is NOT what gets evicted",
            digests.contains(oldestByTimestamp.identityDigest),
        )
        assertEquals("the new pin is appended last", "digest-seventeenth", digests.last())
        assertEquals(
            "surviving order is untouched",
            scrambledFullStore().drop(1).map { it.identityDigest } + "digest-seventeenth",
            digests,
        )
    }

    /**
     * A wall clock the customer moved backwards makes every record look ageless — every
     * age is negative, so the TTL drops nothing. The cap has no clock and holds anyway.
     */
    @Test
    fun clockRolledBackwardsStillEvictsUnderTheCap() {
        store.records = scrambledFullStore()
        val firstInList = store.records.first()

        // Somebody set the device date to last year.
        clockMillis = baseNow - 365L * ConfirmIdempotency.TIME_TO_LIVE_MILLIS

        registry().attempt("digest-seventeenth", "int_seventeen")

        assertEquals(ConfirmIdempotency.MAX_PERSISTED_ATTEMPTS, store.records.size)
        assertFalse(store.records.map { it.identityDigest }.contains(firstInList.identityDigest))
        assertEquals("digest-seventeenth", store.records.last().identityDigest)
    }

    /** Sixteen unresolved payments fit; the cap only bites on the seventeenth. */
    @Test
    fun capKeepsExactlySixteenRecords() {
        val registry = registry()
        repeat(ConfirmIdempotency.MAX_PERSISTED_ATTEMPTS) { registry.attempt("digest-$it", "int_$it") }

        assertEquals(ConfirmIdempotency.MAX_PERSISTED_ATTEMPTS, store.records.size)
        assertEquals("digest-0", store.records.first().identityDigest)
    }

    // ---- Stale tasks ------------------------------------------------------------------

    /**
     * The stale-task disaster, and the reason [ConfirmIdempotency.resolve] compares keys.
     *
     * Attempt 1 is in flight. The customer's second valid tap supersedes it, and attempt 2
     * is pinned for the same payload. Attempt 1's request then finishes and resolves. If
     * that resolution un-pinned the registry, attempt 2 would lose its replay protection
     * *while still in flight*, and the next tap would mint a third key against a payment
     * that may already be authorising.
     */
    @Test
    fun staleAttemptResolvingLateCannotUnpinTheNewerAttempt() {
        val registry = registry()
        val stale = registry.attempt("digest-card", "int_1")

        // Attempt 1 is superseded: its pin is released and a newer one takes its place.
        registry.resolve(stale)
        val newer = registry.attempt("digest-card", "int_1")
        assertNotEquals(stale.key, newer.key)

        // Attempt 1's request, still in flight all this time, now finishes.
        registry.resolve(stale)

        assertEquals(
            "the newer pin must survive a stale resolve",
            newer.key,
            registry.attempt("digest-card", "int_1").key,
        )
        assertEquals(listOf(newer.key), store.records.map { it.keyValue })
    }

    /** The same protection, reached through the error path rather than a direct resolve. */
    @Test
    fun staleAttemptFailingDefinitivelyCannotUnpinTheNewerAttempt() {
        val registry = registry()
        val stale = registry.attempt("digest-card", "int_1")
        registry.resolve(stale)
        val newer = registry.attempt("digest-card", "int_1")

        registry.handle(declined(), stale)

        assertEquals(newer.key, registry.attempt("digest-card", "int_1").key)
    }

    /** Resolving ends the attempt: pin gone from memory and record gone from the store. */
    @Test
    fun resolveEndsTheAttempt() {
        val registry = registry()
        val attempt = registry.attempt("digest-card", "int_1")

        registry.resolve(attempt)

        assertTrue(store.records.isEmpty())
        assertNotEquals(attempt.key, registry.attempt("digest-card", "int_1").key)
    }

    // ---- Error classification: the three-way split ------------------------------------

    /**
     * Cancellation is the third category, not a definitive answer. A cancelled task reports
     * nothing and must leave the pin **exactly** as it was — the request it cancelled may
     * still be travelling.
     */
    @Test
    fun cancellationLeavesThePinExactlyAsItWas() {
        for (cancellation in listOf(CancellationException("superseded"), UQPayApiException.Cancelled())) {
            ConfirmIdempotency.clearInMemoryOnlyForTest()
            store.records = emptyList()
            val registry = registry()
            val attempt = registry.attempt("digest-card", "int_1")
            val savesBefore = store.saves.size

            registry.handle(cancellation, attempt)

            assertEquals(
                "${cancellation.javaClass.simpleName} must keep the pin",
                attempt.key,
                registry.attempt("digest-card", "int_1").key,
            )
            assertEquals("and must not rewrite the store", savesBefore, store.saves.size)
            assertEquals(listOf(attempt.key), store.records.map { it.keyValue })
        }
    }

    /**
     * Every error that means "the payment may already have been processed" keeps the pin,
     * so the next send replays the same key rather than opening a second attempt.
     *
     * The classification itself is [UQPayApiException.isOutcomeUnknown]'s, deliberately
     * reused rather than re-derived here — but this test still enumerates the cases,
     * because a change to that property is a change to double-charge protection and should
     * have to break a test in this module too.
     */
    @Test
    fun unknownOutcomeKeepsThePin() {
        val unknowns = listOf(
            UQPayApiException.TransportFailure(IOException("connection reset")),
            UQPayApiException.TimedOut(),
            UQPayApiException.DecodingFailure(200, null, IOException("garbage")),
            UQPayApiException.IdempotencyInFlight(null, null, 400),
            UQPayApiException.ApiError(ApiErrorBody(code = "server_error"), null, 500),
            UQPayApiException.ApiError(ApiErrorBody(code = "rate_limited"), null, 429),
            UQPayApiException.UnexpectedStatus(503, null),
        )

        for (error in unknowns) {
            ConfirmIdempotency.clearInMemoryOnlyForTest()
            store.records = emptyList()
            val registry = registry()
            val attempt = registry.attempt("digest-card", "int_1")

            registry.handle(error, attempt)

            assertEquals(
                "${error.javaClass.simpleName} leaves the outcome unknown; the pin must stay",
                attempt.key,
                registry.attempt("digest-card", "int_1").key,
            )
        }
    }

    /** A definitive answer ends the attempt: the next tap is a new payment, new key. */
    @Test
    fun definitiveFailureEndsTheAttempt() {
        val definitives = listOf(
            declined(),
            UQPayApiException.UnexpectedStatus(404, null),
            UQPayApiException.NotConfigured("no environment"),
        )

        for (error in definitives) {
            ConfirmIdempotency.clearInMemoryOnlyForTest()
            store.records = emptyList()
            val registry = registry()
            val attempt = registry.attempt("digest-card", "int_1")

            registry.handle(error, attempt)

            assertTrue(
                "${error.javaClass.simpleName} is definitive; the record must be gone",
                store.records.isEmpty(),
            )
            assertNotEquals(attempt.key, registry.attempt("digest-card", "int_1").key)
        }
    }

    /**
     * A deliberate divergence from iOS, documented on [ConfirmIdempotency.handle]: an error
     * we cannot classify is not a definitive answer from the gateway, so the pin stays. It
     * may have been thrown after the bytes went out.
     */
    @Test
    fun unrecognisedFailureKeepsThePin() {
        val registry = registry()
        val attempt = registry.attempt("digest-card", "int_1")

        registry.handle(IllegalStateException("something inside the send path"), attempt)

        assertEquals(attempt.key, registry.attempt("digest-card", "int_1").key)
    }

    // ---- Broken storage ---------------------------------------------------------------

    /**
     * A store that reads back nothing — a blob truncated by a kill mid-write, the shape the
     * production store degrades to — must not stop anyone paying. The session's pin still
     * works; only recovery after process death is lost, which is a degradation and not a
     * failure.
     */
    @Test
    fun corruptedStoreStillPinsInMemory() {
        val blackHole = BlackHoleStore()
        val registry = registry(blackHole)

        val first = registry.attempt("digest-card", "int_1")
        assertEquals("the session pin still replays", first.key, registry.attempt("digest-card", "int_1").key)

        ConfirmIdempotency.clearInMemoryOnlyForTest()
        assertNotEquals(
            "and recovery after process death is what is lost — nothing else",
            first.key,
            registry.attempt("digest-card", "int_1").key,
        )
    }

    /** A store whose `save` throws is a contract violation; it still must not cost a payment. */
    @Test
    fun storeWhoseSaveThrowsDegradesWithoutCrashing() {
        val registry = registry(ThrowingStore(onLoad = false, onSave = true))

        val first = registry.attempt("digest-card", "int_1")
        val second = registry.attempt("digest-card", "int_1")

        assertEquals(first.key, second.key)
        registry.resolve(first)
        assertNotEquals(first.key, registry.attempt("digest-card", "int_1").key)
    }

    /** Same for a store whose `load` throws. */
    @Test
    fun storeWhoseLoadThrowsDegradesWithoutCrashing() {
        val registry = registry(ThrowingStore(onLoad = true, onSave = false))

        val first = registry.attempt("digest-card", "int_1")
        assertEquals(first.key, registry.attempt("digest-card", "int_1").key)
    }

    /**
     * `Error` is not swallowed. The store layer does not eat it either, and an
     * `OutOfMemoryError` must reach the engine's boundary rather than be mistaken for
     * "storage is unavailable".
     */
    @Test(expected = OutOfMemoryError::class)
    fun storageErrorsAreNotSwallowed() {
        registry(
            object : ConfirmAttemptStore {
                override fun load(): List<PersistedConfirmAttempt> = throw OutOfMemoryError("heap")
                override fun save(records: List<PersistedConfirmAttempt>) = Unit
            },
        ).attempt("digest-card", "int_1")
    }

    // ---- Concurrency ------------------------------------------------------------------

    /**
     * The double-tap race, which on Android is real: the engine runs on IO dispatchers, so
     * two taps genuinely can enter the registry at the same instant on two threads. iOS got
     * mutual exclusion free from `@MainActor`; we have to write it.
     *
     * Two keys minted here means two live payment attempts for one payload — the exact
     * failure this class exists to prevent.
     */
    @Test
    fun concurrentAttemptsForOnePayloadMintExactlyOneKey() {
        val threads = 32
        val registry = registry()
        val startGate = CountDownLatch(1)
        val keys = ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.execute {
                startGate.await()
                keys += registry.attempt("digest-card", "int_1").key
            }
        }
        startGate.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))

        assertEquals(threads, keys.size)
        assertEquals("one payload, one key", 1, keys.toSet().size)
        assertEquals("one payload, one device capture", 1, deviceCaptureCount.get())
        assertEquals(1, store.records.size)
    }

    /**
     * Concurrent pins for *different* payloads must not lose each other.
     *
     * Each pin is a read-modify-write of one shared stored list. Without the store round
     * trip being inside the same lock as the map update, two writers each read the same
     * list, each append their own record, and the second write erases the first — a pin
     * that silently does not survive process death.
     */
    @Test
    fun concurrentAttemptsForDifferentPayloadsAllPersist() {
        val threads = 12
        val registry = registry()
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) { index ->
            pool.execute {
                startGate.await()
                registry.attempt("digest-$index", "int_$index")
            }
        }
        startGate.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))

        assertEquals(threads, store.records.size)
        assertEquals(threads, store.records.map { it.identityDigest }.toSet().size)
    }

    // ---- Nothing card-derived at rest -------------------------------------------------

    /**
     * The registry writes to disk, so nothing it writes may be card-derived.
     *
     * The payload digest here is built the way a real card confirm builds one — through
     * `ConfirmPayloadIdentity`, whose card identity is BIN-plus-last-4 and whose input
     * excludes the CVC entirely. This scans **every** save the registry made, not just the
     * final state, because an intermediate write is just as much on the disk.
     */
    @Test
    fun noCardDataReachesTheStore() {
        val pan = "4111111111111111"
        val cvc = "737"
        val digest = ConfirmPayloadIdentity.digest(
            listOf("int_1", ConfirmPayloadIdentity.cardNumberIdentity(pan), "12", "2030"),
        )

        val registry = registry()
        val attempt = registry.attempt(digest, "int_1")
        registry.attempt("digest-other", "int_2")

        val written = store.saves.map {
            ConfirmAttemptJson.instance.encodeToString(ListSerializer(PersistedConfirmAttempt.serializer()), it)
        }
        assertTrue(written.isNotEmpty())
        for (blob in written) {
            assertFalse("a PAN reached storage", blob.contains(pan))
            assertFalse("a card identity reached storage", blob.contains("411111:1111"))
            assertFalse("a CVC-shaped field reached storage", blob.contains("cvc", ignoreCase = true))
            assertFalse("a card-shaped field reached storage", blob.contains("card", ignoreCase = true))
            assertFalse(blob.contains("expiry", ignoreCase = true))
        }
        assertTrue(
            "the only card-derived value at rest is a one-way digest",
            attempt.payloadDigest.matches(Regex("[0-9a-f]{64}")),
        )
    }

    // ---- Test hooks -------------------------------------------------------------------

    /** `clearAllForTest` must clear both halves, or state leaks between test classes. */
    @Test
    fun clearAllForTestClearsMemoryAndStorage() {
        val registry = registry()
        val attempt = registry.attempt("digest-card", "int_1")

        registry.clearAllForTest()

        assertTrue(store.records.isEmpty())
        assertNotEquals(attempt.key, registry.attempt("digest-card", "int_1").key)
    }

    /**
     * `clearInMemoryOnlyForTest` must leave storage alone — that asymmetry is the whole
     * point of having two hooks, and it is what makes process death simulable at all.
     */
    @Test
    fun clearInMemoryOnlyForTestLeavesStorageAlone() {
        registry().attempt("digest-card", "int_1")

        ConfirmIdempotency.clearInMemoryOnlyForTest()

        assertEquals(1, store.records.size)
    }

    /**
     * Documents a known, bounded behaviour rather than asserting a virtue: an attempt
     * resolved after its process died leaves its record behind, because there is no pin to
     * match it against. The TTL and the cap are what collect it. iOS behaves identically.
     */
    @Test
    fun resolveAfterProcessDeathLeavesTheRecordToTheTtl() {
        val registry = registry()
        val attempt = registry.attempt("digest-card", "int_1")
        ConfirmIdempotency.clearInMemoryOnlyForTest()

        registry.resolve(attempt)

        assertEquals(1, store.records.size)
    }

    // ---- Helpers ----------------------------------------------------------------------

    private fun declined() = UQPayApiException.ApiError(
        ApiErrorBody(type = "card_error", code = "card_declined", message = "Declined."),
        null,
        402,
    )

    private fun record(
        digest: String,
        key: String,
        intentId: String,
        createdAt: Long,
    ) = PersistedConfirmAttempt(
        identityDigest = digest,
        keyValue = key,
        paymentIntentId = intentId,
        browserInfo = pinnedDevice,
        createdAt = createdAt,
        ipAddress = pinnedIp,
    )

    /**
     * A full store whose list order matches **no** other ordering in the fixture.
     *
     * Deliberately hostile to a test that would otherwise pass by accident: the head of the
     * list carries the newest timestamp, the oldest timestamp is buried in the middle, and
     * neither the digests nor the intent ids ascend. Ascending `int_1 … int_16` fixtures
     * cannot tell list order from timestamp order, and a store that quietly sorted would
     * sail through them.
     */
    private fun scrambledFullStore(): List<PersistedConfirmAttempt> {
        val agesMillis = listOf(
            60_000L, 7_200_000L, 900_000L, 18_000_000L, 300_000L, 3_600_000L, 43_200_000L,
            120_000L, 21_600_000L, 1_800_000L, 79_200_000L, 600_000L, 10_800_000L,
            240_000L, 5_400_000L, 30_000_000L,
        )
        val names = listOf(
            "zulu", "mike", "alpha", "papa", "delta", "romeo", "bravo", "sierra",
            "echo", "tango", "foxtrot", "victor", "golf", "whiskey", "hotel", "xray",
        )
        return names.mapIndexed { index, name ->
            record(
                digest = "digest-$name",
                key = "key-$name",
                intentId = "int_$name",
                createdAt = baseNow - agesMillis[index],
            )
        }
    }

    /** Records every save so a test can scan intermediate state, not just the end state. */
    private class FakeConfirmAttemptStore : ConfirmAttemptStore {
        var records: List<PersistedConfirmAttempt> = emptyList()
        val saves = mutableListOf<List<PersistedConfirmAttempt>>()
        var loadCount = 0

        override fun load(): List<PersistedConfirmAttempt> {
            loadCount++
            return records
        }

        override fun save(records: List<PersistedConfirmAttempt>) {
            saves += records
            this.records = records
        }
    }

    /** What the production store degrades to when its blob cannot be read. */
    private class BlackHoleStore : ConfirmAttemptStore {
        override fun load(): List<PersistedConfirmAttempt> = emptyList()
        override fun save(records: List<PersistedConfirmAttempt>) = Unit
    }

    /** A store that violates its own never-throws contract, in either direction. */
    private class ThrowingStore(
        private val onLoad: Boolean,
        private val onSave: Boolean,
    ) : ConfirmAttemptStore {
        override fun load(): List<PersistedConfirmAttempt> =
            if (onLoad) throw IllegalStateException("storage unavailable") else emptyList()

        override fun save(records: List<PersistedConfirmAttempt>) {
            if (onSave) throw IllegalStateException("disk full")
        }
    }
}
