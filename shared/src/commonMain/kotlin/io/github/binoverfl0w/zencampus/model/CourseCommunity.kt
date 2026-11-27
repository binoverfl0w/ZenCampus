package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.Serializable

@Serializable
data class CommunityItem(val id: String, val title: String, val body: String = "", val secondary: String = "",
    val timestamp: Long = 0, val unread: Int? = null, val children: List<CommunityItem> = emptyList(), val incomplete: Boolean = false,
    val canReply: Boolean = false, val parts: List<CoursewarePart> = emptyList(), val personId: String = "")

@Serializable
data class CoursewarePart(val kind: String, val title: String = "", val text: String = "", val target: String = "", val blockId: String = "")

@Serializable
data class ForumSubscription(val id: String, val discussionId: String)
@Serializable
data class ForumReaction(val id: String, val emoji: String, val userId: String)

@Serializable
data class CampusActivity(val id: String, val title: String, val body: String = "", val courseId: String = "",
    val kind: String = "", val timestamp: Long = 0)
