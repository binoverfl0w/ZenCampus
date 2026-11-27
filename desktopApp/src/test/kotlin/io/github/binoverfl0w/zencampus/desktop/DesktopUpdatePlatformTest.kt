package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.data.UpdateInstall
import io.github.binoverfl0w.zencampus.model.CampusException
import io.github.binoverfl0w.zencampus.model.ReleaseAsset
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Which installations replace themselves, and the macOS signature checks, with the system tools replaced. */
class DesktopUpdatePlatformTest {
    private val team = "ABCDE12345"
    private lateinit var root: File

    @BeforeTest fun setUp() { root = Files.createTempDirectory("zencampus-updates").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    private fun macApp(parent: File, name: String = "zencampus.app"): File = File(parent, name).apply {
        File(this, "Contents/MacOS").mkdirs()
        File(this, "Contents/MacOS/zencampus").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        File(this, "Contents/app").mkdirs()
        File(this, "Contents/app/zencampus.cfg").writeText("app.mainclass=io.github.binoverfl0w.zencampus.desktop.MainKt\n")
        File(this, "Contents/app/resources").mkdirs()
    }

    /** codesign and spctl answers per app path; ditto unpacks [unpackedTeam]'s app into the staging folder. */
    private inner class FakeTools(
        val teams: MutableMap<String, String?> = mutableMapOf(),
        val invalid: Set<String> = emptySet(),
        val rejected: Set<String> = emptySet(),
        val unpackedTeam: String? = team,
    ) : DesktopTools {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>): ToolResult {
            commands += command
            val path = command.last()
            return when {
                command[0] == "/usr/bin/ditto" -> {
                    val app = macApp(File(path))
                    teams[app.canonicalPath] = unpackedTeam
                    ToolResult(0)
                }
                command[0] == "/usr/bin/codesign" && command[1] == "-dvv" -> {
                    val signer = teams[File(path).canonicalPath]
                    ToolResult(0, "Executable=$path/Contents/MacOS/zencampus\nTeamIdentifier=${signer ?: "not set"}\n")
                }
                command[0] == "/usr/bin/codesign" -> ToolResult(if (File(path).canonicalPath in invalid) 1 else 0)
                command[0] == "/usr/sbin/spctl" -> ToolResult(if (File(path).canonicalPath in rejected) 3 else 0)
                else -> ToolResult(127)
            }
        }
    }

    private fun platform(bundle: InstalledBundle?, tools: DesktopTools) =
        DesktopUpdatePlatform(DesktopPaths(File(root, "profile")), CoroutineScope(Dispatchers.Unconfined), emptyList(), tools) { bundle }

    private val assets = listOf(
        ReleaseAsset("zencampus-0.13.0.apk", "https://github.com/a/b/0.apk", 1, null),
        ReleaseAsset("zencampus-0.13.0-linux-x86_64.tar.gz", "https://github.com/a/b/1.tar.gz", 1, null),
        ReleaseAsset("zencampus-0.13.0-macos-arm64.dmg", "https://github.com/a/b/2.dmg", 1, null),
        ReleaseAsset("zencampus-0.13.0-macos-arm64.zip", "https://github.com/a/b/3.zip", 1, null),
    )

    @Test fun eachInstallationPicksItsOwnArchive() {
        val tools = FakeTools()
        assertEquals("zencampus-0.13.0-macos-arm64.zip",
            platform(InstalledBundle(File(root, "zencampus.app"), BundleKind.MAC, team), tools).installable(assets)?.name)
        assertEquals("zencampus-0.13.0-linux-x86_64.tar.gz",
            platform(InstalledBundle(File(root, "zencampus"), BundleKind.LINUX), tools).installable(assets)?.name)
        assertNull(platform(null, tools).installable(assets))
    }

    @Test fun onlyASignedWritableAppleSiliconAppReplacesItself() {
        val applications = File(root, "Applications").apply { mkdirs() }
        val app = macApp(applications)
        val launcher = File(app, "Contents/MacOS/zencampus").path
        val resources = File(app, "Contents/app/resources").path
        val signed = FakeTools(mutableMapOf(app.canonicalPath to team))
        assertEquals(InstalledBundle(app.canonicalFile, BundleKind.MAC, team),
            DesktopUpdatePlatform.findBundle("Mac OS X", "aarch64", launcher, resources, signed))
        assertEquals(app.canonicalFile, DesktopUpdatePlatform.findBundle("Mac OS X", "aarch64", null, resources, signed)?.root)
        assertNull(DesktopUpdatePlatform.findBundle("Mac OS X", "aarch64", launcher, resources, FakeTools()), "ad-hoc or unsigned")
        assertNull(DesktopUpdatePlatform.findBundle("Mac OS X", "x86_64", launcher, resources, signed), "Intel")
        assertNull(DesktopUpdatePlatform.findBundle("Windows 11", "amd64", launcher, resources, signed))
        assertNull(DesktopUpdatePlatform.findBundle("Mac OS X", "aarch64", null, null, signed), "development run")
        // Windows ignores a folder's read-only flag for writing, so this part is Linux and macOS only.
        if (isWindows) return
        applications.setWritable(false)
        try { assertNull(DesktopUpdatePlatform.findBundle("Mac OS X", "aarch64", launcher, resources, signed), "read-only folder") }
        finally { applications.setWritable(true) }
    }

    @Test fun teamIdentifierIsReadFromCodesign() {
        assertEquals(team, DesktopUpdatePlatform.parseSigningTeam("Identifier=io.github.binoverfl0w.zencampus.desktop\nTeamIdentifier=$team\nSealed Resources version=2\n"))
        assertNull(DesktopUpdatePlatform.parseSigningTeam("Signature=adhoc\nTeamIdentifier=not set\n"))
        assertNull(DesktopUpdatePlatform.parseSigningTeam("code object is not signed at all"))
    }

    private fun install(tools: FakeTools): Result<UpdateInstall> {
        val applications = File(root, "Applications").apply { mkdirs() }
        val app = macApp(applications)
        tools.teams[app.canonicalPath] = team
        val platform = platform(InstalledBundle(app.canonicalFile, BundleKind.MAC, team), tools)
        return runBlocking {
            platform.writer(assets[3]).apply { write(byteArrayOf(1, 2, 3), 3); finish() }
            runCatching { platform.install(assets[3]) }
        }
    }

    private val staged get() = File(root, "Applications/.zencampus-update/zencampus.app").canonicalPath

    @Test fun anUpdateFromTheSameTeamThatGatekeeperAcceptsIsStaged() {
        val tools = FakeTools()
        assertEquals(UpdateInstall.RESTART, install(tools).getOrThrow())
        assertTrue(File(staged, "Contents/MacOS/zencampus").canExecute())
        assertTrue(tools.commands.any { it[0] == "/usr/sbin/spctl" }, "Gatekeeper was consulted")
    }

    @Test fun anUpdateSignedByAnotherTeamIsRefusedAndRemoved() {
        val result = install(FakeTools(unpackedTeam = "ZZZZZ99999"))
        assertIs<CampusException>(result.exceptionOrNull())
        assertFalse(File(staged).exists())
    }

    @Test fun anUnsignedOrRejectedUpdateIsRefused() {
        assertIs<CampusException>(install(FakeTools(unpackedTeam = null)).exceptionOrNull())
        assertIs<CampusException>(install(FakeTools(invalid = setOf(staged))).exceptionOrNull())
        assertIs<CampusException>(install(FakeTools(rejected = setOf(staged))).exceptionOrNull())
    }

    private fun windowsApp(parent: File): File = File(parent, WINDOWS_LAUNCHER).apply {
        File(this, "app").mkdirs()
        File(this, "$WINDOWS_LAUNCHER.exe").writeText("MZ")
        File(this, "app/$WINDOWS_LAUNCHER.cfg").writeText("app.mainclass=io.github.binoverfl0w.zencampus.desktop.MainKt\n")
    }

    @Test fun aWindowsInstallUpdatesThroughTheNewerInstaller() = runBlocking {
        val app = windowsApp(root)
        val launcher = File(app, "$WINDOWS_LAUNCHER.exe").absolutePath
        assertEquals(InstalledBundle(app.canonicalFile, BundleKind.WINDOWS),
            DesktopUpdatePlatform.findBundle("Windows 11", "amd64", launcher, null, FakeTools()))
        assertNull(DesktopUpdatePlatform.findBundle("Windows 11", "aarch64", launcher, null, FakeTools()), "only x64 builds exist")
        assertNull(DesktopUpdatePlatform.findBundle("Windows 11", "amd64", null, null, FakeTools()), "development run")

        val assets = listOf(ReleaseAsset("zencampus-0.13.0-linux-x86_64.tar.gz", "https://example.org/a", 1),
            ReleaseAsset("zencampus-0.13.0-windows-x86_64.msi", "https://example.org/b", 1))
        val platform = platform(InstalledBundle(app.canonicalFile, BundleKind.WINDOWS), FakeTools())
        assertEquals("zencampus-0.13.0-windows-x86_64.msi", platform.installable(assets)?.name)

        val download = platform.writer(assets[1])
        val msi = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0, 0)
        download.write(msi, msi.size); download.finish()
        assertEquals(UpdateInstall.RESTART, platform.install(assets[1]))

        val html = "<html>not an installer</html>".toByteArray()
        platform.writer(assets[1]).apply { write(html, html.size); finish() }
        assertTrue(runCatching { platform.install(assets[1]) }.exceptionOrNull() is CampusException, "anything but an MSI is refused")
    }

    @Test fun windowsReadsOnlyCappedConnectionsAsMetered() {
        assertTrue(costIsMetered("Fixed\r\n")); assertTrue(costIsMetered("Variable"))
        assertFalse(costIsMetered("Unrestricted")); assertFalse(costIsMetered("Unknown")); assertFalse(costIsMetered(""))
    }
}
