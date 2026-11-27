package io.github.binoverfl0w.zencampus.android

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.github.binoverfl0w.zencampus.data.MaterialStorage
import io.github.binoverfl0w.zencampus.data.MaterialWriter
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class MaterialPresentation(val file: File, val name: String, val mime: String)
internal fun materialMime(name: String): String? = MimeTypeMap.getSingleton()
    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))

class AndroidMaterials(private val context: Context) : MaterialStorage {
    private val root = File(context.filesDir, "materials").canonicalFile.apply { mkdirs() }
    private val exportMutex = Mutex()
    private fun scopeDirectory(scope: String) = File(root, digest(scope)).apply { mkdirs() }
    internal fun safeFile(path: String): File {
        val file = File(path).canonicalFile
        if (!file.path.startsWith(root.canonicalPath + File.separator)) throw CampusException(FailureKind.STORAGE)
        return file
    }
    internal fun presentation(path: String, mime: String, name: String): MaterialPresentation {
        val file = safeFile(path)
        if (!file.isFile) throw CampusException(FailureKind.STORAGE)
        val head = file.inputStream().use { input -> ByteArray(8).let { bytes -> bytes.take(input.read(bytes).coerceAtLeast(0)).toByteArray() } }
        val detected = when {
            head.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) -> "application/pdf"
            head.take(4) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte()) -> "image/png"
            head.size >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte() -> "image/jpeg"
            head.take(3).toByteArray().contentEquals("GIF".toByteArray()) -> "image/gif"
            else -> null
        }
        val normalized = mime.substringBefore(';').trim().lowercase(Locale.ROOT).let { if (it == "application/x-pdf") "application/pdf" else it }
        val generic = normalized in setOf("", "application/octet-stream", "binary/octet-stream", "application/download", "application/force-download", "unknown/unknown") || !normalized.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+"))
        val type = detected ?: materialMime(name) ?: if (generic) "application/octet-stream" else normalized
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type).orEmpty()
        var display = materialFileName(name, extension)
        if (detected != null && materialMime(display) != detected && extension.isNotBlank()) {
            display = materialFileName(if (display.endsWith(".bin", true)) display.dropLast(4) else display, "")
            display = materialFileName("$display.$extension")
        }
        return MaterialPresentation(file, display, type)
    }
    override suspend fun create(scope: String, document: Document): MaterialWriter = materialIO {
        val directory = File(scopeDirectory(scope), UUID.randomUUID().toString()).apply { if (!mkdirs()) throw CampusException(FailureKind.STORAGE) }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(document.mimeType.substringBefore(';').lowercase(Locale.ROOT)).orEmpty()
        val complete = File(directory, materialFileName(document.name, extension))
        val partial = File(directory, ".download.part")
        val stream = FileOutputStream(partial)
        object : MaterialWriter {
            var closed = false
            override suspend fun write(bytes: ByteArray) = materialIO { stream.write(bytes) }
            override suspend fun finish(): String = materialIO {
                stream.fd.sync(); stream.close(); closed = true
                if (!partial.renameTo(complete)) throw CampusException(FailureKind.STORAGE)
                complete.absolutePath
            }
            override suspend fun abort() = materialIO {
                if (!closed) { runCatching { stream.close() }; closed = true }
                directory.deleteRecursively(); Unit
            }
        }
    }
    override suspend fun prepare(path: String, document: Document): String = materialIO {
        val info = presentation(path, document.mimeType, document.name)
        if (info.file.name == info.name) return@materialIO path
        val accountDirectory = File(root, info.file.relativeTo(root).path.substringBefore(File.separator))
        val directory = File(accountDirectory, UUID.randomUUID().toString()).apply { if (!mkdirs()) throw CampusException(FailureKind.STORAGE) }
        val target = File(directory, info.name)
        try {
            // Keep the old cache path valid until the repository commits the replacement path.
            try { Os.link(info.file.absolutePath, target.absolutePath) }
            catch (_: android.system.ErrnoException) {
                info.file.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
            }
            target.absolutePath
        } catch (error: Exception) { directory.deleteRecursively(); throw CampusException(FailureKind.STORAGE, error) }
    }
    override suspend fun exists(path: String): Boolean = materialIO { safeFile(path).isFile }
    override suspend fun remove(path: String) = materialIO {
        val file = safeFile(path); file.delete()
        file.parentFile?.takeIf { it.parentFile?.parentFile == root && it.list()?.isEmpty() == true }?.delete()
        Unit
    }
    override suspend fun clear(scope: String) = materialIO { scopeDirectory(scope).deleteRecursively(); Unit }
    suspend fun clearAll() = materialIO { root.listFiles()?.forEach { it.deleteRecursively() } }
    suspend fun cleanup(scope: String, keep: Set<String>) = materialIO {
        val directory = scopeDirectory(scope)
        val canonicalKeep = keep.map { safeFile(it).path }.toSet()
        directory.walkBottomUp().forEach { file ->
            if (file.isFile && file.canonicalPath !in canonicalKeep) file.delete()
            else if (file != directory && file.isDirectory && file.list()?.isEmpty() == true) file.delete()
        }
    }
    override suspend fun open(path: String, mimeType: String, displayName: String) {
        val info = materialIO { presentation(path, mimeType, displayName) }
        withContext(Dispatchers.Main) {
            if (info.mime == "application/pdf") context.startActivity(Intent(context, DocumentActivity::class.java)
                .putExtra("path", info.file.absolutePath).putExtra("name", info.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else launch(viewIntent(info))
        }
    }
    internal fun viewIntent(info: MaterialPresentation): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", info.file, info.name)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.mime)
            .setClipDataCompat(info.name, uri)
    }
    private fun Intent.setClipDataCompat(name: String, uri: Uri): Intent = apply {
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun launch(intent: Intent, chooser: Boolean = false) {
        try { context.startActivity((if (chooser) Intent.createChooser(intent, null) else intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (error: android.content.ActivityNotFoundException) { throw CampusException(FailureKind.NO_VIEWER, error) }
    }
    override suspend fun openWith(path: String, mimeType: String, displayName: String) {
        val info = materialIO { presentation(path, mimeType, displayName) }
        withContext(Dispatchers.Main) { launch(viewIntent(info), chooser = true) }
    }
    override suspend fun share(path: String, mimeType: String, displayName: String) {
        val info = materialIO { presentation(path, mimeType, displayName) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", info.file, info.name)
        withContext(Dispatchers.Main) { launch(Intent(Intent.ACTION_SEND).setType(info.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .setClipDataCompat(info.name, uri), chooser = true) }
    }
    override suspend fun saveToDownloads(path: String, mimeType: String, displayName: String): String? {
        val info = materialIO { presentation(path, mimeType, displayName) }
        if (Build.VERSION.SDK_INT < 29) {
            withContext(Dispatchers.Main) { context.startActivity(Intent(context, SaveMaterialActivity::class.java)
                .putExtra("path", path).putExtra("name", info.name).putExtra("mime", info.mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return null
        }
        return materialIO {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, info.name); put(MediaStore.Downloads.MIME_TYPE, info.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ZenCampus")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw CampusException(FailureKind.STORAGE)
            var published = false
            try {
                copyMaterialToUri(context, info.file, uri)
                currentCoroutineContext().ensureActive()
                if (resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) != 1) throw CampusException(FailureKind.STORAGE)
                published = true
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: info.name
                "Downloads/ZenCampus/$name"
            } finally { if (!published) withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } } }
        }
    }
    override suspend fun saveToFolder(path: String, mimeType: String, displayName: String, destination: MaterialDestination): String = exportMutex.withLock {
        materialIO {
            val info = presentation(path, mimeType, displayName)
            val resolver = context.contentResolver
            val tree = Uri.parse(destination.location)
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val names = mutableSetOf<String>()
            val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?: throw CampusException(FailureKind.STORAGE)
            cursor.use { while (it.moveToNext()) it.getString(0)?.let(names::add) }
            val extension = info.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val stem = if (extension.isEmpty()) info.name else info.name.dropLast(extension.length)
            val name = (0..10_000).asSequence().map { if (it == 0) info.name else "$stem ($it)$extension" }
                .firstOrNull { it !in names } ?: throw CampusException(FailureKind.STORAGE)
            val uri = DocumentsContract.createDocument(resolver, parent, info.mime, name) ?: throw CampusException(FailureKind.STORAGE)
            var complete = false
            try {
                copyMaterialToUri(context, info.file, uri)
                currentCoroutineContext().ensureActive()
                val actualName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
                complete = true
                "${destination.label}/$actualName"
            } finally {
                if (!complete) withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            }
        }
    }
    override val syncsWithoutFolder: Boolean get() = Build.VERSION.SDK_INT >= 29

    override suspend fun copyInto(path: String, writer: MaterialWriter) {
        val input = materialIO { safeFile(path).takeIf { it.isFile }?.inputStream() ?: throw CampusException(FailureKind.STORAGE) }
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = materialIO { input.read(buffer) }
                if (read < 0) break
                writer.write(buffer.copyOf(read))
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { input.close() } } }
    }

    override suspend fun syncWriter(destination: MaterialDestination?, folders: List<String>, name: String, mimeType: String): MaterialWriter = materialIO {
        val clean = folders.map(::materialFolderName)
        val fileName = materialFileName(name)
        val mime = materialMime(fileName) ?: mimeType.substringBefore(';').trim().ifBlank { "application/octet-stream" }
        if (destination != null) treeSyncWriter(destination, clean, fileName, mime)
        else if (Build.VERSION.SDK_INT >= 29) downloadsSyncWriter(clean, fileName, mime)
        else throw CampusException(FailureKind.STORAGE)
    }

    /** A chosen folder: subfolders are created as needed; the file is written beside the old one, then swapped in. */
    private fun treeSyncWriter(destination: MaterialDestination, folders: List<String>, name: String, mime: String): MaterialWriter {
        val resolver = context.contentResolver
        val tree = Uri.parse(destination.location)
        var directoryId = DocumentsContract.getTreeDocumentId(tree)
        fun children(id: String): List<Triple<String, String, String>> =
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())) }
            } ?: throw CampusException(FailureKind.STORAGE)
        for (folder in folders) {
            val existing = children(directoryId).firstOrNull { it.second == folder && it.third == DocumentsContract.Document.MIME_TYPE_DIR }
            directoryId = existing?.first ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, directoryId),
                DocumentsContract.Document.MIME_TYPE_DIR, folder)?.let(DocumentsContract::getDocumentId) ?: throw CampusException(FailureKind.STORAGE)
        }
        val parentId = directoryId
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val temporary = DocumentsContract.createDocument(resolver, parent, "application/octet-stream",
            ".$name.${UUID.randomUUID().toString().take(8)}.zencampus-part") ?: throw CampusException(FailureKind.STORAGE)
        val output = resolver.openOutputStream(temporary, "w") ?: run {
            runCatching { DocumentsContract.deleteDocument(resolver, temporary) }; throw CampusException(FailureKind.STORAGE)
        }
        val label = (listOf(destination.label) + folders + name).joinToString("/")
        return object : MaterialWriter {
            var closed = false
            override suspend fun write(bytes: ByteArray) = materialIO { check(!closed); output.write(bytes) }
            override suspend fun finish(): String = materialIO {
                check(!closed); output.flush(); output.close(); closed = true
                children(parentId).filter { it.second == name && it.third != DocumentsContract.Document.MIME_TYPE_DIR }.forEach {
                    DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first))
                }
                val renamed = try { DocumentsContract.renameDocument(resolver, temporary, name) } catch (_: UnsupportedOperationException) { null }
                if (renamed == null) {
                    // Providers without rename: copy into a correctly named file, then drop the temporary one.
                    val final = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw CampusException(FailureKind.STORAGE)
                    resolver.openInputStream(temporary)?.use { input -> resolver.openOutputStream(final, "w")?.use { input.copyTo(it) } }
                        ?: throw CampusException(FailureKind.STORAGE)
                    runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
                }
                label
            }
            override suspend fun abort() = materialIO {
                if (!closed) { runCatching { output.close() }; closed = true }
                runCatching { DocumentsContract.deleteDocument(resolver, temporary) }; Unit
            }
        }
    }

    /** The default folder, Downloads/ZenCampus/<course>/…, through MediaStore; an older copy this app wrote is replaced. */
    @androidx.annotation.RequiresApi(29)
    private fun downloadsSyncWriter(folders: List<String>, name: String, mime: String): MaterialWriter {
        val resolver = context.contentResolver
        val relative = (listOf(Environment.DIRECTORY_DOWNLOADS, "ZenCampus") + folders).joinToString("/") + "/"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relative); put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw CampusException(FailureKind.STORAGE)
        val output = resolver.openOutputStream(uri, "w") ?: run { resolver.delete(uri, null, null); throw CampusException(FailureKind.STORAGE) }
        return object : MaterialWriter {
            var closed = false
            override suspend fun write(bytes: ByteArray) = materialIO { check(!closed); output.write(bytes) }
            override suspend fun finish(): String = materialIO {
                check(!closed); output.flush(); output.close(); closed = true
                val own = android.content.ContentUris.parseId(uri)
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(relative, name), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        // Copies from an earlier installation belong to it; those stay and the new one gets a numbered name.
                        if (id != own) runCatching { resolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
                    }
                }
                if (resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) != 1)
                    throw CampusException(FailureKind.STORAGE)
                val actual = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
                relative.trimEnd('/') + "/" + actual
            }
            override suspend fun abort() = materialIO {
                if (!closed) { runCatching { output.close() }; closed = true }
                runCatching { resolver.delete(uri, null, null) }; Unit
            }
        }
    }

    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal suspend fun copyMaterialToUri(context: Context, file: File, uri: Uri) = materialIO {
    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.flush()
        }
    } ?: throw CampusException(FailureKind.STORAGE)
}

/** Legacy .bin URIs still advertise their supplied, readable filename's type. */
class MaterialFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? = materialMime(uri.getQueryParameter("displayName") ?: uri.lastPathSegment.orEmpty()) ?: super.getType(uri)
}

private suspend fun <T> materialIO(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: CampusException) { throw error }
    catch (error: Exception) { throw CampusException(FailureKind.STORAGE, error) }
}
