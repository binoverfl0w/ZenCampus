package io.github.binoverfl0w.zencampus.model

/**
 * Every cache and sync key in one place. The data layer stores and refreshes under these keys and screens look up
 * loading, stale and error state with the same functions, so a renamed key cannot silently break either side.
 */
object CacheKeys {
    const val SEMESTERS = "semesters"
    const val PROFILE = "profile"
    const val PREFERENCES = "preferences"
    const val DOWNLOADS = "downloads"
    const val ACTIVITY = "activity"
    const val CONTACTS = "contacts"
    const val STUDY_GROUPS = "studygroups"
    const val CONSULTATION_BOOKINGS = "consultation-bookings"
    const val COURSEWARE_BOOKMARKS = "courseware-bookmarks"
    const val MATERIAL_SYNC = "material-sync"
    const val STUDY_SYNC = "study-sync"
    const val CALENDAR_EXPORT = "calendar-export"

    fun courses(semesterId: String) = "courses:$semesterId"
    fun course(courseId: String) = "course:$courseId"
    fun events(courseId: String) = "events:$courseId"
    fun news(courseId: String) = "news:$courseId"
    fun newsComments(newsId: String) = "news-comments:$newsId"
    fun tasks(courseId: String) = "tasks:$courseId"
    fun task(courseId: String, taskId: String) = "task:$courseId:$taskId"
    fun uploadFolders(courseId: String) = "upload-folders:$courseId"
    fun tools(courseId: String) = "tools:$courseId"
    fun vips(courseId: String) = "vips:$courseId"
    fun vipsResults(courseId: String) = "vips-results:$courseId"
    fun courseFiles(courseId: String) = "course-files:$courseId"
    fun folder(courseId: String, parentId: String?) = "folder:$courseId:${parentId ?: "root"}"
    fun download(documentId: String) = "download:$documentId"
    fun messages(sent: Boolean) = "messages:${if (sent) "sent" else "inbox"}"
    fun community(courseId: String, section: String, itemId: String = "") = "community:$courseId:$section:$itemId"
    fun forumSubscriptions(courseId: String) = "forum-subs:$courseId"
    fun forumReactions(courseId: String, postId: String) = "forum-reactions:$courseId:$postId"
    fun peerReviews(courseId: String, taskId: String) = "peer-reviews:$courseId:$taskId"
    fun coursewareProgress(courseId: String, unitId: String) = "courseware-progress:$courseId:$unitId"
    fun holidays(year: Int) = "holidays:$year"
    fun consultationRange(rangeType: String, rangeId: String) = "$rangeType:$rangeId"
    fun consultations(rangeType: String, rangeId: String) = "consultations:${consultationRange(rangeType, rangeId)}"
}
