# Publishing

DeltaList publishes to **Maven Central** under the group `com.latenighthack.deltalist`
using the [Gradle Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/)
and the new [Central Portal](https://central.sonatype.com).

Published modules:

| Module | Artifact | Type |
|---|---|---|
| `deltalist-core` | `com.latenighthack.deltalist:deltalist-core` | KMP (jvm, js, iosX64/Arm64/SimulatorArm64) |
| `deltalist-android-recyclerview` | `com.latenighthack.deltalist:deltalist-android-recyclerview` | Android (aar) |
| `deltalist-android-compose` | `com.latenighthack.deltalist:deltalist-android-compose` | Android (aar) |
| `deltalist-android-notifications` | `com.latenighthack.deltalist:deltalist-android-notifications` | Android (aar) |
| `deltalist-react` | `com.latenighthack.deltalist:deltalist-react` | Kotlin/JS (klib) |

The `demo-*` modules are not published.

## One-time setup

### 1. Verify the `com.latenighthack` namespace

On the [Central Portal](https://central.sonatype.com) → **Namespaces**, add and verify
`com.latenighthack`. Verification requires proving ownership of the `latenighthack.com`
domain (a TXT DNS record the portal dictates). Until the namespace is verified, uploads
under this group are rejected.

> If domain ownership isn't available, the alternative is the GitHub-backed namespace
> `io.github.mproberts` (verified by creating a public repo the portal names). That would
> require changing `GROUP` in `gradle.properties`.

### 2. Generate a Central Portal user token

Central Portal → **Account → Generate User Token**. This yields a username/password pair
(not your portal login) used for uploads.

### 3. Create a GPG signing key

Central Portal requires every artifact to be signed.

```bash
# Generate a key (RSA 4096, no expiry is fine for a CI key)
gpg --gen-key

# Find the key id
gpg --list-secret-keys --keyid-format short

# Publish the public key so Central can verify signatures
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export the private key (ascii-armored) for CI
gpg --armor --export-secret-keys <KEY_ID>
```

## Releasing via GitHub Actions (recommended)

`.github/workflows/release.yml` publishes and releases automatically when a `v*` tag is
pushed. It runs on macOS so the iOS targets of `deltalist-core` are included.

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |
| `SIGNING_KEY` | Full ascii-armored private key (`gpg --armor --export-secret-keys`) |
| `SIGNING_KEY_ID` | Short key id (last 8 hex chars) |
| `SIGNING_PASSWORD` | Passphrase for the GPG key |

Cut a release:

```bash
# 1. Bump VERSION_NAME in gradle.properties (the workflow asserts the tag matches it)
# 2. Commit, then tag and push
git tag v0.1.0
git push origin v0.1.0
```

`SONATYPE_AUTOMATIC_RELEASE=true` (in `gradle.properties`) means the deployment is
released automatically once Central's validation passes — no manual "Publish" click.
To stage without releasing, set it to `false` and release from the portal UI.

## Releasing locally

Provide the same credentials as Gradle properties (e.g. in `~/.gradle/gradle.properties`,
**not** committed):

```properties
mavenCentralUsername=<token-username>
mavenCentralPassword=<token-password>
signingInMemoryKey=<ascii-armored-private-key>
signingInMemoryKeyId=<short-key-id>
signingInMemoryKeyPassword=<key-passphrase>
```

Then, from macOS (required for the iOS artifacts):

```bash
./gradlew publishToMavenCentral --no-configuration-cache
```

## Verifying the wiring without credentials

Publish to the local Maven repo (`~/.m2`) with signing disabled:

```bash
./gradlew publishToMavenLocal -PRELEASE_SIGNING_ENABLED=false
```
