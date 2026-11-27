# Contributing

Thanks for helping improve ZenCampus. For substantial changes, open an issue first to discuss the approach. Small fixes and documentation improvements can be proposed directly in a pull request.

## Set up

- Install JDK 21, Android Studio or the Android SDK, and Android SDK platform 35.
- Clone the repository and open it in Android Studio, or use the included Gradle wrapper. Set `ANDROID_HOME` or create an untracked `local.properties` containing `sdk.dir=/path/to/Android/Sdk`.
- For desktop requirements and the build commands for each platform, see the [README](README.md#build-from-source). iOS and macOS work needs a Mac with Xcode. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the code fits together, and new features are written once in `shared/commonMain`.

From the repository root, run the checks relevant to your change:

```sh
./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug --no-daemon
```

Desktop UI tests need a graphical session and run separately with `./gradlew :desktopApp:desktopUiTest --no-daemon`. They use isolated profiles and local fixtures, including a loopback HTTP server for the embedded browser.

On a Mac, run the iOS simulator tests and device compilation, and the iOS UI fixtures on a fresh simulator:

```sh
./gradlew :shared:iosSimulatorArm64Test :shared:compileKotlinIosArm64 --no-daemon
./iosApp/UITests/run.sh
```

`run.sh` deletes its simulator afterwards (set `ZEN_IOS_KEEP_SIMULATOR=1` to keep it) and replaces `iosApp/build/ZenCampus.app` with the fixture build; run `./iosApp/build-simulator.sh` again afterwards.

Tests use fixtures and the sample account. Behaviour against a real university account still needs a manual check, and [docs/PLATFORMS.md](docs/PLATFORMS.md) records what has been checked where.

Android instrumentation tests sign out accounts, clear local app data, and uninstall the test app. Run them only on an emulator; do not use a physical phone unless explicitly asked to do so:

```sh
adb devices
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest --no-daemon
```

Use `adb -s <serial>` for individual device commands. Do not run the instrumentation command unless `emulator-5554` is present in the device list.

## Pull requests

Explain the problem, the change, and the checks you ran. If the change affects what a platform can do, update its row in [docs/PLATFORMS.md](docs/PLATFORMS.md), and add a line for users under the next version in [CHANGELOG.md](CHANGELOG.md). Include screenshots for visible UI changes where practical. Keep unrelated changes separate and update the documentation when behavior or setup changes. Do not include account data, credentials, downloaded course materials, signing keys, or private test fixtures in commits or reports.

By submitting a contribution, you agree that it can be distributed under the project's [MIT License](LICENSE). Third-party material remains subject to its own license.

For security vulnerabilities, use [SECURITY.md](SECURITY.md) instead of a public issue.
