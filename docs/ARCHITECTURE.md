# Architecture

ZenCampus is one Kotlin Multiplatform module (`shared`) with three thin apps around it: `androidApp`, `desktopApp`
(Linux, Windows and macOS on the JVM) and `iosApp` (a SwiftUI shell and WidgetKit extension around the shared
framework). Everything that is not platform glue lives in `shared/src/commonMain`; [Platforms](PLATFORMS.md) records
what each platform supplies and what has been checked there.

## Packages

| Package | What lives there |
| --- | --- |
| `io.github.binoverfl0w.zencampus.model` | Plain data and pure functions: models, cache keys (`CacheKeys`), planners such as `materialSyncPlan` and `calendarIcs`. No I/O. |
| `io.github.binoverfl0w.zencampus.data` | The seams: `CampusProvider` (split into area interfaces), `CampusStore`, `MaterialStorage`, the shared `CampusSession`, demo providers. |
| `io.github.binoverfl0w.zencampus.data` (upkeep) | `ZenLog` (scrubbed app log), `CrashReports` (opt-in Sentry envelopes), `AppUpdates` (GitHub Releases check, verified download, `UpdatePlatform` seam). The JVM parts both apps share (log file, crash handler, hashing writer) live in `jvmCommonMain`. |
| `io.github.binoverfl0w.zencampus.data.digicampus` | The DigiCampus (Stud.IP JSON:API) client: `DigiCampusApi` (connection, retries, read limiter, pagination, verified writes), one class per area, the JSON mapper and HTML page parsers. |
| `io.github.binoverfl0w.zencampus.data.study` | `CampusRepository` (cache engine, snapshot, lifecycle) and its areas: study sync, courses, coursework, community, messages, office hours, materials, material sync; the planner. |
| `io.github.binoverfl0w.zencampus.data.services` | Library (catalogue, account) and Mensa repositories and providers. |
| `io.github.binoverfl0w.zencampus.ui` | The app shell: `ZenApp`, navigation (`CampusNavigation`, typed `Screen`), board chrome and places, settings, quick search, copy (`Words`). |
| `io.github.binoverfl0w.zencampus.ui.design` | The design system: theme, icons, buttons, lists, dialogs, feedback, `ContentState`. |
| `io.github.binoverfl0w.zencampus.ui.study` | Study screens. |
| `io.github.binoverfl0w.zencampus.ui.services` | Library, Mensa and accounts screens. |

`model` depends on nothing; `data` never imports `ui`.

## Data flow

1. **Providers** fetch from a source. `DigiCampusProvider` composes seven area classes on one `DigiCampusApi` connection;
   reads retry temporary failures with backoff and at most four run at a time.
2. **The repository** stores every read in `CampusStore` under a `CacheKeys` key through one engine (`refresh`): it
   respects freshness, keeps cached data when a read fails temporarily (marked *stale*, retried automatically) and shows
   only definite failures as errors.
3. **The snapshot** (`CampusRepository.snapshot`) is rebuilt from the store on every change, decoding only records that
   changed. Screens observe it; they never fetch on their own except for content a page needs beyond the sync (a
   thread, a task, a folder).
4. **The Study sync** (`StudySyncRepository`) is the one refresh for Study: on start and return to the app, every 15
   minutes, when the network returns and on pull to refresh. Its state drives the single "Updated x ago" line.

Background refresh (WorkManager on Android, BGTaskScheduler on iOS, a timer while the desktop app runs) reuses the same
sync and respects data saver. Update notifications are opt-in: the first successful check only records a baseline, later
checks notify about new items with a generic text, and nothing is pushed by a server.

## Screens and state

- Screens read `snapshot` and `sync`. For one cache key, `sync.content(key, hasData)` gives a `ContentState`, and
  `ContentStatus` renders it by one rule: progress only before the first data, an error only when nothing can be shown.
- Navigation is a saved `CampusNavigation`; `destination.screen` gives the typed `Screen` the shell routes on. The
  stored shape stays backwards compatible, so add new screens there rather than changing saved fields.

## Sessions and platforms

`CampusSession` owns signing in (verifying the cookie), switching and signing out, preview mode, Vips cookie rotation
and the library connection. Each app implements `SessionPlatform` for what differs: where credentials are stored and
what else changes with an account (notifications and workers on Android, keyring markers and reader windows on desktop).

## Sign-in and saved sign-ins

Sign-in always happens on the official pages in an in-app browser (Android WebView, JCEF on desktop, WKWebView on iOS).
`SignInOrigins` lists the hosts the sign-in browser may stay on; anything else opens in the system browser. When
DigiCampus is reached, the app reads the session cookie and `CampusSession` verifies it with a request before connecting
the account.

Saved sign-ins (`SavedSignIn`, `SavedSignInPopup`) are kept per popup kind (Study or Library) in the platform's secure
storage, apart from the account cache. A fill is a script injected only into a top-level page on an allowed origin:
Microsoft's sign-in page for Study (`MicrosoftSignInAttempt`) and the OPAC `form#LoginBean` for Library
(`LibrarySignInAttempt`). The origin is checked before injecting and again inside the script, values are passed as
escaped string literals, there is no bridge from the page back to the app, and each attempt submits a password once. A
rejected password shows a notice and offers to update the entry. One-time code and number fields are never touched.

## Writes

Anything that changes something on a server (uploads, messages, forum posts, reactions, renewals, bookings, Courseware
submissions) goes through one pattern: the screen shows what will be sent and waits for confirmation, the request is
made once and never retried, and success is only shown when the response proves it (for example a `201` with a
`Location`). An unclear result is shown as unclear rather than failed, and asks the student to check the official page
before trying again; for messages it survives restarts. Uploads are limited to 20 MB (`MAX_UPLOAD_BYTES`) and checked against the destination folder first.

Some reads have side effects upstream, so they only run when the student asks for them: opening a Vips sheet can start
an attempt and loading a Courseware chapter can record a visit. Neither is prefetched or synced.

## Downloads and course sync

Material and Vips attachment downloads share one path. A download is retried at most twice after the first attempt
(after 300 ms and 600 ms, or a short numeric `Retry-After`) for connection errors, timeouts, truncated bodies, HTTP 408
and 429, and temporary 5xx responses. Each attempt writes a fresh temporary file, and only a complete response is
committed to the cache. Expired sessions, denied access, missing files, redirects, unexpected content types and local
storage failures stop at once. Repeated taps share the pending transfer. A download allows 45 seconds of socket
inactivity and 120 seconds per attempt.

Course sync (`materialSyncPlan`) compares the course's file list with the last sync record and plans only new, changed
or moved files. It shows the file count and size before it starts, skips files without download permission, files
over 100 MB and Vips sheets, and never deletes files the student already has.

## Course groups

The Courses list groups a lecture with its exercises and tutorials using only course metadata, with no extra requests.
A group needs exactly one plausible lecture and at least one exercise or tutorial with the same subject in the same
semester. Course types, titles and subtitles are read for common English and German labels, subject numbers stay
significant (Analysis I and Analysis II stay apart), and labels such as "Gruppe 1" may join a group. More than one
plausible lecture, an unknown semester, exercises without a lecture or different subjects stay separate. Lecturer or
fuzzy title similarity is not used. Stud.IP has no lecture-to-exercise relation, so this is an inference, and the
student's edits (rename, change members, keep separate) are saved per account and always win.

## DigiCampus coverage

The client uses the Stud.IP JSON:API that DigiCampus advertises at `jsonapi.php/v1/discovery`, plus a few HTML pages
where the API has no route (Vips, some course tabs). A route in the discovery does not prove a student may use it, so
each feature is checked against a real account before it is called done. Where there is no native support, the app
links to the official page in its signed-in browser; the README lists what stays on DigiCampus.

## Desktop host

`desktopApp` runs the shared UI on the JVM and adds embedded Chromium (JCEF) for sign-in and Vips, a PDFBox reader,
native file pickers, session storage (Secret Service through `secret-tool` on Linux, DPAPI on Windows, the Keychain on
macOS) and notifications. Chromium starts only when a sign-in or Vips page opens; its binaries ship with the app and are
unpacked into the user cache on first use. The JCEF wrapper and its native bundle are pinned in
`desktopApp/build.gradle.kts` and the extraction folder (`chromium-152`) in `DesktopBrowser.kt`; update all three
together. Browser cookies stay in memory; only the
verified service session is stored. The Linux launcher (`desktopApp/packaging/zencampus`) reads the generated classpath
and JVM options as they are and defaults Skiko to the software renderer.

## Logging

Log with `ZenLog` in terms of kinds and counts: a cache key's kind (`key.substringBefore(':')`), a `FailureKind`, a
status code. Never log names, titles, URLs, bodies or IDs; `LogScrubber` is a safety net, not a licence.

## Copy

All user-facing text lives in word tables (`Words` and the `*Words.kt` files), English and German side by side.
Tables are built with `wordTable`, which fails on a duplicate key, and `WordTablesTest` checks that both languages have
the same keys. Add new copy to the table of the area it belongs to.

## Tests

- `shared` common/JVM tests cover providers (Ktor `MockEngine`), the repository and pure model code; fakes live in
  `commonTest/.../TestFixtures.kt`.
- `desktopApp` has unit and Compose UI tests (`desktopUiTest`).
- `androidApp` instrumentation tests run only on an emulator (see `AGENTS.md`).
