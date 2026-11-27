package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.lazy.LazyListScope
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ReadingText
import io.github.binoverfl0w.zencampus.ui.design.RowChevron
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
import io.github.binoverfl0w.zencampus.ui.design.ZenIconToggle
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.content
import io.github.binoverfl0w.zencampus.ui.design.courseInitials
import io.github.binoverfl0w.zencampus.ui.study
import io.github.binoverfl0w.zencampus.data.digicampus.HIDDEN_MEMBERS_ID
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseCommunityLinks(course: Course, snapshot: CampusSnapshot, open: (String, String) -> Unit) {
    val words = LocalWords.current
    val tools = snapshot.courseTools[course.id]?.sections.orEmpty()
    fun hasTool(name: String) = tools.any { it.id.contains(name, true) || it.sourceUrl.contains("/$name", true) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ZenChip(words.ui("people"), { open(course.id, "people") }, Modifier.testTag("community-link-people"), symbol = Symbol.PEOPLE)
        ZenChip(words.ui("office_hours"), { open(course.id, "officehours") }, Modifier.testTag("community-link-officehours"), symbol = Symbol.CLOCK)
        if (hasTool("forum")) ZenChip("Forum", { open(course.id, "forum") }, Modifier.testTag("community-link-forum"), symbol = Symbol.FORUM)
        if (hasTool("blubber")) ZenChip("Blubber", { open(course.id, "blubber") }, Modifier.testTag("community-link-blubber"), symbol = Symbol.FORUM)
        if (hasTool("wiki")) ZenChip("Wiki", { open(course.id, "wiki") }, Modifier.testTag("community-link-wiki"), symbol = Symbol.FILE)
        if (hasTool("courseware")) ZenChip("Courseware", { open(course.id, "courseware") }, Modifier.testTag("community-link-courseware"), symbol = Symbol.COURSES)
    }
}

@Composable
fun CourseCommunityScreen(course: Course, section: String, itemId: String, snapshot: CampusSnapshot,
    repository: CampusRepository, sync: SyncStatus, openItem: (String) -> Unit, onOfficial: () -> Unit,
    onOpenLink: (String) -> Unit = {}) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    var wikiPage by rememberSaveable(course.id) { mutableStateOf<String?>(null) }
    var reply by rememberSaveable(course.id, itemId) { mutableStateOf(false) }
    var newBlubber by rememberSaveable(course.id) { mutableStateOf(false) }
    val key = CacheKeys.community(course.id, section, itemId)
    val content = snapshot.community[key]
    LaunchedEffect(course.id, section, itemId) {
        if (section == "blubber" && itemId.isNotBlank()) repository.refreshCommunity(course.id, section)
        repository.refreshCommunity(course.id, section, itemId)
        if (section == "forum" && itemId.isNotBlank()) repository.refreshForumSubscriptions(course.id)
        if (section == "courseware" && itemId.isNotBlank()) repository.refreshCoursewareBookmarks()
        if (section == "people") repository.refreshContacts()
        if (section == "groups" && itemId.isNotBlank()) {
            repository.refreshCommunity(course.id, "groups")
            repository.refreshCommunity(course.id, "group-members", itemId)
        }
    }
    val unitId = snapshot.community["community:${course.id}:courseware:"]?.firstOrNull { it.id == itemId }?.secondary.orEmpty()
    LaunchedEffect(course.id, section, itemId, unitId) {
        if (section == "courseware" && itemId.isNotBlank() && unitId.isNotBlank()) repository.refreshCoursewareProgress(course.id, unitId)
    }
    val title = when(section) {
        "people" -> words.ui("people")
        "forum" -> "Forum"
        "blubber" -> "Blubber"
        "groups" -> words.ui("groups")
        "wiki" -> "Wiki"
        "courseware" -> "Courseware"
        else -> ""
    }
    LazyColumn(Modifier.fillMaxSize().testTag("community-$section"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val group = if (section == "groups" && itemId.isNotBlank())
            snapshot.community[CacheKeys.community(course.id, "groups")]?.firstOrNull { it.id == itemId } else null
        item { SmallLabel(course.title); ScreenTitle(group?.title ?: title, "") }
        if (section == "groups" && itemId.isNotBlank()) groupPageItems(course, itemId, group, snapshot, repository, sync, onOfficial)
        if (section == "people") item {
            ZenChip(words.ui("groups"), { openItem("__groups__") }, Modifier.testTag("community-groups"), symbol = Symbol.GROUP)
        }
        if (section == "blubber" && itemId.isBlank() && content != null && key !in sync.errors) item {
            ZenButton(words.ui("new_post"), { newBlubber = true }, Modifier.testTag("blubber-new"), symbol = Symbol.EDIT)
        }
        if (section == "forum" && itemId.isNotBlank() && content != null && key !in sync.errors) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZenButton(words.ui("reply"), { reply = true }, Modifier.testTag("forum-reply"), symbol = Symbol.EDIT)
                ForumSubscriptionControl(course.id, itemId, snapshot, repository, sync, onOfficial)
            }
        }
        if (section == "blubber" && itemId.isNotBlank()) {
            val thread = snapshot.community["community:${course.id}:blubber:"]?.firstOrNull { it.id == itemId }
            if (thread != null) item {
                // The course's default thread has no text: show its name and reply action, not an empty box.
                ZenPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
                    if (thread.body.isBlank()) Text(thread.title, style = MaterialTheme.typography.titleMedium)
                    if (thread.secondary.isNotBlank() && thread.body.isNotBlank()) SmallLabel(thread.secondary)
                    if (thread.body.isNotBlank()) ReadingText(thread.body)
                    if (thread.canReply) ZenLink(words.ui("reply"), { reply = true })
                }
            }
        }
        item {
            ContentStatus(sync.content(key, content != null), words.ui("loading"),
                { scope.launch { repository.refreshCommunity(course.id, section, itemId, true) } },
                words.ui("no_entries"), content.isNullOrEmpty(), words.text("open_official"), onOfficial)
        }
        items(content.orEmpty(), key = { it.id }) { entry ->
            when {
                section == "people" -> {
                    val isStaff = entry.secondary in setOf("dozent", "tutor")
                    ZenListItem {
                        Box(Modifier.size(36.dp).background(if (isStaff) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                            Text(courseInitials(entry.title), style = MaterialTheme.typography.labelMedium,
                                color = if (isStaff) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ZenListText(entry.title, modifier = Modifier.weight(1f))
                        if (entry.secondary.isNotBlank()) {
                            val roleLabel = when (entry.secondary) {
                                "dozent" -> words.ui("lecturer_2")
                                "tutor" -> words.ui("tutor_2")
                                "autor", "user" -> words.ui("student")
                                else -> entry.secondary
                            }
                            ToneChip(roleLabel, if (isStaff) Tone.PRIMARY else Tone.NEUTRAL)
                        }
                        if (entry.personId.isNotBlank() && entry.personId != snapshot.account.id) {
                            val saved = snapshot.contacts.orEmpty().any { it.id == entry.personId }
                            ZenIconToggle(saved, { scope.launch { repository.setContact(CampusPerson(entry.personId, entry.title), !saved) } },
                                enabled = "write:contact:${entry.personId}" !in sync.active,
                                modifier = Modifier.testTag("contact-${entry.personId}").semantics {
                                    contentDescription = words.ui(if (saved) "remove_contact" else "add_contact") + " · " + entry.title
                                }) {
                                ZenIcon(if (saved) Symbol.PIN_FILLED else Symbol.PIN, Modifier.size(17.dp),
                                    if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                section == "forum" && itemId.isNotBlank() -> ZenPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        if (entry.title.isNotBlank()) Text(entry.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (entry.timestamp > 0) SmallLabel(words.age(entry.timestamp, LocalNow.current))
                    }
                    if (entry.title.isNotBlank() || entry.timestamp > 0) Spacer(Modifier.height(8.dp))
                    ReadingText(entry.body)
                    Spacer(Modifier.height(8.dp))
                    ForumReactionControl(course.id, itemId, entry.id, snapshot, repository, sync, onOfficial)
                }
                section == "blubber" && itemId.isNotBlank() -> ZenPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
                    if (entry.title.isNotBlank()) SmallLabel(entry.title)
                    ReadingText(entry.body)
                    if (entry.timestamp > 0) SmallLabel(words.age(entry.timestamp, LocalNow.current))
                }
                section == "courseware" && itemId.isNotBlank() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entry.title.isNotBlank()) Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    CoursewareBookmarkControl(course.id, entry.id, snapshot, repository, sync, onOfficial)
                    snapshot.coursewareProgress.filterKeys { it.startsWith("courseware-progress:${course.id}:") }.values
                        .firstNotNullOfOrNull { it[entry.id] }?.let { progress ->
                            LinearProgressIndicator(progress = { (progress / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
                            SmallLabel("${progress.toInt()}% · ${words.study("progress_body")}")
                        }
                    if (entry.parts.isEmpty() && entry.body.isNotBlank()) ReadingText(entry.body)
                    entry.parts.forEach { part ->
                        when (part.kind) {
                            "text" -> ReadingText(part.text)
                            "headline" -> Column {
                                Text(part.title, style = MaterialTheme.typography.titleMedium)
                                if (part.text.isNotBlank()) ReadingText(part.text)
                            }
                            "keypoint" -> ZenNotice(part.text, Tone.PRIMARY)
                            "code" -> Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Zen.radiusMedium))
                                .padding(12.dp).testTag("courseware-code")) {
                                if (part.title.isNotBlank()) SmallLabel(part.title)
                                Text(part.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    modifier = Modifier.horizontalScroll(rememberScrollState()))
                            }
                            "date" -> part.target.toLongOrNull()?.let { at ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ZenIcon(Symbol.CLOCK, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                                    Text("${words.formatDate(campusToday(at), true)} · ${words.time(at)}", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            "link" -> ZenButton(part.title, { onOpenLink(part.target) }, style = ButtonStyle.OUTLINE, trailing = Symbol.EXTERNAL)
                            "file" -> Column {
                                ZenButton(part.title, { repository.requestCoursewareFile(course.id, itemId, part) }, style = ButtonStyle.OUTLINE,
                                    enabled = "courseware-file:${part.target}" !in sync.active, symbol = Symbol.FILE)
                                sync.errors[CacheKeys.download(part.target)]?.let { Text(words.failure(it), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    if (entry.incomplete) ZenNotice(words.ui("more_content_in_digicampus"), Tone.NEUTRAL,
                        words.text("open_official"), onOfficial)
                    val progressById = snapshot.coursewareProgress.filterKeys { it.startsWith("courseware-progress:${course.id}:") }.values
                        .fold(emptyMap<String, Double>()) { all, next -> all + next }
                    entry.children.forEach { child ->
                        ZenListItem(GroupPosition.of(entry.children.indexOf(child), entry.children.size), onClick = { openItem(child.id) }) {
                            ZenListText(child.title, modifier = Modifier.weight(1f))
                            progressById[child.id]?.let { ToneChip("${it.toInt()}%", if (it >= 100.0) Tone.SUCCESS else Tone.NEUTRAL) }
                            RowChevron()
                        }
                    }
                    // Previous and next chapter from the parent page this one was opened from.
                    val siblings = snapshot.community.filterKeys { it.startsWith("community:${course.id}:courseware:") && !it.endsWith(":") }.values
                        .flatten().firstOrNull { parent -> parent.children.any { it.id == entry.id } }?.children.orEmpty()
                    val position = siblings.indexOfFirst { it.id == entry.id }
                    if (position >= 0 && siblings.size > 1) Row(Modifier.fillMaxWidth().testTag("courseware-pager"), verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) { siblings.getOrNull(position - 1)?.let { previous ->
                            ZenLink(previous.title, { openItem(previous.id) }, Modifier.testTag("courseware-previous"), symbol = Symbol.BACK)
                        } }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) { siblings.getOrNull(position + 1)?.let { next ->
                            Row(Modifier.clickable { openItem(next.id) }.heightIn(min = 44.dp).testTag("courseware-next"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(next.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                                ZenIcon(Symbol.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                            }
                        } }
                    }
                }
                else -> {
                    ZenListItem(modifier = Modifier.testTag("community-item-${entry.id}"), onClick = {
                        if (section == "wiki") wikiPage = entry.id else openItem(entry.id)
                    }) {
                        ZenListText(entry.title, if (section == "groups") groupLine(entry, words) else "", modifier = Modifier.weight(1f))
                        if (entry.unread != null && entry.unread > 0) ToneChip("${entry.unread} ${words.ui("unread_2")}", Tone.PRIMARY)
                        RowChevron()
                    }
                }
            }
        }
    }
    if (reply && section in setOf("forum", "blubber") && itemId.isNotBlank())
        ForumReplyDialog(course, itemId, snapshot, repository, sync, section) { reply = false }
    if (newBlubber && section == "blubber" && itemId.isBlank())
        ForumReplyDialog(course, "", snapshot, repository, sync, "blubber-new") { newBlubber = false }
    if (section == "wiki") content?.firstOrNull { it.id == wikiPage }?.let { page ->
        ZenDialog(onDismissRequest = { wikiPage = null }, title = { Text(page.title) },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { ReadingText(page.body) } },
            neutralButton = { ZenLink(words.text("open_official"), onOfficial, external = true) },
            confirmButton = { DialogButton(words.text("close"), { wikiPage = null }) })
    }
}

@Composable
private fun ForumReplyDialog(course: Course, discussionId: String, snapshot: CampusSnapshot,
    repository: CampusRepository, sync: SyncStatus, section: String = "forum", onDismiss: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val requestId = rememberSaveable(snapshot.account.cacheScope, course.id, discussionId) { kotlin.random.Random.nextLong().toString() + ":" + nowMillis() }
    var body by rememberSaveable(snapshot.account.cacheScope, course.id, discussionId) { mutableStateOf("") }
    val key = if (section == "blubber-new") "write:blubber-new:${course.id}" else "write:$section:${course.id}:$discussionId"
    val record = snapshot.courseworkWrites[key]
    val pending = record?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)
    val busy = key in sync.active
    LaunchedEffect(record?.status, record?.requestId) {
        if (record?.status == WriteStatus.CONFIRMED && record.requestId == requestId) onDismiss()
    }
    ZenDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (section == "blubber-new") { words.ui("new_post") } else words.ui("reply_2")) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending) ZenNotice(words.ui("check_the_discussion_in_digicampus_b"),
                action = words.ui("i_checked"), onAction = { scope.launch { repository.acknowledgeWrite(key) } }, actionEnabled = !busy)
            OutlinedTextField(body, { body = it.take(32_000) }, label = { Text(words.ui("reply_2")) },
                modifier = Modifier.fillMaxWidth().testTag("forum-reply-body"), minLines = 4)
            if (section == "forum") SmallLabel(words.ui("digicampus_may_subscribe_you_to_this"))
            if (record?.status == WriteStatus.REJECTED && record.requestId == requestId)
                Text(record.failure?.let(words::failure).orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { DialogButton(words.ui("post"), { scope.launch { when (section) {
            "blubber-new" -> repository.postBlubberThread(course.id, body, requestId)
            "blubber" -> repository.postBlubberReply(course.id, discussionId, body, requestId)
            else -> repository.postForumReply(course.id, discussionId, body, requestId)
        } } }, enabled = body.isNotBlank() && !busy && !pending, modifier = Modifier.testTag("forum-send"), primary = true) },
        dismissButton = { DialogButton(words.text("cancel"), onDismiss, enabled = !busy) })
}

@Composable
private fun ForumSubscriptionControl(courseId: String, discussionId: String, snapshot: CampusSnapshot,
    repository: CampusRepository, sync: SyncStatus, onOfficial: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val subs = snapshot.forumSubscriptions[courseId]
    val sourceKey = CacheKeys.forumSubscriptions(courseId)
    val writeKey = "write:forum-sub:$courseId:$discussionId"
    val record = snapshot.courseworkWrites[writeKey]
    val pending = record?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)
    val busy = writeKey in sync.active || sourceKey in sync.active
    if (pending) {
        ZenNotice(words.ui("check_digicampus_before_trying_again"),
            action = words.ui("i_checked"), onAction = { scope.launch { repository.acknowledgeWrite(writeKey) } }, actionEnabled = !busy,
            secondary = words.text("open_official"), onSecondary = onOfficial, secondaryExternal = true)
    } else if (subs != null) {
        val subscribed = subs.any { it.discussionId == discussionId }
        ZenLink(if (subscribed) { words.ui("unsubscribe") }
                else { words.ui("subscribe") }, { scope.launch { repository.setForumSubscription(courseId, discussionId, !subscribed,
            kotlin.random.Random.nextLong().toString() + ":" + nowMillis()) } }, enabled = !busy, modifier = Modifier.testTag("forum-subscribe"))
    } else if (sourceKey in sync.errors) {
        ZenLink(words.text("retry"), { scope.launch { repository.refreshForumSubscriptions(courseId, true) } }, enabled = !busy)
    }
    if (record?.status == WriteStatus.REJECTED) record.failure?.let { SmallLabel(words.failure(it)) }
}

@Composable
private fun ForumReactionControl(courseId: String, discussionId: String, postId: String, snapshot: CampusSnapshot,
    repository: CampusRepository, sync: SyncStatus, onOfficial: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val sourceKey = CacheKeys.forumReactions(courseId, postId)
    val writeKey = "write:forum-react:$courseId:$postId"
    val reactions = snapshot.forumReactions[sourceKey]
    val record = snapshot.courseworkWrites[writeKey]
    val pending = record?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)
    val busy = sourceKey in sync.active || writeKey in sync.active
    LaunchedEffect(courseId, postId) { repository.refreshForumReactions(courseId, postId) }
    if (pending) {
        ZenNotice(words.ui("check_digicampus_before_trying_again"),
            action = words.ui("i_checked"), onAction = { scope.launch { repository.acknowledgeWrite(writeKey) } }, actionEnabled = !busy,
            secondary = words.text("open_official"), onSecondary = onOfficial, secondaryExternal = true)
    } else if (reactions != null) {
        val likes = reactions.filter { it.emoji == "👍" }
        val selected = likes.any { it.userId == snapshot.account.id }
        ZenChip("👍 ${likes.size}", { scope.launch { repository.setForumReaction(courseId, discussionId, postId, !selected,
            kotlin.random.Random.nextLong().toString() + ":" + nowMillis()) } }, Modifier.testTag("forum-reaction-$postId"), selected = selected, enabled = !busy)
    } else if (sourceKey in sync.errors) {
        ZenLink(words.text("retry"), { scope.launch { repository.refreshForumReactions(courseId, postId, true) } }, enabled = !busy)
    }
    if (record?.status == WriteStatus.REJECTED) record.failure?.let { SmallLabel(words.failure(it)) }
}

@Composable
private fun CoursewareBookmarkControl(courseId: String, elementId: String, snapshot: CampusSnapshot,
    repository: CampusRepository, sync: SyncStatus, onOfficial: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val key = "write:bookmark:$courseId:$elementId"
    val record = snapshot.courseworkWrites[key]
    val pending = record?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)
    val busy = key in sync.active || "courseware-bookmarks" in sync.active
    val bookmarks = snapshot.coursewareBookmarks
    if (pending) {
        ZenNotice(words.ui("check_digicampus_before_trying_again"),
            action = words.ui("i_checked"), onAction = { scope.launch { repository.acknowledgeWrite(key) } }, actionEnabled = !busy,
            secondary = words.text("open_official"), onSecondary = onOfficial, secondaryExternal = true)
    } else if (bookmarks != null) {
        val selected = elementId in bookmarks
        ZenChip(if (selected) { words.ui("bookmarked") } else { words.ui("bookmark") },
            { scope.launch { repository.setCoursewareBookmark(courseId, elementId, !selected,
                kotlin.random.Random.nextLong().toString() + ":" + nowMillis()) } }, Modifier.testTag("courseware-bookmark"),
            symbol = if (selected) Symbol.PIN_FILLED else Symbol.PIN, selected = selected, enabled = !busy)
    } else if ("courseware-bookmarks" in sync.errors) {
        ZenLink(words.text("retry"), { scope.launch { repository.refreshCoursewareBookmarks(true) } }, enabled = !busy)
    }
    if (record?.status == WriteStatus.REJECTED) record.failure?.let { SmallLabel(words.failure(it)) }
}

/**
 * One group's page: its facts, description and members, laid out like the course's People list. Members come from
 * the official group fragment; people who hide their membership are only counted.
 */
private fun LazyListScope.groupPageItems(course: Course, groupId: String, group: CommunityItem?, snapshot: CampusSnapshot,
    repository: CampusRepository, sync: SyncStatus, onOfficial: () -> Unit) {
    val membersKey = CacheKeys.community(course.id, "group-members", groupId)
    val loaded = snapshot.community[membersKey]
    val members = loaded?.filterNot { it.id == HIDDEN_MEMBERS_ID }
    val hidden = loaded?.firstOrNull { it.id == HIDDEN_MEMBERS_ID }?.unread ?: 0
    item(key = "group-facts") {
        val words = LocalWords.current
        Column(Modifier.testTag("group-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val facts = group?.let { groupLine(it, words, members?.let { list -> list.size + hidden }) }.orEmpty().split(" · ").filter { it.isNotBlank() }
            if (facts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                facts.forEachIndexed { index, fact -> ToneChip(fact, if (index == 0) Tone.PRIMARY else Tone.SUCCESS) }
            }
            if (group != null && group.body.isNotBlank()) ReadingText(group.body)
        }
    }
    item(key = "group-members-title") { SectionTitle(LocalWords.current.ui("group_members_title")) }
    item(key = "group-members-status") {
        val words = LocalWords.current
        val scope = rememberCoroutineScope()
        ContentStatus(sync.content(membersKey, members != null), words.ui("loading"),
            { scope.launch { repository.refreshCommunity(course.id, "group-members", groupId, true) } },
            words.ui("group_no_members"), members.isNullOrEmpty() && hidden == 0, words.text("open_official"), onOfficial)
    }
    val visible = members.orEmpty()
    items(visible.size, key = { "group-member-${visible[it].id}" }) { index ->
        val member = visible[index]
        ZenListItem(GroupPosition.of(index, visible.size), Modifier.testTag("group-member-${member.id}")) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                Text(courseInitials(member.title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ZenListText(member.title, modifier = Modifier.weight(1f))
        }
    }
    if (hidden > 0) item(key = "group-hidden") { SmallLabel(LocalWords.current.ui("group_hidden").replace("%1", "$hidden")) }
    item(key = "group-official") { ZenLink(LocalWords.current.text("open_official"), onOfficial, external = true) }
}

/**
 * "12 of 20 members · Join in DigiCampus". The list only knows the capacity (members come with the group's own page),
 * so it shows "Up to 20 members" until [loaded] members are known.
 */
private fun groupLine(group: CommunityItem, words: Words, loaded: Int? = null): String {
    val parts = group.secondary.split('|')
    val limit = parts.first().split('/').getOrNull(1)
    val count = loaded ?: group.children.size.takeIf { it > 0 }
    val members = when {
        count != null && limit != null -> words.ui("group_members_of").replace("%1", "$count").replace("%2", limit)
        count != null -> words.ui("group_members").replace("%1", "$count")
        limit != null -> words.ui("group_up_to").replace("%1", limit)
        else -> null
    }
    return listOfNotNull(members, words.ui("group_selfassign").takeIf { "selfassign" in parts }).joinToString(" · ")
}
