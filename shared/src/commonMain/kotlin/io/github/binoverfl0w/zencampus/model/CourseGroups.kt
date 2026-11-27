package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedCourseGroup(val id: String, val title: String, val courseIds: Set<String>)

enum class CourseRole { UNKNOWN, LECTURE, EXERCISE, TUTORIAL, OTHER }

data class CourseSubject(val title: String, val key: String, val role: CourseRole)
data class CourseGroup(val id: String, val title: String, val courses: List<Course>, val automatic: Boolean)

private const val ROLE = "vorlesung(?:en)?|lectures?|übung(?:en)?|uebung(?:en)?|exercises?|tutorials?|tutorien|tutorium"
private val prefix = Regex("^($ROLE)\\b\\s*[:–—-]?\\s*", RegexOption.IGNORE_CASE)
private val suffix = Regex("(?:\\s*[\\[(]\\s*($ROLE)(?:\\s+(?:(?:gruppe|group)\\s+)?[A-Z0-9]+)?\\s*[)\\]]|\\s*[:–—,-]\\s*($ROLE)(?:\\s+(?:gruppe|group)\\s+[A-Z0-9]+)?)\\s*$", RegexOption.IGNORE_CASE)
private val connector = Regex("^(?:zu|zur|zum|der|dem|for|in|on|the)\\s+", RegexOption.IGNORE_CASE)
private val section = Regex("^(?:(?:gruppe|group)\\s+[A-Z0-9]+\\s*[:–—-]?|\\d+\\s*:|\\d+\\s+(?=(?:zu|zur|zum|for)\\b))\\s*", RegexOption.IGNORE_CASE)
private val exerciseRole = Regex("^(?:übung(?:en)?|uebung(?:en)?|exercises?)\\b", RegexOption.IGNORE_CASE)
private val tutorialRole = Regex("^(?:tutorials?|tutorien|tutorium)\\b", RegexOption.IGNORE_CASE)
private val lectureRole = Regex("^(?:vorlesung(?:en)?|lectures?)\\b", RegexOption.IGNORE_CASE)
private val otherTitle = Regex("^(?:seminar|hauptseminar|proseminar|praktikum|lab|labor|klausur|prüfung|exam)\\b", RegexOption.IGNORE_CASE)
private val trailingSection = Regex("(?:\\s*[\\[(]\\s*(?:gruppe|group)\\s+[A-Z0-9]+\\s*[)\\]]|\\s*[:–—,-]?\\s+(?:gruppe|group)\\s+[A-Z0-9]+)\\s*$", RegexOption.IGNORE_CASE)
private val whitespace = Regex("\\s+")

private fun roleOf(text: String): CourseRole {
    val value = text.lowercase().trim()
    return when {
        exerciseRole.containsMatchIn(value) -> CourseRole.EXERCISE
        tutorialRole.containsMatchIn(value) -> CourseRole.TUTORIAL
        lectureRole.containsMatchIn(value) -> CourseRole.LECTURE
        value.isNotBlank() -> CourseRole.OTHER
        else -> CourseRole.UNKNOWN
    }
}

/** Exact subject matches only; numbers and other subject qualifiers remain significant. */
fun Course.subject(): CourseSubject {
    var subject = title.trim()
    var inferred = CourseRole.UNKNOWN
    repeat(3) {
        val leading = prefix.find(subject)
        val trailing = suffix.find(subject)
        when {
            leading != null -> {
                if (inferred == CourseRole.UNKNOWN) inferred = roleOf(leading.groupValues[1])
                subject = subject.substring(leading.range.last + 1).trim()
                subject = section.replaceFirst(subject, "").trim()
                repeat(3) { subject = connector.replaceFirst(subject, "").trim() }
            }
            trailing != null -> {
                if (inferred == CourseRole.UNKNOWN) inferred = roleOf(trailing.groupValues[1].ifBlank { trailing.groupValues[2] })
                subject = subject.substring(0, trailing.range.first).trim()
            }
        }
    }
    val sourceRole = roleOf(typeName)
    val subtitleRole = roleOf(subtitle)
    val role = if (sourceRole != CourseRole.UNKNOWN) sourceRole else if (inferred != CourseRole.UNKNOWN) inferred
        else if (subtitleRole in setOf(CourseRole.LECTURE, CourseRole.EXERCISE, CourseRole.TUTORIAL)) subtitleRole
        else if (otherTitle.containsMatchIn(title) || otherTitle.containsMatchIn(subtitle)) CourseRole.OTHER
        else CourseRole.UNKNOWN
    if (role in setOf(CourseRole.EXERCISE, CourseRole.TUTORIAL)) subject = trailingSection.replaceFirst(subject, "").trim()
    val canonical = subject.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(whitespace, " ")
    return CourseSubject(subject, canonical, role)
}

/** No network reads. Stored corrections win; uncertain relationships remain separate courses. */
fun courseGroups(courses: List<Course>, preferences: AccountPreferences): List<CourseGroup> {
    val available = courses.distinctBy { it.id }.associateBy { it.id }
    val subjects = available.mapValues { it.value.subject() }
    val claimed = mutableSetOf<String>()
    val groups = mutableListOf<CourseGroup>()
    preferences.courseGroups.forEach { saved ->
        val members = saved.courseIds.mapNotNull { available[it] }.filter { claimed.add(it.id) }
        if (members.isNotEmpty()) groups += CourseGroup(saved.id, saved.title, members, automatic = false)
    }
    val candidates = available.values.filter { it.id !in claimed && it.id !in preferences.separateCourseIds }
    candidates.groupBy { course ->
        val semester = preferences.semesterId.orEmpty().ifBlank { course.semesterId }
        Triple(semester, if (preferences.semesterId.isNullOrBlank()) course.endSemesterId else "", subjects.getValue(course.id).key)
    }.forEach { (key, members) ->
        val roles = members.associateWith { subjects.getValue(it.id) }
        val anchors = members.filter { roles.getValue(it).role in setOf(CourseRole.LECTURE, CourseRole.UNKNOWN) }
        val exercises = members.filter { roles.getValue(it).role in setOf(CourseRole.EXERCISE, CourseRole.TUTORIAL) }
        if (key.first.isNotBlank() && key.third.length >= 4 && anchors.size == 1 && exercises.isNotEmpty()) {
            val anchor = anchors.single()
            val related = listOf(anchor) + exercises.sortedWith(compareBy<Course> { roles.getValue(it).role.ordinal }.thenBy { it.title })
            groups += CourseGroup("automatic:${key.first}:${key.second}:${key.third}", roles.getValue(anchor).title, related, automatic = true)
            claimed += related.map { it.id }
        }
    }
    available.values.filter { it.id !in claimed }.forEach { groups += CourseGroup("course:${it.id}", it.title, listOf(it), automatic = false) }
    return groups.sortedWith(compareByDescending<CourseGroup> { it.courses.any { c -> c.id in preferences.pinnedCourses } }.thenBy { it.title.lowercase() })
}

/** Moving a course never leaves it in two groups or silently reattaches an excluded class. */
fun AccountPreferences.saveCourseGroup(id: String, title: String, courseIds: Set<String>, previousMembers: Set<String> = emptySet()): AccountPreferences {
    val name = title.trim()
    val members = courseIds.filter { it.isNotBlank() }.toSet()
    require(id.isNotBlank() && name.isNotBlank() && name.length <= 100 && members.size in 2..500)
    val old = this.courseGroups.firstOrNull { it.id == id }?.courseIds.orEmpty() + previousMembers
    val remaining = this.courseGroups.filterNot { it.id == id }.map { it.copy(courseIds = it.courseIds - members) }
    val singles = remaining.filter { it.courseIds.size < 2 }.flatMap { it.courseIds }
    return copy(courseGroups = remaining.filter { it.courseIds.size >= 2 } + SavedCourseGroup(id, name, members),
        separateCourseIds = (separateCourseIds + (old - members) + singles) - members)
}

fun AccountPreferences.separateCourses(courseIds: Set<String>): AccountPreferences {
    val remaining = courseGroups.map { it.copy(courseIds = it.courseIds - courseIds) }
    val singles = remaining.filter { it.courseIds.size < 2 }.flatMap { it.courseIds }
    return copy(courseGroups = remaining.filter { it.courseIds.size >= 2 }, separateCourseIds = separateCourseIds + courseIds + singles)
}
