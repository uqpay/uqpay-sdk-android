# UQPAY SDK for Android — Acceptance Criteria

The SDK is accepted for release only when **every** item below is verified. Use this as
the release sign-off checklist; each item should be checked against the current release
candidate, not assumed from a previous release.

## 1. Integration

- [ ] SDK is integrable via a single Gradle dependency (`implementation("com.uqpay:uqpay-sdk-android:<version>")`).
- [ ] Integration requires no manual manifest edits beyond documented ones.
- [ ] `docs/integration-guide.md` is accurate for the release candidate and a new
      developer can complete integration from it alone.
- [ ] Sample app demonstrates a complete, working integration.

## 2. API

- [ ] Public API surface is minimal, stable, and reviewed (everything else `internal`).
- [ ] Every public class/method has KDoc and appears in `docs/api-reference.md`.
- [ ] No breaking changes versus the previous minor/patch release (or major bump + migration notes).

## 3. Payment Flow

- [ ] Payment initiation, success, failure, cancellation, and timeout are each handled
      and produce the correct `PaymentStatus`.
- [ ] Timeout duration is defined, documented, and enforced.
- [ ] A payment can never end in an undefined state — exactly one terminal callback per payment.

## 4. Security

- [ ] No sensitive payment data (PAN, CVV, expiry, tokens, keys, PII) in logs, crash
      reports, exceptions, or test fixtures — verified by log audit on a full payment run.
- [ ] All communication is HTTPS; no cleartext traffic permitted by manifest or
      network security config.
- [ ] No card data persisted to disk, SharedPreferences, or databases.
- [ ] No hardcoded secrets in SDK or sample app source.
- [ ] ProGuard/R8 consumer rules ship with the AAR and a minified integration works.

## 5. Compatibility

- [ ] Works on all supported Android versions (minSdk 24 → latest stable), verified on
      at least the min, a mid, and the latest API level.
- [ ] Works across common device configurations: small/large screens, dark mode,
      RTL locales, tablets.
- [ ] Java-based host apps can consume the public API without friction.

## 6. Error Handling

- [ ] Every failure surfaces a stable `UQPayErrorCode` with an actionable message,
      per `docs/error-codes.md`.
- [ ] Error codes/messages are consistent across all failure paths (network, validation,
      server, user action).
- [ ] The SDK recovers safely from errors — no crashes, no stuck UI, host app can retry.

## 7. Callbacks / Results

- [ ] Result callbacks are delivered exactly once per `launch` call, on the main thread.
      (Two launches of one intent are one payment but two calls — see the callback
      contract in the integration guide.)
- [ ] Callbacks are delivered reliably across configuration changes and process death.
- [ ] Callback contract (when, on which thread, how many times) is documented.

## 8. UI / UX

- [ ] Rotation, background/foreground transitions, and process death mid-payment do not
      lose the payment or crash.
- [ ] Duplicate submission (double-tap pay, re-launch) cannot create a duplicate payment.
- [ ] Back-press behavior during payment is defined and results in CANCELLED, not silence.

## 9. Testing

- [ ] Unit and integration tests pass in CI.
- [ ] High-risk scenarios covered by automated tests: success, failure, cancellation,
      timeout, network loss mid-payment, duplicate submission, rotation mid-payment.
- [ ] Manual test pass completed on physical device(s) for the release candidate.

## 10. Performance

- [ ] No measurable impact on host app cold start (SDK does no work before `init`,
      and `init` is cheap or async).
- [ ] No memory leaks (verified with LeakCanary or equivalent on the sample app).
- [ ] UI stays responsive during payment (no main-thread network/disk I/O).
- [ ] AAR size is tracked and justified; no unnecessary transitive dependencies.

## 11. Release

- [ ] Semantic version assigned; `CHANGELOG.md` updated with release notes.
- [ ] Backward-compatibility policy stated and honored.
- [ ] Artifact published from a tagged, reproducible build per `docs/release-process.md`.
- [ ] Sources/javadoc jars and POM metadata are correct.

## 12. Documentation

- [ ] Integration guide, API reference, error codes, and troubleshooting docs are
      complete and match the released version.
- [ ] Sample app compiles against the released artifact (not a local snapshot).
