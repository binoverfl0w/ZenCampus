# Release checklist

This checklist is for maintainers preparing a public source repository or distributing a build. It does not replace testing on the target platforms.

## Before publishing the repository

- Review the exact commits and files to be published, including history, for credentials, personal information, private university content and generated artifacts. Removing a secret in a later commit does not remove it from history.
- Confirm you have the rights to distribute the source and assets, and review [third-party notices](THIRD_PARTY_NOTICES.md), including the licenses of bundled and transitive dependencies.
- Review pending local changes and include the intended new source files. Publish only the intended branches and tags; do not push local automation or checkpoint refs.
- Enable GitHub private vulnerability reporting if you want to use the reporting path described in [SECURITY.md](SECURITY.md), and confirm the maintainer's private contact method is available.
- Confirm the GitHub Actions checks pass on the published branch. Local success does not verify the hosted workflow.

## Before distributing a build

- Run the relevant checks in [CONTRIBUTING.md](CONTRIBUTING.md), and verify installation, upgrades and important user flows on each supported platform. Use an emulator for Android instrumentation unless a physical-device run has been explicitly authorized.
- Set the version and write its section in [CHANGELOG.md](CHANGELOG.md), including known limitations such as the unsigned Windows and macOS builds.
- Sign Android release builds with the intended distribution key, keeping the key and credentials out of the repository. macOS builds without notarization are published as an ad-hoc signed dmg only (see [macOS](#macos)).
- Review the contents, bundled licenses and provenance of each release artifact. Publish only artifacts you have built and verified.

## Publishing a release

Releases are published on GitHub. The Android and desktop apps check the repository's latest release once a day (while
"Check for updates" is on), verify the download against the SHA-256 published with it, and install it: Android hands the
APK to the system installer (the student confirms); the Linux bundle and the macOS app unpack the new version next to
themselves and switch on restart. A macOS update must also be signed by the same Apple developer team and accepted by
Gatekeeper. Windows downloads the newer MSI and runs it once the app has quit. Installs from a deb/rpm package, unsigned macOS builds,
Intel Macs and development runs only get a link to the release page. iOS goes through TestFlight and the App Store instead (see [iOS](#ios)).


### One-time setup

1. Create the release signing key and keep it safe; **every** published APK must be signed with it, or Android refuses
   the update. Builds signed with the debug key (everything installed so far) cannot update to it: those installs need
   one manual reinstall, which signs the student out and clears their offline files.
   ```sh
   keytool -genkeypair -keystore zencampus-release.jks -alias zencampus -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Add repository secrets (Settings → Secrets and variables → Actions):
   - `ANDROID_KEYSTORE_BASE64`: `base64 -w0 zencampus-release.jks`
   - `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
   - `ZENCAMPUS_CRASH_DSN` (optional): the DSN of a Sentry or self-hosted GlitchTip project. Without it, crash reports
     stay off and the setting is hidden. In the project, turn off storing IP addresses and enable server-side data
     scrubbing; the apps send no user, account or device name, and scrub messages before sending.
   - `SENTRY_AUTH_TOKEN` (optional): a Sentry organization token (Settings → Developer Settings → Organization Tokens).
     The workflow uploads the Android R8 mapping with it, so Android crash reports show real files and line numbers;
     the APK names its mapping by the ID `sentry-cli upload-proguard` gives it. With a personal token instead (scopes
     `project:releases` and `org:read`), also set the repository variable `SENTRY_ORG` to the organization slug.
3. The workflow sets `ZENCAMPUS_UPDATE_REPO` to the repository it runs in, so the published builds check that repository.
4. macOS (optional until there is an Apple Developer Program membership; without these secrets the release carries an
   ad-hoc signed dmg and no update zip):
   - `MAC_CERTIFICATE_P12_BASE64` and `MAC_CERTIFICATE_PASSWORD`: a "Developer ID Application" certificate with its
     private key, exported from Keychain Access as .p12 (`base64 -i certificate.p12`).
   - `MAC_SIGN_IDENTITY`: the certificate's name, for example `Developer ID Application: Jane Doe (ABCDE12345)`.
   - `NOTARY_KEY_P8_BASE64`, `NOTARY_KEY_ID`, `NOTARY_ISSUER_ID`: an App Store Connect API key (Users and Access →
     Integrations, role Developer) for notarisation: the .p8 file (`base64 -i AuthKey_XXXX.p8`), its key ID and the issuer
     ID.

### Each release

1. Raise `zencampus.version` and `zencampus.versionCode` in `gradle.properties` (one version for every app; the
   versionCode must grow with every release).
2. Move the changes into a `## <version>` section of [CHANGELOG.md](CHANGELOG.md). That section is the release notes:
   plain text, one line per bullet, because the apps show it as is when they offer the update.
3. Commit, then tag with the same notes and push the tag:
   ```sh
   git tag -a v0.1.0 -F <(scripts/release_notes.sh 0.1.0)
   git push origin v0.1.0
   ```
4. The [release workflow](.github/workflows/release.yml) checks that the tag matches the version and that CHANGELOG.md
   has notes for it, and runs the tests. It then builds the signed APK and the Linux archive with
   `scripts/package_release.sh`, the macOS disk image (and, when notarised, the update archive) with
   `scripts/package_macos.sh` on a Mac runner, and the unsigned per-user MSI on a Windows runner. It publishes them with
   `SHA256SUMS` and the CHANGELOG.md section as the release text.

`scripts/package_release.sh` also works locally with the same `ZENCAMPUS_*` variables, writing to `build/release`, and so
does `scripts/package_macos.sh` on an Apple silicon Mac (see its header for the signing variables). Running the release
workflow by hand (Actions → Release → Run workflow) builds only the macOS files, unsigned unless the secrets exist, and
keeps them as a workflow artifact without publishing anything.

### macOS

The release carries `zencampus-<version>-macos-arm64.dmg` (drag ZenCampus to Applications) and
`zencampus-<version>-macos-arm64.zip`, which installed copies download to update themselves. Both contain the same app,
signed with the Developer ID certificate under the hardened runtime and notarised by Apple, with the ticket stapled so
Gatekeeper accepts it offline. The app needs three hardened-runtime exceptions
([entitlements](desktopApp/packaging/macos/entitlements.plist)): the JVM compiles code at run time, and the Chromium, JNA
and Skiko libraries are unpacked into the cache at run time. Only Apple silicon is built; macOS 27 no longer runs on Intel.

Until the Developer ID secrets exist, the release carries only the dmg, with the app ad-hoc signed. macOS refuses to open
it the first time; the student then chooses **Open Anyway** in System Settings → Privacy & Security once. Such an app
cannot pass the updater's signature check, so it shows new versions with a link to the release page.

### iOS

iOS builds are not published on GitHub. `iosApp/archive.sh` produces the Release archive; with a team ID and an App Store
Connect API key it signs it and can upload it to TestFlight. Release builds never look for GitHub updates, because the App
Store and TestFlight install updates themselves. See [Platforms](docs/PLATFORMS.md#distribution) for the plan.

For crash reports, run it with `ZENCAMPUS_CRASH_DSN` set (built into the app as for Android) and `SENTRY_AUTH_TOKEN` set
to the organization token: the script then uploads the archive's dSYMs, which Sentry needs to name the frames of the
stripped release binary. iOS reports Kotlin exceptions as they happen and other crashes from MetricKit on the next start;
MetricKit only reports from real devices, not the simulator.

## Logs and crash reports

Each installation keeps a local log (`zencampus.log`, rotated at 512 KB) of syncs, failures, sign-ins and updates.
Messages describe kinds and counts; credentials, e-mail addresses, query strings, long IDs and home folders are
scrubbed before anything is written. Students can share it from Settings → Updates & diagnostics. On Linux, run with
`ZENCAMPUS_LOG=1` to also print it to the terminal; Android debug builds mirror it to Logcat.

Crash reports are sent only after the student agrees: either "Send crash reports" in Settings, or once from the prompt
shown after a crash. A report holds the scrubbed exception chain with code locations, the app and OS version, the
device model on Android and the last 60 log lines.
