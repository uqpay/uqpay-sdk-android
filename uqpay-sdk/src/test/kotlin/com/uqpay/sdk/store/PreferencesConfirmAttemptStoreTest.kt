package com.uqpay.sdk.store

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.engine.BrowserDetails
import com.uqpay.sdk.engine.BrowserInfo
import com.uqpay.sdk.engine.MobileInfo
import com.uqpay.sdk.network.UQPayLogger
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The store is the only thing that stands between a killed app and a second charge, and it
 * is also the component most likely to be handed something broken: a blob truncated by a
 * kill mid-write, a blob from a version that no longer exists, a disk that has nothing left
 * to give. Every test here is therefore a disaster first and a happy path second.
 *
 * The invariant under test throughout: **the store degrades, it never throws.** A store
 * that throws on the confirm path converts "we lost relaunch recovery" into "the payment
 * failed", which is a strictly worse outcome in every scenario it can happen in.
 *
 * No card number, CVC or real idempotency key appears in this file; the fixture values are
 * invented, and one test asserts the written blob cannot contain such a field at all.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesConfirmAttemptStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The real production file, opened the same way the production constructor opens it. */
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val store = PreferencesConfirmAttemptStore(context)

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    // ---- Fixtures --------------------------------------------------------------------

    private val browserInfo = BrowserInfo(
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
    )

    private fun record(
        index: Int,
        ipAddress: String? = "192.168.1.42",
    ) = PersistedConfirmAttempt(
        identityDigest = "digest-$index",
        keyValue = "key-$index",
        paymentIntentId = "int_$index",
        browserInfo = browserInfo,
        createdAt = 1_786_924_800_000L + index,
        ipAddress = ipAddress,
    )

    /** Writes a raw value straight into the production entry, bypassing the store. */
    private fun writeRawBlob(blob: String) {
        assertTrue(preferences.edit().putString(RECORDS_KEY, blob).commit())
    }

    private fun storedBlob(): String? = preferences.getString(RECORDS_KEY, null)

    // ---- Corruption: every shape degrades to empty ------------------------------------

    /**
     * A fresh install. Not corruption, but the same answer, and the baseline the corruption
     * cases are compared against.
     */
    @Test
    fun `an empty store loads as an empty list`() {
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /**
     * The realistic corruption: the process died partway through writing the file, so the
     * blob stops mid-token. `isLenient = false` on the shared codec is what makes this fail
     * loudly enough for the store to notice and discard.
     */
    @Test
    fun `a truncated blob loads as an empty list`() {
        val whole = ConfirmAttemptJson.instance.encodeToString(
            ListSerializer(PersistedConfirmAttempt.serializer()),
            listOf(record(1), record(2)),
        )
        writeRawBlob(whole.substring(0, whole.length / 2))

        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /** Valid JSON, wrong top-level type: an object where the reader expects an array. */
    @Test
    fun `a blob of the wrong json type loads as an empty list`() {
        writeRawBlob("""{"identity_digest":"digest-1"}""")

        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /** And the other wrong types, for completeness — a bare scalar and a bare string. */
    @Test
    fun `a blob that is a bare scalar loads as an empty list`() {
        writeRawBlob("42")
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())

        writeRawBlob("\"not a record list\"")
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())

        writeRawBlob("null")
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /**
     * An empty string is what a partially-cleared or zero-length write leaves behind. It is
     * not valid JSON, and it must not reach the parser as though it were.
     */
    @Test
    fun `an empty string blob loads as an empty list`() {
        writeRawBlob("")

        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /** Whitespace only — same class, and the one `isNullOrEmpty` alone does not catch. */
    @Test
    fun `a whitespace-only blob loads as an empty list`() {
        writeRawBlob("   \n  ")

        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /** Well-formed JSON in the right container, describing something that is not a record. */
    @Test
    fun `valid json of the wrong shape loads as an empty list`() {
        writeRawBlob("""[{"unrelated":"payload","count":3}]""")
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())

        writeRawBlob("""[[1,2,3]]""")
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /**
     * A record with the right keys but a wrong-typed value. `coerceInputValues = false`
     * matters here: a garbage `created_at` silently becoming `0` would read as a pin minted
     * in 1970 and be dropped as expired — the pin would disappear with nothing looking
     * broken.
     */
    @Test
    fun `a record with a wrong-typed field loads as an empty list`() {
        val whole = ConfirmAttemptJson.instance.encodeToString(
            ListSerializer(PersistedConfirmAttempt.serializer()),
            listOf(record(1)),
        )
        writeRawBlob(whole.replaceFirst("1786924800001", "\"not-a-timestamp\""))

        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /**
     * **The wholesale-discard rule, stated as a test.** One damaged record poisons the
     * blob; the good records beside it are dropped too.
     *
     * This looks wasteful and is deliberate. Salvaging would mean guessing which records
     * survived a write that demonstrably did not complete, and a *wrong* pinned key is a
     * replay the gateway rejects, whereas *no* pin is an honest fresh mint that the
     * pre-confirm intercept still guards. If someone ever "improves" this into per-record
     * recovery, this test is the argument against it.
     */
    @Test
    fun `one damaged record discards the whole blob rather than salvaging the rest`() {
        val whole = ConfirmAttemptJson.instance.encodeToString(
            ListSerializer(PersistedConfirmAttempt.serializer()),
            listOf(record(1), record(2), record(3)),
        )
        writeRawBlob(whole.replaceFirst("\"key_value\":\"key-2\"", "\"key_value\":17"))

        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /** A corrupt blob is inert, not contagious: reading it repeatedly stays harmless. */
    @Test
    fun `loading a corrupt blob repeatedly never throws`() {
        writeRawBlob("{not json at all")

        repeat(5) { assertTrue(store.load().isEmpty()) }
    }

    // ---- Recovery ---------------------------------------------------------------------

    /**
     * The degraded state must be exitable. After a corrupt blob is read as empty, the next
     * save has to land and the next load has to return it — otherwise one bad write would
     * disable relaunch recovery permanently on that device.
     */
    @Test
    fun `a save over a corrupted blob recovers cleanly`() {
        writeRawBlob("{\"truncated\": ")
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())

        val records = listOf(record(1), record(2))
        store.save(records)

        assertEquals(records, store.load())
    }

    /** The same, through a *new* store instance — which is what a relaunch actually is. */
    @Test
    fun `records written by one store instance are read by the next`() {
        val records = listOf(record(7), record(8))
        store.save(records)

        val afterRelaunch = PreferencesConfirmAttemptStore(context)

        assertEquals(records, afterRelaunch.load())
    }

    // ---- Round trip and ordering ------------------------------------------------------

    @Test
    fun `several records round trip unchanged`() {
        val records = listOf(record(1), record(2, ipAddress = null), record(3))

        store.save(records)

        val restored = store.load()
        assertEquals(records, restored)
        assertNull(restored[1].ipAddress)
        assertEquals(browserInfo, restored[0].browserInfo)
    }

    /**
     * **Insertion order is part of the contract.** The registry caps the store and evicts
     * the *oldest first*; with a wall clock the customer can set, list order is the more
     * trustworthy notion of oldest. A store that reordered — via a set, a map, or a sort
     * added "for determinism" — would silently change which pin gets thrown away.
     */
    @Test
    fun `insertion order survives save and load`() {
        // Deliberately an order no sort would produce: not by id, not by key, not by
        // timestamp, in neither direction. An ascending 1..8 fixture would pass against a
        // store that quietly sorted, which is the mutation this test exists to catch.
        val records = listOf(record(6), record(1), record(8), record(3), record(2))

        store.save(records)

        val restored = store.load()
        assertEquals(records, restored)
        assertEquals(listOf("int_6", "int_1", "int_8", "int_3", "int_2"), restored.map { it.paymentIntentId })
        assertEquals("int_6", restored.first().paymentIntentId)
        assertEquals("int_2", restored.last().paymentIntentId)
    }

    /**
     * The registry evicts oldest-**first**, so the head of the list is the record that goes.
     * Pinned separately from the general ordering test because the direction, not just the
     * stability, is what the eviction rule reads.
     */
    @Test
    fun `the oldest record is at the head where the eviction rule expects it`() {
        val oldest = record(1).copy(createdAt = 1_000L)
        val newest = record(2).copy(createdAt = 9_000L)

        store.save(listOf(oldest, newest))

        assertEquals(oldest, store.load().first())
        assertEquals(newest, store.load().last())
    }

    /** A later save replaces the set wholesale; nothing from the previous one leaks. */
    @Test
    fun `a save replaces the previous set rather than merging with it`() {
        store.save(listOf(record(1), record(2)))

        store.save(listOf(record(3)))

        assertEquals(listOf("int_3"), store.load().map { it.paymentIntentId })
    }

    /**
     * The key round trips byte-identically through storage, whatever its shape. The server
     * matches idempotency keys as opaque strings: a key that comes back one character
     * different is a different key, and a different key against a payment that may already
     * be authorising is a double charge.
     */
    @Test
    fun `an unusually shaped key survives storage byte-identically`() {
        val shapes = listOf(
            "6F4C2A1E-8B3D-4F9A-9C0E-2D7B5A1F4E63",
            "  padded-key-with-spaces  ",
            "not-a-uuid-at-all",
            "ключ-0027-🔑",
        )
        val records = shapes.mapIndexed { index, shape -> record(index).copy(keyValue = shape) }

        store.save(records)

        assertEquals(shapes, store.load().map { it.keyValue })
    }

    // ---- Clearing ---------------------------------------------------------------------

    /**
     * Saving nothing removes the entry rather than writing `"[]"`. Both read back as an
     * empty list, so the choice costs nothing — and it means a device with no live payments
     * leaves no residue at rest at all, instead of an empty artefact that looks like state.
     */
    @Test
    fun `saving an empty list clears the entry rather than writing an empty array`() {
        store.save(listOf(record(1)))
        assertNotNull(storedBlob())

        store.save(emptyList())

        assertNull("the store left an empty artefact behind", storedBlob())
        assertFalse(preferences.contains(RECORDS_KEY))
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /** Clearing an already-clear store is a no-op, not a failure. */
    @Test
    fun `saving an empty list over nothing is harmless`() {
        store.save(emptyList())
        store.save(emptyList())

        assertNull(storedBlob())
        assertEquals(emptyList<PersistedConfirmAttempt>(), store.load())
    }

    /** Clearing over corruption removes the corruption too. */
    @Test
    fun `saving an empty list clears a corrupted blob`() {
        writeRawBlob("{not json")

        store.save(emptyList())

        assertNull(storedBlob())
    }

    // ---- Write failure is never fatal --------------------------------------------------

    /**
     * The disk refuses the commit. `SharedPreferences.commit()` reports this as `false`
     * rather than throwing — which is exactly why `apply()` is not used: it cannot report
     * it at all, and a silent write failure on the pin path is the thing that would go
     * unnoticed until a customer was charged twice.
     */
    @Test
    fun `a commit that returns false does not throw`() {
        val recording = RecordingLogger()
        val failing = PreferencesConfirmAttemptStore(
            preferences = { RefusingPreferences(preferences, throwOnCommit = false) },
            logger = recording,
        )

        failing.save(listOf(record(1)))

        assertTrue("a refused write went unreported", recording.errors.isNotEmpty())
    }

    /** The commit throws outright — a full disk surfaces this way too. */
    @Test
    fun `a commit that throws does not escape save`() {
        val recording = RecordingLogger()
        val failing = PreferencesConfirmAttemptStore(
            preferences = { RefusingPreferences(preferences, throwOnCommit = true) },
            logger = recording,
        )

        failing.save(listOf(record(1)))
        failing.save(emptyList())

        assertEquals(2, recording.errors.size)
    }

    /**
     * The preferences file itself cannot be opened. This is not hypothetical: opening a
     * credential-encrypted preferences file before first unlock (direct boot) throws, and
     * a store that took the confirm path down in that state would be worse than useless.
     */
    @Test
    fun `a preferences file that cannot be opened degrades on both paths`() {
        val recording = RecordingLogger()
        val broken = PreferencesConfirmAttemptStore(
            preferences = { throw IllegalStateException("SharedPreferences in credential encrypted storage") },
            logger = recording,
        )

        assertEquals(emptyList<PersistedConfirmAttempt>(), broken.load())
        broken.save(listOf(record(1)))

        assertEquals(2, recording.errors.size)
    }

    /** A failed write leaves whatever was there before; it does not half-destroy it. */
    @Test
    fun `a failed write leaves the previous pins readable`() {
        val survivors = listOf(record(1))
        store.save(survivors)

        PreferencesConfirmAttemptStore(
            preferences = { RefusingPreferences(preferences, throwOnCommit = true) },
        ).save(listOf(record(2)))

        assertEquals(survivors, store.load())
    }

    /** The default logger discards, so a failure with no logger wired must still not throw. */
    @Test
    fun `a write failure with the default logger still does not throw`() {
        PreferencesConfirmAttemptStore(
            preferences = { RefusingPreferences(preferences, throwOnCommit = true) },
        ).save(listOf(record(1)))
    }

    // ---- What is logged, and what is not ------------------------------------------------

    /**
     * A `SerializationException` quotes the offending input, and the offending input is a
     * record carrying a live idempotency key. The logger must therefore never receive the
     * throwable or the blob — only enough to tell "the disk refused" from "the blob is
     * garbage". A merchant may have wired that logger to a crash reporter.
     */
    @Test
    fun `failure logs carry no key, no digest and no blob contents`() {
        val recording = RecordingLogger()
        writeRawBlob("""[{"key_value":"leaked-key-0001","identity_digest":"leaked-digest"}]""")

        PreferencesConfirmAttemptStore(preferences = { preferences }, logger = recording).load()

        assertTrue(recording.errors.isNotEmpty())
        val logged = recording.errors.joinToString("\n")
        assertFalse(logged.contains("leaked-key-0001"))
        assertFalse(logged.contains("leaked-digest"))
        assertFalse(logged.contains("key_value"))
        assertTrue("no throwable may be handed to the logger", recording.throwables.all { it == null })
    }

    // ---- The storage contract itself ----------------------------------------------------

    /**
     * The entry key is `.v1` and is frozen. `PersistedConfirmAttempt`'s field names are a
     * stored format that every future version must keep decoding, and version identity
     * lives in this key rather than in a field inside the blob. A change that renames it
     * orphans every pin already on a customer's device.
     */
    @Test
    fun `the blob is written under the frozen versioned key`() {
        store.save(listOf(record(1)))

        assertEquals("com.uqpay.sdk.confirm-pins.v1", RECORDS_KEY)
        assertEquals("com.uqpay.sdk.store", PREFERENCES_NAME)
        assertNotNull(preferences.getString("com.uqpay.sdk.confirm-pins.v1", null))
        assertEquals(setOf(RECORDS_KEY), preferences.all.keys)
    }

    /**
     * The store reads what the shared codec writes, and the shared codec reads what the
     * store writes. This is the WU-2.2 contract as a test: a second `Json` instance
     * configured here — however reasonably — would write a differently shaped blob and
     * orphan every pin already in the field.
     */
    @Test
    fun `the store uses the shared codec and no other`() {
        val serializer = ListSerializer(PersistedConfirmAttempt.serializer())
        val records = listOf(record(1), record(2, ipAddress = null))

        // What the store writes, the shared codec reads.
        store.save(records)
        val written = storedBlob()!!
        assertEquals(records, ConfirmAttemptJson.instance.decodeFromString(serializer, written))

        // …and the codec's durability settings are visible in the bytes the store itself
        // produced. Asserted on `written`, captured *before* anything else touches the
        // entry: a round trip alone proves nothing here, because `ip_address` has a default
        // and so an omitted null decodes back to an equal record. Only the bytes differ.
        assertTrue("the store's own blob omitted a null instead of writing it", written.contains("\"ip_address\":null"))
        assertFalse("the store's own blob was pretty-printed", written.contains("\n"))

        // And what the shared codec writes, the store reads.
        writeRawBlob(ConfirmAttemptJson.instance.encodeToString(serializer, records))
        assertEquals(records, store.load())
    }

    /**
     * **The failure a second `Json` instance would actually cause**, made reachable.
     *
     * `BrowserInfo.touchSupport` / `hardwareConcurrency` / `deviceMemory` are nullable
     * **without defaults**, which in kotlinx means *required present*. Under the shared
     * codec's `explicitNulls = true` they are written as explicit nulls and read back fine.
     * Under `explicitNulls = false` — a perfectly reasonable-looking setting, and the one
     * the network codec uses — they are omitted, and the very next read of that blob throws
     * `MissingFieldException` and the pin is gone.
     *
     * The blast radius is not this session: it is every pin already written by every
     * shipped version, on every device. This test fails the moment the store stops writing
     * through `ConfirmAttemptJson.instance`.
     */
    @Test
    fun `a record whose optional device fields are null survives storage`() {
        val sparse = record(1).copy(
            ipAddress = null,
            browserInfo = browserInfo.copy(
                touchSupport = null,
                hardwareConcurrency = null,
                deviceMemory = null,
                browser = browserInfo.browser.copy(
                    cookieEnabled = null,
                    plugins = null,
                    doNotTrack = null,
                ),
            ),
        )

        store.save(listOf(sparse))

        val blob = storedBlob()!!
        listOf(
            "\"touch_support\":null", "\"hardware_concurrency\":null", "\"device_memory\":null",
            "\"cookie_enabled\":null", "\"plugins\":null", "\"do_not_track\":null",
        ).forEach {
            assertTrue("a required-but-nullable field was omitted rather than written: $it", blob.contains(it))
        }
        assertEquals(listOf(sparse), store.load())
    }

    /**
     * Acceptance §4.3, asserted on the bytes that reach the disk. The at-rest blob is not
     * encrypted, and this is the test that keeps that decision defensible: if it ever stops
     * holding, the correct fix is to remove the field, not to add encryption.
     */
    @Test
    fun `nothing card-derived reaches the disk`() {
        store.save(listOf(record(1), record(2)))
        val blob = storedBlob()!!

        listOf(
            "pan", "cvc", "cvv", "csc", "card_number", "cardholder", "security_code",
            "expiry", "exp_month", "exp_year", "password", "email", "phone", "api_key",
        ).forEach {
            assertFalse("`$it` reached the persisted blob", blob.contains(it))
        }
        // A 13-19 digit run is what a PAN looks like; no field here may produce one.
        assertFalse(
            "a PAN-shaped digit run reached the persisted blob",
            Regex("\\d{13,19}").containsMatchIn(blob.replace(Regex("\"created_at\":\\d+"), "")),
        )
    }

    // ---- Test doubles -------------------------------------------------------------------

    /** Captures what the store tried to log, so the redaction rules can be asserted. */
    private class RecordingLogger : UQPayLogger {
        val errors = mutableListOf<String>()
        val throwables = mutableListOf<Throwable?>()

        override fun debug(message: String) = Unit

        override fun error(message: String, t: Throwable?) {
            errors += message
            throwables += t
        }
    }

    /**
     * Real preferences for reads, a hostile editor for writes — the shape a full disk or a
     * revoked file takes. Delegation keeps every other member honest, so the store is
     * exercised against a real `SharedPreferences` in every respect but the one under test.
     */
    private class RefusingPreferences(
        private val delegate: SharedPreferences,
        private val throwOnCommit: Boolean,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor =
            RefusingEditor(delegate.edit(), throwOnCommit)
    }

    private class RefusingEditor(
        delegate: SharedPreferences.Editor,
        private val throwOnCommit: Boolean,
    ) : SharedPreferences.Editor by delegate {
        override fun commit(): Boolean =
            if (throwOnCommit) throw IllegalStateException("no space left on device") else false
    }

    private companion object {
        /** Mirrors the production constants, which are private to the store by design. */
        const val PREFERENCES_NAME = "com.uqpay.sdk.store"
        const val RECORDS_KEY = "com.uqpay.sdk.confirm-pins.v1"
    }
}
