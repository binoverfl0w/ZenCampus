package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The range endpoint returns the root and every visible descendant, not just its children. */
internal class MaterialFolderTree(resources: List<JsonObject>, courseId: String) {
    private val nodes = resources.filter {
        ((it["attributes"] as? JsonObject)?.get("is-visible") as? JsonPrimitive)?.booleanOrNull != false
    }.map { JsonApiMapper.folder(it, courseId, "") }.distinctBy { it.id }
    val root: CampusFolder? = nodes.filter { it.parentId.isBlank() }.let { roots ->
        if (nodes.isNotEmpty() && roots.size != 1) throw CampusException(FailureKind.INVALID_RESPONSE)
        roots.singleOrNull()
    }
    private val children = nodes.groupBy { it.parentId }
    private val assessed = mutableMapOf<String, CampusFolder>()

    init { root?.let { assess(it, emptySet()) } }

    fun submissionFolders(): List<CampusFolder> = assessed.values.filter { it.submissionFolder }.sortedBy { it.name }
    fun allFolders(): List<CampusFolder> = assessed.values.toList()

    fun folder(id: String): CampusFolder = assessed[id] ?: throw CampusException(FailureKind.NOT_FOUND)
    fun childrenOf(parentId: String?): List<CampusFolder> {
        val id = parentId ?: root?.id ?: return emptyList()
        return children[id].orEmpty().map { folder(it.id) }
    }

    private fun assess(folder: CampusFolder, ancestors: Set<String>): CampusFolder {
        if (folder.id in ancestors || ancestors.size > 100) throw CampusException(FailureKind.INVALID_RESPONSE)
        val descendants = children[folder.id].orEmpty().map { assess(it, ancestors + folder.id) }
        val empty = when {
            !folder.readable -> null
            folder.fileCount == null -> null
            folder.fileCount > 0 || descendants.any { it.empty == false } -> false
            descendants.any { it.empty == null } -> null
            else -> true
        }
        return folder.copy(empty = empty).also { assessed[folder.id] = it }
    }
}
