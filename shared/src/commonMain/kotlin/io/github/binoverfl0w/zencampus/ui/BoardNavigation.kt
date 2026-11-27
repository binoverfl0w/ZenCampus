package io.github.binoverfl0w.zencampus.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.CampusContentFrame
import io.github.binoverfl0w.zencampus.ui.design.CourseBadge
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalKeyboardShortcutModifier
import io.github.binoverfl0w.zencampus.ui.design.LocalKeyboardShortcuts
import io.github.binoverfl0w.zencampus.ui.design.LocalReducedMotion
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.RowChevron
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenSheet
import io.github.binoverfl0w.zencampus.ui.design.boardTint
import io.github.binoverfl0w.zencampus.ui.design.toneColors

fun Words.board(board: CampusBoard) = service(when(board) {
    CampusBoard.STUDY -> "study_board"; CampusBoard.LIBRARY -> "library"; CampusBoard.MENSA -> "mensa"
})
fun Words.section(tab: String) = when(tab) {
    "catalogue" -> service("catalogue_section"); "loans" -> service("loans_section"); "saved" -> service("saved_section")
    "messages" -> ui("inbox")
    else -> text(tab)
}

fun boardSymbol(board: CampusBoard) = when (board) {
    CampusBoard.STUDY -> Symbol.CAMPUS
    CampusBoard.LIBRARY -> Symbol.COURSES
    CampusBoard.MENSA -> Symbol.DINING
}

/** The root sections of a board, in navigation order. Mensa is a single page. */
fun boardSections(board: CampusBoard): List<Pair<String, Symbol>> = when (board) {
    CampusBoard.STUDY -> listOf("today" to Symbol.TODAY, "courses" to Symbol.COURSES, "calendar" to Symbol.CALENDAR, "messages" to Symbol.MAIL)
    CampusBoard.LIBRARY -> listOf("catalogue" to Symbol.SEARCH, "loans" to Symbol.COURSES, "saved" to Symbol.PIN)
    CampusBoard.MENSA -> emptyList()
}

enum class SyncState { CURRENT, REFRESHING, OFFLINE }
/** One-line freshness shown in the header (phone) or rail footer (wide). */
data class SyncLine(val text: String, val state: SyncState = SyncState.CURRENT)

@Composable
fun destinationTitle(destination: CampusDestination): String {
    val words = LocalWords.current
    return when (destination.route) {
        "accounts" -> words.service("your_cards")
        "settings" -> words.text("settings")
        "downloads" -> words.text("downloads")
        else -> words.board(destination.board)
    }
}

/** The context shown on the wide top bar while a detail page is open. */
@Composable
fun destinationContext(destination: CampusDestination): String {
    val words = LocalWords.current
    val route = destination.route
    return when {
        route == null -> words.section(destination.tab)
        route.startsWith("course:") -> words.text("courses")
        route == "book" -> words.service("book_details")
        route in setOf("coursework", "task", "assignment") -> words.study("coursework")
        route == "updates" -> words.text("updates")
        route == "community" -> words.text("courses")
        else -> destinationTitle(destination)
    }
}

@Composable
private fun SyncDot(state: SyncState) {
    val reducedMotion = LocalReducedMotion.current
    if (state == SyncState.REFRESHING && !reducedMotion) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
    else Box(Modifier.size(7.dp).background(
        if (state == SyncState.OFFLINE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, CircleShape))
}

@Composable
private fun RefreshButton(onRefresh: () -> Unit, enabled: Boolean, refreshing: Boolean) {
    val words = LocalWords.current
    IconAction(words.text("refresh"), onRefresh, Modifier.testTag("header-refresh"), enabled = enabled && !refreshing) {
        if (refreshing && !LocalReducedMotion.current) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.8.dp)
        else ZenIcon(Symbol.REFRESH, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline)
    }
}

/** Phone header: the board title doubles as the board switcher; freshness sits under it. */
@Composable
fun BoardTopBar(destination: CampusDestination, onBoards: () -> Unit, onCards: () -> Unit,
    onBack: (() -> Unit)? = null, onRefresh: (() -> Unit)? = null, refreshEnabled: Boolean = true,
    onSettings: (() -> Unit)? = null, status: SyncLine? = null, refreshing: Boolean = false, onSearch: (() -> Unit)? = null) {
    val words = LocalWords.current
    val title = destinationTitle(destination)
    val context = if (destination.route != null && !destination.global) destinationContext(destination) else ""
    CampusContentFrame(Modifier.testTag("board-header-column")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = if (onBack == null) 12.dp else 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconAction(Symbol.BACK, words.text("back"), onBack)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Column(Modifier.clip(RoundedCornerShape(Zen.radiusMedium)).clickable(onClick = onBoards)
                    .testTag("board-switcher").semantics(mergeDescendants = true) {
                        contentDescription = words.service("switch_board")
                        stateDescription = title
                    }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold, fontSize = 23.sp, letterSpacing = (-.5).sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        ZenIcon(Symbol.DOWN, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (status != null && status.text.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SyncDot(status.state)
                        Text(if (context.isBlank()) status.text else "$context · ${status.text}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if (context.isNotBlank()) SmallLabel(context)
                }
            }
            // Pull-to-refresh replaces the header's refresh button wherever search is offered.
            if (onSearch != null) IconAction(Symbol.SEARCH, words.ui("search"), onSearch)
            else if (onRefresh != null) RefreshButton(onRefresh, refreshEnabled, refreshing)
            if (destination.route != "accounts") IconAction(Symbol.CARD, words.service("your_cards"), onCards)
            if (onSettings != null && destination.route != "settings") IconAction(Symbol.SETTINGS, words.text("settings"), onSettings)
        }
    }
}

/** Wide windows: the rail carries navigation, so the bar only names the open page and offers Back. */
@Composable
fun WideTopBar(destination: CampusDestination, onBack: (() -> Unit)?) {
    val words = LocalWords.current
    if (onBack == null) return
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 16.dp).testTag("board-header-column"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconAction(Symbol.BACK, words.text("back"), onBack)
        Text(destinationContext(destination), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun BoardSections(destination: CampusDestination, badges: Map<String, String> = emptyMap(), onSection: (String) -> Unit) {
    if (destination.route != null || destination.board == CampusBoard.MENSA) return
    val words = LocalWords.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CampusContentFrame(Modifier.testTag("board-sections-column")) {
                NavigationBar(Modifier.testTag("board-sections"), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    boardSections(destination.board).forEach { (id, symbol) ->
                        val active = destination.tab == id
                        NavigationBarItem(active, { if (!active) onSection(id) },
                            icon = {
                                val badge = badges[id]
                                if (badge.isNullOrBlank()) ZenIcon(symbol, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                else BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(badge) } }) {
                                    ZenIcon(symbol, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            label = { Text(words.section(id), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium) },
                            modifier = Modifier.testTag("section-$id"),
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
        }
    }
}

/** A secondary rail entry (a route rather than a root section). */
data class RailLink(val id: String, val symbol: Symbol, val label: String, val selected: Boolean,
    val badge: String = "", val tone: Tone = Tone.NEUTRAL, val onClick: () -> Unit)

@Composable
private fun RailItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    leading: @Composable () -> Unit, trailing: @Composable () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(12.dp), color = if (selected) scheme.surface else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, scheme.outlineVariant) else null) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading()
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing()
        }
    }
}

@Composable
private fun RailHeading(text: String) {
    Text(text, Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Wide-window navigation: boards, the current board's sections, pinned courses, then cards, settings and freshness. */
@Composable
fun CampusRail(
    destination: CampusDestination, onBoard: (CampusBoard) -> Unit, onSection: (String) -> Unit,
    onCards: () -> Unit, onSettings: (() -> Unit)?,
    links: List<RailLink> = emptyList(), sectionBadges: Map<String, String> = emptyMap(),
    pinned: List<Course> = emptyList(), onCourse: (String) -> Unit = {},
    status: SyncLine? = null, onRefresh: (() -> Unit)? = null, refreshEnabled: Boolean = true, refreshing: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    val words = LocalWords.current
    val scheme = MaterialTheme.colorScheme
    val shortcuts = LocalKeyboardShortcuts.current
    val modifier = LocalKeyboardShortcutModifier.current ?: words.ui("ctrl")
    Surface(Modifier.width(Zen.railWidth).fillMaxHeight().testTag("board-rail"), color = scheme.surfaceContainer) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(horizontal = 12.dp, vertical = 18.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(32.dp).background(scheme.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        ZenIcon(Symbol.LEAF, Modifier.size(18.dp), scheme.onPrimaryContainer)
                    }
                    Text("ZenCampus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (onSearch != null) Surface(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("rail-search"),
                    shape = RoundedCornerShape(12.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ZenIcon(Symbol.SEARCH, Modifier.size(17.dp), scheme.onSurfaceVariant)
                        Text(words.ui("search"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        if (shortcuts) Text("$modifier K", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RailHeading(words.ui("boards"))
                    CampusBoard.entries.forEachIndexed { index, board ->
                        val active = destination.board == board && !destination.global
                        val tint = boardTint(board)
                        Surface(onClick = { onBoard(board) }, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                            .testTag("rail-board-${board.name.lowercase()}").semantics { selected = active },
                            shape = RoundedCornerShape(12.dp), color = if (active) scheme.primaryContainer else Color.Transparent) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(10.dp).background(tint.ink, RoundedCornerShape(3.dp)))
                                Text(words.board(board), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) scheme.onPrimaryContainer else scheme.onSurface)
                                if (shortcuts) Text("$modifier ${index + 1}", style = MaterialTheme.typography.labelSmall,
                                    color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val sections = boardSections(destination.board)
                if (sections.isNotEmpty() || links.isNotEmpty()) Column(Modifier.testTag("board-sections"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RailHeading(words.board(destination.board))
                    sections.forEach { (id, symbol) ->
                        val active = destination.route == null && destination.tab == id
                        RailItem(words.section(id), active, { onSection(id) }, Modifier.testTag("section-$id"),
                            leading = { ZenIcon(symbol, Modifier.size(20.dp), if (active) scheme.primary else scheme.onSurfaceVariant) },
                            trailing = { sectionBadges[id]?.takeIf { it.isNotBlank() }?.let { CountBadge(it) } })
                    }
                    links.forEach { link ->
                        RailItem(link.label, link.selected, link.onClick, Modifier.testTag("rail-${link.id}"),
                            leading = { ZenIcon(link.symbol, Modifier.size(20.dp), if (link.selected) scheme.primary else scheme.onSurfaceVariant) },
                            trailing = { if (link.badge.isNotBlank()) Text(link.badge, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold, color = toneColors(link.tone).ink) })
                    }
                }
                if (pinned.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RailHeading(words.ui("pinned"))
                    pinned.forEach { course ->
                        RailItem(course.title, destination.route == "course:${course.id}", { onCourse(course.id) },
                            Modifier.testTag("rail-course-${course.id}"), leading = { CourseBadge(course, 24.dp) })
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Spacer(Modifier.height(8.dp))
                RailItem(words.service("your_cards"), destination.route == "accounts", onCards, Modifier.testTag("rail-cards"),
                    leading = { ZenIcon(Symbol.CARD, Modifier.size(20.dp), scheme.onSurfaceVariant) })
                if (onSettings != null) RailItem(words.text("settings"), destination.route == "settings", onSettings, Modifier.testTag("rail-settings"),
                    leading = { ZenIcon(Symbol.SETTINGS, Modifier.size(20.dp), scheme.onSurfaceVariant) })
                if (status != null || onRefresh != null) {
                    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp), color = scheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status != null) SyncDot(status.state)
                        Text(status?.text.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (onRefresh != null) RefreshButton(onRefresh, refreshEnabled, refreshing)
                    }
                }
            }
        }
    }
}

@Composable
fun CountBadge(text: String) {
    Box(Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
        .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/**
 * The adaptive frame. Wide windows get the rail and a wider content area; phones get the header and bottom sections.
 * [fullWidth] lets pages that benefit from space (Today, Calendar) use the whole wide column.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CampusShell(
    wide: Boolean, fullWidth: Boolean,
    rail: @Composable () -> Unit,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    onRefresh: (() -> Unit)? = null, refreshing: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val body: @Composable (PaddingValues) -> Unit = { padding ->
        val column: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = if (wide && fullWidth) Zen.wideContentWidth else Zen.readingWidth).fillMaxSize()
                    .testTag("board-content-column"), content = content)
            }
        }
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            // Pull down on any page to refresh it; the rail and Ctrl+R remain on desktop.
            if (onRefresh != null) PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize().testTag("pull-refresh")) { column() }
            else column()
        }
    }
    if (wide) Row(Modifier.fillMaxSize().background(background)) {
        rail()
        Scaffold(Modifier.weight(1f).fillMaxHeight(), containerColor = background,
            topBar = { Column(Modifier.statusBarsPadding()) { topBar() } }, content = body)
    } else Scaffold(containerColor = background, topBar = { Column(Modifier.statusBarsPadding()) { topBar() } },
        bottomBar = bottomBar, content = body)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardSwitcher(current: CampusBoard, account: Account?, library: LibraryState, onDismiss: () -> Unit,
    onBoard: (CampusBoard) -> Unit, onCards: () -> Unit, places: List<RailLink> = emptyList()) {
    val words = LocalWords.current
    ZenSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().testTag("boards-sheet"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(words.service("your_boards"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)) }
            CampusBoard.entries.forEach { board -> item {
                val active = current == board
                val tint = boardTint(board)
                Surface(onClick = { onDismiss(); onBoard(board) }, Modifier.fillMaxWidth().testTag("choose-${board.name.lowercase()}")
                    .semantics { selected = active }, shape = RoundedCornerShape(Zen.radiusLarge),
                    border = if (active) BorderStroke(1.5.dp, tint.ink) else null,
                    color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(44.dp).background(tint.soft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            ZenIcon(boardSymbol(board), Modifier.size(22.dp), tint.ink)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(words.board(board), style = MaterialTheme.typography.titleMedium)
                            Text(words.service(when(board) { CampusBoard.STUDY -> "study_sections"; CampusBoard.LIBRARY -> "library_sections"; CampusBoard.MENSA -> "mensa_sections" }),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            SmallLabel(when(board) {
                                CampusBoard.STUDY -> if (account == null) words.service("connect_digi") else if (account.demo) words.text("preview_mode") else "DigiCampus"
                                CampusBoard.LIBRARY -> words.service(if (library.connected) "library_connected" else "catalogue_public")
                                CampusBoard.MENSA -> words.service("mensa_source")
                            })
                        }
                        if (active) Box(Modifier.size(28.dp).background(tint.ink, CircleShape), contentAlignment = Alignment.Center) {
                            ZenIcon(Symbol.CHECK, Modifier.size(15.dp), tint.soft)
                        }
                        else ZenIcon(Symbol.NEXT, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }
            if (places.isNotEmpty()) item {
                // Secondary destinations of the current board live here instead of in the bottom bar.
                Text("${words.ui("more_in")} ${words.board(current)}", Modifier.padding(top = 8.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.testTag("board-places")) {
                    places.forEachIndexed { index, place ->
                        ZenListItem(GroupPosition.of(index, places.size), Modifier.testTag("place-${place.id}"), { onDismiss(); place.onClick() }) {
                            ZenIconTile(place.symbol)
                            ZenListText(place.label, modifier = Modifier.weight(1f))
                            if (place.badge.isNotBlank()) ToneChip(place.badge, place.tone)
                            RowChevron()
                        }
                    }
                }
            }
            item {
                ZenButton(words.service("your_cards"), { onDismiss(); onCards() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), style = ButtonStyle.OUTLINE, symbol = Symbol.CARD)
            }
        }
    }
}
