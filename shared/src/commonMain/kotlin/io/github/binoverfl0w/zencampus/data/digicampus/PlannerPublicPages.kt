package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.model.*
import com.fleeksoft.ksoup.Ksoup
import kotlin.time.Instant
import kotlinx.datetime.*

internal object PlannerPublicPages {
    private val datePattern = Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})\\b")
    private val timePattern = Regex("\\b([0-2]?\\d):([0-5]\\d)\\s*[-–]\\s*([0-2]?\\d):([0-5]\\d)")
    private fun date(text: String): LocalDate? = datePattern.find(text)?.let {
        val year = it.groupValues[3].toInt().let { y -> if (y < 100) 2000 + y else y }
        runCatching { LocalDate(year, it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
    }
    private fun term(text: String): String? {
        val year = Regex("20\\d{2}").find(text)?.value ?: return null
        val lower = text.lowercase()
        return when {
            "winter" in lower || Regex("\\b(ws|wise)(?=\\s|20)").containsMatchIn(lower) -> "W$year"
            "sommer" in lower || Regex("\\b(ss|sose)(?=\\s|20)").containsMatchIn(lower) -> "S$year"
            else -> null
        }
    }
    /** Public recurring times are explicitly summaries: the dated API is still the authority for changes. */
    fun course(html: String, course: Course, semester: Semester): PlannerSchedule {
        val document = Ksoup.parse(html)
        val articles = document.select("article.studip")
        val info = articles.firstOrNull { it.selectFirst("h1")?.text()?.trim() in setOf("Allgemeine Informationen", "General information") }
            ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        val publishedSemester = info.select("tr").firstOrNull { it.select("td").firstOrNull()?.text()?.trim() == "Semester" }
            ?.select("td")?.getOrNull(1)?.text().orEmpty()
        val selectedTerm = term(semester.title)
        if (selectedTerm == null || term(publishedSemester) != selectedTerm) throw CampusException(FailureKind.NOT_FOUND)
        val teachers = articles.firstOrNull { it.selectFirst("h1")?.text()?.trim() in setOf("Lehrende", "Lecturers") }
            ?.select("section li")?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.distinct()?.joinToString(", ").orEmpty()
        val enriched = course.copy(teacher = teachers.ifBlank { course.teacher })
        val rooms = articles.firstOrNull { it.selectFirst("h1")?.text()?.trim() in setOf("Räume und Zeiten", "Rooms and times") }
            ?: return PlannerSchedule(enriched, semester.id, emptyList(), summaryOnly = true, unsupported = 1)
        val firstTerm = Instant.fromEpochMilliseconds(semester.start).toLocalDateTime(CAMPUS_ZONE).date
        val lastTerm = Instant.fromEpochMilliseconds(semester.end - 1).toLocalDateTime(CAMPUS_ZONE).date
        val events = mutableListOf<CampusEvent>(); var unsupported = 0
        for ((roomIndex, heading) in rooms.select("h4").withIndex()) {
            val list = heading.nextElementSibling()?.takeIf { it.tagName() == "ul" } ?: continue
            for ((index, item) in list.select("li").withIndex()) {
                val text = item.text()
                val times = timePattern.find(text)
                val first = date(text)
                if (times == null || first == null) { unsupported++; continue }
                val hour = times.groupValues[1].toInt(); val minute = times.groupValues[2].toInt()
                val endHour = times.groupValues[3].toInt(); val endMinute = times.groupValues[4].toInt()
                if (hour > 23 || endHour > 23 || endHour * 60 + endMinute <= hour * 60 + minute) { unsupported++; continue }
                val lower = text.lowercase()
                val weekly = Regex("(?<![\\p{L}-])(wöchentlich|weekly)(?!\\p{L})").containsMatchIn(lower)
                val interval = when {
                    "14-täg" in lower || "14 täg" in lower || "zweiwöchentlich" in lower || "fortnightly" in lower -> 14
                    "dreiwöchentlich" in lower || "three-weekly" in lower || Regex("\\btriweekly\\b").containsMatchIn(lower) -> 21
                    weekly && "unregel" !in lower -> 7
                    "ab dem" in lower || "täglich" in lower || "woche" in lower || "week" in lower || "daily" in lower -> { unsupported++; continue }
                    else -> 0
                }
                var day: LocalDate = first; var iterations = 0
                while (day <= lastTerm && ++iterations <= 370) {
                    if (day >= firstTerm) {
                        val start = day.atTime(hour, minute).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
                        val end = day.atTime(endHour, endMinute).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
                        events += CampusEvent("public-${course.id}-$roomIndex-$index-$day", enriched.title, start, end,
                            course.id, heading.text().trim(), text)
                    }
                    if (interval == 0) break
                    day = day.plus(interval, DateTimeUnit.DAY)
                }
            }
        }
        return PlannerSchedule(enriched, semester.id, events.distinctBy { listOf(it.start, it.end, it.location) }.sortedBy { it.start }, true, unsupported)
    }
    /** Fixed faculty source; room identifiers stay namespaced to avoid merging ambiguous campus room names. */
    fun rooms(html: String, now: Long): PublishedRoomSchedule {
        val document = Ksoup.parse(html)
        if (!document.text().contains("Belegungsplan für die nächsten 14 Tage")) throw CampusException(FailureKind.INVALID_RESPONSE)
        val today = campusToday(now)
        val from = today.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        val until = today.plus(14, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        val rooms = mutableListOf<PublishedRoom>(); val entries = mutableListOf<PlannerEntry>()
        for (heading in document.select("h3")) {
            val match = Regex("^Raum\\s+(\\d{3,5})$").matchEntire(heading.text().trim()) ?: continue
            val table = heading.parent()?.nextElementSibling()?.takeIf { it.tagName() == "table" }
                ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val header = table.selectFirst("tr")?.text().orEmpty()
            if (!listOf("Datum", "Uhrzeit", "Veranstaltung", "Dozent").all { it in header }) throw CampusException(FailureKind.INVALID_RESPONSE)
            val room = PublishedRoom("wiwi:${match.groupValues[1]}", "WiWi · ${match.groupValues[1]}")
            rooms += room
            for ((index, row) in table.select("tr").drop(1).withIndex()) {
                val cells = row.select("td")
                if (cells.size != 10) throw CampusException(FailureKind.INVALID_RESPONSE)
                val day = date(cells[0].text()) ?: throw CampusException(FailureKind.INVALID_RESPONSE)
                val times = timePattern.find(cells[2].text() + " " + cells[3].text()) ?: throw CampusException(FailureKind.INVALID_RESPONSE)
                fun time(a: Int, b: Int) = runCatching { day.atTime(times.groupValues[a].toInt(), times.groupValues[b].toInt()).toInstant(CAMPUS_ZONE).toEpochMilliseconds() }.getOrNull()
                    ?: throw CampusException(FailureKind.INVALID_RESPONSE)
                val start = time(1, 2); val end = time(3, 4)
                if (end <= start) throw CampusException(FailureKind.INVALID_RESPONSE)
                if (start >= from && start < until) {
                    val event = CampusEvent("${room.id}:$index:$day", cells[7].text().trim().ifBlank { "—" }, start, end,
                        location = room.label, description = cells[5].text().trim())
                    entries += PlannerEntry(event, cells[9].text().trim().takeUnless { it == "-" }.orEmpty(), roomId = room.id)
                }
            }
        }
        if (rooms.isEmpty() || rooms.size > 50 || rooms.distinctBy { it.id }.size != rooms.size) throw CampusException(FailureKind.INVALID_RESPONSE)
        return PublishedRoomSchedule(from, until, rooms, entries)
    }
}
