# Privacy

ZenCampus is an unofficial, independent app for students of the University of Augsburg. It has no servers of its own
and no accounts, analytics, advertising or tracking. Your data stays on your device, except for the requests listed
below, which go straight from the app to each service.

## What stays on your device

- **Sign-in sessions** for DigiCampus and the library. You sign in on the official pages. ZenCampus keeps the
  resulting sessions in Android Keystore-protected storage, the Secret Service keyring on Linux, the Keychain on macOS
  and iOS, or DPAPI-protected storage on Windows.
- **Saved sign-ins** are optional. From the Study or Library sign-in popup, choose **Add account** to save a
  university email or library number and password, with an optional label. Entries and their last-used times are
  kept in this device’s secure storage, separate from whichever DigiCampus account is signed in. iOS uses
  device-only Keychain entries and Windows uses the local user profile. The app keeps passwords out of its cache,
  logs, crash reports and backups. It fills and submits each saved sign-in only on the official sign-in page for
  its saved site. The Authenticator step stays on your phone. Manage or remove entries in **Settings → Saved sign-ins**.
  DigiCampus sign-out offers to remove all entries and library disconnect offers to remove library entries.
  Both options are checked by default. Entries you keep stay on the device until you remove them.
- **Cached content**: courses, timetable, messages, materials, loans, menus and your settings, so the app works offline.
  Signing out deletes that account's session and its cached data. Disconnecting the library leaves DigiCampus signed in.
- **A diagnostic log**: short technical notes such as "refresh failed: offline". The app never writes names, course
  titles or content to it, and passwords, cookies, tokens, e-mail addresses, long IDs, the query part of web addresses
  and home-folder paths are removed automatically before anything is written. The log leaves your device only if you
  share it yourself (Settings → Updates & diagnostics).
- **Reminders and notifications** are scheduled and shown on the device.

## Who the app talks to

| Service | When | What is sent |
| --- | --- | --- |
| DigiCampus (`digicampus.uni-augsburg.de`) | While you are signed in | Your session, to read and, when you confirm it, submit your study content |
| Microsoft sign-in | While signing in to DigiCampus using a saved sign-in | Your saved university email and password go to Microsoft's sign-in page |
| University library (`opac.bibliothek.uni-augsburg.de`) | When signing in with a saved library account, while connected, or when searching the catalogue | Your saved library number and password on the login page, your library session, or the search terms |
| Studierendenwerk menus (`augsburg.my-mensa.de`) | When you view the Mensa | Nothing personal |
| Faculty room plans (`itwiwi-scripts.wiwi.uni-augsburg.de`) | When you look for rooms | Nothing personal |
| [Open-Meteo](https://open-meteo.com/) | For the campus weather | Fixed campus coordinates, never your location |
| [MyMemory](https://mymemory.translated.net/) | Only with the app in English and dish translation on | The German dish names and descriptions from the public menu |
| GitHub (`api.github.com`, `github.com`) | Once a day while "Check for updates" is on (Android and desktop) | A request for the latest release |
| Crash reporting | Only if you turn crash reports on | See below |

Each service also sees your IP address, as with any internet request, and handles it under its own privacy policy.
The app sends sessions only to the service they belong to and saved sign-ins only to the page they fill.

## Crash reports (off unless you turn them on)

If the app crashes and you have turned crash reports on, it sends one report to the project's crash-reporting service:
the app version, the platform and operating system version, the device model, the code locations in the crash, and the
last lines of the diagnostic log described above, with messages scrubbed the same way. It contains no account, name, course or file information. Reports go to
[Sentry](https://sentry.io/) in the EU (Frankfurt), which is set not to store IP addresses; it keeps only the approximate
location (country and city) it works out from the address before discarding it. On iOS, crashes outside the app's
Kotlin code are taken from Apple's own crash diagnostics (MetricKit), which iOS hands to the app on its next start; they
hold the same kind of technical details. You can turn this off again at any time in Settings.

## Your choices

Settings let you turn off update checks, crash reports, background refresh and reminders. The Mensa screen lets you
turn off dish translation. To remove a saved sign-in before uninstalling, use **Settings → Saved sign-ins** or leave the removal option selected when you sign out. Because the
project holds no data about you, there is nothing to request or delete on our side. For data held by the university
or the other services, contact them directly.

## Contact

Questions about this policy: open an issue on the [ZenCampus repository](https://github.com/binoverfl0w/ZenCampus)
or contact the maintainer through their GitHub profile. Security problems: see [SECURITY.md](SECURITY.md).

This policy describes the current source code. Changes to it are made in this file and visible in the repository's
history.
