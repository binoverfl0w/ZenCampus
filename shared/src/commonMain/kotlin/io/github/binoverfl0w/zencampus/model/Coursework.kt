package io.github.binoverfl0w.zencampus.model

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable

/** Published Courseware assignment state. Content progress and submission are independent. */
@Serializable
data class CourseworkTask(
    val id: String,
    val courseId: String,
    val title: String,
    val due: Long? = null,
    val submitted: Boolean,
    val progress: Double? = null,
    val renewal: String = "",
    val originalDue: Long? = null,
    val canSolve: Boolean = false,
) {
    val sourceUrl: String get() = "$DIGICAMPUS_ORIGIN/dispatch.php/course/courseware/tasks?cid=${courseId.encodeURLParameter()}"
    fun overdue(now: Long): Boolean = !submitted && due?.let { it < now } == true
}

@Serializable
data class CourseworkDetail(
    val task: CourseworkTask,
    val sections: List<CourseworkSection> = emptyList(),
    val hasInteractiveContent: Boolean = false,
    val feedback: String = "",
)

@Serializable
data class CourseworkSection(val title: String = "", val text: String, val rating: Int? = null)

@Serializable
data class PeerReview(val id: String, val reviewer: Boolean, val submitter: Boolean,
    val available: Boolean, val sections: List<CourseworkSection> = emptyList(), val unsupported: Boolean = false)

fun List<CourseworkTask>.orderedCoursework(): List<CourseworkTask> =
    sortedWith(compareBy<CourseworkTask> { it.submitted }.thenBy { it.due ?: Long.MAX_VALUE }.thenBy { it.title })
