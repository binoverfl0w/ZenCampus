package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

// iOS crashes: Kotlin/Native stack traces and Apple's MetricKit crash diagnostics, turned into the shared report.

/** "12  ZenCampus  0x0000000102b1c8f7 kfun:io.github.binoverfl0w.zencampus.ui.Foo#bar(kotlin.Int){} + 95 (/path/Foo.kt:28:37)" */
private val NATIVE_FRAME = Regex("""^\s*\d+\s+(\S+)\s+(0x[0-9a-fA-F]+)\s*(.*?)\s*$""")
private val NATIVE_SOURCE = Regex("""\s*\(([^()]*):(\d+):\d+\)\s*$""")
private val NATIVE_OFFSET = Regex("""\s*\+\s*\d+\s*$""")

/**
 * Frames of a Kotlin/Native stack trace, innermost first. Release binaries are stripped, so names may be missing; the
 * address and binary let Sentry name them from the dSYM. Only file names are kept, never the build machine's paths.
 */
fun kotlinNativeFrames(lines: List<String>, appImage: String, exceptionType: String = ""): List<CrashFrame> {
    val frames = lines.mapNotNull { line ->
        val match = NATIVE_FRAME.matchEntire(line) ?: return@mapNotNull null
        val (image, address, rest) = match.destructured
        val source = NATIVE_SOURCE.find(rest)
        val symbol = (if (source != null) rest.removeRange(source.range) else rest).replace(NATIVE_OFFSET, "").trim()
        val (module, function) = kotlinSymbol(symbol)
        CrashFrame(function, module, source?.groupValues?.get(1)?.substringAfterLast('/'),
            source?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }, address.lowercase(), image, inApp = image == appImage)
    }
    // A Kotlin/Native trace starts inside the exception's own constructors.
    return frames.dropWhile { it.function == "<init>" && (it.module.startsWith("kotlin.") || it.module == exceptionType) }
}

/**
 * "kfun:io.github.binoverfl0w.zencampus.ui.Foo#bar(kotlin.Int){}" becomes ("io.github.binoverfl0w.zencampus.ui.Foo", "bar"). Internal functions put the name
 * before the marker ("kfun:io.github.binoverfl0w.zencampus.ui.Foo.bar#internal"). Other symbols keep their name.
 */
internal fun kotlinSymbol(symbol: String): Pair<String, String> {
    if (!symbol.startsWith("kfun:")) return "" to symbol.takeUnless { it.startsWith("0x") }.orEmpty()
    val name = symbol.removePrefix("kfun:")
    val owner = name.substringBefore('#', "")
    val tail = name.substringAfter('#')
    if (owner.isNotEmpty() && '(' !in tail) return owner.substringBeforeLast('.', "") to owner.substringAfterLast('.')
    return owner to tail.substringBefore('(')
}

/**
 * A crash that MetricKit delivered on a later launch. [json] is `MXCrashDiagnostic.jsonRepresentation()`: the crashed
 * thread's frames (binary, UUID and offset each), the signal or exception, and the app and OS version at the time.
 * It holds no user data; an Objective-C exception's message is scrubbed like any other. Null when unreadable.
 */
fun metricKitCrashReport(json: String, id: String, time: Long, appImage: String): CrashReport? {
    val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    val meta = root["diagnosticMetaData"] as? JsonObject ?: return null
    val stacks = ((root["callStackTree"] as? JsonObject)?.get("callStacks") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val crashed = stacks.firstOrNull { (it["threadAttributed"] as? JsonPrimitive)?.contentOrNull == "true" } ?: stacks.firstOrNull()
    val chain = mutableListOf<JsonObject>()
    var frame = (crashed?.get("callStackRootFrames") as? JsonArray)?.firstOrNull() as? JsonObject
    while (frame != null && chain.size < MAX_NATIVE_FRAMES) {
        chain += frame
        frame = (frame["subFrames"] as? JsonArray)?.firstOrNull() as? JsonObject
    }
    val images = linkedMapOf<String, CrashImage>()
    val frames = chain.mapNotNull { node ->
        val name = node.text("binaryName") ?: return@mapNotNull null
        val uuid = node.text("binaryUUID") ?: return@mapNotNull null
        val address = node.number("address") ?: return@mapNotNull null
        val offset = node.number("offsetIntoBinaryTextSegment") ?: return@mapNotNull null
        images.getOrPut(uuid) { CrashImage(name, uuid, hex(address - offset)) }
        CrashFrame("", "", address = hex(address), image = name, inApp = name == appImage)
    }
    val reason = meta["exceptionReason"] as? JsonObject
    val signal = (meta["signal"] as? JsonPrimitive)?.intOrNull
    val mach = (meta["exceptionType"] as? JsonPrimitive)?.intOrNull
    val type = reason?.text("exceptionName") ?: signalName(signal) ?: machExceptionName(mach) ?: "Crash"
    val message = reason?.text("composedMessage") ?: meta.text("terminationReason")
        ?: listOfNotNull(machExceptionName(mach), signalName(signal)).joinToString(" / ")
    return CrashReport(
        id = id, time = time, release = meta.text("appVersion").orEmpty(), platform = "ios", osName = "iOS",
        osVersion = meta.text("osVersion").orEmpty().removePrefix("iPhone OS ").removePrefix("iPadOS "),
        device = meta.text("deviceType").orEmpty(), thread = "crashed",
        exceptions = listOf(CrashException(type, LogScrubber.scrub(message).take(1_000), frames)), images = images.values.toList(),
    )
}

private const val MAX_NATIVE_FRAMES = 120

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
private fun hex(value: Long) = "0x" + value.toULong().toString(16)

private fun signalName(signal: Int?): String? = when (signal) {
    4 -> "SIGILL"; 5 -> "SIGTRAP"; 6 -> "SIGABRT"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 10 -> "SIGBUS"; 11 -> "SIGSEGV"; 13 -> "SIGPIPE"
    else -> null
}

private fun machExceptionName(type: Int?): String? = when (type) {
    1 -> "EXC_BAD_ACCESS"; 2 -> "EXC_BAD_INSTRUCTION"; 3 -> "EXC_ARITHMETIC"; 5 -> "EXC_SOFTWARE"; 6 -> "EXC_BREAKPOINT"
    10 -> "EXC_CRASH"; 11 -> "EXC_RESOURCE"; 12 -> "EXC_GUARD"
    else -> null
}
