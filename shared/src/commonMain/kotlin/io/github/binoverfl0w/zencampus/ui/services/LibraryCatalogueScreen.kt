package io.github.binoverfl0w.zencampus.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.digicampus.text
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.data.services.catalogueUrl
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.CampusBoard
import io.github.binoverfl0w.zencampus.ui.SettingToggle
import io.github.binoverfl0w.zencampus.ui.catalogueResultCount
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.EmptyMoment
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenChip
import io.github.binoverfl0w.zencampus.ui.design.ZenDivider
import io.github.binoverfl0w.zencampus.ui.design.ZenGroup
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconToggle
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.ZenRow
import io.github.binoverfl0w.zencampus.ui.design.ZenStatus
import io.github.binoverfl0w.zencampus.ui.design.ZenTabs
import io.github.binoverfl0w.zencampus.ui.design.boardTint
import io.github.binoverfl0w.zencampus.ui.planner
import io.github.binoverfl0w.zencampus.ui.service
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryCatalogueScreen(services: CampusServicesRepository, network: NetworkConditions,
    onBook: (String) -> Unit, onOpenUrl: (String) -> Unit, initialQuery: String = "") {
    val words = LocalWords.current
    val catalogue = services.catalogue
    val state by catalogue.search.collectAsState()
    val saved by catalogue.saved.collectAsState()
    val recent by catalogue.recent.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var field by rememberSaveable { mutableStateOf(CatalogueField.ALL) }
    var submitted by rememberSaveable { mutableStateOf("") }
    var submittedField by rememberSaveable { mutableStateOf(CatalogueField.ALL) }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var request by rememberSaveable { mutableIntStateOf(0) }
    val query = CatalogueQuery(submitted, submittedField)
    val results = state.results?.takeIf { it.query == query && it.page == page }
    val submit: () -> Unit = {
        if (text.isNotBlank()) { focus.clearFocus(); submitted = text.trim(); submittedField = field; page = 1; request++ }
    }
    var handedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && initialQuery != handedQuery) {
            handedQuery = initialQuery; text = initialQuery; field = CatalogueField.ALL
            submitted = initialQuery; submittedField = CatalogueField.ALL; page = 1; request++
        }
    }
    LaunchedEffect(catalogue, submitted, submittedField, page, request, network.connected) {
        if (submitted.isNotBlank()) catalogue.search(query, page)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("catalogue-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            ScreenTitle(words.service("catalogue_title"), words.service("catalogue_body"))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                text,
                { text = it.take(180) },
                Modifier.fillMaxWidth().testTag("catalogue-query"),
                label = { Text(words.service("catalogue_search_hint")) },
                singleLine = true,
                leadingIcon = { ZenIcon(Symbol.SEARCH, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = if (text.isNotEmpty()) ({
                    IconAction(Symbol.CLOSE, words.planner("clear"), { text = "" })
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            Spacer(Modifier.height(10.dp))
            ZenTabs(CatalogueField.entries.map { words.service("search_${it.name.lowercase()}") }, field.ordinal,
                { field = CatalogueField.entries[it] })
            Spacer(Modifier.height(10.dp))
            ZenButton(words.service("search_catalogue"), submit, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = text.isNotBlank(), style = ButtonStyle.PRIMARY)
        }
        if (submitted.isBlank()) {
            if (recent.isNotEmpty()) item {
                SectionTitle(words.service("recent_searches"))
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { q ->
                        ZenChip("${q.text} · ${words.service("search_${q.field.name.lowercase()}")}", { text = q.text; field = q.field; submitted = q.text; submittedField = q.field; page = 1; request++ }, symbol = Symbol.CLOCK)
                    }
                }
            }
            if (saved.isNotEmpty()) item {
                SectionTitle(words.service("saved_section"))
                Spacer(Modifier.height(8.dp))
                ZenGroup(Modifier.testTag("catalogue-saved-preview")) {
                    saved.take(3).forEachIndexed { index, book ->
                        if (index > 0) ZenDivider()
                        ZenRow(onClick = { onBook(book.id) }) {
                            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center) { ZenIcon(Symbol.COURSES, Modifier.size(18.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
                            Column(Modifier.weight(1f)) {
                                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val detail = listOf(book.author, book.year).filter { it.isNotBlank() }.joinToString(" · ")
                                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ZenIcon(Symbol.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { SmallLabel(words.service("discover_books_body")) }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(results?.total?.let(words::catalogueResultCount) ?: words.service("search_results"), style = MaterialTheme.typography.titleMedium)
                        CatalogueAge(results?.fetchedAt, state.loading, services.preview, network)
                    }
                    IconAction(Symbol.REFRESH, words.text("refresh"), { scope.launch { catalogue.search(query, page, true) } }, !state.loading && network.connected)
                }
                if (state.error != null) Text(words.service(if (!network.connected || state.error == FailureKind.OFFLINE) "catalogue_offline" else "catalogue_error"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (state.loading && results == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            if (results != null && results.books.isEmpty()) item { EmptyMoment(words.service("no_catalogue_results"), words.service("no_catalogue_results_body"), Symbol.COURSES) }
            items(results?.books.orEmpty(), key = { it.id }) { book ->
                CatalogueBookCard(book, saved.any { it.id == book.id }, { onBook(book.id) }, { scope.launch { catalogue.toggleSaved(book) } })
            }
            if (results != null) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    ZenLink(words.service("previous_page"), { page-- }, enabled = page > 1 && !state.loading)
                    SmallLabel("${words.service("page")} $page")
                    ZenLink(words.service("next_page"), { page++ }, enabled = results.hasNext && page < 500 && !state.loading)
                }
            }
        }
        item { ZenLink(words.service("official_catalogue"), { onOpenUrl(LIBRARY_START) }, external = true) }
    }
}

@Composable
fun SavedBooksScreen(services: CampusServicesRepository, onBook: (String) -> Unit) {
    val words = LocalWords.current
    val saved by services.catalogue.saved.collectAsState()
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().testTag("saved-books-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle(words.service("saved_books"), words.service("saved_books_body")) }
        if (saved.isEmpty()) item { EmptyMoment(words.service("no_saved_books"), words.service("no_saved_books_body"), Symbol.PIN) }
        items(saved, key = { it.id }) { book -> CatalogueBookCard(book, true, { onBook(book.id) }, { scope.launch { services.catalogue.toggleSaved(book) } }) }
    }
}

@Composable
private fun CatalogueBookCard(book: CatalogueBook, saved: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    val words = LocalWords.current
    ZenListItem(modifier = Modifier.testTag("catalogue-book"), onClick = onOpen) {
        ZenIconTile(Symbol.COURSES, boardTint(CampusBoard.LIBRARY))
        ZenListText(book.title, book.author, meta = listOf(book.year, book.edition, book.media).filter { it.isNotBlank() }.joinToString(" · "),
            modifier = Modifier.weight(1f))
        ZenIconToggle(saved, { onSave() }, modifier = Modifier.semantics {
            contentDescription = words.service(if (saved) "remove_saved_book" else "save_book")
        }) {
            ZenIcon(if (saved) Symbol.PIN_FILLED else Symbol.PIN, Modifier.size(18.dp),
                if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogueBookScreen(services: CampusServicesRepository, id: String, network: NetworkConditions, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val catalogue = services.catalogue
    val state by catalogue.record.collectAsState()
    val saved by catalogue.saved.collectAsState()
    val preferences by services.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    val record = state.record?.takeIf { state.id == id }
    LaunchedEffect(catalogue, id, network.connected) { catalogue.openRecord(id) }
    LazyColumn(Modifier.fillMaxSize().testTag("catalogue-detail-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            if (record != null) {
                ScreenTitle(record.book.title, listOf(record.book.author, record.book.year, record.book.edition).filter { it.isNotBlank() }.joinToString(" · "))
                Spacer(Modifier.height(10.dp))
                CatalogueAge(record.fetchedAt, state.loading, record.sample, network)
                Spacer(Modifier.height(10.dp))
                val isSaved = saved.any { it.id == id }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZenButton(words.service(if (isSaved) "remove_saved_book" else "save_book"), { scope.launch { catalogue.toggleSaved(record.book) } }, style = ButtonStyle.TONAL, symbol = Symbol.PIN)
                    ZenButton(words.text("refresh"), { scope.launch { catalogue.openRecord(id, true) } }, enabled = network.connected && !state.loading, style = ButtonStyle.OUTLINE, symbol = Symbol.REFRESH)
                }
                // Only offered for print copies that are all out; e-books and available titles need no watch.
                val watched = preferences.watchedBooks.any { it.id == id }
                if (LocalServiceAlerts.current && (watched || (!record.holdingsUnavailable && record.copies.isNotEmpty() && record.copies.none { copyAvailable(it.status) }))) {
                    Spacer(Modifier.height(10.dp))
                    val authorize = LocalAuthorizeServiceAlert.current
                    SettingToggle(words.ui("notify_available"), words.ui("notify_available_body"), watched, inset = 0.dp) { on ->
                        authorize("books", on) { allowed -> if (allowed) scope.launch { services.watchBook(record.book, on) } }
                    }
                }
            } else {
                if (state.loading || state.id != id) ZenStatus(words.service("book_loading"), loading = true)
                ZenLink(words.text("refresh"), { scope.launch { catalogue.openRecord(id, true) } }, enabled = network.connected && !state.loading)
            }
            if (state.error != null && state.id == id) {
                Spacer(Modifier.height(8.dp))
                Text(words.service(if (state.error == FailureKind.NOT_FOUND) "book_not_found" else if (!network.connected || state.error == FailureKind.OFFLINE) "catalogue_offline" else "catalogue_error"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (record != null) {
            item {
                SectionTitle(words.service("book_copies"))
                Spacer(Modifier.height(6.dp))
                Text(words.service("availability_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (record.holdingsUnavailable) {
                    Text(words.service("holdings_error"), color = MaterialTheme.colorScheme.error)
                    if (record.copies.isNotEmpty() || record.holdingsNote.isNotBlank()) SmallLabel("${words.service("saved_availability")} · ${words.service("service_updated")} ${words.age(record.holdingsFetchedAt ?: record.fetchedAt, LocalNow.current)}")
                }
                else if (record.copies.isEmpty()) Text(record.holdingsNote.ifBlank { words.service("no_copies") })
            }
            itemsIndexed(record.copies) { _, copy ->
                ZenPanel(Modifier.fillMaxWidth().testTag("catalogue-copy")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(copy.callNumber.ifBlank { words.service("call_number_unknown") }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        ToneChip(copy.status.ifBlank { words.service("status_unknown") }, Tone.PRIMARY)
                    }
                    val locationLine = listOf(copy.location, copy.branch).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    if (locationLine.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(locationLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (record.properties.isNotEmpty()) item {
                SectionTitle(words.service("book_details"))
                Spacer(Modifier.height(10.dp))
                ZenPanel(Modifier.fillMaxWidth()) {
                    record.properties.forEachIndexed { index, field ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        SmallLabel(field.label)
                        Text(field.value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (record.links.isNotEmpty()) item {
                SectionTitle(words.service("book_links"))
                record.links.forEach { link -> ZenLink(link.label, { onOpenUrl(link.url) }, external = true) }
                Text(words.service("ebook_access"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                ZenButton(words.service("official_book"), { onOpenUrl(catalogueUrl(record.book)) }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, trailing = Symbol.EXTERNAL)
                Spacer(Modifier.height(8.dp))
                Text(words.service("official_book_actions"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else item { ZenLink(words.service("official_catalogue"), { onOpenUrl(LIBRARY_START) }, external = true) }
    }
}

@Composable
private fun CatalogueAge(updated: Long?, loading: Boolean, sample: Boolean, network: NetworkConditions) {
    val words = LocalWords.current
    if (sample) SmallLabel(words.service("sample_services")) else {
        if (!network.connected) SmallLabel(words.service("service_offline"))
        else if (loading) SmallLabel(words.text("refreshing"))
        if (updated != null) SmallLabel("${words.service("service_updated")} ${words.age(updated, LocalNow.current)}")
    }
}
