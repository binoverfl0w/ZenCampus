package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.EVENT_TTL
import io.github.binoverfl0w.zencampus.model.*
import kotlin.reflect.typeOf
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

/**
 * Courseware tasks, Vips, submissions, personal deadlines and reminders.
 *
 * Part of [CampusRepository]: each operation runs with the repository as receiver (`with(repo)`), so the shared
 * cache engine, provider, store and other areas are reached exactly as from the repository itself.
 */
internal class CourseworkRepository(private val repo: CampusRepository) {
    /** Read the current course's accessible uploads and released results on explicit request. */
    suspend fun refreshCourseSubmitted(courseId: String, force: Boolean = false) { with(repo) {
        if (courseId !in selectedStoredCourses().map { it.id }) return
        refreshCourseMaterials(courseId, force)
        refreshCourseTools(courseId, force)
        if (_errors.value[CacheKeys.tools(courseId)] !in setOf(FailureKind.AUTHENTICATION, FailureKind.PERMISSION) &&
            (stored<CourseTools>(CacheKeys.tools(courseId))?.hasVips == true || stored<VipsSheets>(CacheKeys.vips(courseId)) != null))
            refresh(CacheKeys.vipsResults(courseId), EVENT_TTL, force) { provider.vipsResults(courseId) }
    } }

    /** Explicit overview loads all selected-semester courses, at most two requests at a time. */
    suspend fun refreshAllCoursework(force: Boolean = false) { with(repo) {
        if (selectedStoredCourses().isEmpty()) refreshCourses(force)
        supervisorScope {
            selectedStoredCourses().map { course -> launch {
                courseworkSlots.withPermit {
                    refreshCoursework(course.id, force)
                    refreshCourseTools(course.id, force)
                }
            } }.joinAll()
        }
    } }

    suspend fun refreshCoursework(courseId: String, force: Boolean = false, background: Boolean = false) { with(repo) {
        refresh(CacheKeys.tasks(courseId), EVENT_TTL, force, background) { provider.coursework(courseId) }
        refresh(CacheKeys.uploadFolders(courseId), EVENT_TTL, force, background) { provider.assignmentFolders(courseId, force) }
        val tasks = stored<List<CourseworkTask>>(CacheKeys.tasks(courseId))
        if (tasks != null) commitMutex.withLock {
            if (active) store.observe(account.cacheScope).first().filter { record ->
                record.key.startsWith("task:$courseId:") && tasks.none { record.key == CacheKeys.task(courseId, it.id) }
            }.forEach { store.remove(account.cacheScope, it.key) }
        }
    } }

    suspend fun refreshCourseTools(courseId: String, force: Boolean = false, background: Boolean = false) { with(repo) {
        val key = CacheKeys.tools(courseId)
        refresh(key, EVENT_TTL, force, background) { provider.courseTools(courseId) }
        val tools = stored<CourseTools>(key)
        if (tools?.hasVips == true && _errors.value[key] != FailureKind.AUTHENTICATION) {
            refresh(CacheKeys.vips(courseId), EVENT_TTL, force, background) { provider.vipsSheets(courseId) }
        } else if (tools != null && _errors.value[key] == null) {
            commitMutex.withLock { if (active) {
                courseToolEpochs[courseId] = (courseToolEpochs[courseId] ?: 0L) + 1
                store.remove(account.cacheScope, CacheKeys.vips(courseId))
                store.remove(account.cacheScope, CacheKeys.vipsResults(courseId))
            } }
        }
        trimHistory("tools:", 80)
        trimHistory("vips:", 80)
    } }

    suspend fun refreshPeerReviews(courseId: String, taskId: String, force: Boolean = false) { with(repo) {
        if (snapshot.value.coursework[courseId]?.none { it.id == taskId } != false) return
        refresh(CacheKeys.peerReviews(courseId, taskId), EVENT_TTL, force) { provider.peerReviews(taskId) }
    } }

    suspend fun refreshCourseworkDetail(courseId: String, taskId: String, force: Boolean = false) { with(repo) {
        val key = CacheKeys.task(courseId, taskId)
        refresh(key, EVENT_TTL, force) { provider.courseworkDetail(courseId, taskId) }
        if (_errors.value[key] != null) return
        // A fresh detail can report a newer submission status than the overview. Keep its age honest.
        commitMutex.withLock {
            if (!active) return@withLock
            val detail = stored<CourseworkDetail>(key) ?: return@withLock
            val writeKey = "write:task:$taskId"
            val write = stored<CourseworkWrite>(writeKey)
            if (detail.task.submitted && write?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN))
                store.put(account.cacheScope, writeKey, json.encodeToString(write!!.copy(status = WriteStatus.CONFIRMED)), clock())
            val record = store.read(account.cacheScope, CacheKeys.tasks(courseId)) ?: return@withLock
            val tasks = record.decode<List<CourseworkTask>>() ?: return@withLock
            if (tasks.any { it.id == taskId }) store.put(account.cacheScope, record.key,
                json.encodeToString(tasks.map { if (it.id == taskId) detail.task else it }), record.updatedAt)
        }
        trimHistory("task:", 60)
    } }

    suspend fun selectUpload(draft: UploadDraft) { with(repo) {
        if (!active) return
        if (draft.bytes.isEmpty() || draft.bytes.size > MAX_UPLOAD_BYTES) { _uploadSelectionError.value = FailureKind.STORAGE; return }
        commitMutex.withLock {
            checkActive()
            val key = "write:upload:${draft.folderId}"
            if (stored<CourseworkWrite>(key)?.status !in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)) store.remove(account.cacheScope, key)
        }
        _uploadSelectionError.value = null
        _uploadDraft.value = draft
    } }

    fun uploadSelectionFailed(failure: FailureKind) { with(repo) { if (active) _uploadSelectionError.value = failure } }

    fun dismissUpload() { with(repo) { _uploadDraft.value = null; _uploadSelectionError.value = null } }

    suspend fun setPersonalDeadline(courseId: String, folderId: String, due: Long?) = with(repo) { commitMutex.withLock {
        checkActive()
        val notes = stored<List<PersonalDeadline>>("personal-deadlines").orEmpty().filterNot { it.folderId == folderId }
        store.put(account.cacheScope, "personal-deadlines", json.encodeToString(notes + listOfNotNull(due?.let { PersonalDeadline(courseId, folderId, it) })), clock())
    }
    }

    suspend fun completePersonalDeadline(folderId: String, completed: Boolean) = with(repo) { commitMutex.withLock {
        checkActive()
        val notes = stored<List<PersonalDeadline>>("personal-deadlines").orEmpty().map { if (it.folderId == folderId) it.copy(completed = completed) else it }
        store.put(account.cacheScope, "personal-deadlines", json.encodeToString(notes), clock())
    }
    }

    suspend fun acknowledgeWrite(key: String) = with(repo) { commitMutex.withLock {
        if (active && key !in coordinator.active.value) store.remove(account.cacheScope, key)
    }
    }

    suspend fun submitTask(courseId: String, taskId: String) { with(repo) {
        val key = "write:task:$taskId"
        val title = snapshot.value.coursework[courseId]?.firstOrNull { it.id == taskId }?.title.orEmpty()
        studentWrite(key, courseId, taskId, title, action = { provider.submitCoursework(courseId, taskId) }) { detail ->
            commit(CacheKeys.task(courseId, taskId), json.encodeToString(detail))
            commitMutex.withLock {
                checkActive()
                val record = store.read(account.cacheScope, CacheKeys.tasks(courseId))
                val tasks = record.decode<List<CourseworkTask>>()
                if (record != null && tasks != null) store.put(account.cacheScope, record.key,
                    json.encodeToString(tasks.map { if (it.id == taskId) detail.task else it }), record.updatedAt)
            }
            null
        }
    } }

    suspend fun confirmUpload() { with(repo) {
        val draft = _uploadDraft.value ?: return
        studentWrite("write:upload:${draft.folderId}", draft.courseId, draft.folderId, draft.name, requestId = draft.selectionId,
            action = { provider.uploadAssignment(draft, account.id) }) { doc -> doc }
        // Keep the dialog open to display the confirmed receipt or the uncertain result.
    } }

    suspend fun updateNotificationSources(now: Long): Map<String, Set<String>> { with(repo) {
        checkActive()
        val current = snapshotFrom(store.observe(account.cacheScope).first())
        // Reminders never trust a source whose last read failed, even when that failure stayed off screen.
        return notificationSources(current, _errors.value + _stale.value, now)
    } }

    suspend fun courseworkReminders(now: Long): List<CourseworkReminder> { with(repo) {
        if (_errors.value.values.any { it == FailureKind.AUTHENTICATION }) return emptyList()
        val current = snapshotFrom(store.observe(account.cacheScope).first())
        return courseworkReminders(current, _errors.value + _stale.value, now)
    } }
}
