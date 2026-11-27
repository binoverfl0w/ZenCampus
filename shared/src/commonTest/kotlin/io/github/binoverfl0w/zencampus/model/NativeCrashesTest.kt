package io.github.binoverfl0w.zencampus.model

import kotlin.test.*
import kotlinx.serialization.json.*

/** iOS crashes: Kotlin/Native traces, MetricKit diagnostics, and how both reach Sentry for symbolication. */
class NativeCrashesTest {
    @Test fun kotlinNativeTracesKeepNamesAddressesAndFileNamesOnly() {
        val frames = kotlinNativeFrames(listOf(
            "0   ZenCampus                           0x0000000102b1c8f7 kfun:kotlin.Throwable#<init>(kotlin.String?){} + 95 (/Users/someone/kotlin/Throwable.kt:28:37)",
            "1   ZenCampus                           0x0000000102b16a1b kfun:kotlin.IllegalStateException#<init>(kotlin.String?){} + 91",
            "2   ZenCampus                           0x0000000102c00010 kfun:io.github.binoverfl0w.zencampus.ui.Today#render(kotlin.Int){}kotlin.String + 120 (/Users/someone/zencampus/shared/src/Today.kt:42:9)",
            "3   ZenCampus                           0x0000000102c00a00 kfun:io.github.binoverfl0w.zencampus.data#refresh(){} + 12",
            "3   ZenCampus                           0x00000001016e612f kfun:io.github.binoverfl0w.zencampus.ios.IosRuntime.${'$'}invokeCOROUTINE${'$'}0.invokeSuspend#internal + 935",
            "4   ZenCampus                           0x0000000102c0ff00 0x0 + 4345368320",
            "5   libsystem_c.dylib                   0x00000001a2b3c4d5 abort + 180",
            "not a frame",
        ), appImage = "ZenCampus", exceptionType = "kotlin.IllegalStateException")
        assertEquals(listOf("render", "refresh", "invokeSuspend", "", "abort"), frames.map { it.function }, "constructor frames are dropped")
        assertEquals("io.github.binoverfl0w.zencampus.ios.IosRuntime.${'$'}invokeCOROUTINE${'$'}0", frames[2].module, "internal functions are named before the marker")
        val render = frames.first()
        assertEquals("io.github.binoverfl0w.zencampus.ui.Today", render.module)
        assertEquals("Today.kt", render.file, "only the file name, never the build machine's path")
        assertEquals(42, render.line)
        assertEquals("0x0000000102c00010", render.address)
        assertEquals("ZenCampus", render.image)
        assertEquals(true, render.inApp)
        assertEquals("io.github.binoverfl0w.zencampus.data", frames[1].module)
        assertEquals(false, frames.last().inApp)
    }

    @Test fun metricKitCrashesBecomeSymbolicatableReports() {
        val json = """{
          "callStackTree":{"callStackPerThread":true,"callStacks":[
            {"threadAttributed":false,"callStackRootFrames":[{"binaryName":"libsystem_kernel.dylib","binaryUUID":"AAAAAAAA-0000-0000-0000-000000000000","offsetIntoBinaryTextSegment":10,"address":110}]},
            {"threadAttributed":true,"callStackRootFrames":[
              {"binaryName":"ZenCampus","binaryUUID":"1C2D3E4F-0000-1111-2222-333344445555","offsetIntoBinaryTextSegment":4096,"address":4295004160,"sampleCount":1,
               "subFrames":[{"binaryName":"UIKitCore","binaryUUID":"99999999-8888-7777-6666-555555555555","offsetIntoBinaryTextSegment":512,"address":7516193280,
                 "subFrames":[{"binaryName":"ZenCampus","binaryUUID":"1C2D3E4F-0000-1111-2222-333344445555","offsetIntoBinaryTextSegment":2048,"address":4295002112}]}]}]}]},
          "diagnosticMetaData":{"appVersion":"0.12.0","appBuildVersion":"42","osVersion":"iPhone OS 17.5 (21F79)","deviceType":"iPhone15,2",
            "regionFormat":"DE","platformArchitecture":"arm64","exceptionType":1,"signal":11,"exceptionCode":0,
            "terminationReason":"Namespace SIGNAL, Code 11 user@example.org"}}"""
        val report = metricKitCrashReport(json, "a".repeat(32), 1_790_000_000_000, appImage = "ZenCampus")!!
        assertEquals("0.12.0", report.release)
        assertEquals("ios", report.platform)
        assertEquals("17.5 (21F79)", report.osVersion)
        assertEquals("iPhone15,2", report.device)
        val crash = report.exceptions.single()
        assertEquals("SIGSEGV", crash.type)
        assertFalse("user@example.org" in crash.message, "messages are scrubbed")
        assertEquals(listOf("0x100009000", "0x1c0000200", "0x100008800"), crash.frames.map { it.address }, "crashed thread, innermost first")
        assertEquals(listOf(true, false, true), crash.frames.map { it.inApp })
        assertEquals(listOf("1C2D3E4F-0000-1111-2222-333344445555", "99999999-8888-7777-6666-555555555555"), report.images.map { it.uuid })
        assertEquals("0x100008000", report.images.first().address, "load address = frame address - text offset")
        assertNull(metricKitCrashReport("{}", "b", 0, "ZenCampus"))
        assertNull(metricKitCrashReport("<html>", "b", 0, "ZenCampus"))
    }

    @Test fun objectiveCExceptionsKeepTheirName() {
        val json = """{"callStackTree":{"callStacks":[]},"diagnosticMetaData":{"appVersion":"0.12.0","signal":6,"exceptionType":10,
            "exceptionReason":{"exceptionName":"NSInvalidArgumentException","composedMessage":"-[ZenView frame]: unrecognized selector"}}}"""
        val crash = metricKitCrashReport(json, "c", 0, "ZenCampus")!!.exceptions.single()
        assertEquals("NSInvalidArgumentException", crash.type)
        assertEquals("-[ZenView frame]: unrecognized selector", crash.message)
    }

    @Test fun nativeReportsAskSentryToSymbolicateWithTheDsym() {
        val report = CrashReport("d".repeat(32), 1_790_000_000_000, "0.12.0", "ios", "iOS", "17.5", "iPhone15,2", "main",
            exceptions = listOf(CrashException("kotlin.IllegalStateException", "boom", listOf(
                CrashFrame("", "", address = "0x100009000", image = "ZenCampus", inApp = true),
                CrashFrame("render", "io.github.binoverfl0w.zencampus.ui.Today", "Today.kt", 42, "0x100008800", "ZenCampus", true)))),
            images = listOf(CrashImage("ZenCampus", "1C2D3E4F-0000-1111-2222-333344445555", "0x100008000", 65536)))
        val event = Json.parseToJsonElement(sentryEnvelope(report, CrashDsn.parse("https://key@sentry.example.org/1")!!).trimEnd().lines()[2]).jsonObject
        assertEquals("cocoa", event["platform"]!!.jsonPrimitive.content)
        val frames = event["exception"]!!.jsonObject["values"]!!.jsonArray.single().jsonObject["stacktrace"]!!.jsonObject["frames"]!!.jsonArray.map { it.jsonObject }
        assertEquals("render", frames.first()["function"]!!.jsonPrimitive.content, "outermost call first")
        assertNull(frames.last()["function"], "unknown names are left for Sentry")
        assertEquals("0x100009000", frames.last()["instruction_addr"]!!.jsonPrimitive.content)
        assertEquals("ZenCampus", frames.last()["package"]!!.jsonPrimitive.content)
        val image = event["debug_meta"]!!.jsonObject["images"]!!.jsonArray.single().jsonObject
        assertEquals("macho", image["type"]!!.jsonPrimitive.content)
        assertEquals("1C2D3E4F-0000-1111-2222-333344445555", image["debug_id"]!!.jsonPrimitive.content)
        assertEquals("0x100008000", image["image_addr"]!!.jsonPrimitive.content)
        assertEquals(65536, image["image_size"]!!.jsonPrimitive.long)
    }
}
