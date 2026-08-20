# Security Policy

The UQPAY SDK for Android handles payment data. We take reports about it seriously and
would much rather hear about a problem early than read about it later.

## Supported versions

| Version | Supported |
| ------- | --------- |
| `0.1.x` (pre-release) | ✅ Current development line |

No version has been released yet. Once `0.1.0` ships, this table will list the release
lines that receive security fixes.

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security problem.** A public issue tells
everyone about the weakness before merchants have a fixed version to move to.

Use **GitHub's private vulnerability reporting** instead:

> Repository → **Security** tab → **Report a vulnerability**

That opens a private channel visible only to the maintainers. It keeps the whole exchange
in one place, and it lets us credit you when the fix ships.

**If you cannot use GitHub, email [it@uqpay.com](mailto:it@uqpay.com)** with `SECURITY` in
the subject line. Plain email is not encrypted, so keep the first message short — what the
issue affects and roughly how severe you believe it is — and we will arrange a secure
channel before you send details or any proof of concept.

### What to include

- The SDK version (`UQPay.version`) and the Android version and device model.
- What an attacker can achieve, and the steps to reproduce it.
- Any proof-of-concept code, and the impact you believe it has.

**Never include real card numbers, security codes, API keys, access tokens, or customer
personal data in a report.** Use the documented test values. If a real value is genuinely
necessary to explain the issue, say so and we will arrange a safer channel — do not paste
it. Mask anything you must reference, for example a card as `•••• •••• •••• 1234`.

## What to expect

These are our targets, measured in business days:

| Stage | Target |
| ----- | ------ |
| Acknowledgement that we received the report | 3 days |
| Initial assessment and a severity judgement | 10 days |
| Fix or documented mitigation for a confirmed high-severity issue | 30 days |

We will keep you updated if something takes longer, and we will tell you when a fix ships.

## Scope

**In scope** — anything in this repository: the `uqpay-sdk` library, its network and
storage behaviour, the payment UI, and the sample app.

**Out of scope** — the UQPAY gateway and platform APIs themselves. Those are a separate
system with a separate reporting path; contact UQPAY directly rather than filing here.
Also out of scope: reports that depend on a rooted or compromised device, on the host app
deliberately misusing the public API, or on credentials the reporter already controls.

## Coordinated disclosure

We ask that you give us a reasonable window to ship a fix before publishing details. We
will not take legal action against anyone who reports a vulnerability in good faith,
follows this policy, and avoids privacy violations, data destruction, and any disruption
to production systems or real payments.
