package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.Serializable

/** One course file written to the device by a material sync, as last written. */
@Serializable data class SyncedMaterial(
    val documentId: String, val folders: List<String>, val name: String,
    val version: Long, val size: Long, val location: String = "",
)

/** What was synced for one course, and into which folder ("" is the default ZenCampus downloads folder). */
@Serializable data class MaterialSyncRecord(
    val courseId: String, val destination: String = "", val files: List<SyncedMaterial> = emptyList(),
    val lastSync: Long = 0, val automatic: Boolean = false,
)

/** Where one course file goes inside the course's sync folder. */
data class MaterialSyncTarget(val document: Document, val folders: List<String>, val name: String)

data class MaterialSyncPlan(
    val courseId: String,
    /** Every file that can be synced, in folder order. */
    val targets: List<MaterialSyncTarget>,
    /** New, changed, renamed or moved files that need writing. */
    val pending: List<MaterialSyncTarget>,
    /** Files that cannot be synced: files without download permission and oversized files. */
    val skipped: List<Document>,
) {
    val bytes: Long get() = pending.sumOf { it.document.size.coerceAtLeast(0) }
    val upToDate: Int get() = targets.size - pending.size
}

/** A folder name that is safe on Android and desktop file systems. */
fun materialFolderName(value: String): String {
    var clean = value.map { if (it < ' ' || it == '\u007f' || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim().trim('.').trim()
    while (clean.encodeToByteArray().size > 120) {
        clean = clean.dropLast(1)
        if (clean.lastOrNull()?.isHighSurrogate() == true) clean = clean.dropLast(1)
    }
    return clean.trim().ifBlank { "Folder" }
}

/**
 * The directories below a course's folder for files in [folderId], following the DigiCampus folder tree. The root
 * folder is the course itself, so only folders below it count; unknown folders map to the course folder.
 */
fun materialFolderPath(folders: List<CampusFolder>, folderId: String): List<String> {
    val byId = folders.associateBy { it.id }
    val names = mutableListOf<String>()
    var current = byId[folderId]
    val seen = mutableSetOf<String>()
    while (current != null && current.parentId.isNotBlank() && seen.add(current.id) && seen.size <= 100) {
        names += materialFolderName(current.name)
        current = byId[current.parentId]
    }
    return names.reversed()
}

/** The file name a course file gets in its course folder, as material sync names it. */
fun materialSyncName(document: Document): String =
    materialFileName(document.name, if (document.mimeType == "application/pdf") "pdf" else "")

/**
 * Maps every syncable file of a course to its DigiCampus folder path and finds what needs writing.
 * Names are made unique per folder (ignoring case, as many file systems do) so two files never overwrite each other.
 */
fun materialSyncPlan(materials: CourseMaterials, record: MaterialSyncRecord?, destination: String, maxBytes: Long): MaterialSyncPlan {
    val paths = mutableMapOf<String, List<String>>()
    fun path(folderId: String): List<String> = paths.getOrPut(folderId) { materialFolderPath(materials.folders, folderId) }
    val (syncable, skipped) = materials.documents.distinctBy { it.id }
        // externalUrl is the file's own address on DigiCampus (every file has one), not a sign of a web link.
        .partition { it.downloadable && it.size <= maxBytes }
    val targets = syncable
        .map { document -> MaterialSyncTarget(document, path(document.folderId), materialSyncName(document)) }
        .groupBy { (it.folders + it.name).joinToString("/").lowercase() }
        .flatMap { (_, same) ->
            same.sortedWith(compareBy<MaterialSyncTarget> { it.document.created }.thenBy { it.document.id }).mapIndexed { index, target ->
                if (index == 0) target else {
                    val extension = target.name.substringAfterLast('.', "").let { if (it.isEmpty() || it == target.name) "" else ".$it" }
                    target.copy(name = "${target.name.dropLast(extension.length)} (${index + 1})$extension")
                }
            }
        }
        .sortedWith(compareBy<MaterialSyncTarget> { it.folders.joinToString("/").lowercase() }.thenBy { it.name.lowercase() })
    val previous = record?.takeIf { it.destination == destination }?.files.orEmpty().associateBy { it.documentId }
    val pending = targets.filter { target ->
        val saved = previous[target.document.id]
        saved == null || saved.version < target.document.modified || saved.folders != target.folders || saved.name != target.name
    }
    return MaterialSyncPlan(materials.courseId, targets, pending, skipped)
}

/** A running or finished sync, for the progress line and the result summary. */
data class MaterialSyncProgress(
    val courseIds: List<String>, val total: Int, val done: Int = 0, val bytesTotal: Long = 0, val bytesDone: Long = 0,
    val running: Boolean = false, val current: String = "", val failed: Map<String, FailureKind> = emptyMap(),
    val cancelled: Boolean = false, val stopped: FailureKind? = null, val finishedAt: Long? = null,
)
