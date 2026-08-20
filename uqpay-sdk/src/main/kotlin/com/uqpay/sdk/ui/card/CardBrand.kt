package com.uqpay.sdk.ui.card

/**
 * The card network a PAN belongs to, detected from its IIN (issuer identification number)
 * prefix, together with the two things the form needs from it: how long the security code
 * is, and how long the number may be.
 *
 * ### Unknown is usable, not blocked
 *
 * [UNKNOWN] is a first-class member with working defaults — a 3-digit CVC and 19 digits of
 * PAN — and never a reason to refuse a card. Prefix tables go stale: networks are issued new
 * IIN ranges continually (Mastercard's 2-series only appeared in 2017), and a table that
 * refused everything it did not recognise would decline real cards for the crime of being
 * newer than this release. Detection exists to *help* — to size the CVC field, group the
 * digits, and fill `network` on the wire — never to gate. Luhn and the length bounds decide
 * validity; see [CardValidation].
 *
 * ### The wire value
 *
 * [wireName] is what goes into `payment_method.card.network`. It is lowercase because the
 * API contract requires it (§9.1), and its spellings — including `dinersclub` and the
 * literal `unknown` — are the shipped iOS SDK's values verbatim
 * (`CardBrand.rawValue.lowercased()`, `PaymentCardViewController.swift:1238`). iOS is the
 * only client verified to work against this gateway, so its bytes are the reference; a
 * "tidier" spelling here would be an unverified change to a field the risk engine reads.
 *
 * @property wireName the lowercase `network` value sent on the confirm.
 * @property cvcLength how many digits the security code has. **Amex is 4, everything else
 *   is 3** — an audit finding (G20): a form that hard-codes 3 makes every Amex card
 *   impossible to enter, and one that hard-codes 4 does the same to every other card.
 * @property maxPanLength the **input bound** for the number field, never a validity rule.
 *   19 for everything except Amex, which is exactly 15. iOS shipped a 16-digit cap that
 *   made UnionPay's 19-digit numbers literally untypeable (G20); this is why the default
 *   is the maximum ISO/IEC 7812 allows rather than the most common length.
 * @property panGroups how the digits are grouped for display, e.g. `4-4-4-4-3`. Cosmetic
 *   only; [CardValidation] never sees the spaces.
 */
internal enum class CardBrand(
    val wireName: String,
    val cvcLength: Int,
    val maxPanLength: Int,
    val panGroups: List<Int>,
) {
    VISA("visa", cvcLength = 3, maxPanLength = 19, panGroups = GROUPS_OF_FOUR),
    MASTERCARD("mastercard", cvcLength = 3, maxPanLength = 19, panGroups = GROUPS_OF_FOUR),

    /** 15 digits, grouped 4-6-5, and the only brand with a 4-digit security code. */
    AMEX("amex", cvcLength = 4, maxPanLength = 15, panGroups = listOf(4, 6, 5)),

    UNIONPAY("unionpay", cvcLength = 3, maxPanLength = 19, panGroups = GROUPS_OF_FOUR),
    JCB("jcb", cvcLength = 3, maxPanLength = 19, panGroups = GROUPS_OF_FOUR),
    DISCOVER("discover", cvcLength = 3, maxPanLength = 19, panGroups = GROUPS_OF_FOUR),

    /** Classic Diners numbers are 14 digits, grouped 4-6-4; co-badged ones run to 19. */
    DINERS("dinersclub", cvcLength = 3, maxPanLength = 19, panGroups = listOf(4, 6, 4)),

    /**
     * A prefix this SDK version does not recognise. Fully payable: 3-digit CVC, up to 19
     * digits, grouped in fours. See the class KDoc.
     */
    UNKNOWN("unknown", cvcLength = 3, maxPanLength = 19, panGroups = GROUPS_OF_FOUR),
    ;

    internal companion object {

        /**
         * The brand for [pan], from its leading digits. Never throws, never returns null,
         * and answers on a partially typed number so the form can size the CVC field while
         * the customer is still typing.
         *
         * The rules are the shipped iOS SDK's (`CardValidator.brand(for:)`), which are the
         * ones that have actually been exercised against this gateway. Order matters: the
         * Discover 65 range and the Diners 36/38 ranges overlap nothing here, but the
         * Mastercard 2-series (2221–2720) and JCB (3528–3589) both need four digits before
         * they can be told apart from their neighbours, so they are tested by numeric range
         * rather than by string prefix and simply do not match until enough has been typed.
         */
        fun of(pan: String): CardBrand {
            val digits = pan.filter(Char::isDigit)
            if (digits.isEmpty()) return UNKNOWN
            if (digits.startsWith("4")) return VISA
            if (digits.startsWithAny("51", "52", "53", "54", "55")) return MASTERCARD
            numericPrefix(digits, 4)?.let { if (it in 2221..2720) return MASTERCARD }
            if (digits.startsWithAny("34", "37")) return AMEX
            if (digits.startsWith("6011") || digits.startsWith("65")) return DISCOVER
            numericPrefix(digits, 3)?.let { if (it in 644..649) return DISCOVER }
            numericPrefix(digits, 4)?.let { if (it in 3528..3589) return JCB }
            if (digits.startsWithAny("300", "301", "302", "303", "304", "305", "36", "38")) return DINERS
            if (digits.startsWith("62")) return UNIONPAY
            return UNKNOWN
        }

        /**
         * The first [length] digits as a number, or null when fewer have been typed.
         *
         * A range test on a short prefix is not merely inaccurate, it is wrong in a
         * dangerous direction: `"2"` read as the number 2 falls outside 2221–2720 and
         * `"25"` read as 25 does too, so a naive implementation would flip the brand back
         * and forth as the customer types. Returning null until the digits exist keeps the
         * answer [UNKNOWN] — which is usable — rather than briefly wrong.
         */
        private fun numericPrefix(digits: String, length: Int): Int? =
            if (digits.length < length) null else digits.take(length).toIntOrNull()

        private fun String.startsWithAny(vararg prefixes: String): Boolean =
            prefixes.any { startsWith(it) }
    }
}

/** Groups of four, the layout every brand but Amex and Diners uses. */
private val GROUPS_OF_FOUR: List<Int> = listOf(4, 4, 4, 4, 3)
