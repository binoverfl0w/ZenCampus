@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)

package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.data.CrashStore
import io.github.binoverfl0w.zencampus.data.ZenLog
import io.github.binoverfl0w.zencampus.model.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.*
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname

// iOS crash reports. Kotlin exceptions are caught here as they happen; native crashes (and the abort that ends a
// Kotlin crash) come from MetricKit on the next launch, through IosRuntime.recordNativeCrash.

private val crashJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Called by the app first thing at launch, with the app binary as "name uuid load-address text-size" (read from dyld
 * in Swift). Crashes are kept only when this build can send them, as on the other platforms.
 */
fun startCrashCapture(image: String) = IosCrashCapture.install(parseCrashImage(image), iosCrashDsn() != null)

internal fun iosCrashDsn(): CrashDsn? = CrashDsn.parse((NSBundle.mainBundle.objectForInfoDictionaryKey("ZenCrashDsn") as? String).orEmpty())

internal fun parseCrashImage(text: String): CrashImage? {
    val parts = text.trim().split(' ')
    if (parts.size != 4 || parts.any { it.isBlank() } || !parts[2].startsWith("0x")) return null
    return CrashImage(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: 0)
}

internal object IosCrashCapture {
    private var installed = false
    /** The app binary's name; MetricKit frames inside it are the app's own. */
    var appImage = ""
        private set

    fun install(image: CrashImage?, keep: Boolean) {
        if (installed) return
        installed = true
        appImage = image?.name.orEmpty()
        val info = IosCrashInfo(releaseVersion(), UIDevice.currentDevice.systemVersion, machineName(), image)
        val store = if (keep) IosCrashStore() else null
        var previous: ((Throwable) -> Unit)? = null
        previous = setUnhandledExceptionHook { error ->
            val thread = if (NSThread.isMainThread) "main" else "background"
            try {
                ZenLog.error("crash", "uncaught exception on $thread", error)
                store?.let { crashes -> iosCrashReport(error, info, thread).also { if (crashes.save(it)) crashes.noteKotlinCrash(it.time) } }
            } catch (_: Throwable) { }
            previous?.invoke(error)
            // A custom hook replaces the default fatal handler; returning would swallow coroutine crashes.
            terminateWithUnhandledException(error)
        }
    }
}

internal data class IosCrashInfo(val release: String, val osVersion: String, val device: String, val image: CrashImage?)

/** A Kotlin exception as a report; messages are scrubbed, and frames keep only names, files, lines and addresses. */
internal fun iosCrashReport(error: Throwable, info: IosCrashInfo, thread: String, time: Long = nowMillis()): CrashReport {
    val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
    val app = info.image?.name.orEmpty()
    return CrashReport(
        id = newCrashId(), time = time, release = info.release, platform = "ios", osName = "iOS",
        osVersion = info.osVersion, device = info.device, thread = thread,
        exceptions = chain.map { exception ->
            val type = exception::class.qualifiedName ?: "kotlin.Throwable"
            CrashException(type, LogScrubber.scrub(exception.message.orEmpty()).take(1_000),
                kotlinNativeFrames(exception.getStackTrace().toList(), app, type).take(80))
        },
        log = ZenLog.recent().takeLast(60), images = listOfNotNull(info.image),
    )
}

internal fun newCrashId() = NSUUID().UUIDString.replace("-", "").lowercase()

private fun releaseVersion() = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String).orEmpty()

/** The model identifier, such as "iPhone15,2"; never the device's name. */
private fun machineName(): String = memScoped {
    val system = alloc<utsname>()
    if (uname(system.ptr) == 0) system.machine.toKString() else ""
}

/** Crash reports waiting on disk, one JSON file each, as on Android and the desktop. */
internal class IosCrashStore(private val directory: String = defaultCrashDirectory()) : CrashStore {
    private val files = NSFileManager.defaultManager

    override suspend fun pending(): List<CrashReport> = withContext(Dispatchers.Default) { read() }

    fun read(): List<CrashReport> = files.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>()
        .filter { it.startsWith("crash-") && it.endsWith(".json") }
        .mapNotNull { name ->
            val path = "$directory/$name"
            val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
            text?.let { runCatching { crashJson.decodeFromString(CrashReport.serializer(), it) }.getOrNull() }
                ?: run { files.removeItemAtPath(path, null); null }
        }

    override suspend fun remove(id: String) { withContext(Dispatchers.Default) { files.removeItemAtPath("$directory/crash-$id.json", null) } }

    /** Called on the crashing thread: plain blocking Foundation calls. The write is atomic, so no half report is left. */
    fun save(report: CrashReport): Boolean {
        files.createDirectoryAtPath(directory, true, null, null)
        return write("$directory/crash-${report.id}.json", crashJson.encodeToString(CrashReport.serializer(), report))
    }

    /** When Kotlin crashes happened, so MetricKit's report of the same crash (its final abort) can be told apart. */
    fun noteKotlinCrash(time: Long) {
        val times = (kotlinCrashTimes() + time).takeLast(KEPT_TIMES)
        files.createDirectoryAtPath(directory, true, null, null)
        write("$directory/$KOTLIN_TIMES", times.joinToString("\n"))
    }

    fun kotlinCrashTimes(): List<Long> = NSString.stringWithContentsOfFile("$directory/$KOTLIN_TIMES", NSUTF8StringEncoding, null)
        ?.lines()?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun write(path: String, text: String): Boolean = (text as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)

    private companion object {
        const val KOTLIN_TIMES = "kotlin-crashes.txt"
        const val KEPT_TIMES = 10
    }
}

private fun defaultCrashDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/ZenCampusDiagnostics/crashes"
