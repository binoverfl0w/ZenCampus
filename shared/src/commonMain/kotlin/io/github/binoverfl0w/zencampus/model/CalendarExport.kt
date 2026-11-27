package io.github.binoverfl0w.zencampus.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * An RFC 5545 calendar of the loaded timetable, for importing into another calendar app.
 * Times are written in UTC; all-day entries use their campus date. Cancelled classes stay in the file
 * with STATUS:CANCELLED so an earlier import learns about the cancellation.
 */
fun calendarIcs(events: List<CampusEvent>, courses: List<Course>, generatedAt: Long): String {
    val titles = courses.associate { it.id to it.title }
    val lines = mutableListOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//ZenCampus//Timetable export//EN", "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH", "X-WR-CALNAME:ZenCampus",
    )
    events.filter { it.end >= it.start }.distinctBy { it.id + ":" + it.start }.sortedBy { it.start }.forEach { event ->
        val course = titles[event.courseId]
        val summary = when {
            course == null -> event.title
            event.title.isBlank() || event.title == course -> course
            else -> "$course · ${event.title}"
        }
        lines += "BEGIN:VEVENT"
        lines += "UID:${icsText(event.id)}-${event.start}@zencampus"
        lines += "DTSTAMP:${icsUtc(generatedAt)}"
        if (event.allDay) {
            val first = campusToday(event.start)
            // DTEND is exclusive for all-day events.
            val last = campusToday((event.end - 1).coerceAtLeast(event.start))
            lines += "DTSTART;VALUE=DATE:${icsDate(first)}"
            lines += "DTEND;VALUE=DATE:${icsDate(last.nextDay())}"
        } else {
            lines += "DTSTART:${icsUtc(event.start)}"
            lines += "DTEND:${icsUtc(event.end)}"
        }
        lines += "SUMMARY:${icsText(summary.ifBlank { "ZenCampus" })}"
        if (event.location.isNotBlank()) lines += "LOCATION:${icsText(event.location)}"
        if (event.description.isNotBlank()) lines += "DESCRIPTION:${icsText(event.description)}"
        if (event.cancelled) lines += "STATUS:CANCELLED"
        lines += "END:VEVENT"
    }
    lines += "END:VCALENDAR"
    return lines.joinToString("") { foldIcsLine(it) + "\r\n" }
}

private fun two(value: Int) = value.toString().padStart(2, '0')

internal fun icsUtc(epochMillis: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC)
    return "${t.year}${two(t.month.ordinal + 1)}${two(t.day)}T${two(t.hour)}${two(t.minute)}${two(t.second)}Z"
}

internal fun icsDate(date: LocalDate): String = "${date.year}${two(date.month.ordinal + 1)}${two(date.day)}"

/** Escapes TEXT values (RFC 5545 §3.3.11). */
internal fun icsText(value: String): String = value
    .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
    .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

/** Folds lines longer than 75 octets with CRLF + space, never splitting a UTF-8 sequence. */
internal fun foldIcsLine(line: String): String {
    val out = StringBuilder()
    var octets = 0
    var limit = 75
    var index = 0
    while (index < line.length) {
        // Keep surrogate pairs (emoji) together so a fold never splits one character.
        val end = if (line[index].isHighSurrogate() && index + 1 < line.length) index + 2 else index + 1
        val piece = line.substring(index, end)
        val size = piece.encodeToByteArray().size
        if (octets + size > limit) { out.append("\r\n "); octets = 0; limit = 74 }
        out.append(piece); octets += size
        index = end
    }
    return out.toString()
}
