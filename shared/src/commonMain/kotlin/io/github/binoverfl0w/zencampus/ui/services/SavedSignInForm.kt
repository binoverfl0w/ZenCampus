package io.github.binoverfl0w.zencampus.ui.services

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.design.*
import io.github.binoverfl0w.zencampus.ui.service
import kotlinx.coroutines.launch

internal val LocalSavedSignIns = staticCompositionLocalOf<SavedSignInStore?> { null }

internal val LocalSignInFields = staticCompositionLocalOf<@Composable (SignInKind, String, (String) -> Unit, String, (String) -> Unit, Boolean, () -> Unit) -> Unit> {
    { kind, username, onUsername, password, onPassword, visible, onToggle ->
        ComposeSignInFields(kind, username, onUsername, password, onPassword, visible, onToggle)
    }
}

fun Words.savedSignInUsername(kind: SignInKind) = service(if (kind == SignInKind.STUDY) "saved_email" else "saved_library_number")
fun Words.savedSignInAs(username: String) = service("saved_as").replace("%s", username)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedSignInBar(popup: SavedSignInPopup, currentPage: String?, onFill: () -> Unit, modifier: Modifier = Modifier) {
    val words = LocalWords.current
    val entries by popup.updates.collectAsState()
    val matching = entries.filter { it.kind == popup.kind }
    var selected by remember(popup) { mutableStateOf(popup.selected?.id) }
    val choice = matching.find { it.id == selected && popup.selected?.id == it.id }
    var chooser by remember { mutableStateOf(false) }
    var addingSite by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SavedSignIn?>(null) }
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (matching.isNotEmpty()) Box {
                ZenLink(choice?.let { words.savedSignInAs(it.username) } ?: words.service("saved_choose"), { chooser = true })
                DropdownMenu(chooser, { chooser = false }) {
                    matching.forEach { entry ->
                        DropdownMenuItem(
                            text = { Column {
                                Text(words.savedSignInAs(entry.username))
                                Text(listOfNotNull(entry.label, entry.site).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            } },
                            onClick = {
                                chooser = false
                                if (popup.pick(entry.id)) { selected = entry.id; onFill() }
                            },
                        )
                    }
                }
            }
            ZenLink(words.service("saved_add"), { addingSite = popup.siteForPage(currentPage) })
            if (choice != null) ZenLink(words.service("saved_edit"), { editing = choice })
        }
    }
    val entry = editing
    val site = entry?.site ?: addingSite
    if (site != null) {
        SavedSignInForm(popup.kind, site, entry, dismiss = { addingSite = null; editing = null }) { username, password, label, done ->
            popup.save(site, username, password, label, entry?.id) { success ->
                if (success) { selected = popup.selected?.id; onFill() }
                done(success)
            }
        }
    }
}

@Composable
internal fun SavedSignInForm(kind: SignInKind, site: String, entry: SavedSignIn?, dismiss: () -> Unit,
    onSave: (String, String, String?, (Boolean) -> Unit) -> Unit) {
    val words = LocalWords.current
    var username by remember { mutableStateOf(entry?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var label by remember { mutableStateOf(entry?.label.orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    ZenDialog(
        onDismissRequest = { if (!working) dismiss() },
        title = { Text(words.service(if (entry == null) "saved_add" else "saved_edit")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(words.service("saved_details"))
                Text(site, style = MaterialTheme.typography.bodySmall)
                LocalSignInFields.current(kind, username, { username = it }, password, { password = it }, visible, { visible = !visible })
                if (entry != null) Text(words.service("saved_keep_password"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(),
                    label = { Text(words.service("saved_label")) }, singleLine = true)
                if (failed) Text(words.service("saved_storage_error"), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            DialogButton(words.service("saved_save"), {
                working = true
                failed = false
                onSave(username, password, label) { success ->
                    working = false
                    if (success) { password = ""; dismiss() } else failed = true
                }
            }, primary = true, enabled = !working && username.isNotBlank() && (entry != null || password.isNotEmpty()))
        },
        dismissButton = { DialogButton(words.text("cancel"), dismiss, enabled = !working) },
    )
}

@Composable
private fun ComposeSignInFields(kind: SignInKind, username: String, onUsername: (String) -> Unit, password: String,
    onPassword: (String) -> Unit, visible: Boolean, onToggle: () -> Unit) {
    val words = LocalWords.current
    val study = kind == SignInKind.STUDY
    OutlinedTextField(username, onUsername, Modifier.fillMaxWidth().semantics {
        contentType = if (study) ContentType.Username + ContentType.EmailAddress else ContentType.Username
    }, label = { Text(words.savedSignInUsername(kind)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (study) KeyboardType.Email else KeyboardType.Text))
    OutlinedTextField(password, onPassword, Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        label = { Text(words.service("saved_password")) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation())
    ZenLink(words.service(if (visible) "saved_hide_password" else "saved_show_password"), onToggle)
}

@Composable
internal fun RemoveSavedSignInsOption(checked: Boolean, onChecked: (Boolean) -> Unit) {
    if (LocalSavedSignIns.current != null) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChecked),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(checked, onCheckedChange = null)
            Text(LocalWords.current.service("saved_sign_out"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedSignInsSection(store: SavedSignInStore) {
    val words = LocalWords.current
    val entries by store.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<SavedSignIn?>(null) }
    var rename by remember { mutableStateOf<SavedSignIn?>(null) }
    var delete by remember { mutableStateOf<SavedSignIn?>(null) }
    var removeAll by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(store) { failed = trySavedSignIn { store.load() } == null }

    fun change(action: suspend () -> Unit, onSuccess: () -> Unit) {
        working = true
        failed = false
        scope.launch {
            val result = trySavedSignIn(action)
            working = false
            if (result == null) failed = true else onSuccess()
        }
    }

    SectionTitle(words.service("saved_sign_ins"))
    ZenPanel(Modifier.fillMaxWidth()) {
        if (entries.isEmpty() && !failed) Text(words.service("saved_empty"))
        for (kind in SignInKind.entries) {
            val matching = entries.filter { it.kind == kind }
            if (matching.isEmpty()) continue
            Text(words.service(if (kind == SignInKind.STUDY) "study_board" else "library"), style = MaterialTheme.typography.titleSmall)
            for (entry in matching) {
                Spacer(Modifier.height(8.dp))
                Text(entry.username, style = MaterialTheme.typography.bodyMedium)
                entry.label?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(entry.site, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZenLink(words.service("saved_edit_short"), { edit = entry })
                    ZenLink(words.service("saved_rename"), { failed = false; rename = entry })
                    ZenLink(words.service("saved_delete"), { failed = false; delete = entry }, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (failed && rename == null && delete == null && !removeAll) Text(words.service("saved_storage_error"), color = MaterialTheme.colorScheme.error)
        if (entries.isNotEmpty()) ZenLink(words.service("saved_remove_all"), { failed = false; removeAll = true },
            color = MaterialTheme.colorScheme.error)
    }
    edit?.let { entry ->
        SavedSignInForm(entry.kind, entry.site, entry, dismiss = { edit = null }) { username, password, label, done ->
            scope.launch { done(trySavedSignIn { store.save(entry.kind, entry.site, username, password, label, entry.id) } != null) }
        }
    }
    rename?.let { entry ->
        var label by remember(entry.id) { mutableStateOf(entry.label.orEmpty()) }
        ZenDialog({ if (!working) rename = null }, title = { Text(words.service("saved_rename")) },
            text = { Column {
                OutlinedTextField(label, { label = it }, label = { Text(words.service("saved_label")) }, singleLine = true)
                if (failed) Text(words.service("saved_storage_error"), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { DialogButton(words.service("saved_save"), {
                change({ store.rename(entry.id, label) }) { rename = null }
            }, primary = true, enabled = !working) },
            dismissButton = { DialogButton(words.text("cancel"), { rename = null }, enabled = !working) })
    }
    if (delete != null || removeAll) ZenDialog(
        onDismissRequest = { if (!working) { delete = null; removeAll = false } },
        title = { Text(words.service(if (removeAll) "saved_remove_all_title" else "saved_delete_title")) },
        text = { Column {
            delete?.let { Text(it.username) }
            if (failed) Text(words.service("saved_storage_error"), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { DialogButton(words.service(if (removeAll) "saved_remove_all" else "saved_delete"), {
            val id = delete?.id
            change({ if (id == null) store.removeAll() else store.delete(id) }) { delete = null; removeAll = false }
        }, primary = true, destructive = true, enabled = !working) },
        dismissButton = { DialogButton(words.text("cancel"), { delete = null; removeAll = false }, enabled = !working) },
    )
}
