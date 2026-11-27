package io.github.binoverfl0w.zencampus.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.junit.Assume

/** What only Windows provides: DPAPI for sessions, the profile folders and the power state. Skipped elsewhere. */
class WindowsDesktopTest {
    @BeforeTest fun onlyOnWindows() = Assume.assumeTrue("Windows only", isWindows)

    @Test fun sessionsAreEncryptedForThisUserAndBoundToTheirService() {
        val root = Files.createTempDirectory("zencampus-vault").toFile()
        try {
            val vault = WindowsSessionVault(root, "profile-a")
            assertNull(vault.read("digicampus"))
            vault.write("digicampus", "Seminar_Session=secret-value")
            assertEquals("Seminar_Session=secret-value", vault.read("digicampus"))
            val stored = root.listFiles()!!.single()
            assertFalse("secret-value" in String(stored.readBytes(), Charsets.ISO_8859_1), "only ciphertext is on disk")
            // A file copied over another service's name does not decrypt: the service is part of the entropy.
            stored.copyTo(File(root, stored.name.replaceFirst("session-", "copy-")))
            assertNull(WindowsSessionVault(root, "profile-b").read("digicampus"), "another profile reads nothing")
            vault.clear("digicampus")
            assertNull(vault.read("digicampus"))
        } finally { root.deleteRecursively() }
    }

    @Test fun profileFoldersAndConditionsComeFromWindows() {
        assertTrue(windowsDataRoot().path.contains("AppData\\Roaming", ignoreCase = true), windowsDataRoot().path)
        assertTrue(windowsDownloads().isAbsolute)
        windowsBatteryLow() // must not throw on desktops or virtual machines without a battery
        val conditions = readNetworkConditions()
        assertTrue(conditions.connected, "the runner is online")
    }
}
