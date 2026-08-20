package com.uqpay.sdk.ui.card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.uqpay.sdk.R
import com.uqpay.sdk.engine.ConfirmBilling
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.ui.CountryCodes

/**
 * Everything the customer typed, held in memory and nowhere else.
 *
 * ### Why this is not a `SavedStateHandle`
 *
 * `SavedStateHandle` is a `Bundle` the OS **writes to disk** so it can survive process
 * death. Putting a PAN or a CVC in one produces card data at rest, in a file the SDK does
 * not own, with a lifetime it does not control — the single thing this SDK must never do
 * (AC §4.3), and the reason `CardFormTest` scans the saved state for card digits rather
 * than trusting a code review to keep noticing.
 *
 * The cost is honest and small: a rotation empties the card fields and the customer types
 * them again. Losing thirty seconds of typing is not comparable to leaving a card number in
 * the merchant's app data directory. The payment itself is untouched either way — it lives
 * in the engine's session, which does survive rotation.
 *
 * Values are read from here on **every** send, never copied into an attempt, a log line, or
 * an exception. See `ConfirmPayload.Card`, which overrides `toString` for the same reason.
 */
internal class CardFormState(prefill: PaymentSessionParams.BillingDetails? = null) {
    // Card values are never prefillable and have no seed parameter: a PAN or a CVC handed
    // in by the host app would have travelled through an Intent extra to get here, which is
    // card data at rest in a Bundle the OS may write to disk. The customer types these
    // three, always. See PaymentSessionParams.BillingDetails.
    private var panValue: String by mutableStateOf("")

    /**
     * The card number, and the one field whose value changes what another field may hold.
     *
     * ### Why the setter touches the CVC
     *
     * The security code's length follows the brand — four digits on Amex, three everywhere
     * else (G20) — and the CVC field enforces that as the customer types. It cannot enforce
     * it *retroactively*: typing `3782…`, then a four-digit code, then replacing the number
     * with a Visa one leaves four digits in a field the Visa rules cap at three. Nothing in
     * the form is visibly wrong — every field is filled, no error is drawn, because errors
     * only appear after a submit attempt and the Pay button is disabled by the very
     * validation failure it would explain. The form looks complete and is silently dead, and
     * the customer's only way out is to guess.
     *
     * Re-truncating here makes the invariant a property of the *state* rather than of one
     * field's `onValueChange`, so it holds however the number is changed — typed, pasted,
     * autofilled, or cleared. Truncation never invents digits: it drops the trailing one, the
     * customer sees a three-digit code that is now too short, and the CVC field's own error
     * says so on the next submit. That is a visible, fixable state; the alternative is not.
     */
    var pan: String
        get() = panValue
        set(value) {
            panValue = value
            val allowed = brand.cvcLength
            if (cvc.length > allowed) cvc = cvc.take(allowed)
        }

    var expiry: String by mutableStateOf("")
    var cvc: String by mutableStateOf("")

    // Billing values seed from the merchant's prefill and stay fully editable — these are
    // ordinary text fields, and what is sent is whatever they hold when Pay is tapped.
    var firstName: String by mutableStateOf(prefill?.firstName.orEmpty())
    var lastName: String by mutableStateOf(prefill?.lastName.orEmpty())
    var email: String by mutableStateOf(prefill?.email.orEmpty())
    var phone: String by mutableStateOf(prefill?.phone.orEmpty())
    var addressLine1: String by mutableStateOf(prefill?.addressLine1.orEmpty())
    var addressLine2: String by mutableStateOf(prefill?.addressLine2.orEmpty())
    var city: String by mutableStateOf(prefill?.city.orEmpty())
    var state: String by mutableStateOf(prefill?.state.orEmpty())
    var postcode: String by mutableStateOf(prefill?.postalCode.orEmpty())

    /**
     * The billing country, as an ISO code — the customer's own selection, or null.
     *
     * Opens on the merchant's prefilled country when that is a real ISO code, otherwise on
     * the device's region, and stays null when neither is a country we can send. **Never
     * falls back to a guess**: iOS shipped a nine-entry table that answered `"US"` for
     * everything else, and every customer outside those nine markets had the wrong country
     * sent to their issuer. See [CountryCodes].
     *
     * An unrecognised prefill is dropped rather than rejected. A merchant typo — `"SGP"`,
     * `"Singapore"`, a stale internal code — must cost the customer one tap in the picker,
     * never a payment that cannot be started at all.
     */
    var countryCode: String? by mutableStateOf(
        CountryCodes.canonical(prefill?.countryCode) ?: CountryCodes.deviceDefault(),
    )

    /** The detected brand, which sizes the CVC field and fills `network` on the wire. */
    val brand: CardBrand get() = CardBrand.of(pan)

    /** The full name as the wire wants it: `card_name`, and a digest field in its own right. */
    val cardholderName: String get() = "${firstName.trim()} ${lastName.trim()}".trim()

    /**
     * Address lines 1 and 2 joined into the single `street` the API takes.
     *
     * Joined here, in the form, exactly as iOS joins them before it builds either the
     * request or the digest — one string on the wire, one field in the identity. Doing it
     * anywhere else would put a second, differently-shaped street value into circulation.
     */
    val street: String
        get() = if (addressLine2.isBlank()) addressLine1.trim() else "${addressLine1.trim()}, ${addressLine2.trim()}"

    /** Validates what is currently typed. */
    fun validate(today: YearMonth = YearMonth.now()): CardValidationResult = CardValidation.validate(
        pan = pan,
        expiry = expiry,
        cvc = cvc,
        cardholderName = cardholderName,
        billingCountryCode = countryCode,
        today = today,
    )

    /**
     * Builds the confirm payload. Call only when [validate] passed — the expiry is read from
     * the parsed result, which only exists for a readable date.
     *
     * ### The null-versus-empty-string rule (audit L-6), which is deliberately split
     *
     * Every optional billing field is emitted as `""` when the customer left it untouched,
     * and never as `null`, so `ConfirmBilling.toJson` writes the key rather than omitting it
     * — and so `address` is **always present**, because all five of its fields are non-null.
     * That is what iOS sends (`text ?? ""` on every field, an `Address` built
     * unconditionally, `PaymentCardViewController.swift:1202-1229`), and iOS is the only
     * client verified to work against this gateway. Its bytes are the reference; a
     * "cleaner" omission is an unverified change to a body the risk engine reads.
     *
     * The **digest** keeps the opposite convention and that is not an inconsistency to be
     * tidied away. `ConfirmPayload.ABSENT_FIELD` (U+0000) exists so an *absent* optional and
     * an *empty* optional hash differently, because they serialise to different bytes under
     * the same idempotency key — and a replayed key with a changed body is rejected by the
     * gateway rather than replayed. This form simply never produces the absent case: it
     * sends `""`, so `identityOf("")` is `""`, and U+0000 stays reserved for a caller that
     * really has no value. Unifying the two halves would re-introduce exactly the collision
     * WU-2.6 removed. `CardFormTest` asserts both halves together, so neither can drift.
     */
    fun toPayload(paymentIntentId: String, expiry: ExpiryDate): ConfirmPayload.Card = ConfirmPayload.Card(
        paymentIntentId = paymentIntentId,
        cardNumber = pan.filter(Char::isDigit),
        expiryMonth = expiry.month,
        expiryYear = expiry.year,
        cvc = cvc.filter(Char::isDigit),
        cardholderName = cardholderName,
        network = brand.wireName,
        billing = ConfirmBilling(
            firstName = firstName.trim(),
            lastName = lastName.trim(),
            email = email.trim(),
            phoneNumber = phone.trim(),
            // Validation guarantees a selection, so this cannot be a guessed country.
            countryCode = countryCode.orEmpty(),
            state = state.trim(),
            city = city.trim(),
            street = street,
            postcode = postcode.trim(),
        ),
        // Null on a first card confirm, exactly as iOS sends it. The gateway answers with
        // `next_action.redirect_iframe` and the 3DS screen takes it from there — verified
        // live against the sandbox on 2026-08-18.
        returnUrl = null,
    )
}

/**
 * The card entry form.
 *
 * Material 3 throughout, so it follows the system dark-mode setting through `UqpayTheme` and
 * every text scales with the customer's font-size preference without any work here. Every
 * field carries a content description (which is both the accessibility contract and how the
 * tests find things) and an `imeAction`, so the keyboard's Next button walks the form in
 * reading order and the last field submits it.
 *
 * The Pay button is disabled until the whole form validates, and validation errors appear
 * per field rather than as one "something is wrong" — see [CardValidationResult].
 *
 * @param canReturnToList false under a card-only presentation, where there is no method list
 *   to go back to and the only way out is to leave.
 * @param onSubmit handed a fully-built, validated payload. The screen does not confirm
 *   anything itself; the engine decides what a tap means, including whether it is a
 *   duplicate.
 * @param billingDetails the merchant's optional prefill, seeded into [state] the first time
 *   the form is composed. Only ever seeds the billing section — see [CardFormState].
 */
@Composable
internal fun CardForm(
    paymentIntentId: String,
    canReturnToList: Boolean,
    submitEnabled: Boolean,
    onSubmit: (ConfirmPayload.Card) -> Unit,
    onReturnToList: () -> Unit,
    onCancel: () -> Unit,
    billingDetails: PaymentSessionParams.BillingDetails? = null,
    state: CardFormState = remember { CardFormState(billingDetails) },
) {
    // Errors appear only after a submit attempt. Scolding a customer for an incomplete card
    // number while they are still typing it is noise, and it trains them to ignore the real
    // message when it arrives.
    var showErrors by remember { mutableStateOf(false) }
    var countryPickerOpen by remember { mutableStateOf(false) }

    val result = state.validate()
    val errors = if (showErrors) result.errors else emptyMap()

    val title = stringResource(R.string.uqpay_card_title)
    val payLabel = stringResource(R.string.uqpay_card_pay)
    val payDescription = stringResource(R.string.uqpay_cd_card_pay)
    val backLabel = stringResource(R.string.uqpay_back_to_methods)
    val backDescription = stringResource(R.string.uqpay_cd_back_to_methods)
    val cancelLabel = stringResource(R.string.uqpay_cancel)
    val cancelDescription = stringResource(R.string.uqpay_cd_cancel)
    val countryLabel = stringResource(R.string.uqpay_card_country)
    val countryDescription = stringResource(R.string.uqpay_cd_card_country)
    val countryPlaceholder = stringResource(R.string.uqpay_card_country_placeholder)
    val countryChooseLabel = stringResource(R.string.uqpay_card_country_choose)
    val countryChooseDescription = stringResource(R.string.uqpay_cd_card_country_choose)
    val brandLabel = brandName(state.brand)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        CardTextField(
            value = state.pan,
            onValueChange = { state.pan = acceptDigits(it, state.brand.maxPanLength) },
            label = stringResource(R.string.uqpay_card_number),
            contentDescription = stringResource(R.string.uqpay_cd_card_number),
            // The brand is shown as trailing text rather than an icon: it tells the customer
            // we read their card correctly, and it explains why the CVC field wants four
            // digits on an Amex instead of three.
            trailing = brandLabel,
            error = errorText(errors[CardField.PAN]),
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
            // Grouped for display only, and re-derived when the brand changes — an Amex
            // regroups from 4-4-4-4 to 4-6-5 the moment its second digit identifies it.
            visualTransformation = remember(state.brand) { DigitGroupingTransformation(state.brand.panGroups, ' ') },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTextField(
                modifier = Modifier.weight(1f),
                value = state.expiry,
                onValueChange = { state.expiry = acceptDigits(it, MAX_EXPIRY_DIGITS) },
                label = stringResource(R.string.uqpay_card_expiry),
                contentDescription = stringResource(R.string.uqpay_cd_card_expiry),
                error = errorText(errors[CardField.EXPIRY]),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
                visualTransformation = ExpiryTransformation,
            )
            CardTextField(
                modifier = Modifier.weight(1f),
                value = state.cvc,
                // The CVC field's own length follows the brand — 4 on an Amex, 3 elsewhere
                // (G20). A fixed cap of 3 makes every Amex card unpayable.
                onValueChange = { state.cvc = acceptDigits(it, state.brand.cvcLength) },
                label = stringResource(R.string.uqpay_card_cvc),
                contentDescription = stringResource(R.string.uqpay_cd_card_cvc),
                error = errorText(errors[CardField.CVC]),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            )
        }

        SectionHeader(stringResource(R.string.uqpay_card_billing))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTextField(
                modifier = Modifier.weight(1f),
                value = state.firstName,
                onValueChange = { state.firstName = it },
                label = stringResource(R.string.uqpay_card_first_name),
                contentDescription = stringResource(R.string.uqpay_cd_card_first_name),
                // The cardholder-name error belongs to both name fields; showing it under
                // the first one is where the eye lands.
                error = errorText(errors[CardField.CARDHOLDER_NAME]),
                imeAction = ImeAction.Next,
            )
            CardTextField(
                modifier = Modifier.weight(1f),
                value = state.lastName,
                onValueChange = { state.lastName = it },
                label = stringResource(R.string.uqpay_card_last_name),
                contentDescription = stringResource(R.string.uqpay_cd_card_last_name),
                imeAction = ImeAction.Next,
            )
        }
        CardTextField(
            value = state.email,
            onValueChange = { state.email = it },
            label = stringResource(R.string.uqpay_card_email),
            contentDescription = stringResource(R.string.uqpay_cd_card_email),
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        )
        CardTextField(
            value = state.phone,
            onValueChange = { state.phone = it },
            label = stringResource(R.string.uqpay_card_phone),
            contentDescription = stringResource(R.string.uqpay_cd_card_phone),
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Next,
        )
        CardTextField(
            value = state.addressLine1,
            onValueChange = { state.addressLine1 = it },
            label = stringResource(R.string.uqpay_card_address1),
            contentDescription = stringResource(R.string.uqpay_cd_card_address1),
            imeAction = ImeAction.Next,
        )
        CardTextField(
            value = state.addressLine2,
            onValueChange = { state.addressLine2 = it },
            label = stringResource(R.string.uqpay_card_address2),
            contentDescription = stringResource(R.string.uqpay_cd_card_address2),
            imeAction = ImeAction.Next,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTextField(
                modifier = Modifier.weight(1f),
                value = state.city,
                onValueChange = { state.city = it },
                label = stringResource(R.string.uqpay_card_city),
                contentDescription = stringResource(R.string.uqpay_cd_card_city),
                imeAction = ImeAction.Next,
            )
            CardTextField(
                modifier = Modifier.weight(1f),
                value = state.state,
                onValueChange = { state.state = it },
                label = stringResource(R.string.uqpay_card_state),
                contentDescription = stringResource(R.string.uqpay_cd_card_state),
                imeAction = ImeAction.Next,
            )
        }
        CardTextField(
            value = state.postcode,
            onValueChange = { state.postcode = it },
            label = stringResource(R.string.uqpay_card_postcode),
            contentDescription = stringResource(R.string.uqpay_cd_card_postcode),
            // The last field submits, rather than leaving the customer to dismiss the
            // keyboard and hunt for the button.
            imeAction = ImeAction.Done,
        )

        // The country is chosen from the full ISO list and never typed: a free-text field is
        // what forced the nine-entry lookup table that silently sent "US" (see CountryCodes).
        // A dialog rather than a dropdown because the list is ~249 long — a menu that long is
        // unusable without a filter, and unreachable with a screen reader.
        val selectedCountryName = state.countryCode?.let { code ->
            CountryCodes.all.firstOrNull { it.code == code }?.name
        }
        val countryError = errorText(errors[CardField.BILLING_COUNTRY])
        OutlinedTextField(
            value = selectedCountryName.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(countryLabel) },
            placeholder = { Text(countryPlaceholder) },
            isError = countryError != null,
            supportingText = countryError?.let { text -> { Text(text) } },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { countryPickerOpen = true }
                .semantics { contentDescription = countryDescription },
        )
        TextButton(
            onClick = { countryPickerOpen = true },
            modifier = Modifier.semantics { contentDescription = countryChooseDescription },
        ) {
            Text(countryChooseLabel)
        }

        if (countryPickerOpen) {
            CountryPickerDialog(
                selected = state.countryCode,
                onSelected = { code ->
                    state.countryCode = code
                    countryPickerOpen = false
                },
                onDismiss = { countryPickerOpen = false },
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                showErrors = true
                val checked = state.validate()
                // Belt and braces: the button is already disabled when invalid, but a
                // disabled button is a *display* rule and this is the money boundary.
                val expiry = checked.expiry
                if (checked.isValid && expiry != null) onSubmit(state.toPayload(paymentIntentId, expiry))
            },
            // Disabled **only** while a confirm is already accepted — the screen-side half of
            // the duplicate-submission guard (AC §8.2). The engine refuses a second attempt
            // anyway; this is what stops the customer generating one.
            //
            // Deliberately *not* disabled by validation, though the click above still refuses
            // to submit an invalid form. A button greyed out by a rule the customer cannot
            // see is a dead end: errors appear only after a submit attempt, and a submit
            // attempt is exactly what the greying prevents. The customer is left with a form
            // that looks complete, no message anywhere, and nothing to try. Tapping an
            // enabled button reveals every field error at once, which is the whole point of
            // validating per field. It also gives a screen reader something to announce —
            // a disabled control is skipped, so the blocked state was previously silent.
            enabled = submitEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = payDescription },
        ) {
            Text(payLabel)
        }
        Spacer(Modifier.height(8.dp))
        if (canReturnToList) {
            TextButton(
                onClick = onReturnToList,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = backDescription },
            ) {
                Text(backLabel)
            }
        } else {
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = cancelDescription },
            ) {
                Text(cancelLabel)
            }
        }
    }
}

/**
 * The full ISO country list, filterable, in a dialog.
 *
 * Filterable because ~249 rows is not something anyone scrolls through twice, and matched on
 * **both** the display name and the code so a customer who knows they want `SG` can type it.
 * Every row is a real ISO code; nothing here can substitute one for another.
 */
@Composable
private fun CountryPickerDialog(
    selected: String?,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val title = stringResource(R.string.uqpay_card_country)
    val filterLabel = stringResource(R.string.uqpay_card_country_filter)
    val filterDescription = stringResource(R.string.uqpay_cd_card_country_filter)
    val dismissLabel = stringResource(R.string.uqpay_cancel)
    val rowFormat = stringResource(R.string.uqpay_cd_card_country_row_format)
    val matches = remember(query) { CountryCodes.filter(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(filterLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = filterDescription },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(matches, key = { it.code }) { country ->
                        val rowDescription = rowFormat.format(country.name)
                        TextButton(
                            onClick = { onSelected(country.code) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = rowDescription },
                        ) {
                            Text(
                                text = country.name,
                                style = if (country.code == selected) {
                                    MaterialTheme.typography.bodyLarge
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text = text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

/**
 * One labelled field, with its error and its keyboard behaviour.
 *
 * @param value the **raw** value the state holds — digits for the number, expiry and CVC.
 * @param visualTransformation how to *display* [value]. The grouping separators exist only on
 *   screen and are never part of what is held, validated or sent.
 *
 *   This is a [VisualTransformation] rather than a reformat inside `onValueChange`, and that
 *   distinction is the whole bug fix. Feeding a formatted string back into the field made
 *   every inserted space a length change the caret could not survive: the field re-anchored
 *   the cursor one position back, and the next digit landed in front of the one before it.
 *   Typing 5346930100108117 produced 5346 3010 1081 7109 — a number that is not the
 *   customer's card, fails Luhn, and is reported as "invalid" with nothing on screen to
 *   explain why. A transformation leaves the value alone and only dresses it up, so there is
 *   no length change to survive.
 */
@Composable
private fun CardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        visualTransformation = visualTransformation,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { text -> { Text(text) } },
        trailingIcon = trailing?.let { text -> { Text(text) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { this.contentDescription = contentDescription },
    )
}

/** The brand's customer-facing name, or blank while nothing identifies it yet. */
@Composable
private fun brandName(brand: CardBrand): String = when (brand) {
    CardBrand.VISA -> stringResource(R.string.uqpay_brand_visa)
    CardBrand.MASTERCARD -> stringResource(R.string.uqpay_brand_mastercard)
    CardBrand.AMEX -> stringResource(R.string.uqpay_brand_amex)
    CardBrand.UNIONPAY -> stringResource(R.string.uqpay_brand_unionpay)
    CardBrand.JCB -> stringResource(R.string.uqpay_brand_jcb)
    CardBrand.DISCOVER -> stringResource(R.string.uqpay_brand_discover)
    CardBrand.DINERS -> stringResource(R.string.uqpay_brand_diners)
    // Deliberately blank, not "Unknown". An unrecognised prefix is not a problem the
    // customer can do anything about, and the card is perfectly payable (see CardBrand).
    CardBrand.UNKNOWN -> ""
}

/** The message for a field error, or null when the field is fine. */
@Composable
private fun errorText(error: CardFieldError?): String? = when (error) {
    null -> null
    CardFieldError.MISSING -> stringResource(R.string.uqpay_card_error_required)
    CardFieldError.PAN_LENGTH -> stringResource(R.string.uqpay_card_error_number_length)
    CardFieldError.PAN_LUHN -> stringResource(R.string.uqpay_card_error_number)
    CardFieldError.EXPIRY_MALFORMED -> stringResource(R.string.uqpay_card_error_expiry)
    CardFieldError.EXPIRY_MONTH -> stringResource(R.string.uqpay_card_error_expiry_month)
    CardFieldError.EXPIRY_PAST -> stringResource(R.string.uqpay_card_error_expiry_past)
    CardFieldError.CVC_LENGTH -> stringResource(R.string.uqpay_card_error_cvc)
    CardFieldError.COUNTRY_UNKNOWN -> stringResource(R.string.uqpay_card_error_country)
}

/** The most digits an expiry can carry: `MMYY`. */
private const val MAX_EXPIRY_DIGITS = 4

/** `MM` then `YY`. The expiry's equivalent of a brand's `panGroups`. */
private val EXPIRY_GROUPS: List<Int> = listOf(2, 2)

/**
 * Keeps the digits of what was typed, up to [max].
 *
 * The bound is an *input* bound only — it stops a customer typing a 25-digit number, it never
 * decides validity. It is also why the PAN field's bound comes from the brand
 * (`CardBrand.maxPanLength`, 19 for everything but Amex) rather than a constant 16, which is
 * the shipped iOS bug that made UnionPay cards impossible to enter (G20).
 */
internal fun acceptDigits(input: String, max: Int): String =
    input.filter(Char::isDigit).take(max)

/**
 * Groups a PAN for display: `4242 4242 4242 4242`, or `3782 822463 10005` on an Amex.
 *
 * Display only. Nothing downstream ever sees the spaces — the state holds digits, validation
 * strips anything that is not a digit, and the payload sends digits.
 */
internal fun formatPan(digits: String, brand: CardBrand): String =
    groupDigits(digits, brand.panGroups, ' ')

/** Displays expiry digits as `MM/YY`. Display only; the state holds `MMYY`. */
internal fun formatExpiry(digits: String): String = groupDigits(digits, EXPIRY_GROUPS, '/')

/**
 * Where the separators fall in a raw string of [length] digits grouped as [groups].
 *
 * The **single** source of the grouping arithmetic: the displayed string and both halves of
 * the [OffsetMapping] are all derived from this list, so a caret cannot disagree with the
 * text it sits in. Hand-tabulated mappings are how `IllegalStateException:
 * OffsetMapping.originalToTransformed returned invalid mapping` reaches a customer.
 *
 * Returned in ascending order, holding every group edge strictly inside the string. An edge
 * at 0 would put a leading separator on screen, and one at [length] a trailing separator that
 * the customer would then have to backspace through. Both are excluded.
 *
 * Digits past the last group edge — a 19-digit Diners card under a 4-6-4 table — simply run
 * on as a final chunk. Truncating them on screen would tell a customer the field ate their
 * digits, and a customer who believes that retypes, which is how a correct number becomes a
 * wrong one.
 */
private fun separatorPositions(length: Int, groups: List<Int>): List<Int> {
    val positions = ArrayList<Int>(groups.size)
    var edge = 0
    for (size in groups) {
        edge += size
        if (edge >= length) break
        positions.add(edge)
    }
    return positions
}

/** [digits] with [separator] inserted at every position [separatorPositions] names. */
private fun groupDigits(digits: String, groups: List<Int>, separator: Char): String {
    val positions = separatorPositions(digits.length, groups)
    if (positions.isEmpty()) return digits
    return buildString(digits.length + positions.size) {
        var next = 0
        for (index in digits.indices) {
            if (next < positions.size && positions[next] == index) {
                append(separator)
                next++
            }
            append(digits[index])
        }
    }
}

/**
 * Draws raw digits grouped for the eye — `5346 9301 0010 8117`, `12/30` — while the field
 * itself keeps holding exactly the digits the customer typed.
 *
 * The grouping is the brand's own (`CardBrand.panGroups`), never a second copy of it: Amex
 * runs 4-6-5, Diners 4-6-4, everything else in fours, and this class is told which.
 */
internal class DigitGroupingTransformation(
    private val groups: List<Int>,
    private val separator: Char,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        return TransformedText(
            AnnotatedString(groupDigits(raw, groups, separator)),
            GroupingOffsetMapping(separatorPositions(raw.length, groups)),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is DigitGroupingTransformation && other.groups == groups && other.separator == separator

    override fun hashCode(): Int = 31 * groups.hashCode() + separator.hashCode()
}

/** The expiry's transformation: `MMYY` held, `MM/YY` drawn. */
internal val ExpiryTransformation: VisualTransformation = DigitGroupingTransformation(EXPIRY_GROUPS, '/')

/**
 * Translates caret offsets between the digits the field holds and the grouped string on
 * screen, given the raw [positions] the separators were inserted at.
 *
 * Both directions are counted from [positions] rather than tabulated, which is what makes
 * them exact at every offset — 0, each group edge, and the end included. The k-th separator
 * lands at *displayed* index `positions[k] + k`, because the k separators before it have each
 * pushed the string one character to the right; that identity is the whole of the reverse
 * direction.
 */
private class GroupingOffsetMapping(private val positions: List<Int>) : OffsetMapping {

    /** Raw offset, plus one for every separator drawn before it. */
    override fun originalToTransformed(offset: Int): Int =
        offset + positions.count { it <= offset }

    /** Displayed offset, less every separator standing before it. */
    override fun transformedToOriginal(offset: Int): Int =
        offset - positions.indices.count { positions[it] + it < offset }
}
