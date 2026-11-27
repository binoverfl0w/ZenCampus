package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where log lines go besides the in-memory tail: the rotating log file, Logcat or the terminal. */
fun interface LogSink { fun write(line: LogLine) }

/**
 * The app's log. Every message is scrubbed ([LogScrubber]) before any sink sees it. Describe what happened in terms of
 * kinds and counts (a cache key's kind, a [FailureKind], a status code); never log names, titles, URLs or bodies.
 */
object ZenLog {
    private val sinks = MutableStateFlow<List<LogSink>>(emptyList())
    private val tail = MutableStateFlow<List<LogLine>>(emptyList())

    /** Lines below this level are dropped; debug builds lower it. */
    var minimum: LogLevel = LogLevel.INFO

    fun install(sink: LogSink) = sinks.update { it + sink }
    fun uninstall(sink: LogSink) = sinks.update { it - sink }

    /** The latest lines, oldest first; a crash report carries them as breadcrumbs. */
    fun recent(): List<LogLine> = tail.value

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun error(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error, trace = true)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, trace: Boolean = false) {
        if (level < minimum) return
        val detail = when {
            error == null -> ""
            trace -> "\n" + error.stackTraceToString().lineSequence().take(MAX_TRACE_LINES).joinToString("\n")
            else -> " (${describe(error)})"
        }
        val line = LogLine(nowMillis(), level, tag, LogScrubber.scrub(message + detail))
        tail.update { (it + line).takeLast(TAIL_LINES) }
        sinks.value.forEach { sink -> try { sink.write(line) } catch (_: Exception) { } }
    }

    /** A failure in a few safe words: its kind and status for campus failures, otherwise its class names. */
    fun describe(error: Throwable): String {
        val own = when (error) {
            is CampusException -> error.kind.name + (error.httpStatus?.let { " $it" } ?: "")
            else -> error::class.simpleName ?: "Exception"
        }
        val cause = error.cause?.takeIf { it !== error }
        return if (cause == null || error is CampusException && cause is CampusException) own else "$own ← ${cause::class.simpleName}"
    }

    const val TAIL_LINES = 120
    private const val MAX_TRACE_LINES = 40
}

/** What each app keeps on disk for crash reports. */
interface CrashStore {
    suspend fun pending(): List<CrashReport>
    suspend fun remove(id: String)
}

/**
 * Crash reports, sent only with consent. A crash is written to disk while the app goes down; on the next start it is
 * sent if the student turned crash reports on, offered once otherwise, and dropped when declined. Without a configured
 * [dsn] (development builds) nothing is ever sent and crashes stay in the local log only.
 */
class CrashReports(
    private val client: () -> HttpClient,
    private val dsn: CrashDsn?,
    private val store: CrashStore,
    private val clientVersion: String,
    private val enabled: () -> Boolean,
) {
    val configured: Boolean get() = dsn != null
    private val _pending = MutableStateFlow<List<CrashReport>>(emptyList())
    /** Crashes waiting for a decision; only shown to the student while reports are off. */
    val pending: StateFlow<List<CrashReport>> = _pending.asStateFlow()
    private val sending = Mutex()

    /** Reads crashes left by earlier runs and sends them when the student has agreed to that. */
    suspend fun start() {
        val found = try { store.pending() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        if (dsn == null) { found.forEach { store.remove(it.id) }; return }
        _pending.value = found.sortedBy { it.time }.takeLast(MAX_PENDING)
        found.filter { it !in _pending.value }.forEach { store.remove(it.id) }
        if (enabled() && found.isNotEmpty()) send()
    }

    /** Sends every waiting report. Returns whether all of them reached the server. */
    suspend fun send(): Boolean = sending.withLock {
        val target = dsn ?: return@withLock false
        var complete = true
        for (report in _pending.value) {
            val outcome = try {
                val response = client().post(target.endpoint) {
                    header("X-Sentry-Auth", target.authHeader(clientVersion))
                    contentType(ContentType("application", "x-sentry-envelope"))
                    setBody(sentryEnvelope(report, target))
                }
                response.status.value
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ZenLog.warn(TAG, "crash report not sent", e); null }
            // A rejected report (bad key, malformed event) would fail forever; only network trouble and overload keep it.
            val keep = outcome == null || outcome == 429 || outcome in 500..599
            if (keep) complete = false else forget(report.id)
            ZenLog.info(TAG, if (outcome in 200..299) "crash report sent" else "crash report answered $outcome")
        }
        complete
    }

    /** The student declined: the waiting reports are deleted. */
    suspend fun discard() = sending.withLock { _pending.value.forEach { forget(it.id) } }

    private suspend fun forget(id: String) {
        try { store.remove(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        _pending.update { list -> list.filterNot { it.id == id } }
    }

    companion object {
        const val TAG = "crash"
        const val MAX_PENDING = 5
    }
}
