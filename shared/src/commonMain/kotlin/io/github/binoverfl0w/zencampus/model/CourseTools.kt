package io.github.binoverfl0w.zencampus.model

import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.datetime.*
import kotlin.time.Instant

fun courseOverviewUrl(courseId: String): String = URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/course/overview").apply {
    parameters.append("cid", courseId)
}.buildString()

fun vipsSheetsUrl(courseId: String): String = URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets").apply {
    parameters.append("cid", courseId)
}.buildString()

fun vipsResultsUrl(courseId: String): String = URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/vips/solutions").apply {
    parameters.append("cid", courseId)
}.buildString()

fun courseFilesUrl(courseId: String): String = URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/course/files").apply {
    parameters.append("cid", courseId)
}.buildString()

/**
 * The DigiCampus page that lists [document], for "Open in DigiCampus": its folder, or the sheets of a Vips attachment.
 * Not the file's own address, which is a download (often relative) rather than a page.
 */
fun documentPageUrl(document: Document): String = when (document.folderId) {
    VIPS_ATTACHMENTS -> vipsSheetsUrl(document.courseId)
    "" -> courseFilesUrl(document.courseId)
    else -> URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/course/files/index").apply {
        appendPathSegments(document.folderId)
        parameters.append("cid", document.courseId)
    }.buildString()
}

/** The folder id of files the app reached through a Vips sheet rather than the course's file tree. */
const val VIPS_ATTACHMENTS = "vips-attachments"

fun courseTasksUrl(courseId: String): String = URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/course/courseware/tasks").apply {
    parameters.append("cid", courseId)
}.buildString()

/**
 * The DigiCampus page of a course's community section: the course's own navigation entry for it, else Stud.IP's
 * standard page for that section, so "Open in DigiCampus" never falls back to the course's front page for them.
 */
fun communityOfficialUrl(courseId: String, section: String, tools: List<CourseTool>): String {
    val names = when (section) {
        "people" -> listOf("members", "people")
        "groups" -> listOf("statusgroups", "groups")
        else -> listOf(section)
    }
    tools.firstOrNull { tool -> tool.sourceUrl.isNotBlank() && names.any { tool.id.contains(it, true) || tool.sourceUrl.contains("/$it", true) } }
        ?.let { return it.sourceUrl }
    val route = when (section) {
        "people" -> "course/members"
        "groups" -> "course/statusgroups"
        "courseware" -> "course/courseware"
        else -> "course/overview"
    }
    return URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/$route").apply { parameters.append("cid", courseId) }.buildString()
}

/** Server navigation identities and labels, not a list of hard-coded course names. */
@Serializable
data class CourseTool(val id: String, val title: String, val sourceUrl: String = "") {
    val vips: Boolean get() = runCatching { Url(sourceUrl) }.getOrNull()?.let {
        it.protocol == URLProtocol.HTTPS && it.host == "digicampus.uni-augsburg.de" && it.port == 443 &&
            it.encodedPath.trimEnd('/') == "/dispatch.php/vips/sheets"
    } == true
    val additional: Boolean get() = id !in setOf("main", "files")
}

@Serializable
data class CourseTools(val sections: List<CourseTool>) {
    val hasVips: Boolean get() = sections.any { it.vips }
}

/** Text/status exactly as published in the student list; it never proves submission. */
@Serializable
data class VipsSheet(
    val id: String, val courseId: String, val title: String, val sourceUrl: String,
    val starts: String = "", val ends: String = "", val mode: String = "", val status: String = "",
    val group: String = "",
    val publishedKind: VipsKind = VipsKind.UNKNOWN,
) {
    // Computed from the saved source text so existing offline caches gain dates too.
    val kind: VipsKind get() = publishedKind.takeUnless { it == VipsKind.UNKNOWN } ?: vipsKind(mode)
    val startsAt: Long? by lazy { vipsTimestamp(starts) }
    val endsAt: Long? by lazy { vipsTimestamp(ends) }
    val datesVerified: Boolean get() {
        val start = startsAt ?: return false
        val end = endsAt
        return if (ends.isBlank()) kind == VipsKind.SELFTEST else end != null && end >= start
    }
    val deadline: Long? get() = endsAt.takeIf { kind == VipsKind.PRACTICE && datesVerified }

    /** An exam's published window is not its participant's individual countdown. */
    fun attentionAt(now: Long): Long? = if (!datesVerified) null else
        if (kind != VipsKind.PRACTICE && startsAt?.let { it > now } == true) startsAt else endsAt
}

@Serializable enum class VipsKind { PRACTICE, SELFTEST, EXAM, UNKNOWN }

fun vipsKind(mode: String): VipsKind = when (mode.trim().lowercase()) {
    "übung", "übungsblatt", "exercises", "exercise sheet" -> VipsKind.PRACTICE
    "selbsttest", "self test", "self-test" -> VipsKind.SELFTEST
    "klausur", "written examination", "exam" -> VipsKind.EXAM
    else -> VipsKind.UNKNOWN
}

/** Stud.IP's student list uses d.m.Y, H:i in both languages, in the campus timezone. */
private val vipsDatePattern = Regex("^([0-9]{2})\\.([0-9]{2})\\.([0-9]{4}),\\s*([0-9]{2}):([0-9]{2})$")
internal fun vipsTimestamp(value: String): Long? {
    val match = vipsDatePattern.matchEntire(value.trim()) ?: return null
    return runCatching {
        val (day, month, year, hour, minute) = match.destructured
        val local = LocalDateTime(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt())
        val epoch = local.toInstant(CAMPUS_ZONE).toEpochMilliseconds()
        // The list has no UTC offset. Never invent an exact instant in a DST gap or repeated hour.
        listOf(epoch - 3_600_000, epoch, epoch + 3_600_000).filter {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(CAMPUS_ZONE) == local
        }.singleOrNull()
    }.getOrNull()
}

fun CampusSnapshot.listedVipsSheets(): List<VipsSheet> = courses.flatMap { course ->
    vipsSheets[course.id]?.sheets.orEmpty().filter { it.courseId == course.id }
}

fun CampusSnapshot.upcomingVipsSheets(now: Long, horizon: Long = 7L * 86_400_000): List<VipsSheet> =
    listedVipsSheets().filter { it.attentionAt(now)?.let { date -> date > now && date <= now + horizon } == true }
        .sortedBy { it.attentionAt(now) }

/** Missing, failed or unreadable sources must not become an all-clear message. */
fun CampusSnapshot.vipsDatesChecked(errors: Map<String, FailureKind>, active: Set<String> = emptySet()): Boolean = courses.all { course ->
    val toolsKey = "tools:${course.id}"
    val sheetsKey = "vips:${course.id}"
    val tools = courseTools[course.id]
    tools != null && toolsKey !in errors && toolsKey !in active && (!tools.hasVips ||
        (sheetsKey !in errors && sheetsKey !in active && vipsSheets[course.id]?.sheets?.all { it.datesVerified } == true))
}

@Serializable
data class VipsSheets(val sheets: List<VipsSheet>)

@Serializable
data class VipsResult(val id: String, val courseId: String, val title: String, val sourceUrl: String,
    val start: String = "", val end: String = "", val points: String = "")
