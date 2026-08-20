package com.uqpay.sdk.store

import android.content.Context
import com.uqpay.sdk.network.UQPayLogger
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileOutputStream

/**
 * The production [ConfirmAttemptStore]: one JSON blob in a file the platform's backup system
 * is contractually required to ignore.
 *
 * ### Why a file in `no_backup`, and not `SharedPreferences`
 *
 * This started life as a `SharedPreferences` store, which was the obvious choice and the
 * wrong one. Every `SharedPreferences` file lives in `shared_prefs/` inside the app's data
 * directory, and that directory is exactly what **Android Auto Backup** uploads. `allowBackup`
 * defaults to `true`, so the default is that it is uploaded, from **every merchant app that
 * integrates this SDK**, unless that merchant knew to write backup rules for a file they have
 * never heard of.
 *
 * A library cannot fix that declaratively. `android:fullBackupContent` and
 * `android:dataExtractionRules` are *application* attributes: a library that sets them either
 * silently overrides the merchant's own rules or fails their build with a manifest-merger
 * conflict. The mechanism Android does offer a library is
 * [Context.getNoBackupFilesDir], which is documented as excluded from backup and needs
 * nothing from the host app at all. So the blob moved there.
 *
 * ### What was leaving the device, and what it could do on arrival
 *
 * A [PersistedConfirmAttempt] holds no card data — that is enforced by
 * `ConfirmAttemptRecordTest` and is why this file is not encrypted — but it is not nothing:
 * a live idempotency key, `ANDROID_ID`, the device IP and the frozen device fingerprint.
 * Two distinct problems, and the second is the expensive one:
 *
 * 1. **Privacy.** A pseudonymous device id and an IP address leave the device and land in a
 *    cloud backup, for every merchant, by default. The store's own reasoning for shipping
 *    unencrypted rests on the data staying app-private on one device.
 * 2. **Money.** A pin is only useful with a byte-identical body, and the frozen fingerprint
 *    that makes the body identical is persisted *beside it* — so a restored backup restores a
 *    working replay. Inside the gateway's 24h idempotency window, a customer who restores to
 *    a new phone and pays the same card again can have that genuinely new payment collapsed
 *    onto the old attempt: they are shown a success, and the merchant is paid once for two
 *    orders. Rare, and not a risk to take for a file that had no reason to travel.
 *
 * ### The legacy file is deleted, not migrated
 *
 * Any blob written by the `SharedPreferences` version is removed the first time this store
 * reads (see [deleteLegacyStoreOnce]) rather than carried across. Migrating would preserve a
 * pin for a payment that was mid-confirm when the app was *updated* — a process death of the
 * rarest kind — at the price of copying the exposure forward. Losing a pin degrades to the
 * pre-confirm intercept, which re-reads the intent and reports an already-settled payment as
 * settled; that is the same safety net the reinstall case has always relied on.
 *
 * ### Durability: temp file, `fsync`, atomic rename
 *
 * The whole point of this file is to survive a process death that may arrive at any instant,
 * including between writing a pin and sending the confirm it belongs to. So [save] writes a
 * sibling temp file, forces it to disk with [java.io.FileDescriptor.sync], and renames it over
 * the target — a rename within one directory is atomic, so a reader sees either the old blob
 * or the new one and never a half-written one. `SharedPreferences.commit()` bought the same
 * durability and this buys the atomicity too; a kill mid-write can no longer leave the
 * truncated blob that [load] has to defend against.
 *
 * ### Degraded mode is the design, not the error path
 *
 * Unchanged from the preferences store, because it is a property of the contract rather than
 * of the medium:
 *
 * - **An unreadable blob** — truncated by an older version's kill mid-write, wrong type, a
 *   shape from some future version this build cannot parse — yields `emptyList()`. The whole
 *   blob is discarded rather than salvaged record by record: a half-parsed pin is worse than
 *   no pin, because a *wrong* key is a replay the gateway rejects while *no* key is an honest
 *   fresh mint that the pre-confirm intercept still guards.
 * - **A failed write** logs and continues. The registry's in-memory pin already protects the
 *   current session; only recovery after process death is lost. Throwing here would turn a
 *   degradation into a failed payment.
 *
 * ### Platform divergence from iOS — deliberate, bounded, accepted
 *
 * iOS stores this blob in the **Keychain**, whose items survive an app reinstall, and that is
 * not incidental: a customer who reinstalls mid-confirm is still holding the same
 * possibly-processing payment, and the pin should still be there when they return.
 *
 * Android has no equivalent that does not inherit a worse problem — the one this class exists
 * to close. So the window is accepted, and it is bounded by the two mechanisms either side of
 * it: the server's idempotency window is **24h**, and the **pre-confirm intercept** re-reads
 * the intent before any pin is minted. Losing the pin degrades to "the intercept catches it",
 * not to "double charge". The TTL is nonetheless enforced on read by the registry, exactly as
 * on iOS: no uninstall, reinstall or launch hook can be trusted to have pruned first.
 *
 * ### Threading
 *
 * Safe from any thread: every read and write holds [fileLock], so a save cannot interleave
 * with another save's rename or with a load. Two concurrent [save] calls are last-writer-wins;
 * the registry serialises its own writes, so that ordering is decided above this layer.
 *
 * All disk work happens inside [load] and [save] and **never at construction** — the graph is
 * built on the caller's thread, which is the main thread (`MainThreadIoTest`), while these two
 * are only ever called from the confirm path's IO dispatcher.
 *
 * @param file resolved lazily, per call, rather than held. Resolving the app's no-backup
 *   directory can fail before first unlock (direct boot), and a store that cannot be
 *   *constructed* in that state would take the confirm path down with it. Resolving per call
 *   turns it into the ordinary degraded read this class already handles.
 * @param legacyCleanup removes whatever the previous storage medium left behind, once per
 *   store. Called from [load], on the IO dispatcher, never from the constructor.
 * @param logger defaults to the discarding logger, matching the rest of the SDK. Nothing
 *   sensitive is passed to it — see [logFailure].
 */
internal class NoBackupConfirmAttemptStore internal constructor(
    private val file: () -> File,
    private val logger: UQPayLogger = UQPayLogger.Noop,
    private val legacyCleanup: () -> Unit = {},
) : ConfirmAttemptStore {

    /** The production entry point: a file in the app's no-backup directory, owned by the SDK. */
    constructor(
        context: Context,
        logger: UQPayLogger = UQPayLogger.Noop,
    ) : this(noBackupFile(context), logger, legacyPreferencesCleanup(context))

    /** Serialises this store's own reads and writes. Never held across anything but disk I/O. */
    private val fileLock = Any()

    @Volatile
    private var legacyCleaned = false

    /**
     * Reads the pins, or returns empty for any reason at all.
     *
     * An absent file and an unreadable file deliberately produce the same answer. The caller
     * has nothing different to do with "there were pins but I could not read them", and giving
     * it a second state to handle would only create a path where it does something other than
     * mint a fresh key.
     */
    override fun load(): List<PersistedConfirmAttempt> {
        deleteLegacyStoreOnce()
        val blob = synchronized(fileLock) {
            try {
                file().takeIf { it.isFile }?.readText()
            } catch (failure: Exception) {
                logFailure("pin store unreadable", failure)
                return emptyList()
            }
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
     * Replaces the stored set, in order, durably and atomically. See the class KDoc.
     *
     * An empty set **deletes** the file rather than writing `[]`. Both read back as an empty
     * list, so this costs nothing, and it means a device with no live payments leaves no
     * residue at rest at all rather than an empty artefact that looks like state. Deletion is
     * also what makes "clear the store" a real clear.
     */
    override fun save(records: List<PersistedConfirmAttempt>) {
        try {
            val target = file()
            synchronized(fileLock) {
                if (records.isEmpty()) {
                    if (target.exists() && !target.delete()) logFailure("pin file could not be removed", null)
                    return
                }
                val encoded = ConfirmAttemptJson.instance.encodeToString(RECORDS_SERIALIZER, records)
                writeAtomically(target, encoded)
            }
        } catch (failure: Exception) {
            // Log and continue, never rethrow. The in-memory pin still protects this
            // session; only relaunch recovery is lost.
            logFailure("pin write failed", failure)
        }
    }

    /**
     * Writes [content] to [target] so that a reader sees all of it or none of it.
     *
     * `sync()` before the rename is the part that matters and the part most easily dropped:
     * without it the rename can reach the directory before the bytes reach the disk, and a
     * power loss leaves an atomically-renamed empty file — durable in name only.
     *
     * The temp file is named after the target, so a crash between the write and the rename
     * leaves one identifiable stray file that the next successful write replaces.
     */
    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.takeIf { !it.exists() }?.mkdirs()
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            // `renameTo` refuses to overwrite on some filesystems; delete first, which is why
            // a crash in this window costs the pins rather than corrupting them. `load` reads
            // an absent file as "no pins", which is the safe direction.
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) logFailure("pin write could not be committed", null)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Removes the `SharedPreferences` file this store replaced, the first time it is asked
     * for pins.
     *
     * Once per store instance, and there is one store per process. Done here rather than in
     * the constructor because the constructor runs on the main thread as part of building the
     * payment graph, and deleting a file is disk I/O (`MainThreadIoTest`).
     */
    private fun deleteLegacyStoreOnce() {
        if (legacyCleaned) return
        legacyCleaned = true
        try {
            legacyCleanup()
        } catch (failure: Exception) {
            // A merchant app that cannot delete its own preferences file is not a reason to
            // fail a payment; the worst case is that the old blob is still there, which is
            // where it was already.
            logFailure("legacy pin store could not be removed", failure)
        }
    }

    /**
     * Logs a storage failure **without the throwable and without the blob**.
     *
     * Deliberately lossy. A `SerializationException` from kotlinx quotes the offending input
     * in its message, and the offending input here is a persisted attempt containing a live
     * idempotency key — a credential for replaying a payment. Handing the throwable to a
     * logger a merchant may have wired to Logcat or a crash reporter would publish it. The
     * exception's simple name is enough to tell "the disk refused" from "the blob is
     * garbage", which is the only distinction a reader of this log can act on.
     */
    private fun logFailure(what: String, failure: Exception?) {
        val cause = failure?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        logger.error(
            "UQPay: $what$cause; recovery after process death is unavailable for this session",
        )
    }

    internal companion object {

        /**
         * The SDK's own file, inside the app's no-backup directory. A dedicated file rather
         * than anything shared with the host app, so the SDK can never read, overwrite or be
         * confused by host-app state, and so everything the SDK persists can be reasoned about
         * in one place.
         *
         * The `.v1` is **not decoration**: [PersistedConfirmAttempt]'s field names are a stored
         * wire format that every future SDK version must keep decoding. Version identity lives
         * in the name, rather than in a field inside the blob — one marker, not two that can
         * disagree. A future change that genuinely cannot decode v1 blobs takes a new name and
         * leaves this one alone; it does not redefine v1 in place, because the blobs written
         * under it are already on customers' devices.
         */
        const val FILE_NAME: String = "com.uqpay.sdk.confirm-pins.v1.json"

        /** The `SharedPreferences` file this store replaced. Deleted, never read. */
        const val LEGACY_PREFERENCES_NAME: String = "com.uqpay.sdk.store"

        private const val TEMP_SUFFIX = ".tmp"

        /**
         * Built once from the shared codec object.
         *
         * The `Json` instance is `ConfirmAttemptJson.instance` and **no other**. Its settings —
         * `ignoreUnknownKeys`, `encodeDefaults`, `explicitNulls`, strictness — are properties of
         * the stored *format*, not of this storage medium, and they are documented as durability
         * decisions where they are declared. A second `Json` configured here, however
         * reasonably, would write a differently shaped blob and orphan every pin already
         * persisted in the field.
         */
        val RECORDS_SERIALIZER = ListSerializer(PersistedConfirmAttempt.serializer())

        /**
         * Resolves the application context **eagerly** (so an Activity passed in is not
         * captured and leaked for the life of the store) while leaving the directory itself to
         * be resolved lazily on each call.
         */
        fun noBackupFile(context: Context): () -> File {
            val appContext = context.applicationContext ?: context
            return { File(appContext.noBackupFilesDir, FILE_NAME) }
        }

        /** Deletes the pre-`no_backup` preferences file. `deleteSharedPreferences` is API 24. */
        fun legacyPreferencesCleanup(context: Context): () -> Unit {
            val appContext = context.applicationContext ?: context
            return { appContext.deleteSharedPreferences(LEGACY_PREFERENCES_NAME) }
        }
    }
}
