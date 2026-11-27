package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture

internal val desktopJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Windows has no POSIX permissions; there the per-user profile folders are already private to the user. */
private val posixFiles = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

internal fun privateDirectory(file: File): File = file.apply {
    check(isDirectory || mkdirs()) { "Cannot create ${absolutePath}" }
    if (posixFiles) Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwx------"))
}

internal fun atomicPrivateWrite(file: File, contents: ByteArray) {
    privateDirectory(file.parentFile)
    val part = Files.createTempFile(file.parentFile.toPath(), ".zen-", ".tmp")
    try {
        if (posixFiles) Files.setPosixFilePermissions(part, PosixFilePermissions.fromString("rw-------"))
        java.io.FileOutputStream(part.toFile()).use { it.write(contents); it.fd.sync() }
        Files.move(part, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(part) }
}

internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

internal class DesktopPaths(private val overrideRoot: File? = null) {
    private val mac = System.getProperty("os.name").startsWith("Mac")
    val data = privateDirectory(overrideRoot ?: when {
        mac -> File(System.getProperty("user.home"), "Library/Application Support/ZenCampus")
        isWindows -> windowsDataRoot()
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith('/') } ?: "${System.getProperty("user.home")}/.local/share", "zencampus")
    })
    val cache = privateDirectory(when {
        overrideRoot != null || isWindows -> File(data, "cache")
        mac -> File(System.getProperty("user.home"), "Library/Caches/ZenCampus")
        else -> File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.startsWith('/') } ?: "${System.getProperty("user.home")}/.cache", "zencampus")
    })
    val materials = privateDirectory(File(data, "materials"))
    val downloads: File get() {
        if (overrideRoot != null) return File(data, "Downloads/ZenCampus").apply { mkdirs() }
        if (isWindows) return File(windowsDownloads(), "ZenCampus").apply { mkdirs() }
        val config = File(System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "user-dirs.dirs")
        val configured = config.takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")
            ?.replace("\$HOME", System.getProperty("user.home"))
            ?.takeIf { it.startsWith('/') && !it.contains('\n') }
        return File(configured ?: "${System.getProperty("user.home")}/Downloads", "ZenCampus").apply { mkdirs() }
    }
    val profile = digest(data.canonicalPath).take(24)
}

@Serializable internal data class DesktopPreferences(
    val settings: AppSettings = AppSettings(), val width: Int = 1100, val height: Int = 820,
    val reminderSignatures: Map<String, String> = emptyMap(),
)

internal class DesktopPreferencesStore(private val file: File) {
    @Synchronized fun read(): DesktopPreferences = if (!file.isFile) DesktopPreferences() else
        runCatching { desktopJson.decodeFromString<DesktopPreferences>(file.readText()) }.getOrDefault(DesktopPreferences())
    @Synchronized fun update(change: (DesktopPreferences) -> DesktopPreferences) {
        atomicPrivateWrite(file, desktopJson.encodeToString(change(read())).toByteArray())
    }
}


/** The Secret Service owns secrets, including its unlock prompt. No credentials go to arguments or files. */
internal interface SessionVault {
    fun read(service: String): String?
    fun write(service: String, value: String)
    fun clear(service: String)
}

internal class KeyringSessionVault(private val profile: String) : SessionVault {
    private fun command(action: String, service: String, value: String? = null): String? {
        val arguments = mutableListOf("secret-tool", action)
        if (action == "store") {
            val label = if (service == "saved-sign-ins") "Saved sign-ins" else service
            arguments += "--label=ZenCampus · $label"
        }
        arguments += listOf("application", "io.github.binoverfl0w.zencampus", "profile", profile, "service", service)
        val process = ProcessBuilder(arguments).start()
        val captured = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText().trimEnd('\n', '\r') } }
        val hasError = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText().isNotBlank() } }
        process.outputStream.use { if (value != null) it.write(value.toByteArray()) }
        // Lookup output is tiny and never logged. Limit the process and avoid an abandoned unlock prompt.
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Keyring timed out") }
        val output = captured.get(5, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            if (process.exitValue() == 1 && action != "store" && !hasError.get(5, TimeUnit.SECONDS)) return null
            error("Keyring unavailable")
        }
        return output.ifEmpty { null }
    }
    override fun read(service: String) = command("lookup", service)
    override fun write(service: String, value: String) { command("store", service, value) }
    override fun clear(service: String) { command("clear", service) }
}

internal suspend fun <T> storageIO(action: suspend () -> T): T = withContext(Dispatchers.IO) {
    try { action() }
    catch (e: CancellationException) { throw e }
    catch (e: CampusException) { throw e }
    catch (e: Exception) { throw CampusException(FailureKind.STORAGE, e) }
}
