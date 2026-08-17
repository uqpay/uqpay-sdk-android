# Changelog

All notable changes to the UQPAY SDK for Android are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/); versioning follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing here has been released. The payment flow is still being built: a launched payment
currently finishes as `CANCELLED` with nothing submitted.

### Added
- Public API: `UQPay.createPaymentLauncher`, `UQPayConfiguration`, `PaymentSessionParams`,
  `PaymentResult`, `PaymentCallback`, `UQPayError`, `UQPayErrorCode`, `PaymentMethodType`
  (card + 13 wallets), and the `auth` package (`UQPayTokenProvider`, `UQPayAuthToken`).
- Internal gateway client over raw `HttpsURLConnection` — no OkHttp, so no transitive
  version conflict is forced on the host app. Retries only where provably safe: a
  mutating call without an idempotency key is never resent.
- Single `ErrorMapper` chokepoint converting every internal failure into a public
  `UQPayError`, with a table test asserting every declared code is reachable.
- Access-token caching and refresh-on-401, serialised so concurrent requests trigger one
  fetch rather than a stampede.
- Consumer R8 rules shipped with the AAR, including the `ActivityResultContract` keep
  that stops payment results being silently lost in minified builds.
- CI: `apiCheck`, unit tests, lint, and a minified-consumer job that verifies the
  contract survives R8. **Not yet executed — the repository has no remote.**
- Documentation set: integration guide, API reference, error codes, troubleshooting,
  architecture, acceptance criteria, release process, and the research specs under
  `docs/spec/`.

### Changed
- **Breaking:** `startPayment(activity, request, callback)` is replaced by an
  `ActivityResultContract` launcher. A callback passed at launch cannot survive process
  death; the registry can.
- **Breaking:** `PaymentStatus.TIMEOUT` is replaced by `PENDING`. A closed poll window is
  not an outcome — the customer may have paid moments earlier, and reporting failure
  invites a duplicate charge.
- **Breaking:** the auth model drops the publishable key, which UQPAY does not have. The
  app holds a short-lived token from the merchant backend, never the `x-api-key`.
- `UQPayErrorCode` and `PaymentMethodType` are open sets rather than enums, so adding a
  code or a wallet is not source-breaking for merchants matching on them exhaustively.
