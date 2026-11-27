package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.ContentStatus
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalReducedMotion
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ReadingText
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenChip
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenTabs
import io.github.binoverfl0w.zencampus.ui.design.content
import io.github.binoverfl0w.zencampus.ui.design.courseInitials
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.launch

@Composable
fun MessagesScreen(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus, onOpenUrl: (String) -> Unit,
    header: (@Composable () -> Unit)? = null) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    var sent by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<CampusPerson?>(null) }
    var replySubject by remember { mutableStateOf("") }
    val folder = if (sent) "sent" else "inbox"
    val key = "messages:$folder"
    val messages = snapshot.messages[folder]
    // The inbox comes from the Study sync; sent messages are only read when that tab is opened.
    LaunchedEffect(repository, sent) { if (sent) repository.refreshMessages(sent = true) }
    LazyColumn(Modifier.fillMaxSize().testTag("messages-screen"),
        contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val compose: @Composable () -> Unit = {
            ZenButton(words.ui("new_2"), { replyTo = null; replySubject = ""; composing = true }, modifier = Modifier.testTag("message-compose"), style = ButtonStyle.TONAL, symbol = Symbol.EDIT)
        }
        item {
            if (header != null) header()
            else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.weight(1f)) {
                    ScreenTitle(words.ui("messages_2"), "")
                }
                compose()
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZenTabs(listOf(words.ui("received"), words.ui("sent")), if (sent) 1 else 0,
                    { sent = it == 1; selected = null }, Modifier.weight(1f), tags = listOf("messages-inbox", "messages-sent"))
                if (header != null) compose()
            }
        }
        item {
            ContentStatus(sync.content(key, messages != null), words.ui("loading_messages"),
                { scope.launch { repository.refreshMessages(sent, true) } }, words.ui("no_messages"), messages.isNullOrEmpty())
        }
        if (!messages.isNullOrEmpty()) item(key = "messages:$folder") { Column {
            messages.forEachIndexed { index, message ->
                val unread = !sent && !message.read
                val correspondent = if (sent) message.recipients.firstOrNull().orEmpty() else message.sender
                ZenListItem(GroupPosition.of(index, messages.size), Modifier.testTag("message-${message.id}"), {
                    selected = message.id
                    if (unread) scope.launch { repository.setMessageRead(message.id, true) }
                }) {
                    Box(Modifier.size(36.dp).background(if (unread) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        val ink = if (unread) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        if (correspondent.isBlank()) ZenIcon(Symbol.MAIL, Modifier.size(17.dp), ink)
                        else Text(courseInitials(correspondent), style = MaterialTheme.typography.labelMedium, color = ink)
                    }
                    ZenListText(message.subject.ifBlank { words.ui("no_subject") },
                        (if (sent) message.recipients.joinToString(", ") else message.sender),
                        meta = words.age(message.timestamp, LocalNow.current), modifier = Modifier.weight(1f))
                    if (unread) ToneChip(words.ui("unread"), Tone.PRIMARY)
                }
            }
        } }
    }
    messages?.firstOrNull { it.id == selected }?.let { message ->
        val canReply = !sent && message.senderId.isNotBlank()
        ZenDialog(onDismissRequest = { selected = null },
            title = { Text(message.subject.ifBlank { words.ui("no_subject") }) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val person = if (sent) message.recipients.joinToString(", ") else message.sender
                Text(listOf(person, words.age(message.timestamp, LocalNow.current)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium)
                ReadingText(message.body)
            } },
            neutralButton = { ZenLink(words.text("open_official"), { onOpenUrl("$DIGICAMPUS_ORIGIN/dispatch.php/messages") }, external = true) },
            dismissButton = { DialogButton(words.text("close"), { selected = null }) },
            confirmButton = {
                if (canReply) DialogButton(words.ui("reply"), {
                    replyTo = CampusPerson(message.senderId, message.sender.ifBlank { message.senderId })
                    replySubject = if (message.subject.startsWith("Re:", true)) message.subject else "Re: ${message.subject}"
                    selected = null; composing = true
                }, primary = true)
            })
    }
    if (composing) MessageComposeDialog(snapshot, repository, sync, replyTo, replySubject) { composing = false }
}

@Composable
private fun MessageComposeDialog(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus,
    initialRecipient: CampusPerson?, initialSubject: String, onDismiss: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val requestId = rememberSaveable(snapshot.account.cacheScope) { kotlin.random.Random.nextLong().toString() + ":" + nowMillis() }
    val isReply = rememberSaveable(snapshot.account.cacheScope) { initialRecipient != null }
    var query by rememberSaveable(snapshot.account.cacheScope) { mutableStateOf("") }
    var subject by rememberSaveable(snapshot.account.cacheScope) { mutableStateOf(initialSubject) }
    var body by rememberSaveable(snapshot.account.cacheScope) { mutableStateOf("") }
    var recipientId by rememberSaveable(snapshot.account.cacheScope) { mutableStateOf(initialRecipient?.id.orEmpty()) }
    var recipientName by rememberSaveable(snapshot.account.cacheScope) { mutableStateOf(initialRecipient?.name.orEmpty()) }
    val recipient = recipientId.takeIf { it.isNotBlank() }?.let { CampusPerson(it, recipientName) }
    var results by remember { mutableStateOf<List<CampusPerson>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<FailureKind?>(null) }
    val record = snapshot.messageWrite
    val pending = record?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)
    val busy = "write:message" in sync.active
    LaunchedEffect(record?.status, record?.requestId) {
        if (record?.status == WriteStatus.CONFIRMED && record.requestId == requestId) onDismiss()
    }
    LaunchedEffect(repository) { repository.refreshContacts() }
    ZenDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(words.ui("message")) },
        text = {
            val search: () -> Unit = {
                        searching = true; failure = null
                        val requested = query.trim()
                        scope.launch {
                            try { val found = repository.findPeople(requested); if (query.trim() == requested) { results = found; searched = true } }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { if (query.trim() == requested) failure = (e as? CampusException)?.kind ?: FailureKind.OFFLINE }
                            finally { searching = false }
                        }
                    }
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pending) ZenNotice(words.ui("check_your_digicampus_sent_folder_be"),
                    action = words.ui("i_checked"), onAction = { scope.launch { repository.acknowledgeWrite("write:message") } }, actionEnabled = !busy)
                if (recipient != null) {
                    Text(words.ui("to"), style = MaterialTheme.typography.labelMedium)
                    ZenChip(recipient.name, { recipientId = ""; recipientName = ""; results = emptyList() }, selected = true, trailing = Symbol.CLOSE,
                        modifier = Modifier.semantics { contentDescription = "${recipient.name}. ${words.ui("change_recipient")}" })
                } else {
                    val canSearch = query.trim().length >= 3 && !searching
                    OutlinedTextField(query, { query = it; results = emptyList(); failure = null; searched = false },
                        label = { Text(words.ui("find_recipient")) }, singleLine = true,
                        supportingText = { Text(words.ui("at_least_3_characters")) },
                        trailingIcon = {
                            if (searching && !LocalReducedMotion.current) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else IconAction(Symbol.SEARCH, words.ui("search_2"), search, enabled = canSearch)
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (canSearch) search() }),
                        modifier = Modifier.fillMaxWidth().testTag("message-recipient-query"))
                    val contacts = snapshot.contacts.orEmpty()
                    if (query.isBlank() && contacts.isNotEmpty()) {
                        SmallLabel(words.ui("contacts"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            contacts.forEach { person ->
                                ZenChip(person.name, { recipientId = person.id; recipientName = person.name; results = emptyList() },
                                    Modifier.testTag("contact-suggestion-${person.id}"), symbol = Symbol.PEOPLE)
                            }
                        }
                    }
                    failure?.let { Text(words.failure(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (searched && results.isEmpty()) Text(words.ui("no_matches"), style = MaterialTheme.typography.bodySmall)
                    if (results.isNotEmpty()) Column {
                        results.forEachIndexed { index, person ->
                            ZenListItem(GroupPosition.of(index, results.size), Modifier.testTag("message-recipient-${person.id}"),
                                { recipientId = person.id; recipientName = person.name; results = emptyList() }) {
                                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                                    Text(courseInitials(person.name), style = MaterialTheme.typography.labelSmall)
                                }
                                ZenListText(person.name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                OutlinedTextField(subject, { subject = it.take(300) }, label = { Text(words.ui("subject")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("message-subject"))
                OutlinedTextField(body, { body = it.take(32_000) }, label = { Text(words.ui("message")) },
                    modifier = Modifier.fillMaxWidth().testTag("message-body"), minLines = 4)
                if (isReply) SmallLabel(words.ui("your_reply_is_sent_as_a_new_message"))
                SmallLabel(words.ui("digicampus_may_email_a_copy_to_the_r"))
                if (record?.status == WriteStatus.REJECTED && record.requestId == requestId)
                    Text(record.failure?.let(words::failure).orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            DialogButton(words.ui("send"), { val target = recipient; if (target != null) scope.launch { repository.sendMessage(target, subject, body, requestId) } },
                enabled = recipient != null && subject.isNotBlank() && body.isNotBlank() && !pending && !busy,
                modifier = Modifier.testTag("message-send"), primary = true)
        },
        dismissButton = { DialogButton(words.text("cancel"), onDismiss, enabled = !busy) })
}
