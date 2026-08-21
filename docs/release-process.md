# Release Process

The SDK is published to **Maven Central** as `com.uqpay.sdk:uqpay-sdk-android`, through
the [Central Portal](https://central.sonatype.com) under the `com.uqpay.sdk` namespace the
company account owns (the same namespace as the Java server SDK). Merchants need no
repository block.

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

## Publishing setup

Publishing is configured in `uqpay-sdk/build.gradle.kts` (`mavenPublishing { … }`) using the
`com.vanniktech.maven.publish` plugin. Coordinates and version come from
`gradle/libs.versions.toml` (`uqpaySdkGroup`, `uqpaySdkArtifact`, `uqpaySdk`).

### Credentials

Nothing secret lives in the repository. The build reads these Gradle properties, which CI
supplies from repository secrets as `ORG_GRADLE_PROJECT_<name>` environment variables:

| Property | What it is | Where it comes from |
|---|---|---|
| `mavenCentralUsername` | Central Portal **user token** name | Portal → account → *Generate User Token* |
| `mavenCentralPassword` | Central Portal **user token** secret | same |
| `signingInMemoryKey` | ASCII-armoured GPG **private** key | `gpg --armor --export-secret-keys <KEY_ID>` |
| `signingInMemoryKeyPassword` | its passphrase | set when the key was created |

The user token is *not* the account password, and the GPG public key must be on a public
keyserver (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`) or Central rejects
the signature. Keep all four in the password manager and in GitHub → *Settings → Secrets
and variables → Actions*; never in `gradle.properties`, `local.properties` or a shell history.

Signing is wired up only when a key is present, so every ordinary build — and
`publishToMavenLocal` — works on a developer machine with none of these set.

### Verify locally before any remote publish

```bash
./gradlew :uqpay-sdk:publishToMavenLocal
ls ~/.m2/repository/com/uqpay/sdk/uqpay-sdk-android/<version>/
#  → .aar  -sources.jar  -javadoc.jar  .pom  .module
./gradlew :sample-app:assembleDebug -PuqpaySdkFromMavenLocal=true
```

The second command builds the sample app against the *published* coordinates instead of the
module, which proves the AAR, its POM and its transitive dependencies resolve the way they
will for a merchant.

### Publish

Pushing a `v<version>` tag runs `.github/workflows/publish.yml`, which checks the tag
against the catalog version, runs the verification tasks, and then runs

```bash
./gradlew :uqpay-sdk:publishToMavenCentral
```

With the credentials above in the environment this uploads a signed bundle to the Central
Portal and **stops at "validated"** — automatic release is deliberately off. A human then
opens [Deployments](https://central.sonatype.com/publishing/deployments), checks the
contents, and clicks **Publish**. Release builds are immutable once published; a mistake is
fixed by a new patch version, never by overwriting. Artifacts appear on
`repo1.maven.org` within about 30 minutes and in search within a few hours.

## Release checklist

1. All items in [acceptance-criteria.md](acceptance-criteria.md) verified for the RC.
2. Bump version in `gradle/libs.versions.toml`.
3. Update `CHANGELOG.md` (added / changed / fixed / deprecated / security).
4. Update docs to match the released API; sample app builds against the RC artifact.
5. `./gradlew clean :uqpay-sdk:assembleRelease :uqpay-sdk:test lint` — all green.
6. Tag `v<version>`, run `publishToMavenCentral` from the tag, review the deployment in
   the Central Portal and click **Publish**.
7. Smoke-test the published artifact in a fresh empty app (not the sample app workspace),
   after it has reached `repo1.maven.org`.
8. Announce release notes to integrators.
