package com.uqpay.sdk.ui

import java.util.Locale

/**
 * One selectable billing country: the ISO 3166-1 alpha-2 code the confirm API wants, and a
 * name a customer recognises.
 *
 * @property code ISO 3166-1 alpha-2, uppercase, e.g. `SG`.
 * @property name the display name in the device's locale, e.g. "Singapore".
 */
internal data class Country(val code: String, val name: String)

/**
 * Every ISO 3166-1 alpha-2 country and territory, taken from the platform.
 *
 * ### Why this is a file and not a nine-entry `when`
 *
 * The shipped iOS SDK had exactly that: a hand-written table of nine countries, with `"US"`
 * as the fallback for everything it did not recognise. It did not fail loudly. It sent
 * `country_code: "US"` to the issuer for a customer in Indonesia, and for a customer
 * anywhere else outside those nine markets — wrong AVS, wrong risk data, on every single
 * payment, silently, for as long as it shipped. A hand-maintained subset of a standard list
 * is a bug with a delay fuse, which is why this one is not hand-maintained at all.
 *
 * The list comes from [Locale.getISOCountries], so it is the platform's own ISO table: about
 * 249 entries, updated with the OS, including the ones a hand-written list always forgets —
 * `AX` (Åland), `BQ` (Caribbean Netherlands), `SX` (Sint Maarten), `TL` (Timor-Leste), `XK`
 * where the platform carries it. iOS reaches the same place through `Locale.Region.isoRegions`;
 * this is the deliberate parallel, not a coincidence.
 *
 * The country a customer picks is the country that is sent. Nothing here substitutes,
 * guesses, or falls back to a default — [isValid] answers whether a code is a real ISO code
 * and the form refuses to submit without a selection, which together make a silent
 * substitution impossible rather than merely unlikely.
 */
internal object CountryCodes {

    /**
     * Every country, sorted by display name in the device's locale.
     *
     * Computed once. `getDisplayCountry` is not free and the list is ~249 long; a picker
     * that rebuilt it per recomposition would be noticeable on a low-end device.
     */
    val all: List<Country> by lazy {
        Locale.getISOCountries()
            .map { code -> Country(code, displayName(code)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** The set of valid codes, for [isValid]. Uppercase. */
    private val codes: Set<String> by lazy { all.mapTo(HashSet()) { it.code } }

    /**
     * True when [code] is a real ISO 3166-1 alpha-2 code.
     *
     * Case-insensitive on the way in, because a merchant-supplied default may be written
     * either way; what is *sent* is always the canonical uppercase form from [all].
     */
    fun isValid(code: String?): Boolean {
        val candidate = code?.trim()?.uppercase(Locale.ROOT) ?: return false
        return candidate in codes
    }

    /** [code] in canonical form when it is a real ISO code, otherwise null. */
    fun canonical(code: String?): String? {
        val candidate = code?.trim()?.uppercase(Locale.ROOT) ?: return null
        return candidate.takeIf { it in codes }
    }

    /**
     * The device's own region, as an opening selection — never as a fallback.
     *
     * Returns null when the device's region is not an ISO country this list knows, and the
     * form then starts with **no** selection rather than a guess. A wrong country the
     * customer never chose is the exact failure this whole file exists to prevent; making
     * them pick is the honest alternative.
     */
    fun deviceDefault(): String? = canonical(Locale.getDefault().country)

    /**
     * The countries whose name or code matches [query], or all of them for a blank query.
     *
     * Matches the **code** as well as the name so a customer who knows they want `SG` can
     * type it, and matches anywhere in the name rather than only at the start — "Korea"
     * should find "South Korea".
     */
    fun filter(query: String): List<Country> {
        val needle = query.trim()
        if (needle.isEmpty()) return all
        return all.filter {
            it.name.contains(needle, ignoreCase = true) || it.code.startsWith(needle, ignoreCase = true)
        }
    }

    private fun displayName(code: String): String {
        val name = Locale("", code).getDisplayCountry(Locale.getDefault())
        // `getDisplayCountry` echoes the code back when it has no localised name. Showing
        // the code is worse than nothing to read but far better than an empty row, which a
        // customer cannot select on purpose.
        return name.ifBlank { code }
    }
}
