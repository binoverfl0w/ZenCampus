package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.model.CampusException
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.memcpy

internal data class IosIncomingFile(val path: String, val name: String, val mime: String)

/** Read while the document picker's security-scoped URL is accessible. */
@OptIn(ExperimentalForeignApi::class)
internal fun readIosUpload(path: String, name: String, mime: String, course: String, folder: String): UploadDraft {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: throw CampusException(FailureKind.STORAGE)
    val size = (attributes[NSFileSize] as? NSNumber)?.unsignedLongLongValue ?: throw CampusException(FailureKind.STORAGE)
    if (size == 0uL || size > MAX_UPLOAD_BYTES.toULong()) throw CampusException(FailureKind.STORAGE)
    val data = NSData.dataWithContentsOfFile(path) ?: throw CampusException(FailureKind.STORAGE)
    if (data.length == 0uL || data.length > MAX_UPLOAD_BYTES.toULong()) throw CampusException(FailureKind.STORAGE)
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return UploadDraft(course, folder, materialFileName(name.ifBlank { "assignment" }).take(180),
        mime.ifBlank { "application/octet-stream" }, bytes)
}
