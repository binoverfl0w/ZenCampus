package io.github.binoverfl0w.zencampus.data.study

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
 * Forum, Blubber and Courseware reading, bookmarks and progress.
 *
 * Part of [CampusRepository]: each operation runs with the repository as receiver (`with(repo)`), so the shared
 * cache engine, provider, store and other areas are reached exactly as from the repository itself.
 */
internal class CommunityRepository(private val repo: CampusRepository) {
    suspend fun refreshCoursewareBookmarks(force: Boolean = false) = with(repo) {
        refresh("courseware-bookmarks", 5 * 60_000L, force) { provider.coursewareBookmarks(account.id) }
    }

    suspend fun refreshCoursewareProgress(courseId: String, unitId: String, force: Boolean = false) { with(repo) {
        if (courseId !in snapshot.value.courses.map { it.id } || unitId.isBlank()) return
        refresh(CacheKeys.coursewareProgress(courseId, unitId), 5 * 60_000L, force) { provider.coursewareProgress(unitId) }
    } }

    suspend fun setCoursewareBookmark(courseId: String, elementId: String, selected: Boolean, requestId: String) { with(repo) {
        if (requestId.isBlank() || elementId.isBlank() || courseId !in snapshot.value.courses.map { it.id }) return
        val key = "write:bookmark:$courseId:$elementId"
        refreshCoursewareBookmarks(true)
        if ("courseware-bookmarks" in _errors.value) return
        val current = stored<List<String>>("courseware-bookmarks").orEmpty()
        if ((elementId in current) == selected) return
        studentWrite(key, courseId, elementId, "", requestId, action = { provider.setCoursewareBookmark(account.id, elementId, selected) }) { null }
        if (stored<CourseworkWrite>(key)?.status == WriteStatus.CONFIRMED) refreshCoursewareBookmarks(true)
    } }

    suspend fun refreshForumSubscriptions(courseId: String, force: Boolean = false) { with(repo) {
        if (courseId !in snapshot.value.courses.map { it.id }) return
        refresh(CacheKeys.forumSubscriptions(courseId), 5 * 60_000L, force) { provider.forumSubscriptions(courseId) }
    } }

    suspend fun refreshForumReactions(courseId: String, postId: String, force: Boolean = false) { with(repo) {
        if (courseId !in snapshot.value.courses.map { it.id }) return
        courseworkSlots.withPermit { refresh(CacheKeys.forumReactions(courseId, postId), 5 * 60_000L, force) { provider.forumReactions(postId) } }
    } }

    suspend fun setForumSubscription(courseId: String, discussionId: String, subscribed: Boolean, requestId: String) { with(repo) {
        if (requestId.isBlank() || snapshot.value.community["community:$courseId:forum:"]?.none { it.id == discussionId } != false) return
        val key = "write:forum-sub:$courseId:$discussionId"
        refreshForumSubscriptions(courseId, true)
        if (CacheKeys.forumSubscriptions(courseId) in _errors.value) return
        val current = stored<List<ForumSubscription>>(CacheKeys.forumSubscriptions(courseId)).orEmpty().firstOrNull { it.discussionId == discussionId }
        if ((current != null) == subscribed) return
        studentWrite(key, courseId, discussionId, "", requestId,
            action = { provider.changeForumSubscription(courseId, discussionId, current?.id) }) { null }
        if (stored<CourseworkWrite>(key)?.status == WriteStatus.CONFIRMED) refreshForumSubscriptions(courseId, true)
    } }

    suspend fun setForumReaction(courseId: String, discussionId: String, postId: String, selected: Boolean, requestId: String) { with(repo) {
        if (requestId.isBlank() || snapshot.value.community["community:$courseId:forum:$discussionId"]?.none { it.id == postId } != false) return
        val key = "write:forum-react:$courseId:$postId"
        refreshForumReactions(courseId, postId, true)
        if (CacheKeys.forumReactions(courseId, postId) in _errors.value) return
        val current = stored<List<ForumReaction>>(CacheKeys.forumReactions(courseId, postId)).orEmpty().firstOrNull { it.emoji == "👍" && it.userId == account.id }
        if ((current != null) == selected) return
        studentWrite(key, courseId, postId, "", requestId, action = { provider.changeForumReaction(postId, current?.id) }) { null }
        if (stored<CourseworkWrite>(key)?.status == WriteStatus.CONFIRMED) refreshForumReactions(courseId, postId, true)
    } }

    suspend fun postForumReply(courseId: String, discussionId: String, body: String, requestId: String) { with(repo) {
        if (requestId.isBlank() || snapshot.value.community["community:$courseId:forum:"]?.none { it.id == discussionId } != false) return
        val key = "write:forum:$courseId:$discussionId"
        studentWrite(key, courseId, discussionId, "", requestId, action = { provider.postForumReply(discussionId, body) }) { null }
        if (stored<CourseworkWrite>(key)?.status == WriteStatus.CONFIRMED) {
            refreshCommunity(courseId, "forum", discussionId, force = true)
            refreshCommunity(courseId, "forum", force = true)
            refreshForumSubscriptions(courseId, true)
        }
    } }

    suspend fun postBlubberReply(courseId: String, threadId: String, body: String, requestId: String) { with(repo) {
        if (requestId.isBlank() || snapshot.value.community["community:$courseId:blubber:"]?.none { it.id == threadId && it.canReply } != false) return
        val key = "write:blubber:$courseId:$threadId"
        studentWrite(key, courseId, threadId, "", requestId, action = { provider.postBlubberReply(threadId, body) }) { null }
        if (stored<CourseworkWrite>(key)?.status == WriteStatus.CONFIRMED) {
            refreshCommunity(courseId, "blubber", threadId, force = true)
            refreshCommunity(courseId, "blubber", force = true)
        }
    } }

    suspend fun postBlubberThread(courseId: String, body: String, requestId: String) { with(repo) {
        if (requestId.isBlank() || courseId !in snapshot.value.courses.map { it.id } ||
            snapshot.value.community["community:$courseId:blubber:"] == null) return
        val key = "write:blubber-new:$courseId"
        studentWrite(key, courseId, courseId, "", requestId, action = { provider.postBlubberThread(courseId, body) }) { null }
        if (stored<CourseworkWrite>(key)?.status == WriteStatus.CONFIRMED) refreshCommunity(courseId, "blubber", force = true)
    } }

    suspend fun refreshCommunity(courseId: String, section: String, itemId: String = "", force: Boolean = false) { with(repo) {
        if (section !in setOf("forum", "blubber", "people", "groups", "group-members", "wiki", "courseware") || courseId !in snapshot.value.courses.map { it.id }) return
        if (section in setOf("people", "groups", "wiki") && itemId.isNotBlank()) return
        if (section == "group-members" && itemId.isBlank()) return
        refresh(CacheKeys.community(courseId, section, itemId), 5 * 60_000L, force) { provider.community(courseId, section, itemId) }
    } }
}
