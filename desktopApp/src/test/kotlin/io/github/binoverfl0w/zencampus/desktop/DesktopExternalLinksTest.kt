package io.github.binoverfl0w.zencampus.desktop

import org.junit.Test
import java.net.URI
import java.nio.file.Files
import kotlin.test.*

class DesktopExternalLinksTest {
    @Test fun unsupportedDesktopUsesSystemLauncherOnceAndKeepsTheWholeUrl() {
        val url = "https://digicampus.uni-augsburg.de/dispatch.php/course?cid=abc&tab=files"
        val seen = mutableListOf<URI>()
        assertTrue(launchExternalLink(url, desktopBrowse = { false }, systemBrowse = { seen += it; true }))
        assertEquals(listOf(URI(url)), seen)
    }

    @Test fun supportedDesktopDoesNotStartFallbackAndFailureIsReported() {
        var fallbackCalls = 0
        val url = "https://digicampus.uni-augsburg.de/"
        assertTrue(launchExternalLink(url, desktopBrowse = { true }, systemBrowse = { fallbackCalls++; true }))
        assertEquals(0, fallbackCalls)
        assertFalse(launchExternalLink(url, desktopBrowse = { throw UnsupportedOperationException() }, systemBrowse = { fallbackCalls++; false }))
        assertEquals(1, fallbackCalls)
    }

    @Test fun unsafeUrlsNeverReachEitherLauncher() {
        for (url in listOf("http://example.org/", "file:///etc/passwd", "javascript:alert(1)", "https://user@example.org/", "https://example.org:8443/")) {
            var calls = 0
            assertFalse(launchExternalLink(url, desktopBrowse = { calls++; true }, systemBrowse = { calls++; true }))
            assertEquals(0, calls)
        }
    }

    @Test fun systemLauncherReceivesOneLiteralArgumentAndReportsFailure() {
        org.junit.Assume.assumeFalse("The fake launcher is a shell script", isWindows)
        val root = Files.createTempDirectory("zen-open-link-test-").toFile()
        try {
            val launcher = root.resolve("launcher").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"\$(dirname \"\$0\")/args\"\n")
                setExecutable(true)
            }
            val uri = URI("https://digicampus.uni-augsburg.de/dispatch.php/course?cid=abc&tab=files")
            assertTrue(browseWithXdgOpen(uri, launcher.absolutePath))
            assertEquals(listOf(uri.toASCIIString()), root.resolve("args").readLines())
            launcher.writeText("#!/bin/sh\nexit 7\n")
            assertFalse(browseWithXdgOpen(uri, launcher.absolutePath))
        } finally { root.deleteRecursively() }
    }
}
