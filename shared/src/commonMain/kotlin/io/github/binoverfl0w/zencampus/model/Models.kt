package io.github.binoverfl0w.zencampus.model

import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

const val DIGICAMPUS_ORIGIN = "https://digicampus.uni-augsburg.de"
const val DIGICAMPUS_API = "$DIGICAMPUS_ORIGIN/jsonapi.php/v1"
// Stud.IP includes the final day when it queries through "+2 weeks".
const val CALENDAR_WINDOW_DAYS = 15
val CAMPUS_ZONE: TimeZone = TimeZone.of("Europe/Berlin")

@Serializable
data class Account(val id: String, val name: String, val username: String, val demo: Boolean = false,
    val providerId: String = "digicampus", val institutionId: String = "augsburg", val givenName: String = "") {
    val cacheScope: String get() = "$providerId:$institutionId:${if (demo) "preview" else id}"
    val firstName: String get() = givenName.ifBlank { name.substringBefore(' ').ifBlank { username } }
}

@Serializable
data class Semester(val id: String, val title: String, val start: Long, val end: Long,
    val lectureStart: Long = 0, val lectureEnd: Long = 0)

@Serializable
data class CampusWeather(
    val temperatureC: Double, val weatherCode: Int, val windKmh: Double, val observedAt: Long,
    val hourly: List<HourlyWeather> = emptyList(), val daily: List<DailyWeather> = emptyList(),
) {
    val hasForecast: Boolean get() = hourly.isNotEmpty() && daily.isNotEmpty()
    fun hoursOn(date: LocalDate): List<HourlyWeather> = hourly.filter {
        Instant.fromEpochMilliseconds(it.start).toLocalDateTime(CAMPUS_ZONE).date == date
    }.sortedBy { it.start }
    fun day(date: LocalDate): DailyWeather? = daily.firstOrNull { it.date == date }
}

@Serializable
data class HourlyWeather(
    val start: Long, val temperatureC: Double, val weatherCode: Int,
    val precipitationProbability: Int? = null, val isDay: Boolean? = null,
)

@Serializable
data class DailyWeather(
    val date: LocalDate, val minC: Double, val maxC: Double, val weatherCode: Int,
    val precipitationProbability: Int? = null,
)

@Serializable
data class Course(
    val id: String, val title: String, val subtitle: String = "", val number: String = "",
    val description: String = "", val location: String = "", val teacher: String = "",
    val semesterId: String = "", val endSemesterId: String = "", val colorIndex: Int = 0,
    val typeName: String = "",
) { val sourceUrl: String get() = "$DIGICAMPUS_ORIGIN/dispatch.php/course/details/index/$id" }

@Serializable
data class CampusEvent(
    val id: String, val title: String, val start: Long, val end: Long,
    val courseId: String = "", val location: String = "", val description: String = "",
    val cancelled: Boolean = false, val allDay: Boolean = false,
) {
    val date: LocalDate get() = Instant.fromEpochMilliseconds(start).toLocalDateTime(CAMPUS_ZONE).date
}

fun CampusEvent.occursOn(day: LocalDate): Boolean {
    val from = day.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
    val until = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
    return start < until && (end > from || (start == end && start >= from))
}

fun nextUpcomingEvent(events: List<CampusEvent>, now: Long): CampusEvent? =
    events.filter { !it.cancelled && !it.allDay && it.end > now && it.end > it.start }
        .minByOrNull { it.start }

@Serializable
data class Announcement(
    val id: String, val courseId: String, val title: String, val content: String,
    val published: Long, val expires: Long = Long.MAX_VALUE, val commentsAllowed: Boolean = false,
)

@Serializable
data class CampusFolder(
    val id: String, val courseId: String, val name: String, val parentId: String = "",
    val description: String = "", val readable: Boolean = true,
    val folderType: String = "", val writable: Boolean? = null, val fileCount: Int? = null,
    val empty: Boolean? = null,
    val availableFrom: Long? = null, val availableUntil: Long? = null, val uploadOnly: Boolean = false,
    val created: Long = 0, val modified: Long = 0,
) {
    val submissionFolder: Boolean get() = folderType == "HomeworkFolder" || uploadOnly
    val acceptsUploads: Boolean get() = writable == true || submissionFolder
    val sourceUrl: String get() = "$DIGICAMPUS_ORIGIN/dispatch.php/course/files/index/$id?cid=$courseId"
}

@Serializable
data class Document(
    val id: String, val courseId: String, val folderId: String, val name: String,
    val size: Long = 0, val mimeType: String = "application/octet-stream",
    val modified: Long = 0, val downloadable: Boolean = true, val externalUrl: String = "",
    val downloadUrl: String = "",
    val created: Long = 0, val ownerId: String = "",
) {
    val activityAt: Long get() = maxOf(created, modified)
    val updatedAfterCreation: Boolean get() = modified > 0 && (created <= 0 || modified > created)
}

@Serializable
data class FolderContent(
    val folders: List<CampusFolder> = emptyList(), val documents: List<Document> = emptyList(),
    val currentFolder: CampusFolder? = null, val version: Int = 0,
)

@Serializable
data class LocalDocument(
    val document: Document, val path: String, val size: Long, val pinned: Boolean,
    val lastAccess: Long, val version: Long,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage { SYSTEM, ENGLISH, GERMAN }

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM, val language: AppLanguage = AppLanguage.SYSTEM,
    val dataSaver: Boolean = false, val backgroundRefresh: Boolean = false,
    val reduceMotion: Boolean = false,
    val courseworkReminders: Boolean = false,
    val updateNotifications: Boolean = false,
    /** Look for a new ZenCampus release once a day. */
    val checkUpdates: Boolean = true,
    /** Send crash reports automatically; off until the student agrees. */
    val crashReports: Boolean = false,
)

@Serializable
data class MaterialDestination(val location: String, val label: String)

@Serializable
data class AccountPreferences(
    val semesterId: String? = null, val pinnedCourses: Set<String> = emptySet(),
    val recentCourses: List<String> = emptyList(), val readAnnouncements: Set<String> = emptySet(),
    val courseGroups: List<SavedCourseGroup> = emptyList(), val separateCourseIds: Set<String> = emptySet(),
    val materialDestinations: Map<String, MaterialDestination> = emptyMap(),
)

data class CampusSnapshot(
    val account: Account,
    val semesters: List<Semester> = emptyList(),
    val courses: List<Course> = emptyList(),
    val events: List<CampusEvent> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val folderContents: Map<String, FolderContent> = emptyMap(),
    val localDocuments: List<LocalDocument> = emptyList(),
    val preferences: AccountPreferences = AccountPreferences(),
    val updatedAt: Map<String, Long> = emptyMap(),
    val weather: CampusWeather? = null,
    val coursework: Map<String, List<CourseworkTask>> = emptyMap(),
    val courseworkDetails: Map<String, CourseworkDetail> = emptyMap(),
    val assignmentFolders: Map<String, List<CampusFolder>> = emptyMap(),
    val personalDeadlines: Map<String, PersonalDeadline> = emptyMap(),
    val courseworkWrites: Map<String, CourseworkWrite> = emptyMap(),
    val courseTools: Map<String, CourseTools> = emptyMap(),
    val vipsSheets: Map<String, VipsSheets> = emptyMap(),
    val courseMaterials: Map<String, CourseMaterials> = emptyMap(),
    val materialSync: Map<String, MaterialSyncRecord> = emptyMap(),
    val messages: Map<String, List<CampusMessage>> = emptyMap(),
    val messageWrite: CourseworkWrite? = null,
    val community: Map<String, List<CommunityItem>> = emptyMap(),
    val forumSubscriptions: Map<String, List<ForumSubscription>> = emptyMap(),
    val forumReactions: Map<String, List<ForumReaction>> = emptyMap(),
    val coursewareBookmarks: List<String>? = null,
    val coursewareProgress: Map<String, Map<String, Double>> = emptyMap(),
    val activity: List<CampusActivity> = emptyList(),
    val vipsResults: Map<String, List<VipsResult>> = emptyMap(),
    val peerReviews: Map<String, List<PeerReview>> = emptyMap(),
    val holidays: List<Holiday> = emptyList(),
    /** Office-hours blocks keyed by "type:id" of their owner (a lecturer, course or institute). */
    val consultations: Map<String, List<ConsultationBlock>> = emptyMap(),
    val consultationBookings: List<ConsultationBooking> = emptyList(),
    val newsComments: Map<String, List<NewsComment>> = emptyMap(),
    val contacts: List<CampusPerson>? = null,
    val studyGroups: List<Course>? = null,
)

enum class FailureKind { OFFLINE, AUTHENTICATION, PERMISSION, NOT_FOUND, SERVER, INVALID_RESPONSE, STORAGE, EXTERNAL_LINK, NO_VIEWER }
open class CampusException(
    val kind: FailureKind, cause: Throwable? = null, val httpStatus: Int? = null,
    /** How long the server asked us to wait before trying again, when it said so. */
    open val retryAfterMillis: Long? = null,
) : Exception(kind.name, cause) {
    /** Worth retrying later: the connection or the server, not the request, was the problem. */
    val transient: Boolean get() = kind == FailureKind.OFFLINE || kind == FailureKind.SERVER
}
/** The single Study refresh, for the header line and pull to refresh. */
data class StudySyncState(val running: Boolean = false, val lastCompleted: Long? = null)

data class SyncStatus(
    val active: Set<String> = emptySet(),
    val errors: Map<String, FailureKind> = emptyMap(),
    val sessionExpired: Boolean = false,
    val materialProgress: Map<String, MaterialProgress> = emptyMap(),
    val materialExports: Map<String, String> = emptyMap(),
    /** Showing cached data because the latest read failed temporarily; it is retried automatically. */
    val stale: Set<String> = emptySet(),
) { val refreshing: Boolean get() = active.isNotEmpty() }

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
fun campusToday(now: Long = nowMillis()): LocalDate =
    Instant.fromEpochMilliseconds(now).toLocalDateTime(CAMPUS_ZONE).date
fun weekDates(date: LocalDate): List<LocalDate> {
    val monday = date.plus(-date.dayOfWeek.ordinal, DateTimeUnit.DAY)
    return (0..6).map { monday.plus(it, DateTimeUnit.DAY) }
}
fun isFresh(updated: Long?, now: Long, ttl: Long): Boolean =
    updated != null && now >= updated && now - updated < ttl
fun currentSemester(semesters: List<Semester>, now: Long): Semester? =
    semesters.firstOrNull { now in it.start..it.end }
        ?: semesters.filter { it.start <= now }.maxByOrNull { it.start }
        ?: semesters.minByOrNull { it.start }
