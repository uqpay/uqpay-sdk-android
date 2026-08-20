# Contributing

Thanks for taking an interest in the UQPAY SDK for Android.

This is a vendor-maintained payment SDK, still pre-release. The public API is a
compatibility commitment to every merchant who integrates it, so changes to it are
deliberate and reviewed closely. Bug reports and small, focused pull requests are welcome;
if you are planning something larger, please open an issue first so we can agree on the
shape before you spend time on it.

## Reporting problems

- **Security vulnerabilities** — do not open an issue. Follow [`SECURITY.md`](SECURITY.md).
- **Bugs** — open an issue and include what
  [`docs/troubleshooting.md`](docs/troubleshooting.md) asks for: SDK version, Android
  version, device model, `paymentIntentId`, `transactionId`, the `UQPayErrorCode`, and a
  Logcat capture of the `UQPay` tag taken with `loggingEnabled = true`.

**Never put card numbers, security codes, API keys, tokens, or customer personal data in
an issue, a pull request, a commit message, or a test fixture.** The SDK does not log any
of it; please do not add any by hand.

## Getting set up

You need **JDK 17** and the Android SDK (`compileSdk 35`). The Gradle wrapper is committed,
so no local Gradle install is required.

```bash
git clone https://github.com/uqpay/uqpay-sdk-android.git
cd uqpay-sdk-android
./gradlew :uqpay-sdk:assembleRelease
```

To run the sample app you need sandbox credentials in `local.properties`, which is
gitignored and must stay that way:

```properties
uqpay.clientId=<your sandbox client id>
uqpay.sandboxToken=<a short-lived sandbox access token>
uqpay.sandboxApiKey=<your sandbox x-api-key — debug builds only>
```

Missing values still build; the app tells you what is absent instead of failing later with
something cryptic. Run `./scripts/mint-sandbox-token.sh` to mint an access token.

Two things worth knowing before you fill these in:

- **`uqpay.sandboxToken` and `uqpay.sandboxApiKey` are not interchangeable.** An x-api-key
  can issue refunds and payouts and is never a valid access token. Putting one in the token
  slot produces a `401` that reads like an expired credential while compiling the key into
  the APK in the wrong field. The build fails loudly if it detects this.
- **The x-api-key reaches the APK in debug builds only**, so the sample can mint its own
  short-lived tokens while you work. Release builds get an empty value deliberately, since
  an APK cannot keep a secret. A real merchant keeps the key on their own server and never
  ships it in an app at all.

In a real integration, keys are supplied by the host app at initialisation. They are never
hardcoded in the SDK or the sample app source.

## Building and testing

```bash
./gradlew :uqpay-sdk:testDebugUnitTest    # unit tests
./gradlew :uqpay-sdk:assembleRelease      # build the AAR
./gradlew :uqpay-sdk:checkAarSize         # AAR must stay under its recorded ceiling
./gradlew apiCheck                        # public API must match the recorded surface
./gradlew lint                            # Android lint
./gradlew :sample-app:installDebug        # run the sample app
```

CI runs all of the above on every push and pull request, plus a second job that assembles
the sample app with R8 and confirms the SDK survives shrinking.

## Changing the public API

The public surface is frozen in `uqpay-sdk/api/uqpay-sdk.api` and enforced by `apiCheck`.
If your change alters it deliberately:

1. Run `./gradlew apiDump` and commit the updated `.api` file in the same change.
2. Update [`docs/api-reference.md`](docs/api-reference.md) in the same change.
3. Note the compatibility impact. We follow [semantic versioning](https://semver.org/) —
   a breaking change to the public API is a major version bump.

If `apiCheck` fails and you did not mean to change the API, something leaked out of
`internal`. Fix that rather than re-running `apiDump`.

Keep the public surface minimal. Everything merchants do not need is `internal` — the
`network/` and `ui/` packages in particular. Everything merchants do need goes through the
single `UQPay` entry point.

## Rules that are not negotiable

This is payment software, so a few things are hard constraints rather than preferences:

- **No sensitive data in logs.** Not card PAN, security code, expiry, tokens, API secrets,
  or customer personal data — not in Logcat, exceptions, crash reports, or tests. If a
  value must appear, mask it to the last four digits.
- **HTTPS only.** No cleartext traffic, and no `usesCleartextTraffic` or cleartext
  network-security-config entries.
- **Nothing sensitive persisted.** Card data never reaches disk, `SharedPreferences`, or a
  database, and no more goes into an `Intent` extra than is strictly required.
- **No analytics or tracking dependencies.** Merchants do not expect a payment SDK to phone
  anywhere except the gateway.
- **No real card numbers or real API keys in tests.** Use the documented test values.

## Payment outcomes are results, not exceptions

Every payment outcome is delivered through `PaymentCallback` / `PaymentResult` with a
`PaymentStatus` of `SUCCESS`, `FAILED`, `CANCELLED`, or `TIMEOUT`. The SDK throws only for
programmer errors, such as using it before initialisation. Errors carry a `UQPayError` with
a stable `UQPayErrorCode`; codes are documented in
[`docs/error-codes.md`](docs/error-codes.md) and are never renamed or renumbered once
released.

## Tests

Unit tests live in `uqpay-sdk/src/test/`, instrumented tests in
`uqpay-sdk/src/androidTest/`. High-risk payment scenarios need coverage: success, failure,
cancellation, timeout, network loss mid-payment, duplicate submission, rotation
mid-payment, and process death mid-payment.

The SDK must survive configuration changes, process death, and background/foreground
transitions without losing or duplicating a payment.

## Style and commits

Kotlin official code style (`kotlin.code.style=official`). Kotlin-first, with Java
interoperability where it helps (`@JvmStatic`, `@JvmOverloads`). All dependency versions
live in `gradle/libs.versions.toml` — no version literals in a module build file.

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(sdk): add the Compose payment sheet with 3-D Secure and wallet QR
fix(auth): send x-auth-token as Bearer, keep the API key out of the APK
docs: record the implemented payment flow in the changelog
```

Use `!` for a breaking change, as in `feat(api)!:`.

## Pull request checklist

- [ ] `./gradlew :uqpay-sdk:testDebugUnitTest apiCheck lint` passes.
- [ ] Tests cover the risk scenarios your change touches.
- [ ] Documentation updated — the integration guide and API reference for a public API
      change, `docs/error-codes.md` for a new error code.
- [ ] `CHANGELOG.md` updated under the topmost unreleased version heading.
- [ ] No sensitive data in code, tests, logs, or the commit message.
- [ ] The sample app still builds and runs.

## Licence

By contributing, you agree that your contributions are licensed under the
[MIT License](LICENSE) that covers this project.
