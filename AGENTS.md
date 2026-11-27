# Android verification

Run instrumentation tests only on an emulator, selected with `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest --no-daemon`. Check `adb devices` first. These tests sign out, clear the app's local cache, and uninstall the test app; do not run them on a physical phone unless the user explicitly requests that destructive test run. Use `adb -s <serial>` for all device commands. The pinned AGP fails with its `--serial` task option; use the environment variable instead.

# Platforms

Android, Linux, macOS, iOS and Windows build from the same tree. Put features in `shared/commonMain` and keep platform differences in hooks (`expect`/`actual`, platform interfaces, CompositionLocals) whose default keeps the existing Android and Linux behaviour. When a change affects what a platform can do, update its row in `docs/PLATFORMS.md` in the same commit. When a change is visible to users, add a plain one-line bullet under the next version in `CHANGELOG.md`; that section becomes the release notes the apps show. iOS and macOS checks need a Mac: CI runs the simulator tests on every push, and the device and UI checks listed in `docs/PLATFORMS.md` run on a Mac.

# App buttons

Use the design components `ZenButton`, `ZenLink`, `DialogButton`, `ZenChip`, `IconAction` and `ZenIconToggle` for app UI actions. Keep raw Material buttons inside the design components.
