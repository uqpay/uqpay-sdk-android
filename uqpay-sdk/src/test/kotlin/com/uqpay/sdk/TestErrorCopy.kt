package com.uqpay.sdk

import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.error.ErrorCopy

/**
 * The **real** [ErrorCopy], over the SDK's own `res/values/strings.xml`.
 *
 * Deliberately not a stub. `ErrorCopy` is the thing that turns a [com.uqpay.sdk.error.UQPayErrorCode]
 * into the sentence a shopper reads, and a stub returning `"message for $code"` would let
 * every assertion in the suite pass while the real lookup was missing an entry, pointed at
 * the wrong string, or crashing on a code this SDK version predates. The tests assert the
 * actual English sentences for exactly that reason.
 *
 * Requires Robolectric, which is why the test classes that map errors run under it — they
 * are exercising a resource lookup now, and a resource lookup needs a resource table.
 */
internal fun testErrorCopy(): ErrorCopy =
    ErrorCopy.from(ApplicationProvider.getApplicationContext())
