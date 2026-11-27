package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import platform.Foundation.*
import platform.posix.*
import platform.posix.open as posixOpen
import platform.posix.write as posixWrite
import platform.posix.close as posixClose
import platform.posix.read as posixRead

/** Private, account-scoped cache; incomplete downloads are never returned to the repository. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosMaterials(private val platform: IosPlatform) : MaterialStorage {
    private val files = NSFileManager.defaultManager
    private val root: String = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/ZenCampusMaterials"
    private fun directory(scope: String): String {
        val bytes = scope.encodeToByteArray()
        require(bytes.size <= 256)
        return root + "/" + bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun checked(path: String): String {
        // All paths returned by this adapter consist of generated directories and a sanitized filename.
        if (!path.startsWith("$root/") || path.removePrefix("$root/").split('/').any { it.isEmpty() || it == "." || it == ".." })
            throw CampusException(FailureKind.STORAGE)
        return path
    }
    private fun mkdir(path: String) {
        if (!files.createDirectoryAtPath(path, true, null, null)) throw CampusException(FailureKind.STORAGE)
    }
    override suspend fun create(scope: String, document: Document): MaterialWriter {
        val folder = directory(scope) + "/" + NSUUID().UUIDString
        mkdir(folder)
        val target = "$folder/${materialFileName(document.name, if (document.mimeType == "application/pdf") "pdf" else "")}"
        return writer(folder, target, removeFolderOnAbort = true)
    }

    override val syncsWithoutFolder: Boolean get() = true
    override suspend fun syncWriter(destination: MaterialDestination?, folders: List<String>, name: String, mimeType: String): MaterialWriter {
        if (destination != null) {
            val lease = platform.beginMaterialFolder(destination.location) ?: throw CampusException(FailureKind.STORAGE)
            val leaseId = lease.substringBefore('\n')
            try {
                val cache = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
                val staging = "$cache/ZenCampusSyncParts/${NSUUID().UUIDString}"
                mkdir(staging)
                val safeFolders = folders.map(::materialFolderName)
                val safeName = materialFileName(name)
                return writer(staging, "$staging/$safeName", removeFolderOnAbort = true,
                    release = { platform.endMaterialFolder(leaseId) },
                    commit = { staged ->
                        val location = platform.commitMaterialFolder(leaseId, staged, safeFolders, safeName)
                            ?: throw CampusException(FailureKind.STORAGE)
                        files.removeItemAtPath(staging, null)
                        location
                    })
            } catch (e: Throwable) {
                platform.endMaterialFolder(leaseId)
                throw e
            }
        }
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val root = "$documents/ZenCampus"
        val folder = (listOf(root) + folders.map(::materialFolderName)).joinToString("/")
        val canonicalDocuments = (documents as NSString).stringByResolvingSymlinksInPath
        if ((root as NSString).stringByResolvingSymlinksInPath != "$canonicalDocuments/ZenCampus") throw CampusException(FailureKind.STORAGE)
        val canonicalRoot = (root as NSString).stringByResolvingSymlinksInPath
        fun checkFolder() {
            val canonicalFolder = (folder as NSString).stringByResolvingSymlinksInPath
            if (canonicalFolder != canonicalRoot && !canonicalFolder.startsWith("$canonicalRoot/")) throw CampusException(FailureKind.STORAGE)
        }
        checkFolder(); mkdir(folder); checkFolder()
        return writer(folder, "$folder/${materialFileName(name)}", removeFolderOnAbort = false)
    }

    private fun writer(folder: String, target: String, removeFolderOnAbort: Boolean,
                       release: () -> Unit = {}, commit: (String) -> String = { it }): MaterialWriter {
        val temporary = "$folder/.${NSUUID().UUIDString}.download.part"
        val fd = posixOpen(temporary, O_WRONLY or O_CREAT or O_EXCL, S_IRUSR or S_IWUSR)
        if (fd < 0) {
            if (removeFolderOnAbort) files.removeItemAtPath(folder, null)
            throw CampusException(FailureKind.STORAGE)
        }
        return object : MaterialWriter {
            private var closed = false
            private var released = false
            private fun releaseOnce() { if (!released) { released = true; release() } }
            override suspend fun write(bytes: ByteArray) = withContext(storageDispatcher) {
                check(!closed)
                bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = posixWrite(fd, pinned.addressOf(offset), (bytes.size - offset).toULong())
                        if (count < 0 && errno == EINTR) continue
                        if (count <= 0) throw CampusException(FailureKind.STORAGE)
                        offset += count.toInt()
                    }
                }
            }
            override suspend fun finish(): String = withContext(storageDispatcher) {
                check(!closed)
                val synced = fsync(fd) == 0
                val closedOk = posixClose(fd) == 0
                closed = true
                try {
                    if (!synced || !closedOk || rename(temporary, target) != 0) throw CampusException(FailureKind.STORAGE)
                    commit(target)
                } finally { releaseOnce() }
            }
            override suspend fun abort() = withContext(storageDispatcher) {
                if (!closed) { posixClose(fd); closed = true }
                if (removeFolderOnAbort) files.removeItemAtPath(folder, null) else files.removeItemAtPath(temporary, null)
                releaseOnce()
            }
        }
    }

    override suspend fun exists(path: String): Boolean = files.fileExistsAtPath(checked(path))
    override suspend fun remove(path: String) { files.removeItemAtPath(checked(path), null) }
    override suspend fun clear(scope: String) { files.removeItemAtPath(directory(scope), null) }
    override suspend fun open(path: String, mimeType: String, displayName: String) {
        val safe = checked(path)
        if (!files.fileExistsAtPath(safe) || !withContext(Dispatchers.Main) { platform.openMaterial(safe) })
            throw CampusException(FailureKind.NO_VIEWER)
    }
    override suspend fun saveToDownloads(path: String, mimeType: String, displayName: String): String? {
        val safe = checked(path)
        if (!files.fileExistsAtPath(safe)) throw CampusException(FailureKind.STORAGE)
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                platform.exportMaterial(safe) { destination ->
                    if (continuation.isActive) continuation.resume(destination)
                }
            }
        }
    }
    override suspend fun copyInto(path: String, writer: MaterialWriter) {
        val source = posixOpen(checked(path), O_RDONLY)
        if (source < 0) throw CampusException(FailureKind.STORAGE)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = withContext(storageDispatcher) {
                    var n: Long
                    do { n = buffer.usePinned { posixRead(source, it.addressOf(0), buffer.size.toULong()) } } while (n < 0 && errno == EINTR)
                    if (n < 0) throw CampusException(FailureKind.STORAGE)
                    n
                }
                if (count == 0L) break
                writer.write(buffer.copyOf(count.toInt()))
            }
        } finally { posixClose(source) }
    }
    override suspend fun saveToFolder(path: String, mimeType: String, displayName: String, destination: MaterialDestination): String {
        val output = syncWriter(destination, emptyList(), displayName, mimeType)
        try { copyInto(path, output); return output.finish() }
        catch (e: Throwable) { withContext(NonCancellable) { output.abort() }; throw e }
    }
    override suspend fun share(path: String, mimeType: String, displayName: String) {
        val safe = checked(path)
        if (!files.fileExistsAtPath(safe) || !withContext(Dispatchers.Main) { platform.shareMaterial(safe) })
            throw CampusException(FailureKind.NO_VIEWER)
    }
}
