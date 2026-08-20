package com.uqpay.sample

import com.uqpay.sdk.payment.PaymentSessionParams

/**
 * The billing details this demo store already knows about its signed-in customer, handed
 * to the SDK so the card form opens with everything but the card itself filled in.
 *
 * This is the point of `PaymentSessionParams.BillingDetails`: a customer who has an
 * account with you should not retype their name and address at a checkout you already
 * know them at. On the card screen only **card number, expiry and security code** are
 * empty — those three are never prefillable, from any app, by design.
 *
 * ### Two rules a real integration keeps
 *
 * 1. **Send what you actually know, and nothing more.** Every field is optional; a
 *    half-filled form is better than a confidently wrong one. The customer can edit and
 *    clear all of it, and what reaches the gateway is what the form holds when they tap
 *    Pay.
 * 2. **Do not log it.** These are a real person's contact details in production.
 *    `BillingDetails.toString()` redacts the email and the phone for exactly that reason,
 *    and the SDK persists none of it.
 *
 * Everything below is obviously synthetic — a documentation address on a documentation
 * street, and an `@example.com` address, which RFC 2606 reserves so it can never reach a
 * real inbox. Never put a real person's details in a sample app.
 */
object DemoCustomer {

    val billingDetails: PaymentSessionParams.BillingDetails =
        PaymentSessionParams.BillingDetails(
            firstName = "John",
            lastName = "Tan",
            email = "john.tan@example.com",
            phone = "+6591234567",
            addressLine1 = "123 Orchard Road",
            addressLine2 = "#12-01",
            city = "Singapore",
            state = "Singapore",
            postalCode = "238888",
            // ISO 3166-1 alpha-2. A code the SDK does not recognise is ignored and the
            // picker opens on the device's region instead — never substituted for a guess.
            countryCode = "SG",
        )
}
