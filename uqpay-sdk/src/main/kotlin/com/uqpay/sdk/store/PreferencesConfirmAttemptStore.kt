package com.uqpay.sdk.store

import android.content.Context
import android.content.SharedPreferences
import com.uqpay.sdk.network.UQPayLogger
import kotlinx.serialization.builtins.ListSerializer

/**
 * The production [ConfirmAttemptStore]: one JSON blob in app-private `SharedPreferences`.
 *
 * ### Why plain `SharedPreferences`, and not `EncryptedSharedPreferences`
 *
 * `ios-requirements.md` §5.1 suggested `androidx.security-crypto`; that suggestion is
 * **superseded** (execution plan §7 item 3). Two reasons, in order of weight:
 *
 * 1. **There is no secret here to protect.** A [PersistedConfirmAttempt] holds a SHA-256
 *    digest over BIN-and-last-4 identity fields, an opaque UUID, coarse device metrics and
 *    a timestamp. No card data (no PAN, no CVC, no expiry, no token) and no directly
 *    identifying PII — by construction, and a test in `ConfirmAttemptRecordTest` enforces
 *    that no field capable of holding any of them can be added. What *is* stored beyond
 *    the key is pseudonymous: coarse device metrics (model, OS version, language,
 *    timezone, screen size), the device IP, and a pseudonymous device id (`ANDROID_ID`),
 *    frozen so a replay is byte-identical. Encryption would protect a threat model this
 *    data does not have.
 * 2. **`androidx.security-crypto` is deprecated**, and adding a deprecated dependency to a
 *    payment SDK's transitive closure imposes it on every merchant who integrates us.
 *
 * The residual exposure is a rooted or backed-up device reading an idempotency key — which
 * lets an attacker who already has that access *replay a payment they cannot construct the
 * body for*. Set against a hard dependency and a known-deprecated library, that trade is
 * not close.
 *
 * ### Platform divergence from iOS — deliberate, bounded, accepted
 *
 * iOS stores this blob in the **Keychain**, whose items survive an app reinstall, and that
 * is not incidental: a customer who reinstalls mid-confirm is still holding the same
 * possibly-processing payment, and the pin should still be there when they return.
 *
 * **`SharedPreferences` does not survive a reinstall.** Android has no equivalent that does
 * without inheriting a worse problem (auto-backup would put pins in Google's cloud, off the
 * device, which is a strictly larger exposure than the one it fixes). So the window is
 * accepted, and it is bounded by the two mechanisms either side of it:
 *
 * - The server's idempotency window is **24h**, and a reinstall-and-relaunch inside a
 *   single confirm is a small slice of it.
 * - The **pre-confirm intercept** re-reads the intent before any pin is minted, so a
 *   payment that already settled during the lost window is reported as settled rather than
 *   confirmed a second time.
 *
 * Losing the pin therefore degrades to "the intercept catches it", not to "double charge".
 * The TTL is nonetheless enforced on read by the registry, exactly as on iOS: no uninstall,
 * reinstall or launch hook can be trusted to have pruned first.
 *
 * ### Degraded mode is the design, not the error path
 *
 * Storage failure has two shapes and both are survivable:
 *
 * - **An unreadable blob** — truncated by a kill mid-write, wrong type, a shape from some
 *   future version this build cannot parse — yields `emptyList()`. The whole blob is
 *   discarded rather than salvaged record by record: a half-parsed pin is worse than no
 *   pin, because a *wrong* key is a replay the gateway rejects while *no* key is an honest
 *   fresh mint that the pre-confirm intercept still guards.
 * - **A failed write** logs and continues. The registry's in-memory pin already protects
 *   the current session; only recovery after process death is lost. Throwing here would
 *   turn a degradation into a failed payment.
 *
 * ### Threading
 *
 * Safe from any thread — `SharedPreferences` is internally synchronised, and each call
 * here is one read or one atomic commit. Two concurrent [save] calls are last-writer-wins;
 * the registry serialises its own writes, so that ordering is decided above this layer.
 *
 * @param preferences resolved lazily, per call, rather than held. Opening a
 *   credential-encrypted preferences file throws before first unlock (direct boot), and a
 *   store that cannot be *constructed* in that state would take the confirm path down with
 *   it. Resolving per call turns it into the ordinary degraded read this class already
 *   handles.
 * @param logger defaults to the discarding logger, matching the rest of the SDK. Nothing
 *   sensitive is passed to it — see [logFailure].
 */
internal class PreferencesConfirmAttemptStore internal constructor(
    private val preferences: () -> SharedPreferences,
    private val logger: UQPayLogger = UQPayLogger.Noop,
) : ConfirmAttemptStore {

    /** The production entry point: app-private preferences owned entirely by the SDK. */
    constructor(
        context: Context,
        logger: UQPayLogger = UQPayLogger.Noop,
    ) : this(applicationPrivatePreferences(context), logger)

    /**
     * Reads the pins, or returns empty for any reason at all.
     *
     * An absent entry and an unreadable entry deliberately produce the same answer. The
     * caller has nothing different to do with "there were pins but I could not read them",
     * and giving it a second state to handle would only create a path where it does
     * something other than mint a fresh key.
     */
    override fun load(): List<PersistedConfirmAttempt> {
        val blob = try {
            preferences().getString(RECORDS_KEY, null)
        } catch (failure: Exception) {
            logFailure("pin store unreadable", failure)
            return emptyList()
        }
        if (blob.isNullOrEmpty()) return emptyList()

        return try {
            ConfirmAttemptJson.instance.decodeFromString(RECORDS_SERIALIZER, blob)
        } catch (failure: Exception) {
            // Wholesale discard. See the class KDoc: a partially recovered pin set is a
            // worse outcome than none, so nothing here tries to salvage individual records.
            logFailure("pin blob discarded", failure)
            emptyList()
        }
    }

    /**
     * Replaces the stored set, in order.
     *
     * **`commit()`, not `apply()`** — and this is the one performance trade in the class
     * worth defending. `apply()` returns as soon as the in-memory map is updated and
     * flushes to disk on a background thread; if Android kills the process in that gap, the
     * write is simply gone. The entire purpose of this file is to survive a process death
     * that may arrive at any instant, including the instant after a pin is written and
     * before the confirm is sent. An asynchronous write is not durable at exactly the
     * moment durability is the point. The caller is on an IO dispatcher; a few milliseconds
     * of disk is the correct price.
     *
     * `commit()` also *reports* failure, which `apply()` cannot, and a silent write failure
     * on the pin path is precisely the thing that would go unnoticed until a customer was
     * charged twice.
     */
    override fun save(records: List<PersistedConfirmAttempt>) {
        try {
            val editor = preferences().edit()
            if (records.isEmpty()) {
                // Remove rather than write "[]". Both read back as an empty list, so this
                // costs nothing, and it means a device with no live payments leaves no
                // residue at rest at all rather than an empty artefact that looks like
                // state. Removal is also what makes "clear the store" a real clear.
                editor.remove(RECORDS_KEY)
            } else {
                editor.putString(
                    RECORDS_KEY,
                    ConfirmAttemptJson.instance.encodeToString(RECORDS_SERIALIZER, records),
                )
            }
            if (!editor.commit()) {
                logFailure("pin write rejected", null)
            }
        } catch (failure: Exception) {
            // Log and continue, never rethrow. The in-memory pin still protects this
            // session; only relaunch recovery is lost.
            logFailure("pin write failed", failure)
        }
    }

    /**
     * Logs a storage failure **without the throwable and without the blob**.
     *
     * Deliberately lossy. A `SerializationException` from kotlinx quotes the offending
     * input in its message, and the offending input here is a persisted attempt containing
     * a live idempotency key — a credential for replaying a payment. Handing the throwable
     * to a logger a merchant may have wired to Logcat or a crash reporter would publish it.
     * The exception's simple name is enough to tell "the disk refused" from "the blob is
     * garbage", which is the only distinction a reader of this log can act on.
     */
    private fun logFailure(what: String, failure: Exception?) {
        val cause = failure?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        logger.error(
            "UQPay: $what$cause; recovery after process death is unavailable for this session",
        )
    }

    private companion object {

        /**
         * The SDK's own preferences file. A dedicated file rather than the merchant app's
         * default one, so the SDK can never read, overwrite or be confused by host-app
         * state, and so everything the SDK persists can be reasoned about in one place.
         */
        const val PREFERENCES_NAME = "com.uqpay.sdk.store"

        /**
         * The versioned entry key from the iOS design, carried over verbatim.
         *
         * The `.v1` is **not decoration**: [PersistedConfirmAttempt]'s field names are a
         * stored wire format that every future SDK version must keep decoding. Version
         * identity lives here, in the key, rather than in a field inside the blob — one
         * marker, not two that can disagree. A future change that genuinely cannot decode
         * v1 blobs takes a new key and leaves this one alone; it does not redefine v1 in
         * place, because the blobs written under it are already on customers' devices.
         */
        const val RECORDS_KEY = "com.uqpay.sdk.confirm-pins.v1"

        /**
         * Built once from the shared codec object.
         *
         * The `Json` instance is `ConfirmAttemptJson.instance` and **no other**. Its
         * settings — `ignoreUnknownKeys`, `encodeDefaults`, `explicitNulls`, strictness —
         * are properties of the stored *format*, not of this storage medium, and they are
         * documented as durability decisions where they are declared. A second `Json`
         * configured here, however reasonably, would write a differently shaped blob and
         * orphan every pin already persisted in the field.
         */
        val RECORDS_SERIALIZER = ListSerializer(PersistedConfirmAttempt.serializer())

        /**
         * Resolves the application context **eagerly** (so an Activity passed in is not
         * captured and leaked for the life of the store) while leaving the preferences file
         * itself to be opened lazily on each call.
         */
        fun applicationPrivatePreferences(context: Context): () -> SharedPreferences {
            val appContext = context.applicationContext ?: context
            return { appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
        }
    }
}
