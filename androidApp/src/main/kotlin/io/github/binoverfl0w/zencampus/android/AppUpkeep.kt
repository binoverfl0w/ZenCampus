package io.github.binoverfl0w.zencampus.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.model.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where Android keeps the log, waiting crash reports and update downloads. */
internal class AndroidUpkeepFiles(context: Context) {
    val logs = File(context.filesDir, "logs")
    val crashes = File(context.filesDir, "crashes")
    val updates = File(context.cacheDir, "updates")
    val shared = File(context.cacheDir, "diagnostics")
}

/** Installs the log and the crash handler before anything else runs, so a crash during start-up is recorded too. */
internal fun startAndroidDiagnostics(context: Context): FileLogSink {
    val files = AndroidUpkeepFiles(context)
    val log = FileLogSink(files.logs)
    ZenLog.install(log)
    if (BuildConfig.DEBUG) {
        ZenLog.minimum = LogLevel.DEBUG
        ZenLog.install { line ->
            val priority = when (line.level) { LogLevel.DEBUG -> Log.DEBUG; LogLevel.INFO -> Log.INFO; LogLevel.WARN -> Log.WARN; LogLevel.ERROR -> Log.ERROR }
            Log.println(priority, "ZenCampus/${line.tag}", line.message)
        }
    }
    val crashes = if (CrashDsn.parse(BuildConfig.CRASH_DSN) != null) FileCrashStore(files.crashes) else null
    CrashCapture.install(androidRuntime(context), crashes)
    // A fresh start never resumes an earlier download, so its files are only clutter.
    files.updates.deleteRecursively()
    ZenLog.info("app", "started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on API ${Build.VERSION.SDK_INT}")
    return log
}

internal fun androidRuntime(context: Context) = RuntimeInfo(BuildConfig.VERSION_NAME, "android", "Android",
    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    // Written into release builds after R8 runs (see build.gradle.kts); debug builds have none.
    runCatching { context.assets.open(CRASH_MAPPING_ASSET).bufferedReader().use { it.readText().trim() } }.getOrDefault(""))

private const val CRASH_MAPPING_ASSET = "crash-mapping-id.txt"

/** Writes the log with a short description of the installation and opens the share sheet for it. */
internal fun shareAndroidLog(context: Context, log: FileLogSink) {
    val files = AndroidUpkeepFiles(context)
    files.shared.mkdirs()
    val runtime = androidRuntime(context)
    val file = File(files.shared, "zencampus-log.txt")
    file.writeText(log.export("ZenCampus ${runtime.release} · ${runtime.osName} ${runtime.osVersion} · ${runtime.device}"))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "ZenCampus diagnostic log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Android updates: the APK from the release goes to the system installer, which asks the student to confirm. Before
 * that, the download must be this app, newer, and signed with the same key; Android would refuse anything else, but
 * checking first gives a clear message instead of a vague installer error.
 */
internal class AndroidUpdatePlatform(private val context: Context, private val openUrl: (String) -> Unit) : UpdatePlatform {
    private val files = AndroidUpkeepFiles(context)
    private val state = context.getSharedPreferences("zen_updates", Context.MODE_PRIVATE)

    override fun installable(assets: List<ReleaseAsset>): ReleaseAsset? = assets.firstOrNull { it.name.endsWith(".apk") }

    override suspend fun writer(asset: ReleaseAsset): UpdateWriter = withContext(Dispatchers.IO) {
        files.updates.deleteRecursively()
        HashingFileWriter(File(files.updates, "update.apk"))
    }

    override suspend fun install(asset: ReleaseAsset): UpdateInstall = withContext(Dispatchers.IO) {
        val apk = File(files.updates, "update.apk")
        verify(apk)
        val packages = context.packageManager
        if (!packages.canRequestPackageInstalls()) {
            ZenLog.info(AppUpdates.TAG, "asking for permission to install updates")
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@withContext UpdateInstall.NEEDS_PERMISSION
        }
        val installer = packages.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("zencampus.apk", 0, apk.length()).use { output -> input.copyTo(output); session.fsync(output) }
                }
                val result = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        ZenLog.info(AppUpdates.TAG, "handed the update to the system installer")
        UpdateInstall.STARTED
    }

    private fun verify(apk: File) {
        val packages = context.packageManager
        val archive = archiveInfo(packages, apk) ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        val installed = installedInfo(packages)
        when {
            archive.packageName != context.packageName -> "a different app"
            PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed) -> "not newer"
            signers(archive).intersect(signers(installed)).isEmpty() -> "signed with a different key"
            else -> null
        }?.let { reason ->
            ZenLog.warn(AppUpdates.TAG, "downloaded APK refused: $reason")
            throw CampusException(FailureKind.INVALID_RESPONSE)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(packages: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) packages.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else packages.getPackageArchiveInfo(apk.path, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(packages: PackageManager): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else packages.getPackageInfo(context.packageName, flags)
    }

    /** Every certificate the package is or was signed with, so a rotated key still matches. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else info.signatures
        return signatures.orEmpty().map { it.toCharsString() }.toSet()
    }

    override fun openPage(url: String) = openUrl(url)
    override fun readState(): String? = state.getString("state", null)
    override fun writeState(value: String) { state.edit().putString("state", value).apply() }
}

/** The system installer's answer: it asks for confirmation first, then reports success or why it stopped. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { confirm ->
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { ZenLog.warn(AppUpdates.TAG, "installer confirmation could not open", it) }
                }
            PackageInstaller.STATUS_SUCCESS -> ZenLog.info(AppUpdates.TAG, "update installed")
            else -> {
                ZenLog.warn(AppUpdates.TAG, "installer stopped with status $status")
                (context.applicationContext as? ZenApplication)?.container?.updates?.installFinished(false)
            }
        }
    }
}
