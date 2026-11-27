package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.model.AppLanguage
import io.github.binoverfl0w.zencampus.model.AppSettings
import io.github.binoverfl0w.zencampus.ui.Words
import java.util.Locale

internal fun desktopWords(settings: AppSettings) = Words(settings.language == AppLanguage.GERMAN ||
    settings.language == AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de"))

internal fun Words.desktop(key: String): String {
    val entry = desktopCopy[key] ?: return key
    return if (german) entry.second else entry.first
}

internal fun Words.desktopShortcutHelp(): String {
    val help = desktop("shortcuts")
    return if (isMac) help.replace(Regex("(Ctrl|Strg)\\+"), "⌘") else help
}

private val desktopCopy = mapOf(
    "boards" to ("Boards" to "Bereiche"), "navigate" to ("Navigate" to "Navigation"), "help" to ("Help" to "Hilfe"),
    "quit" to ("Quit" to "Beenden"), "local_files" to ("Open local files" to "Lokale Dateien öffnen"),
    "controls" to ("Keyboard shortcuts" to "Tastenkürzel"),
    "shortcuts" to ("Ctrl+1 / 2 / 3   Switch boards\nAlt+← or Esc   Back\nCtrl+R   Refresh\nCtrl+K   Search\nCtrl+Q   Quit\nCtrl+S   Save PDF" to
        "Strg+1 / 2 / 3   Bereich wechseln\nAlt+← oder Esc   Zurück\nStrg+R   Aktualisieren\nStrg+K   Suchen\nStrg+Q   Beenden\nStrg+S   PDF speichern"),
    "save_copy" to ("Save a copy" to "Kopie speichern"), "fit_width" to ("Fit width" to "An Breite anpassen"),
    "go" to ("Go" to "Los"), "find" to ("Find" to "Suchen"), "find_text" to ("Find text" to "Text suchen"),
    "searching" to ("Searching…" to "Wird gesucht…"), "no_matches" to ("No matching text" to "Kein passender Text"),
    "password" to ("PDF password" to "PDF-Passwort"), "open" to ("Open" to "Öffnen"),
    "enter_password" to ("Enter the PDF password." to "Gib das PDF-Passwort ein."),
    "pdf_failed" to ("Couldn’t open this PDF. Try another reader." to "PDF konnte nicht geöffnet werden. Versuche einen anderen Betrachter."),
    "page_failed" to ("Couldn’t render this page." to "Seite konnte nicht angezeigt werden."),
    "save_failed" to ("The copy could not be saved." to "Die Kopie konnte nicht gespeichert werden."),
    "viewer_failed" to ("No external reader could be opened." to "Kein externer Betrachter konnte geöffnet werden."),
    "external_link_failed" to ("Couldn’t open the link. Check your default browser." to "Der Link konnte nicht geöffnet werden. Prüfe deinen Standardbrowser."),
    "preparing" to ("Opening browser…" to "Browser wird geöffnet…"), "continue" to ("Continue" to "Weiter"),
    "official_page" to ("Open in browser" to "Im Browser öffnen"),
    "browser_failed" to ("Couldn’t start the browser. Close this window and try again." to "Browser konnte nicht gestartet werden. Schließe dieses Fenster und versuche es erneut."),
    "connecting" to ("Connecting…" to "Wird verbunden…"),
    "finish_login" to ("Finish signing in, then choose Continue." to "Melde dich an und wähle dann Weiter."),
    "browser_session_failed" to ("Couldn’t prepare the session. Reconnect DigiCampus in Your cards." to "Sitzung konnte nicht vorbereitet werden. Verbinde DigiCampus unter Deine Karten erneut."),
    "certificate_failed" to ("The site’s certificate could not be verified." to "Das Zertifikat der Seite konnte nicht geprüft werden."),
    "load_failed" to ("Page unavailable" to "Seite nicht verfügbar"),
    "reload_back" to ("Reload or go back." to "Aktualisiere die Seite oder gehe zurück."),
    "reconnect" to ("Session expired. Reconnect DigiCampus in Your cards." to "Sitzung abgelaufen. Verbinde DigiCampus unter Deine Karten erneut."),
    "link_blocked" to ("Open this link from its course or the official website." to "Öffne diesen Link im Kurs oder auf der offiziellen Website."),
    "opening_attachment" to ("Opening attachment…" to "Anhang wird geöffnet…"),
    "retry_attachment" to ("Select the attachment to retry." to "Wähle den Anhang erneut."),
)
