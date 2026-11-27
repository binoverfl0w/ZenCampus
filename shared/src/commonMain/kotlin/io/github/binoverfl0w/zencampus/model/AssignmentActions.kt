package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.Serializable

@Serializable
data class PersonalDeadline(val courseId: String, val folderId: String, val due: Long, val completed: Boolean = false)

@Serializable enum class WriteStatus { SENDING, CONFIRMED, REJECTED, UNCERTAIN }
@Serializable
data class CourseworkWrite(val key: String, val courseId: String, val targetId: String, val title: String,
    val startedAt: Long, val status: WriteStatus = WriteStatus.SENDING, val document: Document? = null,
    val failure: FailureKind? = null, val requestId: String = "")

/** Selected bytes are held only for the current account session, never in saved UI state. */
data class UploadDraft(val courseId: String, val folderId: String, val name: String, val mimeType: String, val bytes: ByteArray,
    val selectionId: String = nowMillis().toString() + ":" + kotlin.random.Random.nextLong().toString())
const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

sealed interface WriteResult<out T> {
    data class Confirmed<T>(val value: T) : WriteResult<T>
    data class Rejected(val failure: FailureKind) : WriteResult<Nothing>
    data object Uncertain : WriteResult<Nothing>
}

fun dueCoursework(snapshot: CampusSnapshot, now: Long): List<CourseworkTask> = snapshot.coursework.values.flatten()
    .filter { !it.submitted && it.due?.let { due -> due > now && due <= now + 24 * 60 * 60_000L } == true }.orderedCoursework()


data class CourseworkReminder(val id: String, val title: String, val courseId: String, val due: Long)

/** Only fresh successful sources may generate a remote-work reminder. Local deadlines remain explicitly personal. */
fun courseworkReminders(snapshot: CampusSnapshot, errors: Map<String, FailureKind>, now: Long): List<CourseworkReminder> {
    fun fresh(key: String) = errors[key] == null && isFresh(snapshot.updatedAt[key], now, 5 * 60_000L)
    val remote = dueCoursework(snapshot, now).filter { fresh("tasks:${it.courseId}") }
        .map { CourseworkReminder("task:${it.id}", it.title, it.courseId, it.due!!) }
    val personal = snapshot.personalDeadlines.values.filter { !it.completed && it.due > now && it.due <= now + 24 * 60 * 60_000L && fresh("upload-folders:${it.courseId}") }
        .mapNotNull { note -> snapshot.assignmentFolders[note.courseId]?.firstOrNull { it.id == note.folderId }?.let {
            CourseworkReminder("folder:${it.id}", it.name, it.courseId, note.due)
        } }
    // Only published exercise deadlines. Self-tests and exams have availability windows,
    // and their student-list status does not establish whether a submission is complete.
    val vips = snapshot.listedVipsSheets().filter { sheet ->
        sheet.deadline?.let { it > now && it <= now + 24 * 60 * 60_000L } == true &&
            sheet.startsAt?.let { it <= now } == true && snapshot.courseTools[sheet.courseId]?.hasVips == true &&
            fresh("tools:${sheet.courseId}") && fresh("vips:${sheet.courseId}")
    }.map { CourseworkReminder("vips:${it.courseId}:${it.id}", "Vips · ${it.title}", it.courseId, it.deadline!!) }
    return (remote + personal + vips).sortedBy { it.due }
}
