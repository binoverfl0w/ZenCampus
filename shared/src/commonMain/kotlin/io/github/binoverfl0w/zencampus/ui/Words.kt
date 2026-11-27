package io.github.binoverfl0w.zencampus.ui

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.datetime.*
import kotlin.time.Instant

class Words(val german: Boolean) {
    fun text(key: String): String = (if (german) de[key] else en[key]) ?: en[key] ?: key
    fun weatherCondition(code: Int): String = text(when (code) {
        0 -> "weather_clear"; 1 -> "weather_mainly_clear"; 2 -> "weather_partly_cloudy"; 3 -> "weather_overcast"
        45, 48 -> "weather_fog"; 51, 53, 55, 56, 57 -> "weather_drizzle"
        61, 63, 65, 66, 67 -> "weather_rain"; 71, 73, 75, 77, 85, 86 -> "weather_snow"
        80, 81, 82 -> "weather_showers"; 95, 96, 99 -> "weather_thunderstorm"
        else -> "weather_unknown"
    })
    fun formatDate(date: LocalDate, full: Boolean = false): String {
        val month = (if (german) MONTHS_DE else MONTHS_EN)[date.monthNumber - 1]
        val day = date.dayOfWeek.ordinal
        return if (full) "${(if (german) DAYS_DE else DAYS_EN)[day]}, ${date.dayOfMonth}${if (german) "." else ""} $month"
            else "${date.dayOfMonth}${if (german) "." else ""} $month"
    }
    fun shortDay(date: LocalDate): String = (if (german) SHORT_DE else SHORT_EN)[date.dayOfWeek.ordinal]
    fun time(value: Long): String {
        val time = Instant.fromEpochMilliseconds(value).toLocalDateTime(CAMPUS_ZONE).time
        return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    }
    fun age(value: Long, now: Long): String {
        val minutes = ((now - value).coerceAtLeast(0) / 60_000)
        return when {
            minutes < 1 -> text("just_now")
            minutes < 60 -> "$minutes ${text("min_ago")}"
            minutes < 1440 -> "${minutes / 60} ${text("hours_ago")}"
            else -> "${minutes / 1440} ${text("days_ago")}"
        }
    }
    fun relativeEvent(event: CampusEvent, now: Long): String {
        val minutes = (event.start - now) / 60_000
        return when {
            event.start <= now && event.end > now -> text("happening_now")
            event.date != campusToday(now) -> formatDate(event.date)
            minutes < 60 -> "${text("in")} ${minutes.coerceAtLeast(1)} ${text("minutes")}"
            else -> "${text("today_at")} ${time(event.start)}"
        }
    }
    fun failure(kind: FailureKind): String = text(when (kind) {
        FailureKind.OFFLINE -> "error_offline"
        FailureKind.AUTHENTICATION -> "error_auth"
        FailureKind.PERMISSION -> "error_permission"
        FailureKind.NOT_FOUND -> "error_not_found"
        FailureKind.SERVER -> "error_server"
        FailureKind.INVALID_RESPONSE -> "error_response"
        FailureKind.STORAGE -> "error_storage"
        FailureKind.EXTERNAL_LINK -> "error_external"
        FailureKind.NO_VIEWER -> "error_viewer"
    })
    companion object {
        private val DAYS_EN = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        private val DAYS_DE = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
        private val SHORT_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        private val SHORT_DE = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        private val MONTHS_EN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        private val MONTHS_DE = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
        internal val en = wordTable(
            "today" to "Today", "courses" to "Courses", "calendar" to "Calendar", "messages" to "Messages", "settings" to "Settings",
            "campus" to "University of Augsburg", "tagline" to "A little focus.\nA better campus day.",
            "welcome_body" to "Courses, timetable, materials and campus services.",
            "sign_in" to "Continue with DigiCampus",
            "login_note" to "Use your university sign-in.",
            "greeting_morning" to "Good morning,", "greeting_day" to "Hello,", "greeting_evening" to "Good evening,",
            "up_next" to "UP NEXT", "happening_now" to "Happening now",
            "today_at" to "Today at", "in" to "In", "minutes" to "min", "your_schedule" to "Your schedule",
            "see_in_calendar" to "See in calendar", "all_day" to "All day",
            "view_week" to "View week", "all_clear" to "No upcoming classes",
            "no_classes_today" to "No more classes on your schedule today.",
            "no_upcoming" to "No further classes in your loaded timetable.",
            "updates" to "Updates", "stay_in_loop" to "",
            "no_updates" to "No updates to show.", "no_updates_body" to "",
            "preview_mode" to "Preview mode", "preview_note" to "Test data",
            "offline" to "Offline · showing saved content", "refreshing" to "Updating…",
            "refresh" to "Refresh", "retry" to "Try again", "dismiss" to "Dismiss", "last_updated" to "Updated",
            "just_now" to "just now", "min_ago" to "min ago", "hours_ago" to "h ago", "days_ago" to "days ago",
            "your_courses" to "Your courses", "courses_subtitle" to "",
            "search_courses" to "Search your courses", "all_courses" to "All courses", "pinned" to "Pinned", "not_pinned" to "Not pinned",
            "semester" to "Semester", "all_semesters" to "All semesters", "no_courses" to "No courses found",
            "no_courses_body" to "Check the selected semester or refresh your courses.",
            "no_results" to "No courses match your search.", "pin_course" to "Pin course", "unpin_course" to "Unpin course",
            "overview" to "Overview", "materials" to "Materials", "about_course" to "About this course",
            "next_session" to "Next session", "open_official" to "Open in DigiCampus", "open_browser" to "Open in browser", "back" to "Back",
            "cancelled" to "Cancelled", "room" to "Room", "no_room" to "Room to be announced",
            "new" to "New", "announcement" to "Announcement", "close" to "Close", "mark_read" to "Mark as read",
            "folders" to "Folders", "no_materials" to "No files here yet.",
            "upload_folder" to "Uploads allowed", "submission_folder" to "Submission folder",
            "folder_official_body" to "Use DigiCampus to upload files or submit your work here.",
            "folder_restricted" to "Contents are restricted.",
            "folder_restricted_body" to "This folder is visible, but its contents cannot be read here.",
            "no_materials_body" to "Files and folders from DigiCampus’s Files section appear here.",
            "open_with" to "Open with…", "share_file" to "Share file", "save_downloads" to "Save a copy",
            "material_save_folder" to "Save copies to", "material_default_folder" to "Downloads / ZenCampus",
            "material_choose_folder" to "Choose folder",
            "material_change_folder" to "Change folder",
            "material_reset_folder" to "Use Downloads",
            "file_downloading" to "Downloading…",
            "file_opening" to "Opening…",
            "file_exporting" to "Saving a copy…",
            "file_saving_offline" to "Saving offline…",
            "file_saved_to" to "Saved to", "file_saved_files" to "Saved in Files.",
            "file_location_hint" to "Use “Save a copy” to keep a file outside ZenCampus.",
            "open_file" to "Open file", "keep_offline" to "Keep offline", "saved_offline" to "Saved offline",
            "remove_download" to "Remove saved copy", "download" to "Download", "download_unavailable" to "Download unavailable",
            "files" to "Files", "downloads" to "Offline materials", "downloads_body" to "",
            "no_downloads" to "No offline files", "no_downloads_body" to "Choose “Keep offline” in a file’s menu.",
            "your_week" to "Your week", "calendar_subtitle" to "",
            "previous_week" to "Previous week", "next_week" to "Next week", "this_week" to "This week",
            "no_events" to "No classes today", "no_events_body" to "No classes or events in the saved schedule for this day.",
            "stale_schedule" to "",
            "appearance" to "Appearance", "theme_system" to "Follow device", "theme_light" to "Light", "theme_dark" to "Dark",
            "language" to "Language", "language_system" to "Device language", "language_en" to "English", "language_de" to "Deutsch",
            "connection" to "Connection", "data_saver" to "Data saver", "data_saver_body" to "Download only what you open.",
            "while_open" to "While ZenCampus is open.",
            "background_refresh" to "Background refresh", "background_refresh_body" to "Update on Wi-Fi when battery allows.",
            "reduce_motion" to "Reduce motion", "reduce_motion_body" to "Reduce animations.",
            "storage" to "Storage", "clear_cache" to "Refresh saved content", "clear_cache_body" to "Keep offline files and preferences.",
            "clear_downloads" to "Remove offline files", "clear_downloads_body" to "Course files can be downloaded again.",
            "confirm_remove" to "Remove all saved files?", "confirm_remove_body" to "They will need a connection to download again.",
            "remove" to "Remove", "cancel" to "Cancel", "account" to "Account", "sign_out" to "Sign out",
            "sign_out_body" to "Your DigiCampus session, saved courses, and offline files will be removed from this device. Your library connection stays available.",
            "confirm_signout" to "Sign out of DigiCampus?",
            "about" to "About",
            "about_body" to "An independent campus companion.\nNot an official University of Augsburg app.",
            "campus_weather" to "Weather", "weather_location" to "Main campus · Augsburg",
            "through_the_day" to "Today", "daily_weather" to "Weather · high / low",
            "weather_now" to "Now", "precipitation_chance" to "Rain",
            "high" to "High", "low" to "Low", "forecast_unavailable" to "Forecast unavailable for these dates",
            "forecast_some_unavailable" to "Forecast unavailable for some days", "refresh_weather" to "Refresh weather",
            "wind" to "Wind", "weather_loading" to "Loading weather…", "weather_unavailable" to "Weather unavailable",
            "weather_retry" to "Refresh to try again", "sample_weather" to "Sample weather", "saved_weather" to "Saved weather",
            "weather_clear" to "Clear", "weather_mainly_clear" to "Mostly clear", "weather_partly_cloudy" to "Partly cloudy",
            "weather_overcast" to "Overcast", "weather_fog" to "Fog", "weather_drizzle" to "Drizzle",
            "weather_rain" to "Rain", "weather_snow" to "Snow", "weather_showers" to "Rain showers",
            "weather_thunderstorm" to "Thunderstorms", "weather_unknown" to "Conditions unavailable",
            "version" to "Version",
            "loading" to "Loading…",
            "error_offline" to "The connection is unavailable. Your saved content is still here.",
            "error_auth" to "Your DigiCampus session has ended. Sign in again to refresh.",
            "error_permission" to "DigiCampus no longer allows access to this content.",
            "error_not_found" to "This content is no longer available in DigiCampus.",
            "error_server" to "DigiCampus is taking a moment. Try again shortly.",
            "error_response" to "DigiCampus returned content we couldn't read. Try opening the official page.",
            "error_storage" to "This file couldn't be saved or opened. Check storage space and remove unused offline files.",
            "ready_offline" to "Ready offline", "source_timezone" to "Times shown in Europe/Berlin",
            "error_external" to "This file opens on another site. Choose “Open in DigiCampus” from its menu.",
            "error_viewer" to "There isn't an app installed to open this kind of file.",
            "share_pdf" to "Share PDF", "pdf_error" to "This document couldn't be opened.", "page" to "Page",
            "newer_copy" to "A newer version is available.", "schedule_updated" to "Schedule updated",
        )
        internal val de = wordTable(
            "today" to "Heute", "courses" to "Kurse", "calendar" to "Kalender", "messages" to "Nachrichten", "settings" to "Einstellungen",
            "campus" to "Universität Augsburg", "tagline" to "Ein bisschen Fokus.\nEin besserer Unitag.",
            "welcome_body" to "Kurse, Stundenplan, Materialien und Campusangebote.",
            "sign_in" to "Mit DigiCampus anmelden",
            "login_note" to "Melde dich mit deinem Universitätskonto an.",
            "greeting_morning" to "Guten Morgen,", "greeting_day" to "Hallo,", "greeting_evening" to "Guten Abend,",
            "up_next" to "ALS NÄCHSTES", "happening_now" to "Findet gerade statt",
            "today_at" to "Heute um", "in" to "In", "minutes" to "Min.", "your_schedule" to "Dein Stundenplan",
            "see_in_calendar" to "Im Kalender ansehen", "all_day" to "Ganztägig",
            "view_week" to "Zur Woche", "all_clear" to "Keine anstehenden Veranstaltungen",
            "no_classes_today" to "Für heute stehen keine weiteren Kurse an.",
            "no_upcoming" to "Keine weiteren Veranstaltungen im geladenen Stundenplan.",
            "updates" to "Neuigkeiten", "stay_in_loop" to "",
            "no_updates" to "Keine Neuigkeiten zum Anzeigen.", "no_updates_body" to "",
            "preview_mode" to "Vorschau", "preview_note" to "Testdaten",
            "offline" to "Offline · gespeicherte Inhalte", "refreshing" to "Wird aktualisiert…", "refresh" to "Aktualisieren",
            "retry" to "Erneut versuchen", "dismiss" to "Ausblenden", "last_updated" to "Aktualisiert",
            "just_now" to "gerade eben", "min_ago" to "Min. zuvor", "hours_ago" to "Std. zuvor", "days_ago" to "Tage zuvor",
            "your_courses" to "Deine Kurse", "courses_subtitle" to "", "search_courses" to "Kurse durchsuchen",
            "all_courses" to "Alle Kurse",
            "pinned" to "Angeheftet",
            "not_pinned" to "Nicht angeheftet",
            "semester" to "Semester",
            "all_semesters" to "Alle Semester",
            "no_courses" to "Keine Kurse gefunden", "no_courses_body" to "Prüfe das gewählte Semester oder aktualisiere deine Kurse.",
            "no_results" to "Keine Kurse passen zu deiner Suche.", "pin_course" to "Kurs anheften", "unpin_course" to "Kurs lösen",
            "overview" to "Übersicht",
            "materials" to "Materialien",
            "about_course" to "Über diesen Kurs",
            "next_session" to "Nächster Termin",
            "open_official" to "In Digicampus öffnen",
            "open_browser" to "Im Browser öffnen",
            "back" to "Zurück",
            "cancelled" to "Abgesagt",
            "room" to "Raum",
            "no_room" to "Raum wird noch bekannt gegeben",
            "new" to "Neu", "announcement" to "Ankündigung", "close" to "Schließen", "mark_read" to "Als gelesen markieren",
            "folders" to "Ordner",
            "no_materials" to "Hier gibt es noch keine Dateien.",
            "no_materials_body" to "Dateien und Ordner aus dem DigiCampus-Dateibereich erscheinen hier.",
            "upload_folder" to "Uploads erlaubt", "submission_folder" to "Abgabeordner",
            "folder_official_body" to "Öffne DigiCampus, um hier Dateien hochzuladen oder Aufgaben abzugeben.",
            "folder_restricted" to "Inhalte sind eingeschränkt.",
            "folder_restricted_body" to "Dieser Ordner ist sichtbar, seine Inhalte können hier aber nicht gelesen werden.",
            "open_with" to "Öffnen mit…", "share_file" to "Datei teilen", "save_downloads" to "Kopie speichern",
            "material_save_folder" to "Kopien speichern unter", "material_default_folder" to "Downloads / ZenCampus",
            "material_choose_folder" to "Ordner wählen",
            "material_change_folder" to "Ordner ändern",
            "material_reset_folder" to "Downloads verwenden",
            "file_downloading" to "Wird heruntergeladen…",
            "file_opening" to "Wird geöffnet…",
            "file_exporting" to "Kopie wird gespeichert…",
            "file_saving_offline" to "Wird offline gespeichert…",
            "file_saved_to" to "Gespeichert unter", "file_saved_files" to "In Dateien gespeichert.",
            "file_location_hint" to "Mit „Kopie speichern“ legst du eine Datei außerhalb von ZenCampus ab.",
            "open_file" to "Datei öffnen", "keep_offline" to "Offline speichern", "saved_offline" to "Offline gespeichert",
            "remove_download" to "Gespeicherte Kopie entfernen",
            "download" to "Herunterladen",
            "download_unavailable" to "Download nicht verfügbar",
            "files" to "Dateien", "downloads" to "Offline-Materialien", "downloads_body" to "",
            "no_downloads" to "Keine Offline-Dateien", "no_downloads_body" to "Wähle im Dateimenü „Offline speichern“.",
            "your_week" to "Deine Woche", "calendar_subtitle" to "",
            "previous_week" to "Vorherige Woche", "next_week" to "Nächste Woche", "this_week" to "Diese Woche",
            "no_events" to "Heute keine Veranstaltungen", "no_events_body" to "Keine Termine im gespeicherten Stundenplan für diesen Tag.",
            "stale_schedule" to "",
            "appearance" to "Darstellung", "theme_system" to "Wie auf dem Gerät", "theme_light" to "Hell", "theme_dark" to "Dunkel",
            "language" to "Sprache", "language_system" to "Gerätesprache", "language_en" to "English", "language_de" to "Deutsch",
            "connection" to "Verbindung", "data_saver" to "Datensparmodus", "data_saver_body" to "Lädt nur geöffnete Inhalte herunter.",
            "while_open" to "Solange ZenCampus geöffnet ist.",
            "background_refresh" to "Im Hintergrund aktualisieren",
            "background_refresh_body" to "Aktualisiert über WLAN, wenn der Akku es erlaubt.",
            "reduce_motion" to "Bewegungen reduzieren", "reduce_motion_body" to "Weniger Animationen.",
            "storage" to "Speicher",
            "clear_cache" to "Gespeicherte Inhalte aktualisieren",
            "clear_cache_body" to "Offline-Dateien und Einstellungen bleiben erhalten.",
            "clear_downloads" to "Offline-Dateien entfernen", "clear_downloads_body" to "Kursdateien können erneut heruntergeladen werden.",
            "confirm_remove" to "Alle gespeicherten Dateien entfernen?",
            "confirm_remove_body" to "Für den erneuten Download ist eine Verbindung nötig.",
            "remove" to "Entfernen", "cancel" to "Abbrechen", "account" to "Konto", "sign_out" to "Abmelden",
            "sign_out_body" to "Die DigiCampus-Sitzung, gespeicherte Kurse und Offline-Dateien werden entfernt. Deine Bibliotheksverbindung bleibt verfügbar.",
            "confirm_signout" to "Von DigiCampus abmelden?", "about" to "Über ZenCampus",
            "about_body" to "Ein unabhängiger Begleiter für den Unitag.\nKeine offizielle App der Universität Augsburg.",
            "campus_weather" to "Wetter", "weather_location" to "Hauptcampus · Augsburg",
            "through_the_day" to "Heute", "daily_weather" to "Wetter · Höchst- / Tiefstwert",
            "weather_now" to "Jetzt", "precipitation_chance" to "Regen",
            "high" to "Höchstwert", "low" to "Tiefstwert", "forecast_unavailable" to "Für diese Tage ist keine Vorhersage verfügbar",
            "forecast_some_unavailable" to "Für einige Tage ist keine Vorhersage verfügbar", "refresh_weather" to "Wetter aktualisieren",
            "wind" to "Wind", "weather_loading" to "Wetter wird geladen…", "weather_unavailable" to "Wetter nicht verfügbar",
            "weather_retry" to "Zum Wiederholen aktualisieren",
            "sample_weather" to "Beispielwetter",
            "saved_weather" to "Gespeichertes Wetter",
            "weather_clear" to "Klar", "weather_mainly_clear" to "Überwiegend klar", "weather_partly_cloudy" to "Teilweise bewölkt",
            "weather_overcast" to "Bedeckt", "weather_fog" to "Nebel", "weather_drizzle" to "Nieselregen",
            "weather_rain" to "Regen", "weather_snow" to "Schnee", "weather_showers" to "Regenschauer",
            "weather_thunderstorm" to "Gewitter", "weather_unknown" to "Wetterlage nicht verfügbar",
            "version" to "Version", "loading" to "Wird geladen…",
            "error_offline" to "Keine Verbindung. Deine gespeicherten Inhalte sind weiterhin verfügbar.",
            "error_auth" to "Deine Digicampus-Sitzung ist abgelaufen. Melde dich zum Aktualisieren erneut an.",
            "error_permission" to "Digicampus erlaubt keinen Zugriff mehr auf diese Inhalte.",
            "error_not_found" to "Diese Inhalte sind in Digicampus nicht mehr verfügbar.",
            "error_server" to "Digicampus braucht einen Moment. Versuche es gleich erneut.",
            "error_response" to "Die Antwort von Digicampus konnte nicht gelesen werden. Öffne die offizielle Seite.",
            "error_storage" to "Die Datei konnte nicht gespeichert oder geöffnet werden. Prüfe den Speicherplatz.",
            "ready_offline" to "Offline bereit", "source_timezone" to "Zeiten in Europe/Berlin",
            "error_external" to "Diese Datei öffnet sich auf einer anderen Seite. Wähle im Menü „In Digicampus öffnen“.",
            "error_viewer" to "Es ist keine App zum Öffnen dieses Dateityps installiert.",
            "share_pdf" to "PDF teilen", "pdf_error" to "Dieses Dokument konnte nicht geöffnet werden.", "page" to "Seite",
            "newer_copy" to "Eine neuere Version ist verfügbar.", "schedule_updated" to "Stundenplan aktualisiert",
        )
    }
}
