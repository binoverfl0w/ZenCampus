package io.github.binoverfl0w.zencampus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.AppUpdates
import io.github.binoverfl0w.zencampus.data.CrashReports
import io.github.binoverfl0w.zencampus.data.UpdateFailure
import io.github.binoverfl0w.zencampus.data.UpdateState
import io.github.binoverfl0w.zencampus.model.AppSettings
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenDivider
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.fileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app's own upkeep, owned by each platform's container: updates, crash reports and sharing the diagnostic log.
 * Any part may be missing (development builds have no update repository or crash endpoint).
 */
class AppMaintenance(
    val scope: CoroutineScope,
    val updates: AppUpdates? = null,
    val crashes: CrashReports? = null,
    val shareLog: (() -> Unit)? = null,
    val iosPreviewUnavailable: Boolean = false,
)

val LocalAppMaintenance = staticCompositionLocalOf<AppMaintenance?> { null }

/** The update prompt and the after-crash question; shown over every board, signed in or not. */
@Composable
internal fun ReleasePrompts(settings: AppSettings, onSettings: (AppSettings) -> Unit) {
    val maintenance = LocalAppMaintenance.current ?: return
    maintenance.updates?.takeIf { it.configured }?.let { UpdateDialog(it) }
    maintenance.crashes?.takeIf { it.configured }?.let { CrashDialog(it, maintenance.scope, settings, onSettings) }
}

@Composable
private fun UpdateDialog(updates: AppUpdates) {
    val words = LocalWords.current
    val state by updates.state.collectAsState()
    val dismissed by updates.dismissed.collectAsState()
    // Once the student taps Update here, the dialog follows the download to its end even though it no longer "prompts".
    var following by remember { mutableStateOf(false) }
    val current = state
    val prompting = current is UpdateState.Available && current.release.version != dismissed
    val settled = current is UpdateState.Idle || current is UpdateState.Checking || current is UpdateState.Current
    LaunchedEffect(settled) { if (settled) following = false }
    if (!prompting && !(following && !settled)) return
    val close = { if (current is UpdateState.Available) updates.dismiss(current.release); following = false }
    ZenDialog(onDismissRequest = close, modifier = Modifier.testTag("update-dialog"),
        title = { Text(when (current) {
            is UpdateState.Available -> words.release("prompt_title").replace("%1", current.release.version)
            is UpdateState.Downloading -> words.release("downloading_title")
            is UpdateState.Ready -> words.release(if (current.restart) "restart_title" else "install_title")
            is UpdateState.Failed -> words.release("failed_title")
            else -> ""
        }) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (current) {
                    is UpdateState.Available -> {
                        if (current.asset == null) Text(words.release("prompt_body_manual"))
                        Text(current.release.notes.ifBlank { words.release("no_notes") }, Modifier.testTag("update-notes"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        current.asset?.takeIf { it.size > 0 }?.let { Text(fileSize(it.size), style = MaterialTheme.typography.labelSmall) }
                    }
                    is UpdateState.Downloading -> DownloadProgress(current)
                    is UpdateState.Ready -> Text(when {
                        current.restart -> words.release("restart_body").replace("%1", current.release.version)
                        current.needsPermission -> words.release("permission_body")
                        else -> words.release("install_body")
                    })
                    is UpdateState.Failed -> Text(failureText(current.failure))
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (current) {
                is UpdateState.Available -> DialogButton(words.release(if (current.asset == null) "release_page" else "update"), {
                    if (current.asset == null) { updates.openReleasePage(); close() } else { following = true; updates.update() }
                }, Modifier.testTag("update-confirm"), primary = true)
                is UpdateState.Downloading -> DialogButton(words.release("hide"), { following = false })
                is UpdateState.Ready -> DialogButton(words.release(if (current.restart) "restart" else "install"), updates::update,
                    Modifier.testTag("update-confirm"), primary = true)
                is UpdateState.Failed -> DialogButton(words.release("try_again"), updates::update, primary = true)
                else -> {}
            }
        },
        dismissButton = {
            when (current) {
                is UpdateState.Available, is UpdateState.Ready -> DialogButton(words.release("later"), close)
                is UpdateState.Downloading -> DialogButton(words.release("stop"), updates::cancel, destructive = true)
                is UpdateState.Failed -> DialogButton(words.release("release_page"), { updates.openReleasePage(); following = false })
                else -> {}
            }
        })
}

@Composable
private fun DownloadProgress(state: UpdateState.Downloading) {
    val fraction = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("update-progress"))
    Text("${fileSize(state.done)} / ${fileSize(state.total)}", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun failureText(failure: UpdateFailure): String = LocalWords.current.release(when (failure) {
    UpdateFailure.OFFLINE -> "failed_offline"
    UpdateFailure.UNVERIFIED -> "failed_unverified"
    UpdateFailure.DOWNLOAD -> "failed_download"
    UpdateFailure.INSTALL -> "failed_install"
})

@Composable
private fun CrashDialog(crashes: CrashReports, scope: CoroutineScope, settings: AppSettings, onSettings: (AppSettings) -> Unit) {
    val words = LocalWords.current
    val pending by crashes.pending.collectAsState()
    var closed by remember { mutableStateOf(false) }
    var always by remember { mutableStateOf(false) }
    if (settings.crashReports || pending.isEmpty() || closed) return
    ZenDialog(onDismissRequest = { closed = true }, modifier = Modifier.testTag("crash-dialog"),
        title = { Text(words.release("crash_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(words.release("crash_body"))
                Row(Modifier.fillMaxWidth().toggleable(always, role = Role.Checkbox, onValueChange = { always = it }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(always, onCheckedChange = null)
                    Text(words.release("crash_always"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { DialogButton(words.release("crash_send"), {
            closed = true
            if (always) onSettings(settings.copy(crashReports = true))
            scope.launch { crashes.send() }
        }, Modifier.testTag("crash-send"), primary = true) },
        dismissButton = { DialogButton(words.release("crash_skip"), { closed = true; scope.launch { crashes.discard() } }) })
}

/** The Settings section: the version with its update state, automatic checks, crash reports and the log. */
@Composable
internal fun ReleaseSettings(settings: AppSettings, onSettings: (AppSettings) -> Unit) {
    val maintenance = LocalAppMaintenance.current ?: return
    val words = LocalWords.current
    val updates = maintenance.updates?.takeIf { it.configured }
    val crashes = maintenance.crashes?.takeIf { it.configured }
    val shareLog = maintenance.shareLog
    if (updates == null && crashes == null && shareLog == null && !maintenance.iosPreviewUnavailable) return
    Column {
        SectionTitle(words.release("section"))
        Spacer(Modifier.height(10.dp))
        ZenPanel(Modifier.fillMaxWidth().testTag("release-settings"), padding = 0.dp) {
            var divide = false
            if (maintenance.iosPreviewUnavailable) {
                Text(words.release(if (updates == null) "ios_preview_unconfigured" else "ios_preview_crashes"), Modifier.padding(16.dp))
                divide = true
            }
            if (updates != null) {
                if (divide) ZenDivider()
                UpdateRow(updates)
                ZenDivider()
                SettingToggle(words.release("auto_check"), words.release("auto_check_body"), settings.checkUpdates) {
                    onSettings(settings.copy(checkUpdates = it))
                }
                divide = true
            }
            if (crashes != null) {
                if (divide) ZenDivider()
                SettingToggle(words.release("crash_reports"), words.release("crash_reports_body"), settings.crashReports) { enabled ->
                    onSettings(settings.copy(crashReports = enabled))
                    if (enabled) maintenance.scope.launch { crashes.send() }
                }
                divide = true
            }
            if (shareLog != null) {
                if (divide) ZenDivider()
                SettingsAction(words.release("share_log"), words.release("share_log_body"), Symbol.SHARE, shareLog)
            }
        }
    }
}

@Composable
private fun UpdateRow(updates: AppUpdates) {
    val words = LocalWords.current
    val now = LocalNow.current
    val state by updates.state.collectAsState()
    val current = state
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ZenIconTile(Symbol.LEAF)
            Column(Modifier.weight(1f)) {
                Text(words.release("version").replace("%1", updates.version), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(when (current) {
                    UpdateState.Idle -> words.release("not_checked")
                    UpdateState.Checking -> words.release("checking")
                    is UpdateState.Current -> words.release("up_to_date").replace("%1", words.age(current.checkedAt, now))
                    is UpdateState.Available -> words.release("available").replace("%1", current.release.version)
                    is UpdateState.Downloading -> words.release("downloading").replace("%1", current.release.version)
                        .replace("%2", "${fileSize(current.done)} / ${fileSize(current.total)}")
                    is UpdateState.Ready -> words.release(if (current.restart) "ready_restart" else "ready_install").replace("%1", current.release.version)
                    is UpdateState.Failed -> failureText(current.failure)
                }, Modifier.testTag("update-status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val (label, action) = when (current) {
                is UpdateState.Available -> words.release(if (current.asset == null) "release_page" else "update") to updates::update
                is UpdateState.Downloading -> words.release("stop") to updates::cancel
                is UpdateState.Ready -> words.release(if (current.restart) "restart" else "install") to updates::update
                is UpdateState.Failed -> words.release("try_again") to updates::checkNow
                else -> words.release("check") to updates::checkNow
            }
            val prominent = current is UpdateState.Available || current is UpdateState.Ready
            ZenButton(label, action, Modifier.testTag("update-action"), style = if (prominent) ButtonStyle.PRIMARY else ButtonStyle.TONAL,
                enabled = current !is UpdateState.Checking)
        }
        if (current is UpdateState.Downloading) DownloadProgress(current)
        if (current is UpdateState.Failed && current.release != null)
            ZenLink(words.release("release_page"), updates::openReleasePage, external = true)
    }
}
