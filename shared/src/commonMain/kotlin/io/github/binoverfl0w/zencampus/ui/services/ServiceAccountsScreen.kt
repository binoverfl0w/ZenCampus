package io.github.binoverfl0w.zencampus.ui.services

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.CampusBoard
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.StatusPill
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.service

@Composable
fun ServiceAccountsScreen(account: Account?, digiExpired: Boolean, services: CampusServicesRepository,
    onSignIn: () -> Unit, onSignOut: (Boolean) -> Unit, onConnectLibrary: () -> Unit, onDisconnectLibrary: (Boolean) -> Unit,
    setup: Boolean = false, onContinue: () -> Unit = {}, onBoard: (CampusBoard) -> Unit = {}) {
    val words = LocalWords.current
    val library by services.library.collectAsState()
    var disconnect by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("accounts-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { ScreenTitle(words.service(if (setup) "setup_accounts" else "your_cards"), words.service(if (setup) "setup_accounts_body" else "cards_body")) }
        item {
            AccessCard("DigiCampus", "digi", words.service("required_account"),
                if (digiExpired) words.service("account_expired") else if (account?.demo == true) words.text("preview_mode")
                    else words.service(if (account == null) "account_disconnected" else "card_connected"),
                account?.name?.takeIf { it.isNotBlank() } ?: words.service("study_board"),
                words.service("digi_access"), MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f),
                primary = if (account == null || digiExpired) words.service("connect_digi") else if (!setup) words.service("open_study") else null,
                onPrimary = { if (account == null || digiExpired) onSignIn() else onBoard(CampusBoard.STUDY) },
                onConnect = onSignIn, disconnectLabel = if (account != null) words.text("sign_out") else null,
                onDisconnect = { disconnect = "digi" })
        }
        item {
            val expired = library.error == FailureKind.AUTHENTICATION
            AccessCard(words.service("library"), "library", words.service("optional_account"),
                words.service(when { expired -> "account_expired"; library.connected -> "card_connected"; else -> "account_disconnected" }),
                library.account?.label?.takeIf { it.isNotBlank() } ?: words.service("library_card_identity"),
                words.service("library_card_body"), MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .55f),
                primary = if (!library.connected || expired) words.service("connect_library")
                    else if (!setup) words.service("open_library") else null,
                onPrimary = { if (!library.connected || expired) onConnectLibrary() else onBoard(CampusBoard.LIBRARY) },
                onConnect = onConnectLibrary, disconnectLabel = if (library.connected) words.service("disconnect_library") else null,
                onDisconnect = { disconnect = "library" })
            if (!library.connected || expired) {
                Spacer(Modifier.height(10.dp))
                Text(words.service("library_login_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!setup) item { Text(words.service("accounts_saved_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (setup) item {
            ZenButton(words.service("continue_campus"), onContinue, modifier = Modifier.fillMaxWidth(), enabled = account != null && !digiExpired, style = ButtonStyle.PRIMARY)
        }
    }
    disconnect?.let { service ->
        var removeSaved by rememberSaveable(service) { mutableStateOf(true) }
        ZenDialog(onDismissRequest = { disconnect = null },
            title = { Text(if (service == "digi") words.text("confirm_signout") else words.service("disconnect_library")) },
            text = { Column {
                Text(if (service == "digi") words.text("sign_out_body") else words.service("disconnect_library_body"))
                RemoveSavedSignInsOption(removeSaved) { removeSaved = it }
            } },
            confirmButton = { DialogButton(if (service == "digi") words.text("sign_out") else words.service("disconnect_library"), {
                disconnect = null
                if (service == "digi") onSignOut(removeSaved) else onDisconnectLibrary(removeSaved)
            }, primary = true, destructive = true) },
            dismissButton = { DialogButton(words.text("cancel"), { disconnect = null }) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessCard(title: String, id: String, requirement: String, status: String, identity: String,
    body: String, color: Color, primary: String?, onPrimary: () -> Unit, onConnect: () -> Unit,
    disconnectLabel: String?, onDisconnect: () -> Unit) {
    val words = LocalWords.current
    var options by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().testTag("access-card-$id"),
        shape = RoundedCornerShape(Zen.radiusHero),
        color = color,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SmallLabel(words.service("university_name"))
                    Spacer(Modifier.height(6.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                }
                if (disconnectLabel != null) Box {
                    IconAction(Symbol.MORE, words.service("card_options") + " · " + title, { options = true })
                    DropdownMenu(options, { options = false }) {
                        DropdownMenuItem(text = { Text(words.service("update_sign_in")) }, onClick = { options = false; onConnect() })
                        DropdownMenuItem(text = { Text(disconnectLabel) }, onClick = { options = false; onDisconnect() })
                    }
                } else {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .75f), modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            ZenIcon(Symbol.CARD, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(identity, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                StatusPill(status, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
                Text(requirement, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (primary != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                Spacer(Modifier.height(6.dp))
                ZenLink(primary, onPrimary, Modifier.fillMaxWidth(), trailing = Symbol.NEXT)
            }
        }
    }
}
