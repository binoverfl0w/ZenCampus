package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.swing.JOptionPane

internal data class MaterialPresentation(val file: File, val name: String, val mime: String)

internal class DesktopMaterials(private val paths: DesktopPaths) : MaterialStorage {
    var onPdf: (MaterialPresentation) -> Unit = {}
    var onNotice: (String) -> Unit = {}
    private val root = realFile(paths.materials)
    private fun directory(scope: String) = privateDirectory(File(root, digest(scope)))
    internal fun safeFile(path: String): File = realFile(File(path)).also {
        if (!it.path.startsWith(root.path + File.separator)) throw CampusException(FailureKind.STORAGE)
    }
    internal fun presentation(path: String, mime: String, name: String): MaterialPresentation {
        val file = safeFile(path)
        if (!file.isFile) throw CampusException(FailureKind.STORAGE)
        val head = file.inputStream().use { it.readNBytes(8) }
        val detected = when {
            head.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) -> "application/pdf"
            head.take(4).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) -> "image/png"
            head.size >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() -> "image/jpeg"
            else -> null
        }
        val type = detected ?: Files.probeContentType(File(name).toPath()) ?: mime.substringBefore(';').ifBlank { "application/octet-stream" }
        val extension = when(type) { "application/pdf" -> "pdf"; "image/png" -> "png"; "image/jpeg" -> "jpg"; else -> "" }
        var display = materialFileName(name.ifBlank { file.name }, extension)
        if (extension.isNotEmpty() && !display.endsWith(".$extension", true))
            display = materialFileName((if (display.endsWith(".bin", true)) display.dropLast(4) else display) + ".$extension")
        return MaterialPresentation(file, display, type)
    }
    override suspend fun create(scope: String, document: Document): MaterialWriter = storageIO {
        val folder = privateDirectory(File(directory(scope), UUID.randomUUID().toString()))
        val part = File(folder, ".download.part")
        val complete = File(folder, materialFileName(document.name, if (document.mimeType == "application/pdf") "pdf" else ""))
        val output = FileOutputStream(part)
        object : MaterialWriter {
            var closed = false
            override suspend fun write(bytes: ByteArray) = storageIO { check(!closed); output.write(bytes) }
            override suspend fun finish(): String = storageIO {
                check(!closed); output.fd.sync(); output.close(); closed = true
                Files.move(part.toPath(), complete.toPath(), StandardCopyOption.ATOMIC_MOVE)
                complete.absolutePath
            }
            override suspend fun abort() = storageIO {
                if (!closed) { output.close(); closed = true }
                folder.deleteRecursively(); Unit
            }
        }
    }
    override suspend fun prepare(path: String, document: Document): String = storageIO {
        val info = presentation(path, document.mimeType, document.name)
        if (info.file.name == info.name) return@storageIO path
        val account = info.file.relativeTo(root).path.substringBefore(File.separator)
        val target = File(privateDirectory(File(File(root, account), UUID.randomUUID().toString())), info.name)
        try { Files.createLink(target.toPath(), info.file.toPath()) }
        catch (_: Exception) { Files.copy(info.file.toPath(), target.toPath()) }
        target.absolutePath
    }
    override suspend fun exists(path: String) = storageIO { safeFile(path).isFile }
    override suspend fun remove(path: String) = storageIO {
        val file = safeFile(path); Files.deleteIfExists(file.toPath())
        file.parentFile.takeIf { it.parentFile?.parentFile == root && it.list()?.isEmpty() == true }?.delete(); Unit
    }
    override suspend fun clear(scope: String) = storageIO { directory(scope).deleteRecursively(); Unit }
    suspend fun clearAll() = storageIO { root.listFiles()?.forEach { it.deleteRecursively() } }
    suspend fun cleanup(scope: String, keep: Set<String>) = storageIO {
        val dir = directory(scope)
        val canonicalKeep = keep.map { safeFile(it).path }.toSet()
        dir.walkBottomUp().forEach {
            if (it.isFile && it.canonicalPath !in canonicalKeep) it.delete()
            else if (it != dir && it.isDirectory && it.list()?.isEmpty() == true) it.delete()
        }
    }
    override suspend fun open(path: String, mimeType: String, displayName: String) {
        val info = storageIO { presentation(path, mimeType, displayName) }
        if (info.mime == "application/pdf") withContext(Dispatchers.Main) { onPdf(info) }
        else externalOpen(info.file)
    }
    override suspend fun openWith(path: String, mimeType: String, displayName: String) {
        val info = storageIO { presentation(path, mimeType, displayName) }
        // Windows has its own "Open with" chooser for a file.
        if (isWindows) return withContext(Dispatchers.IO) {
            try { ProcessBuilder("rundll32", "shell32.dll,OpenAs_RunDLL", info.file.absolutePath).start(); Unit }
            catch (e: Exception) { throw CampusException(FailureKind.NO_VIEWER, e) }
        }
        val apps = withContext(Dispatchers.IO) { installedReaders(info.mime) }
        if (apps.isEmpty()) { externalOpen(info.file); return }
        val selection = withContext(Dispatchers.Main) {
            JOptionPane.showInputDialog(null, "Open ${info.name} with", "Open with…", JOptionPane.PLAIN_MESSAGE,
                null, apps.toTypedArray(), apps.first()) as? ReaderApp
        } ?: return
        withContext(Dispatchers.IO) {
            try { ProcessBuilder("gtk-launch", selection.id, info.file.toURI().toString()).start() }
            catch (e: Exception) { throw CampusException(FailureKind.NO_VIEWER, e) }
        }
    }
    override suspend fun share(path: String, mimeType: String, displayName: String) {
        val info = storageIO { presentation(path, mimeType, displayName) }
        withContext(Dispatchers.Main) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
                override fun getTransferData(flavor: DataFlavor): Any {
                    if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                    return listOf(info.file)
                }
            }, null)
            onNotice("File copied. Paste it into your email or chat app.")
        }
    }
    override suspend fun saveToDownloads(path: String, mimeType: String, displayName: String): String =
        saveInDirectory(path, mimeType, displayName, paths.downloads)
    override suspend fun saveToFolder(path: String, mimeType: String, displayName: String, destination: MaterialDestination): String =
        saveInDirectory(path, mimeType, displayName, File(destination.location))

    override val syncsWithoutFolder: Boolean get() = true

    override suspend fun copyInto(path: String, writer: MaterialWriter) {
        val input = storageIO { safeFile(path).takeIf { it.isFile }?.inputStream() ?: throw CampusException(FailureKind.STORAGE) }
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = storageIO { input.read(buffer) }
                if (read < 0) break
                writer.write(buffer.copyOf(read))
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { input.close() } } }
    }

    override suspend fun syncWriter(destination: MaterialDestination?, folders: List<String>, name: String, mimeType: String): MaterialWriter = storageIO {
        val base = realFile(destination?.let { File(it.location) } ?: paths.downloads)
        if (!base.isDirectory) throw CampusException(FailureKind.STORAGE)
        var directory = base
        for (folder in folders) {
            val clean = materialFolderName(folder)
            directory = File(directory, clean)
        }
        val target = realFile(File(directory, materialFileName(name)))
        // Names are sanitized; this still guarantees nothing is written outside the chosen folder.
        if (!target.path.startsWith(base.path + File.separator)) throw CampusException(FailureKind.STORAGE)
        target.parentFile.mkdirs()
        if (!target.parentFile.isDirectory) throw CampusException(FailureKind.STORAGE)
        val part = File(target.parentFile, ".${target.name}.${UUID.randomUUID().toString().take(8)}.zencampus-part")
        val output = FileOutputStream(part)
        object : MaterialWriter {
            var closed = false
            override suspend fun write(bytes: ByteArray) = storageIO { check(!closed); output.write(bytes) }
            override suspend fun finish(): String = storageIO {
                check(!closed); output.fd.sync(); output.close(); closed = true
                try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                target.absolutePath
            }
            override suspend fun abort() = storageIO {
                if (!closed) { output.close(); closed = true }
                part.delete(); Unit
            }
        }
    }

    private suspend fun saveInDirectory(path: String, mimeType: String, displayName: String, directory: File): String = storageIO {
        val info = presentation(path, mimeType, displayName)
        if (!directory.isDirectory) throw CampusException(FailureKind.STORAGE)
        // CREATE_NEW avoids overwriting an existing export, including with simultaneous saves.
        for (number in 0..10_000) {
            currentCoroutineContext().ensureActive()
            val extension = info.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val stem = if (extension.isEmpty()) info.name else info.name.dropLast(extension.length)
            val target = File(directory, if (number == 0) info.name else "$stem ($number)$extension")
            try {
                Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                    try { info.file.inputStream().use { it.copyTo(output) } }
                    catch (e: Exception) { target.delete(); throw e }
                }
                return@storageIO target.absolutePath
            } catch (_: java.nio.file.FileAlreadyExistsException) { }
        }
        throw CampusException(FailureKind.STORAGE)
    }
    suspend fun saveAs(info: MaterialPresentation) {
        val target = withContext(Dispatchers.Main) {
            FileDialog(null as Frame?, "Save a copy", FileDialog.SAVE).run {
                file = info.name; directory = paths.downloads.absolutePath; isVisible = true
                val result = file?.let { File(directory, it) }; dispose(); result
            }
        } ?: return
        storageIO { if (target.canonicalFile != info.file.canonicalFile) Files.copy(info.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

/**
 * The path with every symbolic link resolved, also for a file that does not exist yet (through its nearest existing
 * folder). `canonicalFile` resolves links on Linux and macOS but not on Windows, where a link inside a folder could
 * otherwise lead outside it.
 */
internal fun realFile(file: File): File {
    val normal = file.canonicalFile
    var existing: File? = normal
    val missing = ArrayDeque<String>()
    while (existing != null && !existing.exists()) { missing.addFirst(existing.name); existing = existing.parentFile }
    val base = existing?.toPath()?.toRealPath()?.toFile() ?: return normal
    return missing.fold(base) { folder, name -> File(folder, name) }
}

internal suspend fun externalOpen(file: File) = withContext(Dispatchers.IO) {
    try { Desktop.getDesktop().open(file) }
    catch (e: Exception) { throw CampusException(FailureKind.NO_VIEWER, e) }
}

private data class ReaderApp(val id: String, val label: String) { override fun toString() = label }
private fun installedReaders(mime: String): List<ReaderApp> = runCatching {
    val process = ProcessBuilder("gio", "mime", mime).apply { environment()["LC_ALL"] = "C" }.start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    val bases = listOf(File(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "applications")) +
        (System.getenv("XDG_DATA_DIRS") ?: "/usr/local/share:/usr/share").split(':').map { File(it, "applications") }
    Regex("[A-Za-z0-9_.-]+\\.desktop").findAll(output).map { it.value }.distinct().mapNotNull { id ->
        val entry = bases.map { File(it, id) }.firstOrNull { it.isFile } ?: return@mapNotNull null
        val label = entry.readLines().firstOrNull { it.startsWith("Name=") }?.substringAfter('=') ?: id
        ReaderApp(id, label)
    }.toList()
}.getOrDefault(emptyList())
