@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.model.CrashImage
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class IosCrashesTest {
    private val directory = NSTemporaryDirectory() + "zencampus-crashes-" + NSUUID().UUIDString
    private val image = CrashImage("ZenCampus", "1C2D3E4F-0000-1111-2222-333344445555", "0x100008000", 65536)

    @AfterTest fun clean() { NSFileManager.defaultManager.removeItemAtPath(directory, null) }

    @Test fun kotlinExceptionsBecomeReportsWithTheAppImage() {
        val error = runCatching { failInsideApp() }.exceptionOrNull()!!
        val report = iosCrashReport(error, IosCrashInfo("0.12.0", "17.5", "iPhone15,2", image), "main", time = 1_790_000_000_000)
        assertEquals("ios", report.platform)
        assertEquals(listOf(image), report.images)
        val crash = report.exceptions.first()
        assertEquals("kotlin.IllegalStateException", crash.type)
        assertFalse("student@example.org" in crash.message, "messages are scrubbed")
        assertEquals("kotlin.IllegalArgumentException", report.exceptions[1].type, "causes follow")
        assertTrue(crash.frames.isNotEmpty())
        assertTrue(crash.frames.all { it.address?.startsWith("0x") == true }, "every frame keeps its address for symbolication")
        assertTrue(crash.frames.none { it.function == "<init>" && it.module.startsWith("kotlin.") }, "constructor frames are dropped")
        assertTrue(crash.frames.none { it.file?.contains('/') == true }, "only file names leave the device")
    }

    @Test fun reportsSurviveOnDiskUntilRemovedAndKotlinCrashTimesAreKept() = runTest {
        val store = IosCrashStore(directory)
        val report = iosCrashReport(IllegalStateException("boom"), IosCrashInfo("0.12.0", "17.5", "iPhone15,2", image), "main", time = 5)
        assertTrue(store.save(report))
        assertEquals(listOf(report.id), store.pending().map { it.id })
        assertEquals(report, store.pending().single())
        store.remove(report.id)
        assertEquals(emptyList(), store.pending())
        (1L..12L).forEach(store::noteKotlinCrash)
        assertEquals((3L..12L).toList(), store.kotlinCrashTimes(), "only the latest times are kept")
    }

    @Test fun theAppImageComesFromSwiftAsFourFields() {
        assertEquals(image, parseCrashImage("ZenCampus 1C2D3E4F-0000-1111-2222-333344445555 0x100008000 65536"))
        assertNull(parseCrashImage(""))
        assertNull(parseCrashImage("ZenCampus 1C2D3E4F 4295000064 65536"), "the address is hexadecimal")
    }

    private fun failInsideApp(): Nothing = try {
        throw IllegalArgumentException("inner")
    } catch (cause: IllegalArgumentException) {
        throw IllegalStateException("could not save for student@example.org", cause)
    }
}
