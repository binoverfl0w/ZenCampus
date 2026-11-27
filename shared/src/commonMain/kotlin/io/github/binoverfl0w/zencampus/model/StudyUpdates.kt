package io.github.binoverfl0w.zencampus.model

enum class StudyUpdateKind { MESSAGE, ANNOUNCEMENT, FILE, FORUM, BLUBBER, ACTIVITY }
data class StudyUpdate(val key: String, val kind: StudyUpdateKind, val id: String, val courseId: String,
    val title: String, val timestamp: Long, val subtitle: String = "", val unread: Boolean = false)

/** A feed of loaded source data. Missing sources remain visible through the caller's coverage state. */
fun studyUpdates(snapshot: CampusSnapshot, now: Long): List<StudyUpdate> = buildList {
    snapshot.messages["inbox"].orEmpty().filterNot { it.read }.forEach { message ->
        add(StudyUpdate("message:${message.id}", StudyUpdateKind.MESSAGE, message.id, "", message.subject, message.timestamp, message.sender, true))
    }
    snapshot.announcements.filter { it.expires > now }.forEach { announcement ->
        add(StudyUpdate("news:${announcement.courseId}:${announcement.id}", StudyUpdateKind.ANNOUNCEMENT,
            announcement.id, announcement.courseId, announcement.title, announcement.published,
            snapshot.courses.firstOrNull { it.id == announcement.courseId }?.title.orEmpty(), announcement.id !in snapshot.preferences.readAnnouncements))
    }
    snapshot.courseMaterials.forEach { (courseId, materials) ->
        materials.recentFiles(now).forEach { file ->
            add(StudyUpdate("file:$courseId:${file.id}", StudyUpdateKind.FILE, file.id, courseId, file.name, file.activityAt,
                snapshot.courses.firstOrNull { it.id == courseId }?.title.orEmpty()))
        }
    }
    snapshot.courses.forEach { course ->
        snapshot.community["community:${course.id}:forum:"].orEmpty().filter { (it.unread ?: 0) > 0 }.forEach { discussion ->
            add(StudyUpdate("forum:${course.id}:${discussion.id}", StudyUpdateKind.FORUM, discussion.id, course.id,
                discussion.title, discussion.timestamp, course.title, true))
        }
        snapshot.community["community:${course.id}:blubber:"].orEmpty().filter { (it.unread ?: 0) > 0 }.forEach { thread ->
            add(StudyUpdate("blubber:${course.id}:${thread.id}", StudyUpdateKind.BLUBBER, thread.id, course.id,
                thread.title, thread.timestamp, course.title, true))
        }
    }
    val courseIds = snapshot.courses.map { it.id }.toSet()
    snapshot.activity.filter { it.courseId in courseIds && it.kind in setOf("schedule", "participants", "wiki", "courseware") && it.title.isNotBlank() }
        .forEach { activity ->
            add(StudyUpdate("activity:${activity.id}", StudyUpdateKind.ACTIVITY, activity.id, activity.courseId,
                activity.title, activity.timestamp, snapshot.courses.firstOrNull { it.id == activity.courseId }?.title.orEmpty()))
        }
}.distinctBy { it.key }.sortedWith(compareByDescending<StudyUpdate> { it.timestamp }.thenBy { it.key })

/** Each source establishes a baseline on its first successful check, avoiding historical alerts. */
data class UpdateNotificationDelta(val checkpoints: Map<String, Set<String>>, val newCount: Int)
fun updateNotificationDelta(previous: Map<String, Set<String>>, observed: Map<String, Set<String>>): UpdateNotificationDelta {
    var count = 0
    val result = previous.toMutableMap()
    observed.forEach { (source, items) ->
        val old = previous[source]
        if (old != null) count += (items - old).size
        result[source] = items
    }
    return UpdateNotificationDelta(result, count)
}

/** Only fresh successful sources participate; failures must never reset their baselines. */
fun notificationSources(snapshot: CampusSnapshot, errors: Map<String, FailureKind>, now: Long): Map<String, Set<String>> {
    val updates = studyUpdates(snapshot, now)
    val keys = buildList {
        add("messages:inbox")
        snapshot.courses.forEach { course ->
            add("news:${course.id}"); add("course-files:${course.id}")
            add("community:${course.id}:forum:")
            add("community:${course.id}:blubber:")
        }
    }
    return keys.filter { it !in errors && isFresh(snapshot.updatedAt[it], now, 5 * 60_000L) }.associateWith { source ->
        updates.filter { update ->
            when (update.kind) {
                StudyUpdateKind.MESSAGE -> source == "messages:inbox"
                StudyUpdateKind.ANNOUNCEMENT -> source == "news:${update.courseId}" && update.unread
                StudyUpdateKind.FILE -> source == "course-files:${update.courseId}"
                StudyUpdateKind.FORUM -> source == "community:${update.courseId}:forum:"
                StudyUpdateKind.BLUBBER -> source == "community:${update.courseId}:blubber:"
                StudyUpdateKind.ACTIVITY -> false
            }
        }.map { "${it.key}:${it.timestamp}" }.toSet()
    }
}
