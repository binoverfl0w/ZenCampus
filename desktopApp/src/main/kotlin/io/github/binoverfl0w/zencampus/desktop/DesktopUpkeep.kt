package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.model.*
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The version and release settings built into the jar (see `releaseInfo` in the desktop build). */
internal data class DesktopRelease(val version: String, val updateRepo: String = "", val crashDsn: String = "") {
    companion object {
        fun load(): DesktopRelease {
            val properties = Properties()
            DesktopRelease::class.java.getResourceAsStream("/zencampus-release.properties")?.use(properties::load)
            return DesktopRelease(properties.getProperty("version")?.takeIf { it.isNotBlank() } ?: "Development",
                properties.getProperty("updateRepo").orEmpty().trim(), properties.getProperty("crashDsn").orEmpty().trim())
        }
    }
}

internal fun desktopRuntime(release: DesktopRelease) = RuntimeInfo(release.version,
    when { System.getProperty("os.name").startsWith("Mac") -> "macos"; isWindows -> "windows"; else -> "linux" },
    System.getProperty("os.name"), "${System.getProperty("os.version")} ${System.getProperty("os.arch")}")

/** The log and crash handler, installed before the window opens. */
internal fun startDesktopDiagnostics(paths: DesktopPaths, release: DesktopRelease, console: Boolean = System.getenv("ZENCAMPUS_LOG") == "1"): FileLogSink {
    val log = FileLogSink(privateDirectory(File(paths.data, "logs")))
    ZenLog.install(log)
    if (console) { ZenLog.minimum = LogLevel.DEBUG; ZenLog.install { System.err.println(it.format()) } }
    val crashes = if (CrashDsn.parse(release.crashDsn) != null) FileCrashStore(privateDirectory(File(paths.data, "crashes"))) else null
    CrashCapture.install(desktopRuntime(release), crashes)
    File(paths.cache, "updates").deleteRecursively()
    ZenLog.info("app", "started ${release.version} on ${desktopRuntime(release).let { "${it.osName} ${it.osVersion}" }}")
    return log
}

/** A command-line tool the updater runs; tests replace it. [exit] is -1 when the tool did not finish in time. */
internal fun interface DesktopTools {
    fun run(command: List<String>): ToolResult
}

internal data class ToolResult(val exit: Int, val output: String = "")

internal object SystemTools : DesktopTools {
    override fun run(command: List<String>): ToolResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().use { output.append(it.readText()) } }.apply { isDaemon = true; start() }
        if (!process.waitFor(5, TimeUnit.MINUTES)) { process.destroyForcibly(); return ToolResult(-1) }
        reader.join(1_000)
        return ToolResult(process.exitValue(), output.toString())
    }
}

/** The kinds of installation that can replace themselves, with the release asset and folder each one uses. */
internal enum class BundleKind(val asset: Regex, val download: String, val unpacked: String) {
    LINUX(Regex("""zencampus-[0-9][^/]*-linux-x86_64\.tar\.gz"""), "update.tar.gz", "zencampus"),
    MAC(Regex("""zencampus-[0-9][^/]*-macos-arm64\.zip"""), "update.zip", "zencampus.app"),
    /** Windows installs from an MSI; an update runs the newer MSI, which replaces the installed version. */
    WINDOWS(Regex("""zencampus-[0-9][^/]*-windows-x86_64\.msi"""), "update.msi", ""),
}

/** The installed app this process runs from. [team] is the Apple developer team that signed a macOS app. */
internal data class InstalledBundle(val root: File, val kind: BundleKind, val team: String? = null)

/**
 * Desktop updates replace the bundle the student installed in a folder they own: on Linux the folder from
 * `install_desktop.sh` or an extracted release archive, on macOS the app in /Applications (or wherever it was dragged).
 * The new version is unpacked next to it while the app runs; the swap happens after the app quits, by a small script that
 * waits for this process to end, moves the new bundle into place and starts it.
 *
 * A macOS update must carry a valid signature from the same Apple developer team as the running app and pass Gatekeeper
 * (notarisation). Unsigned or ad-hoc signed builds therefore never replace themselves. Package-manager installs (deb, rpm),
 * apps macOS runs from a read-only location, Intel Macs and development runs only get a link to the release page.
 */
internal class DesktopUpdatePlatform(
    private val paths: DesktopPaths,
    private val scope: CoroutineScope,
    private val arguments: List<String>,
    private val tools: DesktopTools = SystemTools,
    locate: () -> InstalledBundle? = { runningBundle(tools) },
) : UpdatePlatform {
    /** Asks the app to quit; the update script takes over once it has. */
    var quit: () -> Unit = {}
    // Finding the bundle runs codesign on macOS, so it waits until the first update check.
    private val bundle by lazy(locate)
    private val downloads = File(paths.cache, "updates")
    private val stage get() = File(bundle!!.root.parentFile, ".zencampus-update")
    private val stateFile = File(paths.data, "updates.json")

    override fun installable(assets: List<ReleaseAsset>): ReleaseAsset? {
        val kind = bundle?.kind ?: return null
        return assets.firstOrNull { kind.asset.matches(it.name) }
    }

    override suspend fun writer(asset: ReleaseAsset): UpdateWriter = withContext(Dispatchers.IO) {
        downloads.deleteRecursively()
        HashingFileWriter(File(downloads, bundle!!.kind.download))
    }

    override suspend fun install(asset: ReleaseAsset): UpdateInstall = withContext(Dispatchers.IO) {
        val current = bundle!!
        val archive = File(downloads, current.kind.download)
        if (current.kind == BundleKind.WINDOWS) {
            // The checksum is already verified; the installer itself runs after the app has quit.
            if (!looksLikeMsi(archive)) {
                archive.delete()
                ZenLog.warn(AppUpdates.TAG, "downloaded update refused: it is not an installer")
                throw CampusException(FailureKind.INVALID_RESPONSE)
            }
            ZenLog.info(AppUpdates.TAG, "installer ready; it runs on restart")
            return@withContext UpdateInstall.RESTART
        }
        val target = stage
        target.deleteRecursively()
        check(target.mkdirs()) { "Cannot create the update folder" }
        // GNU tar and ditto refuse absolute and ".." member names, so the archive cannot write outside the staging folder.
        val unpack = when (current.kind) {
            BundleKind.LINUX -> listOf("tar", "-xzf", archive.absolutePath, "-C", target.absolutePath)
            BundleKind.MAC -> listOf("/usr/bin/ditto", "-x", "-k", archive.absolutePath, target.absolutePath)
            BundleKind.WINDOWS -> error("Windows updates are installers")
        }
        val result = tools.run(unpack)
        if (result.exit == -1) { target.deleteRecursively(); throw CampusException(FailureKind.STORAGE) }
        val unpacked = File(target, current.kind.unpacked)
        val problem = when {
            result.exit != 0 -> "could not be unpacked"
            !complete(unpacked, current.kind) -> "is incomplete"
            current.kind == BundleKind.MAC -> macSignatureProblem(unpacked, current.team, tools)
            else -> null
        }
        if (problem != null) {
            target.deleteRecursively()
            ZenLog.warn(AppUpdates.TAG, "downloaded update refused: it $problem")
            throw CampusException(FailureKind.INVALID_RESPONSE)
        }
        archive.delete()
        ZenLog.info(AppUpdates.TAG, "update unpacked; it applies on restart")
        UpdateInstall.RESTART
    }

    override fun restart() {
        val current = bundle ?: return
        if (current.kind == BundleKind.WINDOWS) {
            val script = File(downloads, "apply-update.ps1").apply { writeText(WINDOWS_APPLY_SCRIPT) }
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.absolutePath, ProcessHandle.current().pid().toString(), File(downloads, current.kind.download).absolutePath,
                File(current.root, "$WINDOWS_LAUNCHER.exe").absolutePath, File(paths.cache, "update-install.log").absolutePath)
                .redirectErrorStream(true).redirectOutput(File(paths.cache, "update.log")).start()
            ZenLog.info(AppUpdates.TAG, "restarting to install the update")
            quit()
            return
        }
        val script = File(stage, "apply-update.sh")
        script.writeText(if (current.kind == BundleKind.MAC) MAC_APPLY_SCRIPT else LINUX_APPLY_SCRIPT)
        script.setExecutable(true, true)
        val command = buildList {
            if (File("/usr/bin/setsid").canExecute() || File("/bin/setsid").canExecute()) add("setsid")
            addAll(listOf("/bin/sh", script.absolutePath, ProcessHandle.current().pid().toString(), current.root.absolutePath, stage.absolutePath))
            addAll(arguments)
        }
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(File(paths.cache, "update.log")).start()
        ZenLog.info(AppUpdates.TAG, "restarting to apply the update")
        quit()
    }

    override fun openPage(url: String) { scope.launch { openExternal(url) } }
    override fun readState(): String? = stateFile.takeIf { it.isFile }?.readText()
    override fun writeState(value: String) = atomicPrivateWrite(stateFile, value.toByteArray())

    companion object {
        val LINUX_ARCHIVE = BundleKind.LINUX.asset
        val MAC_ARCHIVE = BundleKind.MAC.asset

        fun complete(bundle: File, kind: BundleKind): Boolean = when (kind) {
            BundleKind.LINUX -> File(bundle, "bin/zencampus").canExecute() && File(bundle, "lib/app/zencampus.cfg").isFile
            BundleKind.MAC -> File(bundle, "Contents/MacOS/zencampus").canExecute() && File(bundle, "Contents/app/zencampus.cfg").isFile
            BundleKind.WINDOWS -> File(bundle, "$WINDOWS_LAUNCHER.exe").isFile && File(bundle, "app/$WINDOWS_LAUNCHER.cfg").isFile
        }

        /** The installed bundle this process runs from, when it can replace itself. */
        fun runningBundle(tools: DesktopTools = SystemTools): InstalledBundle? = findBundle(
            System.getProperty("os.name"), System.getProperty("os.arch"),
            System.getProperty("jpackage.app-path"), System.getProperty("compose.application.resources.dir"), tools)

        /**
         * Linux on x86-64 or macOS on Apple silicon, started through the bundle's launcher (which passes the resources
         * folder, and on macOS the launcher path), inside a folder the student can write to. A macOS app must also be
         * signed by a developer team, since an update is only accepted from the same team.
         */
        fun findBundle(os: String, arch: String, appPath: String?, resourcesDir: String?, tools: DesktopTools): InstalledBundle? {
            // The resources folder is <bundle>/lib/app/resources on Linux and <bundle>/Contents/app/resources on macOS.
            val fromResources = resourcesDir?.let(::File)?.parentFile?.parentFile?.parentFile
            val kind = when {
                os.startsWith("Linux") && arch in setOf("amd64", "x86_64") -> BundleKind.LINUX
                os.startsWith("Mac") && arch in setOf("aarch64", "arm64") -> BundleKind.MAC
                os.startsWith("Windows") && arch in setOf("amd64", "x86_64") -> BundleKind.WINDOWS
                else -> return null
            }
            if (kind == BundleKind.WINDOWS) {
                // jpackage names the launcher: <install folder>\ZenCampus.exe. msiexec replaces the folder's contents.
                val root = appPath?.let(::File)?.parentFile?.canonicalFile ?: return null
                return if (complete(root, kind)) InstalledBundle(root, kind) else null
            }
            val root = when (kind) {
                BundleKind.LINUX -> fromResources
                // jpackage names the launcher: <bundle>/Contents/MacOS/zencampus.
                BundleKind.MAC -> appPath?.let(::File)?.parentFile?.parentFile?.parentFile ?: fromResources
                BundleKind.WINDOWS -> null
            }?.canonicalFile ?: return null
            val parent = root.parentFile ?: return null
            if (kind == BundleKind.MAC && !root.name.endsWith(".app")) return null
            if (!complete(root, kind) || !parent.canWrite() || !root.canWrite()) return null
            if (kind == BundleKind.LINUX) return InstalledBundle(root, kind)
            val team = signingTeam(root, tools)
            if (team == null) { ZenLog.info(AppUpdates.TAG, "this app is not signed by a developer team; updates open the release page"); return null }
            return InstalledBundle(root, kind, team)
        }

        fun signingTeam(app: File, tools: DesktopTools): String? =
            tools.run(listOf("/usr/bin/codesign", "-dvv", app.absolutePath)).takeIf { it.exit == 0 }?.output?.let(::parseSigningTeam)

        /** The `TeamIdentifier=` line of `codesign -dvv`; ad-hoc and unsigned apps report "not set". */
        fun parseSigningTeam(output: String): String? =
            Regex("""^TeamIdentifier=([A-Z0-9]{10})\s*$""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)

        /** Why a downloaded macOS app must not replace the running one, or null when it may. */
        fun macSignatureProblem(app: File, team: String?, tools: DesktopTools): String? = when {
            team == null -> "replaces an unsigned app"
            tools.run(listOf("/usr/bin/codesign", "--verify", "--deep", "--strict", app.absolutePath)).exit != 0 -> "is not validly signed"
            signingTeam(app, tools) != team -> "is signed by a different developer"
            tools.run(listOf("/usr/sbin/spctl", "--assess", "--type", "execute", app.absolutePath)).exit != 0 -> "is not accepted by Gatekeeper"
            else -> null
        }

        /** Waits for the app to exit, swaps the bundles (rolling back if that fails) and starts whichever is in place. */
        private val LINUX_APPLY_SCRIPT = """
            #!/bin/sh
            pid="${'$'}1"; bundle="${'$'}2"; stage="${'$'}3"; shift 3
            waited=0
            while kill -0 "${'$'}pid" 2>/dev/null; do
              waited=${'$'}((waited + 1))
              if [ "${'$'}waited" -gt 600 ]; then echo "ZenCampus did not exit; update not applied"; exit 1; fi
              sleep 0.1
            done
            rm -rf "${'$'}bundle.previous"
            if mv "${'$'}bundle" "${'$'}bundle.previous"; then
              if mv "${'$'}stage/zencampus" "${'$'}bundle"; then
                rm -rf "${'$'}stage"
                echo "Update applied"
              else
                echo "Update could not be moved into place; keeping the current version"
                mv "${'$'}bundle.previous" "${'$'}bundle"
              fi
            fi
            exec "${'$'}bundle/bin/zencampus" "${'$'}@"
        """.trimIndent() + "\n"

        /** The macOS swap keeps the old app inside the hidden staging folder, so nothing extra appears in /Applications. */
        private val MAC_APPLY_SCRIPT = """
            #!/bin/sh
            pid="${'$'}1"; bundle="${'$'}2"; stage="${'$'}3"; shift 3
            waited=0
            while kill -0 "${'$'}pid" 2>/dev/null; do
              waited=${'$'}((waited + 1))
              if [ "${'$'}waited" -gt 600 ]; then echo "ZenCampus did not exit; update not applied"; exit 1; fi
              sleep 0.1
            done
            rm -rf "${'$'}stage/previous.app"
            if mv "${'$'}bundle" "${'$'}stage/previous.app"; then
              if mv "${'$'}stage/zencampus.app" "${'$'}bundle"; then
                rm -rf "${'$'}stage"
                echo "Update applied"
              else
                echo "Update could not be moved into place; keeping the current version"
                mv "${'$'}stage/previous.app" "${'$'}bundle"
              fi
            fi
            exec /usr/bin/open -n "${'$'}bundle" --args "${'$'}@"
        """.trimIndent() + "\n"
    }
}

/** Saves the log with a short description of the installation to the ZenCampus downloads folder and shows it. */
internal suspend fun exportDesktopLog(paths: DesktopPaths, release: DesktopRelease, log: FileLogSink): File = withContext(Dispatchers.IO) {
    val runtime = desktopRuntime(release)
    val file = File(paths.downloads, "zencampus-diagnostics.txt")
    file.writeText(log.export("ZenCampus ${runtime.release} · ${runtime.osName} ${runtime.osVersion}"))
    file
}
