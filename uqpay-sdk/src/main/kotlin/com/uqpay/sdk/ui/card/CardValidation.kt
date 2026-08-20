package com.uqpay.sdk.ui.card

import com.uqpay.sdk.ui.CountryCodes
import java.util.Calendar
import java.util.Locale

/**
 * The fields a card form collects and can therefore report a problem against.
 *
 * The result is per-field ([CardValidationResult]) rather than a single boolean because a
 * form that only knows "something is wrong" has to say so next to nothing, and the customer
 * has to re-read four fields to find the typo. Which field is wrong is information we
 * already have; throwing it away is a choice, and it is the wrong one.
 */
internal enum class CardField { PAN, EXPIRY, CVC, CARDHOLDER_NAME, BILLING_COUNTRY }

/**
 * What is wrong with a field. Stable identifiers, not display text — the copy lives in
 * `strings.xml` and is chosen by the form.
 */
internal enum class CardFieldError {

    /** Nothing was entered at all. */
    MISSING,

    /** Fewer than [CardValidation.MIN_PAN_DIGITS] digits, or more than 19. */
    PAN_LENGTH,

    /** The check digit does not agree with the rest of the number — almost always a typo. */
    PAN_LUHN,

    /** Not `MM/YY`, `MMYY`, `MM/YYYY` — the shape could not be read at all. */
    EXPIRY_MALFORMED,

    /** A month that does not exist: `00`, `13`, `99`. */
    EXPIRY_MONTH,

    /** A real month, already gone. The current month is **not** past. */
    EXPIRY_PAST,

    /** The wrong number of digits for the brand: 4 for Amex, 3 for everything else. */
    CVC_LENGTH,

    /** Not an ISO 3166-1 alpha-2 code. See [CountryCodes]. */
    COUNTRY_UNKNOWN,
}

/**
 * A month and a year, as the wire wants them: `"07"` and `"2029"`.
 *
 * @property month two digits, zero-padded.
 * @property year four digits. A two-digit `29` becomes `2029` — the same 2000-offset rule
 *   iOS applies (`convertToFourDigitYear`). Cards are not issued with 20th-century expiry
 *   dates, so the ambiguity is theoretical.
 */
internal data class ExpiryDate(val month: String, val year: String) {

    /** The month as a number, 1–12. */
    val monthNumber: Int get() = month.toInt()

    /** The year as a four-digit number. */
    val yearNumber: Int get() = year.toInt()
}

/**
 * The outcome of validating a card form.
 *
 * @property brand the detected brand, always present — it is what sized the CVC check, and
 *   the form needs it whether the form is valid or not (to label the field, and to fill
 *   `network` on the wire).
 * @property expiry the parsed expiry, when it could be parsed at all. Present even when the
 *   date is in the past, so a caller can say *what* it read.
 * @property errors one entry per bad field. Empty means valid.
 */
internal data class CardValidationResult(
    val brand: CardBrand,
    val expiry: ExpiryDate?,
    val errors: Map<CardField, CardFieldError>,
) {
    /** True when nothing is wrong. The only thing that may unlock the Pay button. */
    val isValid: Boolean get() = errors.isEmpty()

    /** What is wrong with [field], or null. */
    operator fun get(field: CardField): CardFieldError? = errors[field]
}

/**
 * Everything a card form must check before a confirm is sent. Pure Kotlin — no Android, no
 * Compose, no clock it cannot be handed — so every rule below is testable without a device.
 *
 * ### Why the client validates at all
 *
 * It would be simpler to send whatever was typed and let the acquirer answer. iOS did that,
 * and the audit recorded what it cost: a mistyped digit and an expired card both travelled
 * to the acquirer and came back as **declines**. A decline is a worse message ("your card
 * was declined" for what is really "you typed a 3 instead of an 8"), a slower one, and it
 * spends a real authorisation attempt — enough of which, on a real card, trip the issuer's
 * own velocity rules and start declining payments that would otherwise have worked. Luhn
 * costs a microsecond and catches the single-digit typo and the transposition, which is
 * what a mistyped card number nearly always is.
 *
 * ### What it deliberately does not check
 *
 * Not "does this number belong to a real card" — only the issuer knows that. Not brand-exact
 * lengths: a table that insists Visa is 16 digits declines the 19-digit Visa numbers that
 * exist, and this SDK already has one shipped bug of exactly that shape (G20 — a 16-digit
 * cap that made UnionPay cards untypeable). The bounds here are the standard's own, 13 to
 * 19 digits, and the check digit does the rest.
 */
internal object CardValidation {

    /** ISO/IEC 7812 allows 12; no live network issues below 13, and iOS uses 13. */
    const val MIN_PAN_DIGITS: Int = 13

    /**
     * ISO/IEC 7812's maximum. **19, not 16** — UnionPay, and some Visa co-badged products,
     * issue 19-digit numbers, and a 16-digit ceiling makes those cards impossible to pay
     * with (G20). This is the audit finding this constant exists to pin.
     */
    const val MAX_PAN_DIGITS: Int = 19

    /**
     * Validates a whole card form.
     *
     * @param pan the number as typed; spaces and any other formatting are stripped here, so
     *   a caller may pass either the display text or the digits.
     * @param expiry `MM/YY`, `MM / YY`, `MMYY` or `MM/YYYY`; slashes and spaces optional.
     * @param cvc the security code as typed.
     * @param cardholderName the full name; blank or whitespace is [CardFieldError.MISSING].
     * @param billingCountryCode the customer's **selected** ISO code, or null when they have
     *   not chosen. Null is an error, never a default — see [CountryCodes].
     * @param today the month to measure "expired" against, injected so the past-expiry rule
     *   is testable. Defaults to the device's current month.
     */
    fun validate(
        pan: String,
        expiry: String,
        cvc: String,
        cardholderName: String,
        billingCountryCode: String?,
        today: YearMonth = YearMonth.now(),
    ): CardValidationResult {
        val digits = pan.filter(Char::isDigit)
        val brand = CardBrand.of(digits)
        val errors = LinkedHashMap<CardField, CardFieldError>()

        panError(digits)?.let { errors[CardField.PAN] = it }

        val parsed = parseExpiry(expiry)
        expiryError(expiry, parsed, today)?.let { errors[CardField.EXPIRY] = it }

        cvcError(cvc, brand)?.let { errors[CardField.CVC] = it }

        if (cardholderName.isBlank()) errors[CardField.CARDHOLDER_NAME] = CardFieldError.MISSING

        if (billingCountryCode.isNullOrBlank()) {
            errors[CardField.BILLING_COUNTRY] = CardFieldError.MISSING
        } else if (!CountryCodes.isValid(billingCountryCode)) {
            // Reached only if something bypassed the picker. Refusing is the point: the
            // alternative — substituting a default — is the shipped iOS bug (CountryCodes).
            errors[CardField.BILLING_COUNTRY] = CardFieldError.COUNTRY_UNKNOWN
        }

        return CardValidationResult(brand = brand, expiry = parsed, errors = errors)
    }

    private fun panError(digits: String): CardFieldError? = when {
        digits.isEmpty() -> CardFieldError.MISSING
        digits.length !in MIN_PAN_DIGITS..MAX_PAN_DIGITS -> CardFieldError.PAN_LENGTH
        !isLuhnValid(digits) -> CardFieldError.PAN_LUHN
        else -> null
    }

    private fun expiryError(raw: String, parsed: ExpiryDate?, today: YearMonth): CardFieldError? = when {
        raw.isBlank() -> CardFieldError.MISSING
        parsed == null -> malformedOrBadMonth(raw)
        isPast(parsed, today) -> CardFieldError.EXPIRY_PAST
        else -> null
    }

    /**
     * Tells "13/30" (a readable date naming a month that does not exist) apart from "hello"
     * (not a date at all), so the form can say which.
     */
    private fun malformedOrBadMonth(raw: String): CardFieldError {
        val digits = raw.filter(Char::isDigit)
        if (digits.length != 4 && digits.length != 6) return CardFieldError.EXPIRY_MALFORMED
        val month = digits.take(2).toIntOrNull() ?: return CardFieldError.EXPIRY_MALFORMED
        return if (month !in 1..12) CardFieldError.EXPIRY_MONTH else CardFieldError.EXPIRY_MALFORMED
    }

    private fun cvcError(cvc: String, brand: CardBrand): CardFieldError? {
        val digits = cvc.filter(Char::isDigit)
        return when {
            digits.isEmpty() -> CardFieldError.MISSING
            // Length is compared against the *detected brand*, so an Amex needs 4 and every
            // other card needs exactly 3. Not "at least 3" — a 4-digit code on a Visa is a
            // customer reading the wrong number off the card, and sending it is a decline.
            digits.length != brand.cvcLength -> CardFieldError.CVC_LENGTH
            // Non-digits anywhere means the field carried something that is not a code.
            digits.length != cvc.trim().length -> CardFieldError.CVC_LENGTH
            else -> null
        }
    }

    /**
     * The Luhn (mod-10) check digit.
     *
     * Doubles every second digit **from the right**, subtracting 9 from any result above 9,
     * and requires the total to be a multiple of ten. It catches every single-digit error
     * and almost every transposition of adjacent digits — between them, essentially every
     * way a human mistypes a card number.
     *
     * A number shorter than [MIN_PAN_DIGITS] is rejected outright rather than checked:
     * short strings pass mod-10 by coincidence far too often (`"0"` passes), and a
     * coincidence here would let a truncated number through to the acquirer.
     */
    fun isLuhnValid(pan: String): Boolean {
        val digits = pan.filter(Char::isDigit)
        if (digits.length < MIN_PAN_DIGITS) return false
        var sum = 0
        for ((index, char) in digits.reversed().withIndex()) {
            var value = char - '0'
            if (index % 2 == 1) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
        }
        return sum % 10 == 0
    }

    /**
     * Reads `MM/YY`, `MM / YY`, `MMYY`, `MM/YYYY` and `MMYYYY` into an [ExpiryDate], or
     * returns null when the month does not exist or the shape cannot be read.
     *
     * A returned value is guaranteed to name a real month; whether that month is in the past
     * is a separate question, deliberately, because the two produce different copy.
     */
    fun parseExpiry(text: String): ExpiryDate? {
        val digits = text.filter(Char::isDigit)
        val year = when (digits.length) {
            4 -> digits.substring(2).toIntOrNull()?.plus(CENTURY)
            6 -> digits.substring(2).toIntOrNull()
            else -> null
        } ?: return null
        val month = digits.take(2).toIntOrNull() ?: return null
        // 00 and 13+ are not months. Rejected here rather than "clamped": a form that
        // silently turned 13 into 12 would send a date the customer never typed.
        if (month !in 1..12) return null
        return ExpiryDate(month = month.toString().padStart(2, '0'), year = year.toString().padStart(4, '0'))
    }

    /**
     * True when [expiry] names a month strictly before [today].
     *
     * The **current month is not past**: a card marked 08/26 is good through the last day of
     * August 2026, which is how every card scheme defines it. Getting this off by one month
     * refuses a valid card for up to thirty days — and it is the kind of bug that only shows
     * up on the first of the month.
     */
    fun isPast(expiry: ExpiryDate, today: YearMonth): Boolean {
        if (expiry.yearNumber != today.year) return expiry.yearNumber < today.year
        return expiry.monthNumber < today.month
    }

    /** Two-digit years are 21st-century. Matches iOS's `convertToFourDigitYear`. */
    private const val CENTURY = 2000
}

/**
 * A year and a month, with no day, no time and no zone — the only precision a card expiry
 * has.
 *
 * Its own type rather than `java.time.YearMonth` because that class needs API 26 and this
 * SDK supports API 24 without core-library desugaring. Injected into [CardValidation.validate]
 * so "is this card expired" is a pure function of its arguments and can be tested at any
 * date, in either direction, without touching a system clock.
 *
 * @property month 1-based, as a human writes it: January is 1.
 */
internal data class YearMonth(val year: Int, val month: Int) {

    internal companion object {

        /** The device's current month, in its own time zone. */
        fun now(calendar: Calendar = Calendar.getInstance(Locale.ROOT)): YearMonth = YearMonth(
            year = calendar.get(Calendar.YEAR),
            // Calendar.MONTH is 0-based; January is 0. Forgetting this is a classic
            // off-by-one that would reject every card expiring in the current month.
            month = calendar.get(Calendar.MONTH) + 1,
        )
    }
}
