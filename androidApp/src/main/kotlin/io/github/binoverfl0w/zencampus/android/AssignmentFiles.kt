package io.github.binoverfl0w.zencampus.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.binoverfl0w.zencampus.model.*
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun readAssignmentFile(context: Context, uri: Uri, courseId: String, folderId: String): UploadDraft {
    val resolver = context.contentResolver
    var name = "assignment"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) > MAX_UPLOAD_BYTES) throw CampusException(FailureKind.STORAGE)
        }
    }
    // Some apps share plain file URIs without a display name; their last path segment is the file name.
    if (name == "assignment") uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { name = it }
    name = name.map { if (it < ' ' || it in "\"\\/") '_' else it }.joinToString("").trim().take(180).ifBlank { "assignment" }
    val bytes = resolver.openInputStream(uri)?.use(::readBoundedAssignment) ?: throw CampusException(FailureKind.STORAGE)
    if (bytes.isEmpty()) throw CampusException(FailureKind.STORAGE)
    return UploadDraft(courseId, folderId, name, resolver.getType(uri) ?: "application/octet-stream", bytes)
}

internal fun readBoundedAssignment(stream: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        if (output.size().toLong() + read > MAX_UPLOAD_BYTES) throw CampusException(FailureKind.STORAGE)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
