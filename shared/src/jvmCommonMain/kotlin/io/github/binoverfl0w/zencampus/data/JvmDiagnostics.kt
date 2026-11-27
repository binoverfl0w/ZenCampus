package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val diagnosticsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The local log: `zencampus.log`, moved to `zencampus.1.log` when it passes [limit], so at most twice that is kept.
 * Lines arrive already scrubbed. Writes are synchronous so the last lines before a crash are on disk.
 */
class FileLogSink(private val directory: File, private val limit: Long = 512L * 1024) : LogSink {
    private val current = File(directory, "zencampus.log")
    private val previous = File(directory, "zencampus.1.log")

    init { directory.mkdirs() }

    @Synchronized
    override fun write(line: LogLine) {
        if (current.length() > limit) { previous.delete(); current.renameTo(previous) }
        FileOutputStream(current, true).use { it.write((line.format() + "\n").toByteArray()) }
    }

    /** Both log files, oldest first, behind a short header describing the installation (no account details). */
    @Synchronized
    fun export(header: String): String = buildString {
        append(header.trim()).append("\n\n")
        listOf(previous, current).filter { it.isFile }.forEach { append(it.readText()) }
    }

    @Synchronized
    fun clear() { previous.delete(); current.delete() }
}

/** Crash reports waiting on disk, one JSON file each. */
class FileCrashStore(private val directory: File) : CrashStore {
    init { directory.mkdirs() }

    override suspend fun pending(): List<CrashReport> = withContext(Dispatchers.IO) {
        directory.listFiles { file -> file.name.startsWith("crash-") && file.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            runCatching { diagnosticsJson.decodeFromString<CrashReport>(file.readText()) }.getOrElse { file.delete(); null }
        }
    }

    override suspend fun remove(id: String) { withContext(Dispatchers.IO) { File(directory, "crash-$id.json").delete() } }

    /** Called on the crashing thread: plain blocking I/O, no coroutines. */
    fun save(report: CrashReport) {
        directory.mkdirs()
        val target = File(directory, "crash-${report.id}.json")
        val partial = File(directory, "crash-${report.id}.part")
        partial.writeText(diagnosticsJson.encodeToString(CrashReport.serializer(), report))
        partial.renameTo(target)
    }
}

/**
 * Describes the running system for crash reports and the shared log. [mappingId] names the R8 mapping of a minified
 * Android build, so Sentry can restore its file names and line numbers.
 */
data class RuntimeInfo(val release: String, val platform: String, val osName: String, val osVersion: String, val device: String = "",
    val mappingId: String = "")

/**
 * Records uncaught exceptions: the log gets the stack trace, and when crash reports are possible ([store] set) a
 * scrubbed report is saved for the next start. The previous handler still runs, so the platform's own behaviour
 * (Android's "app stopped") is unchanged.
 */
object CrashCapture {
    fun install(info: RuntimeInfo, store: FileCrashStore?) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                ZenLog.error("crash", "uncaught exception on ${threadKind(thread)}", error)
                store?.save(crashReport(error, info, threadKind(thread)))
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Thread names can carry worker or request details; only their kind is kept. */
    private fun threadKind(thread: Thread): String = when {
        thread.name == "main" -> "main"
        thread.name.startsWith("AWT-EventQueue") -> "ui"
        thread.name.startsWith("DefaultDispatcher") -> "background"
        else -> "other"
    }
}

fun crashReport(error: Throwable, info: RuntimeInfo, thread: String = "", time: Long = nowMillis()): CrashReport {
    val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
    return CrashReport(
        id = UUID.randomUUID().toString().replace("-", ""), time = time, release = info.release, platform = info.platform,
        osName = info.osName, osVersion = info.osVersion, device = info.device, thread = thread, mappingId = info.mappingId,
        exceptions = chain.map { exception ->
            CrashException(exception.javaClass.name, LogScrubber.scrub(exception.message.orEmpty()).take(1_000),
                exception.stackTrace.take(MAX_FRAMES).map { frame ->
                    CrashFrame(frame.methodName, frame.className, frame.fileName, frame.lineNumber.takeIf { it > 0 })
                })
        },
        log = ZenLog.recent().takeLast(MAX_BREADCRUMBS),
    )
}

private const val MAX_FRAMES = 80
private const val MAX_BREADCRUMBS = 60

/**
 * Writes an update download next to its final name and hashes it on the way; the file only takes its final name
 * once complete, so a half-downloaded update can never be installed.
 */
class HashingFileWriter(private val target: File) : UpdateWriter {
    private val partial = File(target.parentFile, target.name + ".part")
    private val digest = MessageDigest.getInstance("SHA-256")
    private val output = run { target.parentFile.mkdirs(); target.delete(); FileOutputStream(partial) }

    override suspend fun write(bytes: ByteArray, count: Int) = withContext(Dispatchers.IO) {
        output.write(bytes, 0, count); digest.update(bytes, 0, count)
    }

    override suspend fun finish(): String = withContext(Dispatchers.IO) {
        output.fd.sync(); output.close()
        if (!partial.renameTo(target)) throw CampusException(FailureKind.STORAGE)
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    override suspend fun abort() = withContext(Dispatchers.IO) { runCatching { output.close() }; partial.delete(); Unit }
}
