package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.delay
import kotlinx.datetime.*
import kotlin.time.Instant

/** Clearly labelled preview data; never mixed with authenticated accounts or live API calls. */
class DemoProvider(private val clock: () -> Long = ::nowMillis) : CampusProvider {
    override suspend fun activity(userId: String): List<CampusActivity> = emptyList()
    override suspend fun vipsResults(courseId: String): List<VipsResult> = emptyList()
    private val previewBlubber = mutableListOf(CommunityItem("preview-thread", "Welcome", "Welcome to Blubber.", secondary = "Prof. Weber", unread = 1, canReply = true))
    private val previewBlubberReplies = mutableListOf<CommunityItem>()
    override suspend fun postBlubberThread(courseId: String, body: String): WriteResult<Unit> {
        if (body.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        previewBlubber += CommunityItem("preview-thread-${previewBlubber.size}", body, body, secondary = "Alex Morgan", canReply = true)
        return WriteResult.Confirmed(Unit)
    }
    override suspend fun postBlubberReply(threadId: String, body: String): WriteResult<Unit> {
        if (body.isBlank() || previewBlubber.none { it.id == threadId }) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        previewBlubberReplies += CommunityItem("preview-reply-${previewBlubberReplies.size}", "Alex Morgan", body, timestamp = clock())
        return WriteResult.Confirmed(Unit)
    }
    private val materialPreviewTime = clock() - 2 * 3_600_000L
    private val term = previewTerm(campusToday(clock()))
    // The sample semester keeps a window around today; its lecture period follows the usual Augsburg calendar.
    private val semester = Semester("preview-current", term.title, clock() - 90L * 86_400_000, clock() + 90L * 86_400_000,
        term.lectureStart.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds().coerceIn(clock() - 90L * 86_400_000, clock() + 90L * 86_400_000),
        (term.lectureEnd.atTime(23, 59, 59).toInstant(CAMPUS_ZONE).toEpochMilliseconds()).coerceIn(clock() - 90L * 86_400_000, clock() + 90L * 86_400_000))
    private val courseList = listOf(
        Course("algorithms", "Algorithmen und Datenstrukturen", "Lecture & tutorial", "INF-1002",
            "Understand the ideas behind efficient software. Explore sorting, graphs, and the structures that bring them together.",
            "N · 1002", "Prof. Dr. Weber", semester.id, colorIndex = 0),
        Course("software", "Software Engineering", "Project seminar", "INF-2041",
            "Build thoughtful software as a team. Weekly studio sessions, practical exercises, and a semester project.",
            "N · 2005", "Dr. Lena Fischer", semester.id, colorIndex = 1),
        Course("math", "Lineare Algebra", "Lecture", "MTH-1101",
            "Vectors, linear maps, and the language of modern mathematics. Materials and exercises are shared each week.",
            "L · 1001", "Prof. Dr. Schneider", semester.id, colorIndex = 2),
        Course("networks", "Rechnernetze", "Lecture & lab", "INF-3014",
            "How the internet works, from packets and protocols to the systems that connect us.",
            "N · 1010", "Dr. Michael Bauer", semester.id, colorIndex = 3),
    )

    private val previewMessages = mutableListOf(CampusMessage("welcome-message", "Welcome", "Welcome to the course.", clock() - 3_600_000, false, "Prof. Weber", "teacher"))
    private val previewSentMessages = mutableListOf<CampusMessage>()
    private val previewReplies = mutableListOf<CommunityItem>()
    override suspend fun messages(userId: String, sent: Boolean): List<CampusMessage> {
        pause(); return (if (sent) previewSentMessages else previewMessages).toList()
    }
    override suspend fun findPeople(query: String): List<CampusPerson> {
        pause(); return listOf(CampusPerson("teacher", "Prof. Weber", "weber")).filter { it.name.contains(query, true) || it.username.contains(query, true) }
    }
    override suspend fun setMessageRead(id: String, read: Boolean): WriteResult<Boolean> {
        val i = previewMessages.indexOfFirst { it.id == id }
        if (i < 0) return WriteResult.Rejected(FailureKind.NOT_FOUND)
        previewMessages[i] = previewMessages[i].copy(read = read)
        return WriteResult.Confirmed(read)
    }
    override suspend fun sendMessage(recipientId: String, subject: String, body: String): WriteResult<Unit> {
        if (recipientId != "teacher") return WriteResult.Rejected(FailureKind.NOT_FOUND)
        previewSentMessages.add(0, CampusMessage("sent-${previewSentMessages.size}", subject, body, clock(), true, "Alex Morgan", "preview", listOf("Prof. Weber")))
        return WriteResult.Confirmed(Unit)
    }
    override suspend fun community(courseId: String, section: String, itemId: String): List<CommunityItem> {
        pause()
        if (courseList.none { it.id == courseId }) throw CampusException(FailureKind.NOT_FOUND)
        return when(section) {
            "people" -> listOf(CommunityItem("$courseId-teacher", "Prof. Weber", secondary = "dozent", personId = "teacher"),
                CommunityItem("$courseId-student", "Alex Morgan", secondary = "autor", personId = "preview"))
            "groups" -> listOf(CommunityItem("tutorial", "Tutorial A", "Weekly exercise group.", secondary = "0/25|selfassign"))
            "group-members" -> listOf(CommunityItem("teacher", "Prof. Weber", personId = "teacher"), CommunityItem("preview", "Alex Morgan", personId = "preview"))
            "blubber" -> if (itemId.isBlank()) previewBlubber.toList() else previewBlubberReplies.toList()
            "forum" -> if (itemId.isBlank()) listOf(CommunityItem("questions", "Questions", timestamp = clock() - 3_600_000, unread = 1))
                else if (itemId == "questions") listOf(CommunityItem("post-1", "Prof. Weber", "Welcome to the discussion.", timestamp = clock() - 3_600_000)) + previewReplies
                else throw CampusException(FailureKind.NOT_FOUND)
            "wiki" -> listOf(CommunityItem("wiki-1", "Course notes", "Useful course notes."))
            "courseware" -> if (itemId.isBlank()) listOf(CommunityItem("chapter", "Introduction", secondary = "unit"))
                else if (itemId == "chapter") listOf(CommunityItem("chapter", "Introduction", "Welcome to Courseware.", children = listOf(CommunityItem("chapter-2", "Next chapter")), incomplete = true))
                else listOf(CommunityItem("chapter-2", "Next chapter", "The next chapter."))
            else -> throw CampusException(FailureKind.NOT_FOUND)
        }
    }
    private val previewBookmarks = mutableSetOf<String>()
    override suspend fun coursewareBookmarks(userId: String): List<String> = previewBookmarks.toList()
    override suspend fun setCoursewareBookmark(userId: String, elementId: String, selected: Boolean): WriteResult<Unit> {
        if (selected) previewBookmarks.add(elementId) else previewBookmarks.remove(elementId)
        return WriteResult.Confirmed(Unit)
    }
    override suspend fun coursewareProgress(unitId: String): Map<String, Double> = mapOf("chapter" to 25.0, "chapter-2" to 0.0)

    private val previewSubscriptions = mutableListOf<ForumSubscription>()
    private val previewReactions = mutableMapOf<String, MutableList<ForumReaction>>()
    override suspend fun forumSubscriptions(courseId: String): List<ForumSubscription> = previewSubscriptions.toList()
    override suspend fun forumReactions(postId: String): List<ForumReaction> = previewReactions[postId].orEmpty().toList()
    override suspend fun changeForumSubscription(courseId: String, discussionId: String, subscriptionId: String?): WriteResult<Unit> {
        if (subscriptionId == null) previewSubscriptions.add(ForumSubscription("subscription-$discussionId", discussionId))
        else previewSubscriptions.removeAll { it.id == subscriptionId }
        return WriteResult.Confirmed(Unit)
    }
    override suspend fun changeForumReaction(postId: String, reactionId: String?): WriteResult<Unit> {
        val reactions = previewReactions.getOrPut(postId) { mutableListOf() }
        if (reactionId == null) reactions.add(ForumReaction("reaction-$postId", "👍", "preview"))
        else reactions.removeAll { it.id == reactionId }
        return WriteResult.Confirmed(Unit)
    }
    override suspend fun postForumReply(discussionId: String, body: String): WriteResult<Unit> {
        if (discussionId != "questions" || body.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        previewReplies += CommunityItem("post-${previewReplies.size + 2}", "Alex Morgan", body, timestamp = clock())
        if (previewSubscriptions.none { it.discussionId == discussionId }) previewSubscriptions += ForumSubscription("subscription-$discussionId", discussionId)
        return WriteResult.Confirmed(Unit)
    }

    override suspend fun account() = Account("preview", "Alex Morgan", "alex", demo = true)

    override suspend fun holidays(year: Int): List<Holiday> {
        pause()
        val fixed = listOf(LocalDate(year, 1, 1) to "Neujahr", LocalDate(year, 1, 6) to "Heilige Drei Könige", LocalDate(year, 5, 1) to "Tag der Arbeit",
            LocalDate(year, 8, 8) to "Augsburger Friedensfest", LocalDate(year, 10, 3) to "Tag der Deutschen Einheit",
            LocalDate(year, 11, 1) to "Allerheiligen", LocalDate(year, 12, 25) to "1. Weihnachtstag", LocalDate(year, 12, 26) to "2. Weihnachtstag")
        return fixed.map { (date, name) -> Holiday(date, name, true) }
    }

    private val previewBookings = mutableMapOf<String, String>()
    override suspend fun consultations(rangeType: String, rangeId: String, viewerId: String): List<ConsultationBlock> {
        pause()
        if (rangeType != "users" || rangeId != "teacher") return emptyList()
        val today = campusToday(clock())
        val day = today.plus((8 - today.dayOfWeek.ordinal) % 7 + 1, DateTimeUnit.DAY)
        val start = day.atTime(14, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
        val slots = (0 until 4).map { index ->
            val id = "weber-slot-$index"
            val from = start + index * 30 * 60_000L
            val booked = previewBookings[id]
            ConsultationSlot(id, "weber-block", from, from + 30 * 60_000L, bookable = booked == null && index != 1,
                bookingId = if (booked != null && viewerId == "preview") booked else "")
        }
        return listOf(ConsultationBlock("weber-block", rangeType, rangeId, "Prof. Weber", start, start + 2 * 3_600_000L,
            room = "N 2045", note = "Questions about exercises and the project. Please name your topic.", requireReason = "yes", slots = slots))
    }
    override suspend fun bookConsultation(slotId: String, userId: String, reason: String): WriteResult<String> {
        if (!slotId.startsWith("weber-slot-") || slotId == "weber-slot-1" || slotId in previewBookings) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val id = "preview-booking-${previewBookings.size + 1}"
        previewBookings[slotId] = id
        return WriteResult.Confirmed(id)
    }
    override suspend fun cancelConsultation(bookingId: String, reason: String): WriteResult<Unit> {
        val slot = previewBookings.entries.firstOrNull { it.value == bookingId }?.key ?: return WriteResult.Rejected(FailureKind.NOT_FOUND)
        previewBookings.remove(slot)
        return WriteResult.Confirmed(Unit)
    }

    private val previewComments = mutableMapOf("algorithms-sheet" to mutableListOf(
        NewsComment("comment-1", "Mia Keller", "Is exercise 3 part of the tutorial on Friday?", clock() - 40 * 60_000L)))
    override suspend fun newsComments(newsId: String): List<NewsComment> { pause(); return previewComments[newsId].orEmpty().toList() }
    override suspend fun postNewsComment(newsId: String, content: String): WriteResult<Unit> {
        if (content.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        previewComments.getOrPut(newsId) { mutableListOf() } += NewsComment("comment-${clock()}", "Alex Morgan", content.trim(), clock())
        return WriteResult.Confirmed(Unit)
    }

    private val previewContacts = mutableListOf(CampusPerson("teacher", "Prof. Weber", "weber"))
    override suspend fun contacts(userId: String): List<CampusPerson> { pause(); return previewContacts.toList() }
    override suspend fun setContact(userId: String, contactId: String, add: Boolean): WriteResult<Unit> {
        if (add) { if (previewContacts.none { it.id == contactId }) previewContacts += CampusPerson(contactId, if (contactId == "teacher") "Prof. Weber" else "Mia Keller") }
        else previewContacts.removeAll { it.id == contactId }
        return WriteResult.Confirmed(Unit)
    }
    override suspend fun studyGroupProposals(): List<Course> {
        pause()
        return listOf(Course("group-algorithms", "Lerngruppe Algorithmen", "Study group", description = "Weekly practice for the exercise sheets.", typeName = "Studiengruppe"),
            Course("group-math", "Lineare Algebra – Übungsgruppe", "Study group", typeName = "Studiengruppe"))
    }
    override suspend fun semesters(): List<Semester> {
        pause()
        return listOf(semester, Semester("preview-previous", term.previousTitle, semester.start - 180L * 86_400_000, semester.start - 1))
    }
    override suspend fun courses(userId: String, semesterId: String?): List<Course> {
        pause()
        return if (semesterId == "preview-previous") courseList.take(2) else courseList
    }
    override suspend fun course(id: String): Course { pause(); return courseList.first { it.id == id } }

    override suspend fun courseTools(courseId: String): CourseTools {
        pause()
        return CourseTools(listOf(CourseTool("main", "Overview", courseOverviewUrl(courseId)),
            CourseTool("files", "Files", "$DIGICAMPUS_ORIGIN/dispatch.php/course/files?cid=$courseId"),
            CourseTool("wiki", "Course wiki", "$DIGICAMPUS_ORIGIN/dispatch.php/course/wiki?cid=$courseId"),
            CourseTool("forum", "Forum", "$DIGICAMPUS_ORIGIN/dispatch.php/course/forum?cid=$courseId"),
            CourseTool("blubber", "Blubber", "$DIGICAMPUS_ORIGIN/dispatch.php/course/blubber?cid=$courseId"),
            CourseTool("courseware", "Courseware", "$DIGICAMPUS_ORIGIN/dispatch.php/course/courseware?cid=$courseId")) +
            if (courseId == "algorithms") listOf(CourseTool("vips", "Exercise sheet", vipsSheetsUrl(courseId))) else emptyList())
    }

    override suspend fun vipsSheets(courseId: String): VipsSheets {
        pause()
        return VipsSheets(if (courseId == "algorithms") listOf(VipsSheet("preview-3", courseId, "Graphs · Exercise sheet 3",
            "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&cid=$courseId",
            "19.10.2026, 10:00", "26.10.2026, 10:00", "Exercise sheet", "–")) else emptyList())
    }

    private fun eventsForWeek(from: LocalDate): List<CampusEvent> {
        val week = weekDates(from)
        return listOf(
            event("algorithms", week[0], 10, 0, 90, "Lecture · Trees and graphs"),
            event("math", week[0], 14, 0, 90, "Lecture · Linear maps"),
            event("software", week[1], 9, 0, 120, "Studio · Designing together"),
            event("algorithms", week[2], 10, 0, 90, "Lecture · Finding the shortest path"),
            event("networks", week[2], 14, 0, 90, "Lecture · Reliable connections"),
            event("math", week[3], 10, 0, 90, "Lecture · Bases and dimension"),
            event("software", week[3], 14, 0, 120, "Studio · Your first prototype"),
            event("algorithms", week[4], 11, 0, 90, "Tutorial · Problem-solving session"),
        )
    }
    private fun event(courseId: String, date: LocalDate, hour: Int, minute: Int, duration: Int, title: String): CampusEvent {
        val start = date.atTime(hour, minute).toInstant(CAMPUS_ZONE)
        val course = courseList.first { it.id == courseId }
        return CampusEvent("$courseId-$date-$hour", title, start.toEpochMilliseconds(), start.toEpochMilliseconds() + duration * 60_000,
            courseId, course.location, "Bring your notes and questions. Course materials are available in the Materials tab.")
    }
    override suspend fun events(courseId: String): List<CampusEvent> {
        pause()
        val today = campusToday(clock())
        return (-2..8).flatMap { eventsForWeek(today.plus(it * 7, DateTimeUnit.DAY)) }.filter { it.courseId == courseId }
    }
    override suspend fun personalEvents(userId: String, from: Long): List<CampusEvent> {
        pause()
        val date = Instant.fromEpochMilliseconds(from).toLocalDateTime(CAMPUS_ZONE).date
        val finalDay = date.plus(14, DateTimeUnit.DAY)
        return eventsForWeek(date) + eventsForWeek(date.plus(7, DateTimeUnit.DAY)) + eventsForWeek(finalDay).filter { it.date == finalDay }
    }
    private val extraCourses get() = listOf(
        Course("planning-ai", "Artificial Intelligence", "Lecture & alternative tutorials", "INF-4100", location = "N · 1002", teacher = "Prof. Dr. Weber", semesterId = semester.id),
        Course("planning-systems", "Distributed Systems", "Lecture & lab", "INF-4200", location = "N · 2005", teacher = "Dr. Lena Fischer", semesterId = semester.id))
    override suspend fun searchPlannerCourses(request: PlannerSearch): PlannerCoursePage {
        pause()
        val matches = (courseList + extraCourses).filter { course ->
            val fields = if (request.kind == PlannerSearchKind.LECTURER) listOf(course.teacher) else listOf(course.title, course.teacher, course.number)
            fields.any { it.contains(request.normalized, true) }
        }
        return PlannerCoursePage(request, matches.drop(request.offset).take(20), request.offset + 20 < matches.size, matches.size)
    }
    override suspend fun plannerSchedule(course: Course, semester: Semester): PlannerSchedule {
        pause()
        if (course.id !in extraCourses.map { it.id }) return PlannerSchedule(course, semester.id, events(course.id))
        val monday = weekDates(campusToday(clock())).first()
        val dates = (-2..8).flatMap { offset ->
            val week = weekDates(monday.plus(offset * 7, DateTimeUnit.DAY))
            val slots = if (course.id == "planning-ai") listOf(Triple(week[0], 10, "Lecture"), Triple(week[2], 12, "Tutorial A"), Triple(week[4], 14, "Tutorial B"))
                else listOf(Triple(week[1], 9, "Lecture"), Triple(week[3], 16, "Lab"))
            slots.map { (day, hour, label) ->
                val start = day.atTime(hour, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
                CampusEvent("${course.id}-$day-$hour", course.title, start, start + 90 * 60_000,
                    course.id, course.location, label)
            }
        }
        return PlannerSchedule(course, semester.id, dates)
    }
    override suspend fun publishedRooms(): PublishedRoomSchedule {
        pause()
        val today = campusToday(clock())
        val rooms = listOf(PublishedRoom("wiwi:1113", "WiWi · 1113"), PublishedRoom("wiwi:1114", "WiWi · 1114"))
        val entries = (0..13).filter { today.plus(it, DateTimeUnit.DAY).dayOfWeek.ordinal < 5 }.map { offset ->
            val start = today.plus(offset, DateTimeUnit.DAY).atTime(10, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
            PlannerEntry(CampusEvent("room-$offset", "Economics seminar", start, start + 90 * 60_000, location = rooms[0].label), "Dr. Preview", roomId = rooms[0].id)
        }
        return PublishedRoomSchedule(today.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds(),
            today.plus(14, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds(), rooms, entries)
    }

    override suspend fun searchCampusRooms(query: String): CampusRoomResults {
        pause()
        val rooms = listOf(CampusRoom("preview-n1002", "N · 1002", "Informatics · lecture hall", 120,
            "$DIGICAMPUS_ORIGIN/dispatch.php/resources/room_planning/booking_plan/preview-n1002"),
            CampusRoom("preview-n2005", "N · 2005", "Informatics · seminar room", 35,
                "$DIGICAMPUS_ORIGIN/dispatch.php/resources/room_planning/booking_plan/preview-n2005"),
            CampusRoom("preview-n2013", "N · 2.013", "Informatics · seminar room", 40,
                "$DIGICAMPUS_ORIGIN/dispatch.php/resources/room_planning/booking_plan/preview-n2013"),
            CampusRoom("preview-w2013", "W · 2013", "Seminar room", 30,
                "$DIGICAMPUS_ORIGIN/dispatch.php/resources/room_planning/booking_plan/preview-w2013"))
        return CampusRoomResults(query, rooms.filter { campusRoomNameMatches(query, it.name) }, CAMPUS_ROOM_SEARCH_VERSION)
    }

    override suspend fun campusRoomWeek(room: CampusRoom, monday: LocalDate): CampusRoomWeek {
        pause()
        val from = monday.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        val until = monday.plus(7, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        val entries = (0..4).map { offset ->
            val start = monday.plus(offset, DateTimeUnit.DAY).atTime(10, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
            PlannerEntry(CampusEvent("campus-room-$offset-$monday", "Guest lecture · campus booking", start, start + 90 * 60_000,
                location = room.name), roomId = "campus:${room.id}", sourceUrl = room.sourceUrl)
        }
        return CampusRoomWeek(room, from, until, entries)
    }
    override suspend fun announcements(courseId: String): List<Announcement> {
        pause()
        val now = clock()
        return when (courseId) {
            "algorithms" -> listOf(
                Announcement("algorithms-sheet", courseId, "Exercise sheet 02 is ready",
                    "This week's exercises are in the Materials folder. Try them before Friday's tutorial.\n\nWork through the graph examples at your own pace and bring any questions to the session.", now - 60 * 60_000,
                    commentsAllowed = true),
                Announcement("algorithms-welcome", courseId, "Welcome to the course",
                    "All lecture slides, notes, and exercise sheets will be shared here throughout the semester.", now - 3 * 86_400_000),
            )
            "software" -> listOf(Announcement("software-studio", courseId, "A little preparation for our next studio",
                "Bring one example of an app you enjoy using. We will talk about what makes it feel clear, calm, and useful.", now - 5 * 60 * 60_000))
            "math" -> listOf(Announcement("math-room", courseId, "Thursday's lecture: room L 1001",
                "Our next lecture will take place in L 1001. The updated notes are available under Lecture notes.", now - 24 * 60 * 60_000))
            else -> emptyList()
        }
    }
    override suspend fun coursework(courseId: String): List<CourseworkTask> {
        pause()
        val today = campusToday(clock())
        fun due(days: Int, hour: Int) = today.plus(days, DateTimeUnit.DAY).atTime(hour, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
        return (when (courseId) {
            "algorithms" -> listOf(
                CourseworkTask("graphs", courseId, "Shortest paths & graph traversal", due(0, 18), false, 35.0, canSolve = true),
                CourseworkTask("sorting", courseId, "Sorting fundamentals", due(-3, 18), true, 100.0),
            )
            "software" -> listOf(CourseworkTask("prototype", courseId, "Your first prototype", due(1, 14), false, 60.0, canSolve = true))
            "math" -> listOf(CourseworkTask("vectors", courseId, "Vectors and linear maps", due(-1, 18), false, 100.0),
                CourseworkTask("basis", courseId, "Bases and dimension", due(2, 18), false, 20.0, "granted", due(0, 18)))
            else -> emptyList()
        }).map { it.copy(submitted = it.submitted || it.id in submittedTasks) }
    }
    override suspend fun courseworkDetail(courseId: String, taskId: String): CourseworkDetail {
        val task = coursework(courseId).first { it.id == taskId }.let { it.copy(submitted = it.submitted || taskId in submittedTasks) }
        val body = when (taskId) {
            "graphs" -> "Explore breadth-first and depth-first search. Explain which algorithm you would use to find a shortest path in an unweighted graph.\n\nWork through the examples from this week's lecture, then prepare your solution for the tutorial."
            "vectors" -> "Describe the image and kernel of the given linear map. Show your working and explain how the dimensions relate."
            "prototype" -> "Choose one campus problem and sketch a small, useful solution. Share your prototype and describe one thing you learned from a teammate's feedback."
            else -> "Work through the examples from the lecture and explain your approach."
        }
        return CourseworkDetail(task, listOf(CourseworkSection("Your task", body)), true)
    }
    private val submittedTasks = mutableSetOf<String>()
    override suspend fun assignmentFolders(courseId: String, force: Boolean): List<CampusFolder> =
        folders(courseId, null).filter { it.submissionFolder }
    override suspend fun submitCoursework(courseId: String, taskId: String): WriteResult<CourseworkDetail> {
        pause(); submittedTasks += taskId
        return WriteResult.Confirmed(courseworkDetail(courseId, taskId).let { it.copy(task = it.task.copy(submitted = true)) })
    }
    override suspend fun uploadAssignment(draft: UploadDraft, ownerId: String): WriteResult<Document> {
        pause()
        return WriteResult.Confirmed(Document("preview-upload-${clock()}", draft.courseId, draft.folderId, draft.name, draft.bytes.size.toLong(), draft.mimeType, clock()))
    }
    override suspend fun folders(courseId: String, parentId: String?): List<CampusFolder> {
        pause()
        return if (parentId == null) listOf(
            CampusFolder("$courseId-lectures", courseId, "Lecture notes", "$courseId-root", description = "Slides and notes, week by week",
                created = materialPreviewTime - 40 * 86_400_000L, modified = materialPreviewTime - 20 * 86_400_000L),
            CampusFolder("$courseId-exercises", courseId, "Exercises", "$courseId-root", description = "A little practice goes a long way",
                created = materialPreviewTime - 40 * 86_400_000L, modified = materialPreviewTime - 10 * 86_400_000L),
            CampusFolder("$courseId-empty", courseId, "Additional resources", folderType = "StandardFolder",
                parentId = "$courseId-root", writable = false, fileCount = 0, empty = true, modified = materialPreviewTime - 30 * 86_400_000L),
            CampusFolder("$courseId-submissions", courseId, "Submit your exercises", folderType = "HomeworkFolder",
                parentId = "$courseId-root", writable = true, fileCount = 0, empty = true, modified = materialPreviewTime - 30 * 86_400_000L),
        ) else if (parentId.endsWith("lectures")) listOf(CampusFolder("$courseId-archive", courseId, "Additional reading", parentId,
            modified = materialPreviewTime - 30 * 86_400_000L))
        else emptyList()
    }
    override suspend fun documents(courseId: String, folderId: String): List<Document> {
        pause()
        if (folderId.endsWith("empty") || folderId.endsWith("submissions")) return emptyList()
        val names = if (folderId.endsWith("exercises")) listOf("Exercise sheet 01.pdf", "Exercise sheet 02.pdf")
            else if (folderId.endsWith("archive")) listOf("Further reading.pdf")
            else listOf("01 · Getting started.pdf", "02 · The important ideas.pdf", "Course overview.pdf")
        return names.mapIndexed { i, name ->
            val created = materialPreviewTime - (if (folderId.endsWith("archive")) 45 else if (i == 2) 35 else 4 + i) * 86_400_000L
            val modified = if (folderId.endsWith("archive")) materialPreviewTime - 3 * 86_400_000L
                else if (i == 1) materialPreviewTime else created
            Document("$folderId-$i", courseId, folderId, name, 3_200, "application/pdf", modified, created = created)
        }
    }
    override suspend fun courseMaterials(courseId: String, force: Boolean): CourseMaterials {
        val top = folders(courseId, null)
        val nested = folders(courseId, "$courseId-lectures")
        val files = (top + nested).flatMap { documents(courseId, it.id) }
        return CourseMaterials(courseId, listOf(CampusFolder("$courseId-root", courseId, "Files", folderType = "RootFolder")) + top + nested, files)
    }
    override suspend fun folderContent(courseId: String, parentId: String?, force: Boolean): FolderContent {
        val content = super.folderContent(courseId, parentId, force)
        val current = if (parentId == null) null else folders(courseId, null).firstOrNull { it.id == parentId }
        return content.copy(currentFolder = current)
    }
    override suspend fun linkedDocument(courseId: String, url: String): Document {
        val link = VipsLinks.attachment(url, courseId) ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        require(link.id == "preview-vips-pdf")
        return Document(link.id, courseId, VIPS_ATTACHMENTS, link.name, mimeType = "application/pdf")
    }
    override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long {
        pause()
        val bytes = previewPdf()
        write(bytes)
        return bytes.size.toLong()
    }
    private suspend fun pause() { delay(160) }

    private fun previewPdf(): ByteArray {
        val stream = "BT /F1 24 Tf 54 750 Td (ZenCampus) Tj /F1 12 Tf 0 -36 Td (A quiet place for your campus day.) Tj 0 -30 Td (This is a preview document, not university course material.) Tj 0 -22 Td (Sign in to DigiCampus to access your real materials.) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
        )
        val output = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, obj ->
            val offset = output.length
            output.append("${i + 1} 0 obj\n$obj\nendobj\n")
            offset
        }
        val xref = output.length
        output.append("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { output.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        output.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toString().encodeToByteArray()
    }
}

internal data class PreviewTerm(val title: String, val previousTitle: String, val lectureStart: LocalDate, val lectureEnd: LocalDate)

/** The term containing [today]: winter lectures run mid-October to early February, summer ones mid-April to late July. */
internal fun previewTerm(today: LocalDate): PreviewTerm {
    fun onOrAfter(date: LocalDate, day: DayOfWeek): LocalDate = date.plus((day.ordinal - date.dayOfWeek.ordinal + 7) % 7, DateTimeUnit.DAY)
    val month = today.month.ordinal + 1
    return if (month >= 10 || month <= 3) {
        val year = if (month >= 10) today.year else today.year - 1
        PreviewTerm("Wintersemester $year/${(year + 1) % 100}", "Sommersemester $year",
            onOrAfter(LocalDate(year, 10, 10), DayOfWeek.MONDAY), onOrAfter(LocalDate(year + 1, 2, 3), DayOfWeek.SATURDAY))
    } else {
        val year = today.year
        PreviewTerm("Sommersemester $year", "Wintersemester ${year - 1}/${year % 100}",
            onOrAfter(LocalDate(year, 4, 13), DayOfWeek.MONDAY), onOrAfter(LocalDate(year, 7, 22), DayOfWeek.SATURDAY))
    }
}
