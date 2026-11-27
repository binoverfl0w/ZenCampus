package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.Serializable

const val MATERIAL_ACTIVITY_WINDOW = 14L * 86_400_000

/** Complete metadata for the course's accessible Files area, never file contents or an activity log. */
@Serializable
data class CourseMaterials(val courseId: String, val folders: List<CampusFolder>, val documents: List<Document>) {
    private val folderMap by lazy { folders.associateBy { it.id } }
    private val documentMap by lazy { documents.associateBy { it.id } }
    /** A fresh tracker read can know a newer file version than a cached folder listing. */
    fun latestMetadata(document: Document): Document = documentMap[document.id]?.takeIf {
        document.courseId == courseId && it.courseId == courseId &&
            (it.modified > document.modified || (it.modified == document.modified && document.created == 0L && it.created > 0))
    } ?: document
    private fun ancestors(folderId: String): List<CampusFolder> {
        val result = mutableListOf<CampusFolder>()
        val visited = mutableSetOf<String>()
        var id = folderId
        while (id.isNotBlank() && visited.add(id) && result.size < 100) {
            val folder = folderMap[id] ?: break
            result += folder
            id = folder.parentId
        }
        return result
    }

    fun path(folderId: String): String = ancestors(folderId).asReversed()
        .filter { it.parentId.isNotBlank() }.joinToString(" / ") { it.name }

    /** Upload ownership and an upload-capable ancestor are evidence of a file, not proof of submission. */
    fun ownUploads(ownerId: String): List<Document> = if (ownerId.isBlank()) emptyList() else documents.filter { file ->
        file.courseId == courseId && file.ownerId == ownerId && ancestors(file.folderId).any { it.acceptsUploads }
    }.distinctBy { it.id }.sortedByDescending { it.activityAt }

    /** A nested upload changes the visible activity date of each of its ancestor folders. */
    private val fileActivity by lazy {
        val times = mutableMapOf<String, Long>()
        documents.filter { it.courseId == courseId && it.activityAt > 0 }.forEach { document ->
            ancestors(document.folderId).forEach { folder ->
                times[folder.id] = maxOf(times[folder.id] ?: 0, document.activityAt)
            }
        }
        times
    }
    fun latestFileActivity(folderId: String): Long? = fileActivity[folderId]
    val undatedCount: Int get() = documents.count { it.activityAt <= 0 }
    fun recentFiles(now: Long): List<Document> = documents.filter {
        it.courseId == courseId && it.activityAt in (now - MATERIAL_ACTIVITY_WINDOW)..now && it.activityAt > 0
    }.distinctBy { it.id }.sortedWith(compareByDescending<Document> { it.activityAt }.thenBy { it.name.lowercase() }.thenBy { it.id })
}
