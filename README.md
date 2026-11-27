# ZenCampus

An unofficial campus app for the University of Augsburg. It brings DigiCampus, the university library and the Mensa
into one app that keeps working offline. It runs on Android, Windows, Linux and macOS, and an iPhone version is in
testing. ZenCampus is not made or endorsed by the university.

Downloads are on the [releases page](https://github.com/binoverfl0w/ZenCampus/releases). [Platforms](docs/PLATFORMS.md)
shows what has been checked on which platform, and [CHANGELOG.md](CHANGELOG.md) lists what each version changed.

| Platform | Download | Notes |
| --- | --- | --- |
| Android 8 or newer | `.apk` | Updates itself through the system installer. |
| Windows 10/11, x64 | `.msi` | Per-user install, no admin rights. Not code-signed yet: SmartScreen asks once (**More info** → **Run anyway**). |
| Linux, x86-64 | `.tar.gz` | Needs X11 or XWayland. Unpack and run `zencampus/bin/zencampus`. The app updates itself by swapping the bundle on restart. |
| macOS, Apple silicon | `.dmg` | Not signed by Apple yet: allow it once in System Settings → Privacy & Security → **Open Anyway**. It links to new versions instead of updating itself. |
| iPhone | — | Will come through TestFlight. |

## What it does

The app has three boards, each with its own colour: Study in mist blue, Library in sage and Mensa in sand. On phones you
switch boards with the roller on the left or the board name in the header, and the bottom bar holds the board's
sections. Wider windows (840 dp and up, including the desktop app) show a side rail with the boards, sections and
pinned courses instead. The header search finds courses, files, places and catalogue titles.

**Study** needs a DigiCampus account.

- **Today** shows the next class, the rest of the day with gaps and cancellations, coursework due within seven days,
  Mensa and library at a glance, and course updates. The weather chip opens the hourly forecast.
- **Courses**: materials, announcements and comments, people, forum, wiki and Courseware where the course has them.
  Lectures, exercises and tutorials of the same subject and semester are grouped automatically. You can rename groups,
  change their members or keep courses separate.
- **Coursework** lists Courseware tasks, Vips sheets and submission folders from every course, sorted by due date.
  Vips opens in the app with the official answer controls, already signed in. You can add personal deadlines; they stay
  on the device.
- **Inbox** holds DigiCampus messages (read, write, reply) and a combined updates view.
- **Calendar**: the week and the semester, with lecture periods and holidays. It exports to an `.ics` file, a one-time
  copy rather than a live subscription.
- **Office hours** lists the consultation slots of your lecturers and tutors and books or cancels them.
- **Files** open in the app (PDFs) or in an installed reader. **Sync** copies a whole course into a folder on the
  device, by default `Downloads/ZenCampus/<course>`, keeping the DigiCampus folders. Later syncs fetch only new or
  changed files, and files removed from DigiCampus stay in your folder. Files over 100 MB and Vips sheets are skipped;
  opening a Vips sheet can start an attempt.

**Library** works without signing in for the catalogue: search, copies, shelf locations and saved books. Connect your
library account in **Your cards** to see loans, reservations, pickups and fees, renew loans, and get a reminder before
something is due.

**Mensa** shows every Studierendenwerk Augsburg location by day or week, with diet and allergen filters, the price for
your group and alerts for favourite dishes. The menus are published in German only. In English, the dish names and
descriptions are translated with MyMemory, which you can switch off on the Mensa screen.

**On the home screen**: Android has five widgets: Your day, Next class, Mensa today, Due soon and Library. Long-press
the app icon for Today, Calendar, Mensa, Search and Office hours. To hand in a file, share it to ZenCampus, choose an
open submission folder and confirm the upload. The iPhone version has the same widgets, the first four shortcuts and
document import from Files.

Anything that sends something (an upload, a message, a forum post, a renewal, a booking) shows what will be sent and
waits for your confirmation. It is sent once and never repeated automatically. If the result is unclear, the app
asks you to check the official page before you try again.

### What stays on DigiCampus

Message attachments and forwarding, new forum threads, interactive Courseware blocks and editing, course enrolment and
waiting lists, exercise-group changes, surveys and plugins such as getFeedback! or coLearn!, recordings, external
learning tools, and editing the DigiCampus calendar. For these, **Open in DigiCampus** opens the official page in the
signed-in in-app browser. Placing or cancelling library reservations stays on the library website. Exams and grades
belong to STUDIS, which the app does not cover.

## Signing in

Choose **Continue with DigiCampus** to sign in on the official university and Microsoft pages inside the app. You can
use the Library and Mensa without an account: choose **Library & Mensa**.

The app keeps the session in the platform's secure storage: Android Keystore, the Secret Service keyring on Linux,
DPAPI on Windows, and the Keychain on macOS and iOS. The DigiCampus session ends after a few hours, and the university's
Microsoft sign-in has no "stay signed in", so you sign in again from time to time.

To make that quicker, choose **Add account** in the Study or Library sign-in popup. Study saves a university email and
password for Microsoft's sign-in page, and Library saves a library number and password for the library's login form.
Each popup can hold several accounts. The entries live in the device's secure storage, separate from the signed-in
account, and are only filled into the page they belong to. The second factor (the Authenticator number) stays on your
phone. Manage them in **Settings → Saved sign-ins**. Signing out and disconnecting the library both offer to remove
them.

[PRIVACY.md](PRIVACY.md) lists what the app stores and every service it talks to.

## Desktop

The desktop app uses the same screens as the phone and an embedded Chromium for sign-in and Vips.

| Shortcut (Command on macOS) | Action |
| --- | --- |
| Ctrl+1 / 2 / 3 | Study / Library / Mensa |
| Alt+Left or Escape | Back |
| Ctrl+R | Refresh |
| Ctrl+K | Search |
| Ctrl+Q | Quit |
| Ctrl+S in a PDF | Save a copy |

Background refresh and reminders run while the app is open, including minimised, and stop when you quit.

On **Linux**, `secret-tool` (keyring), `notify-send` (notifications), `gio` and `gtk-launch` (opening files with other
apps) come from your distribution. Without a keyring the app still signs in, but says it cannot remember the session.
`scripts/install_desktop.sh` copies the app to `~/.local/share/zencampus-desktop` and adds a menu entry; the previous
version is kept as `zencampus-desktop.previous`. Account data and offline files live in `~/.local/share/zencampus`,
readable only by you. Rendering uses the software renderer by default because GPU start-up crashes on some graphics
stacks; start with `SKIKO_RENDER_API=OPENGL` to try the GPU.

On **Windows**, the MSI installs to `%LOCALAPPDATA%\ZenCampus` with a Start menu entry. Account data lives in
`%APPDATA%\ZenCampus`. Reminders appear as Windows notifications.

Every desktop build accepts `--data-dir <folder>` to run a separate profile with its own sign-ins.

## Build from source

You need JDK 21 and the Android SDK with platform 35 (`sdk.dir` in an untracked `local.properties`, or `ANDROID_HOME`).

```sh
./gradlew :androidApp:assembleDebug          # Android: androidApp/build/outputs/apk/debug/
./scripts/build_desktop.sh                   # desktop app for this OS
./scripts/run_desktop.sh                     # run it (Linux)
./scripts/install_desktop.sh                 # add it to the Linux application menu
gradlew.bat :desktopApp:packageMsi           # Windows installer, on Windows
./scripts/package_macos.sh                   # macOS dmg, on an Apple silicon Mac
```

On macOS, open the built app with `open desktopApp/build/compose/binaries/main/app/zencampus.app`.

For the iOS simulator you also need Xcode and XcodeGen (`brew install xcodegen`):

```sh
./iosApp/build-simulator.sh
xcrun simctl install booted iosApp/build/ZenCampus.app
xcrun simctl launch booted io.github.binoverfl0w.zencampus.dev
```

`./iosApp/archive.sh` builds an unsigned device archive. With an Apple Developer team and an App Store Connect API key it
signs it and can upload it to TestFlight; see [RELEASE.md](RELEASE.md#ios).

Release builds check GitHub for updates only when built with `ZENCAMPUS_UPDATE_REPO=owner/name`, which the release
workflow sets. iOS release builds never check.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) has the checks to run, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how
the code fits together, and [RELEASE.md](RELEASE.md) covers publishing. Report security problems as described in
[SECURITY.md](SECURITY.md).

## Credits and licence

ZenCampus is available under the [MIT License](LICENSE). Weather comes from [Open-Meteo](https://open-meteo.com/) for
fixed campus coordinates, menus from [Studierendenwerk Augsburg](https://studierendenwerk-augsburg.de/speiseplaene/)
and translations from [MyMemory](https://mymemory.translated.net/). The Manrope font is under the
[SIL Open Font License](shared/src/commonMain/composeResources/files/manrope_ofl.txt). Other components are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Service names belong to their owners.
