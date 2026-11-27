package io.github.binoverfl0w.zencampus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import io.github.binoverfl0w.zencampus.data.platformToggleState
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.LocalAppVersion
import io.github.binoverfl0w.zencampus.ui.design.LocalBackgroundWorkWhileClosed
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenDivider
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.ZenTabs
import io.github.binoverfl0w.zencampus.ui.design.courseInitials
import io.github.binoverfl0w.zencampus.ui.design.fileSize
import io.github.binoverfl0w.zencampus.ui.services.LocalSavedSignIns
import io.github.binoverfl0w.zencampus.ui.services.SavedSignInsSection
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    snapshot: CampusSnapshot, repository: CampusRepository, settings: AppSettings,
    onSettings: (AppSettings) -> Unit, onDownloads: () -> Unit,
    onAccounts: () -> Unit = {},
) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val backgroundWhileClosed = LocalBackgroundWorkWhileClosed.current
    var confirmDownloads by remember { mutableStateOf(false) }
    val savedSignIns = LocalSavedSignIns.current
    LazyColumn(Modifier.fillMaxSize().testTag("settings-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            ZenPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(courseInitials(snapshot.account.name.ifBlank { snapshot.account.username }), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.account.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(snapshot.account.username,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            SectionTitle(words.text("appearance"))
            Spacer(Modifier.height(10.dp))
            ChoicePanel(ThemeMode.entries.map { it to words.text(when(it) {
                ThemeMode.SYSTEM -> "theme_system"; ThemeMode.LIGHT -> "theme_light"; ThemeMode.DARK -> "theme_dark"
            }) }, settings.theme) { onSettings(settings.copy(theme = it)) }
            Spacer(Modifier.height(12.dp))
            ZenPanel(Modifier.fillMaxWidth(), padding = 0.dp) {
                SettingToggle(words.text("reduce_motion"), words.text("reduce_motion_body"), settings.reduceMotion) {
                    onSettings(settings.copy(reduceMotion = it))
                }
            }
        }
        item {
            SectionTitle(words.text("language"))
            Spacer(Modifier.height(10.dp))
            ChoicePanel(AppLanguage.entries.map { it to words.text(when(it) {
                AppLanguage.SYSTEM -> "language_system"; AppLanguage.ENGLISH -> "language_en"; AppLanguage.GERMAN -> "language_de"
            }) }, settings.language) { onSettings(settings.copy(language = it)) }
        }
        item {
            ZenPanel(Modifier.fillMaxWidth(), padding = 0.dp) {
                SettingToggle(words.action("notifications"), words.action("notifications_body") + if (backgroundWhileClosed) "" else " " + words.text("while_open"), settings.courseworkReminders) {
                    onSettings(settings.copy(courseworkReminders = it))
                }
                if (backgroundWhileClosed) {
                    ZenDivider()
                    SettingToggle(words.ui("new_activity"),
                        words.ui("periodically_checks_messages_and_cou"),
                        settings.updateNotifications) { onSettings(settings.copy(updateNotifications = it)) }
                }
            }
        }
        item {
            SectionTitle(words.text("connection"))
            Spacer(Modifier.height(10.dp))
            ZenPanel(Modifier.fillMaxWidth(), padding = 0.dp) {
                SettingToggle(words.text("data_saver"), words.text("data_saver_body"), settings.dataSaver) {
                    onSettings(settings.copy(dataSaver = it))
                }
                ZenDivider()
                SettingToggle(words.text("background_refresh"), words.text(if (backgroundWhileClosed) "background_refresh_body" else "while_open"), settings.backgroundRefresh) {
                    onSettings(settings.copy(backgroundRefresh = it))
                }
            }
        }
        item {
            SectionTitle(words.text("storage"))
            Spacer(Modifier.height(10.dp))
            ZenPanel(Modifier.fillMaxWidth(), padding = 0.dp) {
                SettingsAction(words.text("downloads"), "${fileSize(snapshot.localDocuments.sumOf { it.size })} / 250 MB", Symbol.DOWNLOAD, onDownloads)
                ZenDivider()
                SettingsAction(words.text("clear_cache"), words.text("clear_cache_body"), Symbol.REFRESH, {
                    scope.launch { repository.clearCache() }
                })
                ZenDivider()
                SettingsAction(words.text("clear_downloads"), words.text("clear_downloads_body"), Symbol.FILE, { confirmDownloads = true })
            }
        }
        savedSignIns?.let { store -> item { SavedSignInsSection(store) } }
        item { ReleaseSettings(settings, onSettings) }
        item {
            SectionTitle(words.text("account"))
            Spacer(Modifier.height(10.dp))
            ZenButton(words.service("your_cards"), onAccounts, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), style = ButtonStyle.OUTLINE, symbol = Symbol.CARD)
        }
        item {
            Text(words.text("about_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SmallLabel(listOf("ZenCampus", LocalAppVersion.current).filter { it.isNotBlank() }.joinToString(" "))
        }
    }
    if (confirmDownloads) {
        ZenDialog(onDismissRequest = { confirmDownloads = false },
            title = { Text(words.text("confirm_remove")) },
            text = { Text(words.text("confirm_remove_body")) },
            confirmButton = { DialogButton(words.text("remove"), {
                confirmDownloads = false
                scope.launch { repository.clearDownloads() }
            }, primary = true, destructive = true) },
            dismissButton = { DialogButton(words.text("cancel"), { confirmDownloads = false }) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoicePanel(options: List<Pair<T, String>>, selected: T, onChoice: (T) -> Unit) {
    ZenTabs(options.map { it.second }, options.indexOfFirst { it.first == selected }, { onChoice(options[it].first) })
}

@Composable
internal fun SettingToggle(title: String, subtitle: String, checked: Boolean, inset: androidx.compose.ui.unit.Dp = 16.dp, onToggle: (Boolean) -> Unit) {
    val german = LocalWords.current.german
    val state = if (checked) { if (german) "An" else "On" } else { if (german) "Aus" else "Off" }
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onToggle)
        .platformToggleState(state).padding(horizontal = inset, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
internal fun SettingsAction(title: String, subtitle: String, symbol: Symbol, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ZenIconTile(symbol)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ZenIcon(Symbol.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
