# Platforms

ZenCampus is one codebase. Screens, connectors, sync and caching live in `shared/commonMain`; each platform supplies
small hooks (session storage, files, browser, notifications, updates) in its own source set or app module. A feature is
written once in shared code and then **checked**, not ported, on each platform.

This page is the record of where each feature stands. Update its row in the same commit that changes the feature.

## Status words

| Word | Meaning |
| --- | --- |
| **Verified** | Covered by automated tests on that platform and checked in the running app. |
| **Fixtures** | Implemented; tested on that platform with local fixtures or the sample account, not yet with a live account. |
| **Shared** | Comes from shared code and builds for the platform, but nobody has checked it there yet. |
| **Partial** | Works in a reduced form; the note says what is missing. |
| **Gap** | Not available on the platform yet. |
| — | Does not apply to the platform. |

## Where things stand

Targets: Android (phone, API 26+), Linux desktop (x86-64), macOS desktop (Apple Silicon, JVM), iOS (simulator preview),
Windows desktop (x64; builds and tests in CI and in a Windows 11 VM, not yet tried on a real PC).

### Accounts and data

| Feature | Android | Linux | macOS | iOS | Windows |
| --- | --- | --- | --- | --- | --- |
| DigiCampus sign-in | Verified | Verified | Verified | Verified (simulator) | Shared |
| Library account sign-in | Verified | Verified | Shared | Fixtures | Shared |
| Saved session storage | Verified (Keystore) | Verified (Secret Service) | Verified (Keychain) | Verified (Keychain, simulator) | Fixtures (DPAPI, per Windows user; tested on the CI runner) |
| Device-level saved Study and Library sign-ins, managed in Settings, with one automatic Study popup per expired session | Verified (Keystore and WebView; live library and Microsoft sign-in on a phone) | Shared (Secret Service and JCEF) | Fixtures (JCEF and native account chooser, fake vault) | Fixtures (WKWebView and native account chooser, simulator) | Shared (local DPAPI and JCEF) |
| Offline cache and sign-out purge | Verified | Verified | Verified | Verified (simulator) | Shared |
| Background refresh | Verified (WorkManager) | Verified (while running) | Shared | Fixtures (iOS decides when it runs) | Shared (while running) |
| Reminders and notifications | Verified | Verified | Shared | Fixtures (permission prompt) | Shared (tray notification) |
| Ended DigiCampus session: in-app banner, plus a notification when a background refresh finds it | Shared (notification not yet seen with a live expired session) | Partial (banner only) | Partial (banner only) | Partial (banner only) | Partial (banner only) |

### Study

| Feature | Android | Linux | macOS | iOS | Windows |
| --- | --- | --- | --- | --- | --- |
| Today, courses, inbox, forums, participants | Verified | Verified | Shared | Shared | Shared |
| Calendar, planning, office-hours booking | Verified | Verified | Shared | Shared | Shared |
| Calendar export (.ics) | Verified | Verified | Shared | Shared | Shared |
| Material download, preview, share | Verified | Verified | Verified (download and preview) | Fixtures (Quick Look, share sheet) | Shared |
| Course sync into folders | Verified | Verified | Shared | Fixtures (Files folder or picked folder) | Shared |
| Automatic sync | Verified | Verified | Shared | Partial (only with background refresh) | Shared |
| Save a copy to Downloads | Verified | Verified | Shared | Fixtures (document picker) | Shared |
| Vips reader and attachments | Verified | Verified | Verified | Verified (WebKit reader, simulator) | Shared |
| "Open in DigiCampus" in the signed-in in-app browser (falls back to the system browser for other sites; a file opens at its folder) | Verified | Shared | Shared (a live check found file links failing; fixed, not yet rechecked) | Shared (WebKit reader; a live check found file links failing; fixed, not yet rechecked) | Shared |
| Submission uploads | Verified | Verified | Shared | Fixtures (document picker) | Shared |
| Wiki and Courseware reading | Verified | Verified | Shared | Shared | Shared |

### Library and Mensa

| Feature | Android | Linux | macOS | iOS | Windows |
| --- | --- | --- | --- | --- | --- |
| Catalogue, copies, saved books | Verified | Verified | Shared | Shared | Shared |
| Loans, renewals, reservations, fees | Verified | Verified | Shared | Shared | Shared |
| "Notify me when available" | Verified | Verified | Shared | Fixtures | Shared |
| Mensa menus, filters, prices | Verified | Verified | Verified | Verified (simulator) | Shared |
| Mensa in English (MyMemory dish translation, label and category tables) | Fixtures | Fixtures | Shared | Shared | Shared |
| Favourite dish alerts | Verified | Verified | Shared | Fixtures | Shared |

### Platform extras

| Feature | Android | Linux | macOS | iOS | Windows |
| --- | --- | --- | --- | --- | --- |
| Home-screen widgets: Your day, Next class, Mensa today, Due soon, Library (app colours and font, light and dark) | Verified (widget-host test with the sample account; used on a phone) | — | — | Verified (simulator, sample account, all five in light and dark; App Group provisioning on iPhone pending) | — |
| Launcher shortcuts | Verified | — | — | Verified (simulator, Today, Calendar, Mensa and Search) | — |
| Share a file into ZenCampus | Verified | — | — | Verified (simulator, Files → Open With → ZenCampus, through upload review) | — |
| Keyboard navigation | — | Verified | Fixtures (system menus and Command shortcuts) | — | Shared |
| Desktop app quit and profile cleanup | — | Shared | Fixtures (window close and native Quit with the sample reader) | — | Shared |

### Release and diagnostics

| Feature | Android | Linux | macOS | iOS | Windows |
| --- | --- | --- | --- | --- | --- |
| Release package | Verified (signed APK) | Verified (tar.gz) | Partial (ad-hoc signed dmg, allowed once in Privacy & Security; the update zip is published once notarised) | Partial (unsigned device archive; signing waits for the membership) | Partial (unsigned per-user MSI; SmartScreen asks once) |
| Update check | Fixtures | Fixtures | Fixtures | Partial (development builds only; Release archives never check) | Fixtures |
| Update install | Fixtures (system installer) | Fixtures (bundle swap) | Fixtures (signed app swap; same team and Gatekeeper) | — (TestFlight and the App Store update it) | Fixtures (runs the newer MSI after quitting) |
| Scrubbed local log and sharing | Verified | Verified | Shared | Fixtures | Shared |
| Opt-in crash reports (Android lines restored from the uploaded R8 mapping; iOS dSYMs uploaded by archive.sh) | Fixtures | Verified | Shared | Fixtures (a Kotlin crash on the simulator reached Sentry; MetricKit and dSYM symbolication need a device and a signed archive) | Shared |

## Known gaps worth planning

- **iOS distribution**: the Xcode project builds an unsigned device archive. TestFlight signing and upload
  wait for the Apple Developer Program membership (see [Distribution](#distribution)). Review
  the privacy manifest again before the first submission and after dependency upgrades.
- **iOS extras on iPhone**: the simulator pass covered the five widgets, four quick actions and document import
  through upload review. Check App Group provisioning and the same flows on an iPhone with a Personal Team.
- **iOS crash reports**: Kotlin capture and MetricKit ingestion are implemented; native MetricKit delivery and release
  dSYM symbolication still need an end-to-end check.
- **macOS release**: the packaging, signing, notarisation and self-update are in place. Until there is a Developer ID
  certificate, releases carry an ad-hoc signed dmg that students allow once and that links to new releases instead of
  updating itself (see [Distribution](#distribution)).
- **Windows**: the desktop app runs from the same code, with DPAPI-encrypted sessions, Windows folders, tray
  notifications and an MSI that updates itself; CI runs the desktop and browser tests on Windows, and the browser test
  clicks and types into the embedded page with real system input there. A Windows 11 VM has installed the MSI and
  reached the university sign-in page. It still needs a pass
  on a real PC (sign-in in the embedded browser, notifications, an update from one release to the next) and, to drop
  the SmartScreen prompt, a code-signing certificate.

## Distribution

| Platform | Channel | Status |
| --- | --- | --- |
| Android | GitHub Releases: signed APK, in-app update through the system installer | Ready; needs the release keystore secrets |
| Linux | GitHub Releases: x86-64 tar.gz, in-app update by bundle swap | Ready |
| macOS | GitHub Releases: arm64 dmg; once notarised, plus a zip the app updates itself from | Published by the release workflow; ad-hoc signed (allowed once in Privacy & Security, update by link) until the Developer ID secrets exist |
| iOS | TestFlight first, then the App Store | Xcode project and unsigned archive; signing waits for the membership |
| Windows | GitHub Releases: per-user x64 MSI, in-app update by running the newer MSI | Built and published by the release workflow; unsigned |

Both Apple platforms need one Apple Developer Program membership. Enrolling as an individual shows the legal name as
the seller on the App Store and TestFlight; an organisation shows its name but needs a legal entity and a D-U-N-S number.

### macOS

1. Done: `scripts/package_macos.sh` builds the app with the hardened runtime and its
   [entitlements](../desktopApp/packaging/macos/entitlements.plist), signs it, notarises and staples it, and writes the dmg
   and the update zip. The release workflow runs it on a Mac runner and publishes the dmg, plus the update zip when notarised.
2. Done: a signed app in a writable folder (normally /Applications) downloads the zip, checks its SHA-256, its signature,
   that it comes from the same developer team, and Gatekeeper, then swaps itself on restart.
3. Waiting on the membership: create the Developer ID certificate and the App Store Connect API key and add the secrets
   listed in [RELEASE.md](../RELEASE.md#one-time-setup).
4. Then check on a Mac: the first notarised release installs from the dmg, opens without a warning, signs in
   (Chromium inside the hardened runtime), and a later release updates it.

The Mac App Store is not planned: its sandbox does not fit the bundled Chromium or the course folder sync.

### iOS

1. Done: Xcode project generated by XcodeGen (`iosApp/project.yml`, `brew install xcodegen`), bundle IDs
   `io.github.binoverfl0w.zencampus` (Release) and `io.github.binoverfl0w.zencampus.dev` (Debug), app icon, launch screen, privacy manifest (no tracking;
   opt-in crash data, UserDefaults and file timestamp reasons), and `iosApp/archive.sh`, which builds an unsigned Release
   archive today and signs, exports and uploads once `ZENCAMPUS_IOS_TEAM_ID` and an App Store Connect API key are set.
   Provision the app and its `.widgets` extension with the same App Group (`group.io.github.binoverfl0w.zencampus` for Release,
   `group.io.github.binoverfl0w.zencampus.dev` for Debug). The simulator uses ad hoc signing and its own entitlement files.
2. Test on a real iPhone with an Xcode Personal Team. Apple lists App Groups under [Apple Developer capabilities](https://developer.apple.com/help/account/reference/supported-capabilities-ios/)
   and Xcode manages free development provisioning with [seven-day profiles](https://developer.apple.com/support/compare-memberships/).
   Provisioning the matching app and extension group on a device remains to be tested. Quick actions and document import
   use standard UIKit capabilities. Also check background refresh, notifications, Keychain, sign-in and Files folders.
3. With the membership: signed archive and internal TestFlight (up to 100 testers with App Store Connect access).
4. External TestFlight for students (up to 10,000 testers, after a short Beta App Review; builds expire after 90 days).
5. App Store, once TestFlight has shown it holds up. Review needs the sample account (reviewers cannot sign in to the
   university), a privacy policy URL and the privacy labels, and a clear "unofficial" description; using the
   university's name is a review risk, and an informal OK from the university helps.

Release builds for TestFlight and the App Store never check GitHub for updates, which App Store rules forbid. Crash
reports on iOS catch Kotlin exceptions as they happen and take other crashes from MetricKit on the next start, which
only real devices deliver; `iosApp/archive.sh` uploads the dSYMs Sentry needs, and the App Store privacy label must
declare crash data, not linked to the user (the privacy manifest already does).

## How each platform is checked

| Platform | Every push (GitHub Actions) | Before calling a feature Verified |
| --- | --- | --- |
| Android | `ci.yml` → Android and Linux: unit tests, lint, debug build | `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest` on the emulator (see AGENTS.md) |
| Linux | `ci.yml` → Android and Linux: shared JVM and desktop tests | `./gradlew :desktopApp:desktopUiTest` on a Linux desktop |
| macOS | `ci.yml` → iOS and macOS: desktop tests on a Mac runner | On a Mac: `./scripts/build_desktop.sh` and `./gradlew :desktopApp:desktopUiTest` |
| iOS | `ci.yml` → iOS and macOS: simulator tests, device compilation, simulator app build (Xcode project) | On a Mac: `./iosApp/UITests/run.sh`, `./iosApp/archive.sh`, then a manual pass in the simulator |
| Windows | `ci.yml` → Windows: shared JVM, desktop, UI and browser tests, MSI build | On a Windows PC: install the MSI from CI, sign in, open a material, get a reminder, update to a newer build |

The usual loop:

1. Write the feature in shared code, with platform hooks only where a platform really differs.
2. Push. CI builds and tests every target on its own OS.
3. For anything visible or platform-specific, run the device checks above on each platform the change touches.
4. Update the rows here: what changed, and what is now verified where.
