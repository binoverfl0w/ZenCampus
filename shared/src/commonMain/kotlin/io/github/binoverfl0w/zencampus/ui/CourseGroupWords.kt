package io.github.binoverfl0w.zencampus.ui

fun Words.group(key: String): String = (if (german) groupDe else groupEn)[key] ?: groupEn[key] ?: key
fun Words.classCount(count: Int): String = "$count ${group(if (count == 1) "class" else "classes")}"

internal val groupEn = wordTable(
    "manage" to "Course groups", "new" to "New group", "edit" to "Edit group", "name" to "Group title",
    "class" to "class", "classes" to "classes", "selected" to "selected", "automatic" to "Automatic", "custom" to "Custom",
    "empty" to "No groups yet", "empty_body" to "Lecture and exercise courses with matching subjects are grouped automatically.",
    "local" to "Groups are saved on this device.", "minimum" to "Select at least two classes.",
    "search" to "Find classes", "separate" to "Keep separate", "outside" to "not in the current list",
    "save" to "Save group", "failed" to "Couldn’t save these changes. Try again.",
)
internal val groupDe = wordTable(
    "manage" to "Kursgruppen", "new" to "Neue Gruppe", "edit" to "Gruppe bearbeiten", "name" to "Gruppenname",
    "class" to "Veranstaltung",
    "classes" to "Veranstaltungen",
    "selected" to "ausgewählt",
    "automatic" to "Automatisch",
    "custom" to "Eigene Gruppe",
    "empty" to "Noch keine Gruppen", "empty_body" to "Vorlesungen und Übungen mit passendem Titel werden automatisch gruppiert.",
    "local" to "Gruppen werden auf diesem Gerät gespeichert.", "minimum" to "Wähle mindestens zwei Veranstaltungen.",
    "search" to "Veranstaltungen suchen", "separate" to "Getrennt anzeigen", "outside" to "nicht in der aktuellen Liste",
    "save" to "Gruppe speichern", "failed" to "Änderungen konnten nicht gespeichert werden. Versuche es erneut.",
)
