package io.github.binoverfl0w.zencampus.model

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One scrubbed line of the local log. */
@Serializable
data class LogLine(val time: Long, val level: LogLevel, val tag: String, val message: String) {
    fun format(): String = "${Instant.fromEpochMilliseconds(time)} ${level.name.padEnd(5)} $tag: $message"
}

/**
 * Removes what must never leave the device, or even reach the local log: credentials, cookies, e-mail addresses,
 * query strings, long identifiers and home folders. Log calls should still describe *what kind* of thing happened
 * (a cache key's kind, a failure kind, a status code) and never pass names, titles, response bodies or URLs on purpose;
 * this is the safety net for what slips through, such as an exception message.
 */
object LogScrubber {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""") to "Bearer <redacted>",
        Regex("""(?i)\b(cookie|set-cookie|authorization|token|access_token|password|passwd|secret|session|sessionid|seminar_session|sid|auth|key|sentry_key)(\s*[:=]\s*)[^\s;,&]+""") to "$1$2<redacted>",
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        Regex("""\b(https?://[^\s?#"'<>]+)[?#][^\s"'<>]*""") to "$1?<query>",
        Regex("""\bcontent://[^\s"'<>]+""") to "content://<uri>",
        Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""") to "<id>",
        Regex("""(?i)\b[0-9a-f]{24,}\b""") to "<id>",
        Regex("""\b[A-Za-z0-9_-]{40,}\b""") to "<token>",
        Regex("""(/home/|/Users/|/storage/emulated/\d+/)[^/\s"']+""") to "$1~",
        Regex("""\b\d{6,}\b""") to "<n>",
    )

    fun scrub(text: String): String = rules.fold(text.take(MAX_LOG_MESSAGE)) { value, (pattern, replacement) -> pattern.replace(value, replacement) }

    const val MAX_LOG_MESSAGE = 4_000
}

/**
 * A crashed call stack's frame; only code locations, never values. Native (iOS) frames also carry their [address] and
 * [image], which Sentry resolves against the uploaded debug symbols; their [function] may be blank until then.
 */
@Serializable
data class CrashFrame(
    val function: String, val module: String, val file: String? = null, val line: Int? = null,
    val address: String? = null, val image: String? = null, val inApp: Boolean? = null,
)

/** A loaded binary of a native crash: Sentry matches its [uuid] to the uploaded dSYM. [address] is its load address. */
@Serializable
data class CrashImage(val name: String, val uuid: String, val address: String, val size: Long = 0)

/** One exception of a crash; [frames] are innermost call first, as the JVM prints them. */
@Serializable
data class CrashException(val type: String, val message: String, val frames: List<CrashFrame>)

/**
 * What a crash leaves behind until it is sent or discarded. [exceptions] starts with the exception that crashed the
 * app and continues with its causes. Every message is already scrubbed. Nothing identifies the student: no account,
 * no device name or serial, no IP address chosen by the app.
 */
@Serializable
data class CrashReport(
    val id: String, val time: Long, val release: String, val platform: String,
    val osName: String, val osVersion: String, val device: String = "", val thread: String = "",
    val exceptions: List<CrashException>, val log: List<LogLine> = emptyList(), val mappingId: String = "",
    val images: List<CrashImage> = emptyList(),
)

/** A Sentry-compatible project key: https://<public key>@<host>/<project>. GlitchTip uses the same format. */
data class CrashDsn(val dsn: String, val endpoint: String, val publicKey: String) {
    companion object {
        fun parse(value: String): CrashDsn? {
            if (value.isBlank()) return null
            val url = runCatching { Url(value.trim()) }.getOrNull() ?: return null
            val key = url.user?.takeIf { it.isNotBlank() } ?: return null
            if (url.protocol != URLProtocol.HTTPS || url.host.isBlank()) return null
            val segments = url.encodedPath.trim('/').split('/').filter { it.isNotBlank() }
            val project = segments.lastOrNull()?.takeIf { id -> id.all { it.isLetterOrDigit() || it == '-' } } ?: return null
            val prefix = segments.dropLast(1).joinToString("") { "/$it" }
            val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
            return CrashDsn(value.trim(), "https://${url.host}$port$prefix/api/$project/envelope/", key)
        }
    }

    fun authHeader(clientVersion: String) = "Sentry sentry_version=7, sentry_client=zencampus/$clientVersion, sentry_key=$publicKey"
}

/** The Sentry envelope for one crash: a header line, an item header line and the event, each on its own line. */
fun sentryEnvelope(report: CrashReport, dsn: CrashDsn): String {
    val header = buildJsonObject {
        put("event_id", report.id); put("dsn", dsn.dsn); put("sent_at", Instant.fromEpochMilliseconds(nowMillis()).toString())
    }
    return "$header\n${buildJsonObject { put("type", "event") }}\n${sentryEvent(report)}\n"
}

internal fun sentryEvent(report: CrashReport): JsonObject = buildJsonObject {
    put("event_id", report.id)
    put("timestamp", report.time / 1000.0)
    // Native iOS crashes are symbolicated from binary images like any Apple crash; JVM ones carry names already.
    put("platform", if (report.images.isEmpty()) "java" else "cocoa")
    put("level", "fatal")
    put("logger", "zencampus")
    put("release", "zencampus@${report.release}")
    put("environment", "production")
    putJsonObject("tags") { put("app.platform", report.platform); if (report.thread.isNotBlank()) put("thread", report.thread) }
    putJsonObject("contexts") {
        putJsonObject("os") { put("name", report.osName); put("version", report.osVersion) }
        if (report.device.isNotBlank()) putJsonObject("device") { put("model", report.device) }
        putJsonObject("app") { put("app_version", report.release) }
    }
    putJsonObject("exception") {
        // Sentry lists the oldest exception (the deepest cause) first and each stack's outermost call first.
        putJsonArray("values") {
            report.exceptions.asReversed().forEach { exception ->
                add(buildJsonObject {
                    put("type", exception.type.substringAfterLast('.'))
                    put("module", exception.type.substringBeforeLast('.', ""))
                    put("value", exception.message)
                    putJsonObject("stacktrace") {
                        putJsonArray("frames") {
                            exception.frames.asReversed().forEach { frame ->
                                add(buildJsonObject {
                                    if (frame.function.isNotBlank()) put("function", frame.function)
                                    if (frame.module.isNotBlank()) put("module", frame.module)
                                    frame.file?.let { put("filename", it) }; frame.line?.let { put("lineno", it) }
                                    frame.address?.let { put("instruction_addr", it) }; frame.image?.let { put("package", it) }
                                    put("in_app", frame.inApp ?: frame.module.startsWith("io.github.binoverfl0w.zencampus."))
                                })
                            }
                        }
                    }
                })
            }
        }
    }
    // R8 renumbers lines in minified builds, and iOS release binaries are stripped: these name the uploaded mapping or
    // dSYMs that restore them.
    if (report.mappingId.isNotBlank() || report.images.isNotEmpty()) putJsonObject("debug_meta") {
        putJsonArray("images") {
            if (report.mappingId.isNotBlank()) add(buildJsonObject { put("type", "proguard"); put("uuid", report.mappingId) })
            report.images.forEach { image ->
                add(buildJsonObject {
                    put("type", "macho"); put("debug_id", image.uuid); put("code_file", image.name); put("image_addr", image.address)
                    if (image.size > 0) put("image_size", image.size)
                })
            }
        }
    }
    putJsonObject("breadcrumbs") {
        putJsonArray("values") {
            report.log.forEach { line ->
                add(buildJsonObject {
                    put("timestamp", line.time / 1000.0); put("category", line.tag); put("message", line.message)
                    put("level", when (line.level) { LogLevel.DEBUG -> "debug"; LogLevel.INFO -> "info"; LogLevel.WARN -> "warning"; LogLevel.ERROR -> "error" })
                })
            }
        }
    }
}
