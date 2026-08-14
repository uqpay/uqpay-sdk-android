# Release Process

> **Status:** Skeleton — publication target (Maven Central / private repo) to be decided.

## Versioning

Semantic versioning `MAJOR.MINOR.PATCH`:

- **MAJOR** — breaking public API change (requires migration notes).
- **MINOR** — new backward-compatible functionality.
- **PATCH** — backward-compatible fixes only.

Version is defined once in `gradle/libs.versions.toml` (`uqpaySdk`).

## Backward-compatibility policy

- Public API (`com.uqpay.sdk` excluding `internal`) is stable within a major version.
- Deprecations live for at least one minor release with `@Deprecated` + replacement
  guidance before removal in the next major.
- Error codes and `PaymentStatus` values are never renamed/repurposed once released.

## Release checklist

1. All items in [acceptance-criteria.md](acceptance-criteria.md) verified for the RC.
2. Bump version in `gradle/libs.versions.toml`.
3. Update `CHANGELOG.md` (added / changed / fixed / deprecated / security).
4. Update docs to match the released API; sample app builds against the RC artifact.
5. `./gradlew clean :uqpay-sdk:assembleRelease :uqpay-sdk:test lint` — all green.
6. Tag `v<version>` and publish the artifact (AAR + sources + POM) from the tag.
7. Smoke-test the published artifact in a fresh empty app (not the sample app workspace).
8. Announce release notes to integrators.
