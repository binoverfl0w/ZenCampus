package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Read operations and explicit student actions. Provider DTOs stay inside the connector. */
/**
 * Everything ZenCampus reads from or writes to a campus system, split by area. Optional features default to
 * NOT_FOUND so a provider (or a test fake) implements only what it offers.
 */
interface CampusProvider : CourseSource, PlannerSource, MaterialsSource, CourseworkSource, CommunitySource, MessagingSource, OfficeHoursSource

/** Profile, semesters, courses, their dates and announcements. */
interface CourseSource {
    suspend fun account(): Account
    suspend fun semesters(): List<Semester>
    suspend fun courses(userId: String, semesterId: String?): List<Course>
    suspend fun course(id: String): Course
    suspend fun events(courseId: String): List<CampusEvent>
    /** Complete user/course events from midnight through the day two weeks later, inclusive. */
    suspend fun personalEvents(userId: String, from: Long): List<CampusEvent>
    /** Weekly timetable projected into the same date window, including personal timetable slots. */
    suspend fun scheduledEvents(userId: String, from: Long, semesters: List<Semester>): List<CampusEvent> = emptyList()
    suspend fun plannerSchedule(course: Course, semester: Semester): PlannerSchedule =
        PlannerSchedule(course, semester.id, events(course.id))
    suspend fun announcements(courseId: String): List<Announcement>
    suspend fun activity(userId: String): List<CampusActivity> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun holidays(year: Int): List<Holiday> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun newsComments(newsId: String): List<NewsComment> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun postNewsComment(newsId: String, content: String): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun studyGroupProposals(): List<Course> = throw CampusException(FailureKind.NOT_FOUND)
}

/** Course catalogue search, rooms and lecturers beyond the enrolled courses. */
interface PlannerSource {
    suspend fun searchPlannerCourses(request: PlannerSearch): PlannerCoursePage = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun publishedRooms(): PublishedRoomSchedule = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun searchCampusRooms(query: String): CampusRoomResults = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun campusRoomWeek(room: CampusRoom, monday: kotlinx.datetime.LocalDate): CampusRoomWeek = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun lecturerCourses(query: String, semesterId: String): LecturerCourses {
        val results = linkedMapOf<String, Course>(); var total: Int? = null
        for (offset in 0 until 200 step 20) {
            val page = searchPlannerCourses(PlannerSearch(query, PlannerSearchKind.LECTURER, semesterId, offset))
            total = page.total ?: total
            val before = results.size
            page.courses.forEach { results[it.id] = it }
            if (!page.hasMore) return LecturerCourses(query.trim(), semesterId, results.values.toList(), true, total)
            if (page.courses.isEmpty() || results.size == before) throw CampusException(FailureKind.INVALID_RESPONSE)
        }
        return LecturerCourses(query.trim(), semesterId, results.values.toList(), false, total)
    }
}

/** Course files: folders, file lists, downloads and submission uploads. */
interface MaterialsSource {
    suspend fun folders(courseId: String, parentId: String?): List<CampusFolder>
    suspend fun documents(courseId: String, folderId: String): List<Document>
    suspend fun courseMaterials(courseId: String, force: Boolean = false): CourseMaterials = throw CampusException(FailureKind.NOT_FOUND)
    /** A course file area has a real root folder, which may also contain files. */
    suspend fun folderContent(courseId: String, parentId: String?, force: Boolean = false): FolderContent = coroutineScope {
        val children = async { folders(courseId, parentId) }
        val files = async { if (parentId == null) emptyList() else documents(courseId, parentId) }
        FolderContent(children.await(), files.await(), version = 2)
    }
    suspend fun linkedDocument(courseId: String, url: String): Document = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long
    suspend fun assignmentFolders(courseId: String, force: Boolean = false): List<CampusFolder> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun uploadAssignment(draft: UploadDraft, ownerId: String): WriteResult<Document> = WriteResult.Rejected(FailureKind.NOT_FOUND)
}

/** Courseware tasks, peer review and the course tools behind Vips. */
interface CourseworkSource {
    suspend fun coursework(courseId: String): List<CourseworkTask> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun courseworkDetail(courseId: String, taskId: String): CourseworkDetail = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun peerReviews(taskId: String): List<PeerReview> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun submitCoursework(courseId: String, taskId: String): WriteResult<CourseworkDetail> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun courseTools(courseId: String): CourseTools = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun vipsSheets(courseId: String): VipsSheets = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun vipsResults(courseId: String): List<VipsResult> = throw CampusException(FailureKind.NOT_FOUND)
}

/** Forum, Blubber, wiki and Courseware reading. */
interface CommunitySource {
    suspend fun community(courseId: String, section: String, itemId: String = ""): List<CommunityItem> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun coursewareFile(courseId: String, blockId: String, fileId: String): Document = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun forumSubscriptions(courseId: String): List<ForumSubscription> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun forumReactions(postId: String): List<ForumReaction> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun changeForumSubscription(courseId: String, discussionId: String, subscriptionId: String?): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun changeForumReaction(postId: String, reactionId: String?): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun postForumReply(discussionId: String, body: String): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun postBlubberReply(threadId: String, body: String): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun postBlubberThread(courseId: String, body: String): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun coursewareBookmarks(userId: String): List<String> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun setCoursewareBookmark(userId: String, elementId: String, selected: Boolean): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun coursewareProgress(unitId: String): Map<String, Double> = throw CampusException(FailureKind.NOT_FOUND)
}

/** DigiCampus messages, people search and saved contacts. */
interface MessagingSource {
    suspend fun messages(userId: String, sent: Boolean): List<CampusMessage> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun findPeople(query: String): List<CampusPerson> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun sendMessage(recipientId: String, subject: String, body: String): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun setMessageRead(id: String, read: Boolean): WriteResult<Boolean> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun contacts(userId: String): List<CampusPerson> = throw CampusException(FailureKind.NOT_FOUND)
    suspend fun setContact(userId: String, contactId: String, add: Boolean): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
}

/** Consultation blocks, bookings and cancellations. */
interface OfficeHoursSource {
    /** Current office-hours blocks of a user, course or institute, each with its slots. */
    suspend fun consultations(rangeType: String, rangeId: String, viewerId: String): List<ConsultationBlock> = throw CampusException(FailureKind.NOT_FOUND)
    /** Books a slot for [userId]; the confirmed value is the new booking id. */
    suspend fun bookConsultation(slotId: String, userId: String, reason: String): WriteResult<String> = WriteResult.Rejected(FailureKind.NOT_FOUND)
    suspend fun cancelConsultation(bookingId: String, reason: String): WriteResult<Unit> = WriteResult.Rejected(FailureKind.NOT_FOUND)
}


interface MaterialStorage {
    suspend fun create(scope: String, document: Document): MaterialWriter
    suspend fun exists(path: String): Boolean
    suspend fun remove(path: String)
    suspend fun clear(scope: String)
    suspend fun open(path: String, mimeType: String, displayName: String = "")
    /** May create a readable alias; the caller commits its path before removing the old file. */
    suspend fun prepare(path: String, document: Document): String = path
    suspend fun openWith(path: String, mimeType: String, displayName: String) = open(path, mimeType, displayName)
    suspend fun share(path: String, mimeType: String, displayName: String): Unit = throw CampusException(FailureKind.NO_VIEWER)
    suspend fun saveToDownloads(path: String, mimeType: String, displayName: String): String? = throw CampusException(FailureKind.STORAGE)
    suspend fun saveToFolder(path: String, mimeType: String, displayName: String, destination: MaterialDestination): String =
        throw CampusException(FailureKind.STORAGE)
    /** Whether material sync can write to the default ZenCampus downloads folder without the user choosing one. */
    val syncsWithoutFolder: Boolean get() = false
    /**
     * Streams a synced course file to [folders]/[name] below [destination], or below the default ZenCampus downloads
     * folder when it is null. finish() replaces an existing file of that name and returns a readable location.
     */
    suspend fun syncWriter(destination: MaterialDestination?, folders: List<String>, name: String, mimeType: String): MaterialWriter =
        throw CampusException(FailureKind.STORAGE)
    /** Streams the saved file at [path] into [writer]; the caller finishes or aborts it. */
    suspend fun copyInto(path: String, writer: MaterialWriter): Unit = throw CampusException(FailureKind.STORAGE)
}

interface MaterialWriter {
    suspend fun write(bytes: ByteArray)
    suspend fun finish(): String
    suspend fun abort()
}

data class NetworkConditions(val connected: Boolean = true, val metered: Boolean = false, val batteryLow: Boolean = false) {
    fun canPrefetch(settings: AppSettings): Boolean = connected && !metered && !batteryLow && !settings.dataSaver
}
