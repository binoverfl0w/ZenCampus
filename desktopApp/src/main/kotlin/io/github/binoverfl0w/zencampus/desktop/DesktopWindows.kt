package io.github.binoverfl0w.zencampus.desktop

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WinCrypt
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

// Windows: what Linux and macOS get from the keyring or Keychain, XDG folders, notify-send and /sys.

internal val isMac: Boolean get() = System.getProperty("os.name").startsWith("Mac")

internal val isWindows: Boolean get() = System.getProperty("os.name").startsWith("Windows")

/** The installed launcher's name, ZenCampus.exe; Windows matches it to zencampus.exe from older installs. */
internal const val WINDOWS_LAUNCHER = "ZenCampus"

/** %APPDATA% (Roaming): the per-user MSI installs into %LOCALAPPDATA%\ZenCampus, so data stays out of that folder. */
internal fun windowsDataRoot(): File = File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
    ?: "${System.getProperty("user.home")}\\AppData\\Roaming", "ZenCampus")

internal fun windowsSignInDirectory(): File = File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_LocalAppData),
    "ZenCampusData/remembered-sign-in")

/** The Downloads folder the student chose in Windows, which may have been moved. */
internal fun windowsDownloads(): File = runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)) }
    .getOrElse { File(System.getProperty("user.home"), "Downloads") }

/**
 * Sessions encrypted with DPAPI for this Windows user, one file per service. Only the same user on the same PC can
 * decrypt them; the profile and service are mixed in as entropy, so a file cannot stand in for another service.
 */
internal class WindowsSessionVault(private val directory: File, private val profile: String,
                                   private val localDirectory: File = directory) : SessionVault {
    private fun file(service: String) = File(if (service.startsWith("remembered-sign-in:")) localDirectory else directory,
        "session-" + digest("$profile:$service").take(32) + ".bin")
    private fun entropy(service: String) = "io.github.binoverfl0w.zencampus:$profile:$service".toByteArray()

    override fun read(service: String): String? {
        val file = file(service).takeIf { it.isFile } ?: return null
        return runCatching {
            String(Crypt32Util.cryptUnprotectData(file.readBytes(), entropy(service), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null))
        }.getOrNull()
    }

    override fun write(service: String, value: String) {
        val sealed = Crypt32Util.cryptProtectData(value.toByteArray(), entropy(service), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "ZenCampus", null)
        atomicPrivateWrite(file(service), sealed)
    }

    override fun clear(service: String) { java.nio.file.Files.deleteIfExists(file(service).toPath()) }
}

/** On battery below 15 %; desktops and unknown states count as plugged in. */
internal fun windowsBatteryLow(): Boolean = runCatching {
    val status = SystemPowerStatus()
    WindowsPower.api.GetSystemPowerStatus(status) && status.ACLineStatus.toInt() == 0 &&
        status.BatteryLifePercent.toInt() in 0..14
}.getOrDefault(false)

/** kernel32's GetSystemPowerStatus, which JNA's platform library does not map. */
private object WindowsPower {
    interface Api : Library { fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean }
    val api: Api by lazy { Native.load("kernel32", Api::class.java) }
}

/** SYSTEM_POWER_STATUS. ACLineStatus 0 is on battery; BatteryLifePercent 255 is unknown (-1 as a signed byte). */
@Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
class SystemPowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

/**
 * Whether Windows marks the internet connection as metered (a phone hotspot, a data plan). Asking needs Windows
 * PowerShell, so the answer is kept for ten minutes; when it cannot be read, the connection counts as unmetered.
 */
internal object WindowsCost {
    private val QUERY = "[void][Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]; " +
        "${'$'}p = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile(); " +
        "if (${'$'}p) { ${'$'}p.GetConnectionCost().NetworkCostType } else { 'None' }"
    @Volatile private var checked = 0L
    @Volatile private var last = false

    fun metered(now: Long = System.currentTimeMillis()): Boolean {
        if (now - checked < 10 * 60_000L) return last
        checked = now
        last = runCatching {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", QUERY)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching last }
            costIsMetered(process.inputStream.bufferedReader().use { it.readText() })
        }.getOrDefault(false)
        return last
    }
}

/** NetworkCostType: Unrestricted, Fixed, Variable or Unknown; only the two capped kinds are metered. */
internal fun costIsMetered(output: String): Boolean = output.trim().lowercase() in setOf("fixed", "variable")

/** A Windows notification through a tray icon that is removed again shortly after. True when it was shown. */
internal fun windowsNotify(title: String, body: String): Boolean {
    if (!SystemTray.isSupported()) return false
    val shown = CompletableFuture<Boolean>()
    SwingUtilities.invokeLater {
        runCatching {
            val image = DesktopContainer::class.java.getResource("/zencampus-tray.png")?.let { Toolkit.getDefaultToolkit().getImage(it) }
                ?: return@runCatching false
            val icon = TrayIcon(image, "ZenCampus").apply { isImageAutoSize = true }
            SystemTray.getSystemTray().add(icon)
            icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
            javax.swing.Timer(15_000) { SystemTray.getSystemTray().remove(icon) }.apply { isRepeats = false; start() }
            true
        }.let { shown.complete(it.getOrDefault(false)) }
    }
    return runCatching { shown.get(10, TimeUnit.SECONDS) }.getOrDefault(false)
}

/**
 * Applies a downloaded MSI after ZenCampus has quit: waits for this process, runs the installer with a progress bar only
 * (a per-user install needs no administrator), then starts the app again. Arguments: process id, MSI, app, log.
 */
internal val WINDOWS_APPLY_SCRIPT = """
param([int]${'$'}AppProcess, [string]${'$'}Msi, [string]${'$'}App, [string]${'$'}Log)
try { Wait-Process -Id ${'$'}AppProcess -Timeout 120 -ErrorAction SilentlyContinue } catch { }
${'$'}install = Start-Process -FilePath "msiexec.exe" -ArgumentList @("/i", "`"${'$'}Msi`"", "/passive", "/norestart", "/l*v", "`"${'$'}Log`"") -Wait -PassThru
Remove-Item -LiteralPath ${'$'}Msi -ErrorAction SilentlyContinue
# 0 is done; 3010 is done but Windows wants a restart later. Either way, or after a failure, the app starts again.
Start-Process -FilePath ${'$'}App
exit ${'$'}install.ExitCode
"""

/** An MSI is an OLE compound file; anything else that arrives under its name is refused. */
internal fun looksLikeMsi(file: File): Boolean = runCatching {
    file.inputStream().use { input -> input.readNBytes(8).contentEquals(MSI_MAGIC) }
}.getOrDefault(false)

private val MSI_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
