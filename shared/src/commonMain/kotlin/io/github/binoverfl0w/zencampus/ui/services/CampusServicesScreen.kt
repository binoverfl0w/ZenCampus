package io.github.binoverfl0w.zencampus.ui.services

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.BoardSections
import io.github.binoverfl0w.zencampus.ui.BoardSlider
import io.github.binoverfl0w.zencampus.ui.BoardSwitcher
import io.github.binoverfl0w.zencampus.ui.BoardTopBar
import io.github.binoverfl0w.zencampus.ui.CampusBoard
import io.github.binoverfl0w.zencampus.ui.CampusDestination
import io.github.binoverfl0w.zencampus.ui.CampusNavigation
import io.github.binoverfl0w.zencampus.ui.CampusRail
import io.github.binoverfl0w.zencampus.ui.CampusShell
import io.github.binoverfl0w.zencampus.ui.SettingToggle
import io.github.binoverfl0w.zencampus.ui.SyncLine
import io.github.binoverfl0w.zencampus.ui.WideTopBar
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.boardSymbol
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.EmptyMoment
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWideLayout
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenBoardTheme
import io.github.binoverfl0w.zencampus.ui.design.ZenChip
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconToggle
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.ZenStatus
import io.github.binoverfl0w.zencampus.ui.design.ZenTabs
import io.github.binoverfl0w.zencampus.ui.design.ZenTint
import io.github.binoverfl0w.zencampus.ui.design.boardTint
import io.github.binoverfl0w.zencampus.ui.design.toneColors
import io.github.binoverfl0w.zencampus.ui.mealCategory
import io.github.binoverfl0w.zencampus.ui.mealDescription
import io.github.binoverfl0w.zencampus.ui.mealLabel
import io.github.binoverfl0w.zencampus.ui.mealName
import io.github.binoverfl0w.zencampus.ui.mealOriginalName
import io.github.binoverfl0w.zencampus.ui.service
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

/** Platforms without an alert scheduler hide controls that promise notifications. */
val LocalServiceAlerts = staticCompositionLocalOf { true }
val LocalAuthorizeServiceAlert = staticCompositionLocalOf<(String, Boolean, (Boolean) -> Unit) -> Unit> { { _, _, done -> done(true) } }

@Composable
fun MensaScreen(services: CampusServicesRepository, network: NetworkConditions, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val state by services.mensa.collectAsState()
    val preferences by services.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(campusToday(now).toString()) }
    val date = runCatching { LocalDate.parse(selected) }.getOrDefault(campusToday(now))
    val week = weekDates(date)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var choosePrice by remember { mutableStateOf(false) }
    var chooseLocation by remember { mutableStateOf(false) }
    var editingAllergens by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(services, network.connected, preferences.mensaLocation) { services.refreshMensa() }
    TranslateMenu(services, state.menu, preferences, network.connected)
    val day = state.menu?.days?.firstOrNull { it.date == date }
    val meals = day?.meals.orEmpty().filter { it.visibleWith(preferences) }.sortedByDescending { it.isFavorite(preferences) }
    val hiddenByAllergens = day?.meals.orEmpty().count { it.matches(preferences.mealFilter) && !it.visibleWith(preferences) }
    val location = MENSA_LOCATIONS.firstOrNull { it.key == preferences.mensaLocation } ?: MENSA_LOCATIONS.first()
    LazyColumn(Modifier.fillMaxSize().testTag("mensa-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            // The location picker is the title: one tap to another Studierendenwerk canteen.
            Box {
                Row(Modifier.clickable { chooseLocation = true }.testTag("mensa-location").padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f, fill = false)) { ScreenTitle(location.name, "") }
                    ZenIcon(Symbol.DOWN, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(chooseLocation, { chooseLocation = false }) {
                    MENSA_LOCATIONS.forEach { place ->
                        DropdownMenuItem(text = { Text(place.name) }, modifier = Modifier.testTag("mensa-location-${place.key}"),
                            trailingIcon = { if (place.key == location.key) ZenIcon(Symbol.CHECK, Modifier.size(16.dp), MaterialTheme.colorScheme.primary) },
                            onClick = { chooseLocation = false; scope.launch { services.setMensaLocation(place.key) } })
                    }
                }
            }
            if (words.service("mensa_body").isNotBlank()) Text(words.service("mensa_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Symbol.BACK, words.text("previous_week"), { selected = date.plus(-7, DateTimeUnit.DAY).toString() })
                Text("${words.formatDate(week.first())} – ${words.formatDate(week.last())}", Modifier.weight(1f),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                IconAction(Symbol.NEXT, words.text("next_week"), { selected = date.plus(7, DateTimeUnit.DAY).toString() })
            }
            Row(Modifier.fillMaxWidth().testTag("mensa-week-days"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { dayDate ->
                    val active = dayDate == date
                    val ink = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    val muted = if (active) ink.copy(alpha = .85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(onClick = { selected = dayDate.toString(); expanded = null }, Modifier.weight(1f).semantics {
                        this.selected = active; role = Role.Tab; contentDescription = words.formatDate(dayDate, true)
                    }, shape = RoundedCornerShape(14.dp),
                        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(words.shortDay(dayDate), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                            Text(dayDate.day.toString(), style = MaterialTheme.typography.titleMedium, color = ink)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                ZenTabs(MealFilter.entries.map { filter -> words.service(when (filter) { MealFilter.ALL -> "all_meals"; MealFilter.VEGETARIAN -> "vegetarian"; MealFilter.VEGAN -> "vegan" }) },
                    preferences.mealFilter.ordinal, { index -> scope.launch { services.setPreferences { it.copy(mealFilter = MealFilter.entries[index]) } } })
                Box {
                    ZenChip(words.service(preferences.priceGroup.priceWord()), { choosePrice = true })
                    DropdownMenu(choosePrice, { choosePrice = false }) {
                        MealPriceGroup.entries.forEach { group ->
                            DropdownMenuItem(text = { Text(words.service(group.priceWord())) }, onClick = {
                                choosePrice = false; scope.launch { services.setPreferences { it.copy(priceGroup = group) } }
                            })
                        }
                    }
                }
                ZenChip(if (preferences.excludedLabels.isEmpty()) words.ui("allergens") else "${words.ui("allergens")} · ${preferences.excludedLabels.size}",
                    { editingAllergens = true }, Modifier.testTag("mensa-allergens"), symbol = Symbol.SETTINGS, selected = preferences.excludedLabels.isNotEmpty())
                ZenLink(words.text("today"), { selected = campusToday(now).toString() })
            }
            if (hiddenByAllergens > 0) SmallLabel("$hiddenByAllergens ${words.ui("hidden_by_allergens")}")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ServiceAge(state.menu?.fetchedAt, state.loading, services.preview, network) }
                IconAction(Symbol.REFRESH, words.text("refresh"), { scope.launch { services.refreshMensa(force = true) } }, !state.loading && network.connected)
            }
            if (state.error != null) Text(words.service("menu_error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        when {
            state.loading && state.menu == null -> item { ZenStatus(words.service("menu_loading"), loading = true) }
            day?.availability == MenuAvailability.CLOSED -> item { EmptyMoment(words.service("mensa_closed"), words.service("mensa_closed_body"), Symbol.DINING) }
            day == null -> item { EmptyMoment(words.service("menu_missing"), words.service("menu_missing_body"), Symbol.DINING) }
            meals.isEmpty() -> item { EmptyMoment(words.service("no_filtered_meals"), words.text("refresh"), Symbol.DINING) }
        }
        items(meals, key = { it.id }) { meal ->
            ZenPanel(Modifier.fillMaxWidth().testTag("mensa-meal"), { expanded = if (expanded == meal.id) null else meal.id }) {
                val cents = meal.prices.forGroup(preferences.priceGroup)
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (meal.category.isNotBlank()) Text(words.mealCategory(meal.category), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(words.mealName(meal, preferences), style = MaterialTheme.typography.titleMedium)
                        words.mealOriginalName(meal, preferences)?.let { original ->
                            Text(original, Modifier.testTag("meal-original"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val favorite = meal.isFavorite(preferences)
                    Column(horizontalAlignment = Alignment.End) {
                        // The star is centred on the price line; the shift keeps that line level with the first text line.
                        Row(Modifier.offset(y = (-4).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ZenIconToggle(favorite, { scope.launch { services.toggleFavorite(meal) } }, modifier = Modifier.size(32.dp)
                                .testTag("favorite-${meal.id}").semantics { contentDescription = words.ui(if (favorite) "unfavorite" else "favorite") + " · " + words.mealName(meal, preferences) }) {
                                ZenIcon(if (favorite) Symbol.STAR_FILLED else Symbol.STAR, Modifier.size(18.dp),
                                    if (favorite) toneColors(Tone.WARN).ink else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(words.price(cents), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
                        }
                        if (cents != null && meal.prices.per100g) SmallLabel(words.service("per_100g"))
                    }
                }
                if (meal.description.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(words.mealDescription(meal, preferences), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded == meal.id) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (meal.vegan || meal.vegetarian) ToneChip(words.service(if (meal.vegan) "vegan" else "vegetarian"), Tone.SUCCESS)
                    Spacer(Modifier.weight(1f))
                    SmallLabel(words.service("meal_details"))
                    ZenIcon(if (expanded == meal.id) Symbol.CLOSE else Symbol.DOWN, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded == meal.id) {
                    Spacer(Modifier.height(8.dp))
                    Text(if (meal.labels.isEmpty()) words.service("no_meal_labels") else meal.labels.joinToString(" · ") {
                        val name = words.mealLabel(it)
                        if (name.isBlank()) "${words.service("label_code")} ${it.code}" else "$name (${it.code})"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            val alerts = LocalServiceAlerts.current
            if (alerts || !words.german) ZenPanel(Modifier.fillMaxWidth(), padding = 0.dp) {
                // The menu is only published in German, so the switch only matters in English.
                if (!words.german) SettingToggle(words.ui("translate_dishes"), words.ui("translate_dishes_body"), preferences.translateMeals) { enabled ->
                    scope.launch { services.setPreferences { it.copy(translateMeals = enabled) } }
                }
                if (alerts) {
                    val authorize = LocalAuthorizeServiceAlert.current
                    SettingToggle(words.ui("favorite_alerts"), words.ui("favorite_alerts_body"), preferences.favoriteAlerts) { enabled ->
                        authorize("favorites", enabled) { allowed -> if (allowed) scope.launch { services.setFavoriteAlerts(enabled) } }
                    }
                }
            }
        }
        item { ZenLink("${words.service("official_menu")} · ${words.service("mensa_source")}", { onOpenUrl(state.menu?.sourceUrl ?: mensaUrl(location.key)) }, external = true) }
    }
    if (editingAllergens) {
        val labels = menuLabels(state.menu).sortedBy { words.mealLabel(it).ifBlank { it.code }.lowercase() }
        ZenDialog(onDismissRequest = { editingAllergens = false }, modifier = Modifier.testTag("allergen-dialog"),
            title = { Text(words.ui("hide_dishes_with")) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SmallLabel(words.ui("allergens_body"))
                if (labels.isEmpty()) ZenStatus(words.service("no_meal_labels"))
                labels.forEach { label ->
                    val excluded = label.code in preferences.excludedLabels
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(excluded, role = Role.Checkbox) { scope.launch { services.toggleExcludedLabel(label.code) } }
                        .testTag("allergen-${label.code}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(excluded, onCheckedChange = null)
                        Text(words.mealLabel(label).ifBlank { "${words.service("label_code")} ${label.code}" }, Modifier.weight(1f))
                        SmallLabel(label.code)
                    }
                }
            } },
            confirmButton = { DialogButton(words.text("close"), { editingAllergens = false }, primary = true) })
    }
}

/** In English, asks for translations of the dishes on [menu] that do not have one yet. */
@Composable
private fun TranslateMenu(services: CampusServicesRepository, menu: MensaMenu?, preferences: CampusServicePreferences, connected: Boolean) {
    val german = LocalWords.current.german
    LaunchedEffect(services, menu?.fetchedAt, menu?.location, german, preferences.translateMeals, connected) {
        if (!german && preferences.translateMeals && menu != null) services.translateMenu()
    }
}

private fun MealPriceGroup.priceWord() = when (this) { MealPriceGroup.STUDENT -> "student_prices"; MealPriceGroup.STAFF -> "staff_prices"; MealPriceGroup.GUEST -> "guest_prices" }

internal fun Words.price(cents: Int?): String = if (cents == null) service("price_unknown") else
    "${cents / 100}${if (german) ',' else '.'}${(cents % 100).toString().padStart(2, '0')} €"

private data class TileLine(val title: String, val detail: String, val trailing: String = "", val tone: Tone = Tone.NEUTRAL)

/** Cross-board tile: the other board's colours tell the user where a tap leads. */
@Composable
private fun CampusTile(board: CampusBoard, label: String, lines: List<TileLine>, footer: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, tag: String) {
    val tint = boardTint(board)
    Surface(onClick = onClick, modifier = modifier.testTag(tag), shape = RoundedCornerShape(Zen.radiusLarge), color = tint.soft) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZenIcon(boardSymbol(board), Modifier.size(18.dp), tint.ink)
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = tint.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            lines.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(color = tint.ink.copy(alpha = .14f))
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(line.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (line.detail.isNotBlank()) Text(line.detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (line.trailing.isNotBlank()) Text(line.trailing, style = MaterialTheme.typography.labelLarge,
                        color = if (line.tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else toneColors(line.tone).ink)
                }
            }
            if (footer.isNotBlank()) Text(footer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint.ink)
        }
    }
}

/**
 * Mensa and Library at a glance from Today. Side by side on phones; stacked with more rows in the wide side column.
 */
@Composable
fun CampusDailyBrief(services: CampusServicesRepository, onOpen: (String) -> Unit, stacked: Boolean = false) {
    val words = LocalWords.current
    val now = LocalNow.current
    val menu by services.mensa.collectAsState()
    val library by services.library.collectAsState()
    val preferences by services.preferences.collectAsState()
    val today = campusToday(now)
    val rows = if (stacked) 2 else 1
    val day = menu.menu?.days?.firstOrNull { it.date == today }
    // Favourites first; excluded allergens and the diet filter apply as on the menu.
    val meals = day?.meals.orEmpty().filter { it.visibleWith(preferences) }.sortedByDescending { it.isFavorite(preferences) }
    TranslateMenu(services, menu.menu, preferences, true)
    val mensaLines = meals.take(rows).map { meal ->
        val diet = if (meal.vegan) words.service("vegan") else if (meal.vegetarian) words.service("vegetarian") else words.mealCategory(meal.category)
        val price = words.price(meal.prices.forGroup(preferences.priceGroup))
        val name = if (meal.isFavorite(preferences)) "★ ${words.mealName(meal, preferences)}" else words.mealName(meal, preferences)
        if (stacked) TileLine(name, diet, price) else TileLine(name, listOf(diet, price).filter { it.isNotBlank() }.joinToString(" · "))
    }.ifEmpty { listOf(TileLine(when {
        day?.availability == MenuAvailability.CLOSED -> words.ui("menu_closed")
        menu.loading && menu.menu == null -> words.service("menu_loading")
        else -> words.ui("menu_tile")
    }, "")) }
    val more = meals.size - rows
    val mensaFooter = when { more == 1 -> "+1 ${words.ui("more_dish")}"; more > 1 -> "+$more ${words.ui("more_dishes")}"; else -> "" }
    val loans = library.account?.loans.orEmpty().sortedBy { it.dueDate ?: LocalDate(9999, 1, 1) }
    val ready = library.account?.holds.orEmpty().filter { it.ready }
    val libraryLines = when {
        !library.connected -> listOf(TileLine(words.ui("catalogue_tile"), words.ui("library_connect")))
        // A reservation waiting for pickup matters more than the next due date.
        ready.isNotEmpty() -> ready.take(rows).map { hold ->
            TileLine(hold.title, words.ui("pickup_ready") + (hold.pickupUntil?.let { " · ${words.ui("until")} ${words.formatDate(it)}" } ?: ""), tone = Tone.SUCCESS)
        }
        loans.isEmpty() -> listOf(TileLine(if (library.account == null && library.loading) words.service("library_loading") else words.ui("no_loans_due"), ""))
        else -> loans.take(rows).map { loan ->
            val due = loan.dueDate
            val tone = when { due == null -> Tone.NEUTRAL; due < today -> Tone.DANGER; due <= today.plus(2, DateTimeUnit.DAY) -> Tone.WARN; else -> Tone.NEUTRAL }
            val label = due?.let { "${words.shortDay(it)} ${words.formatDate(it)}" } ?: words.service("due_unknown")
            if (stacked) TileLine(loan.title, loan.details, label, tone) else TileLine(loan.title, "${words.ui("due")} $label", tone = tone)
        }
    }
    val libraryFooter = if (library.connected && loans.isNotEmpty()) "${loans.size} ${words.ui(if (loans.size == 1) "loan" else "loans")}" else ""
    val mensa: @Composable (Modifier) -> Unit = { modifier ->
        CampusTile(CampusBoard.MENSA, words.service("mensa"), mensaLines, mensaFooter, { onOpen("mensa") }, modifier, "today-mensa")
    }
    val books: @Composable (Modifier) -> Unit = { modifier ->
        CampusTile(CampusBoard.LIBRARY, words.service("library"), libraryLines, libraryFooter,
            { onOpen(if (library.connected) "library" else "catalogue") }, modifier, "today-library")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(words.ui("around_campus"))
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { mensa(Modifier.fillMaxWidth()); books(Modifier.fillMaxWidth()) }
        else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            mensa(Modifier.weight(1f).fillMaxHeight()); books(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
fun LibraryScreen(services: CampusServicesRepository, network: NetworkConditions,
    onReminders: (Boolean) -> Unit, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val state by services.library.collectAsState()
    val preferences by services.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    val today = campusToday(LocalNow.current)
    var renew by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(services, state.connectionId, network.connected) { if (state.connected) services.refreshLibrary() }
    LazyColumn(Modifier.fillMaxSize().testTag("library-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle(words.service("your_loans"), words.service("loans_body")) }
        if (!state.connected) item {
            ZenPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ZenIconTile(Symbol.CARD, ZenTint(MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer))
                    Text(words.service("loans_need_card"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    ServiceAge(state.account?.fetchedAt, state.loading, services.preview, network)
                    ZenLink(words.text("refresh"), { scope.launch { services.refreshLibrary(true) } }, enabled = network.connected && !state.loading && state.renewing == null)
                }
                if (state.error != null) Text(if (state.error == FailureKind.AUTHENTICATION) words.service("library_expired") else words.service("library_error"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.account == null && state.loading) item { ZenStatus(words.service("library_loading"), loading = true) }
            else if (state.account?.loans?.isEmpty() == true) item { EmptyMoment(words.service("library_empty"), words.service("library_empty_body"), Symbol.COURSES) }
            items(state.account?.loans.orEmpty().sortedBy { it.dueDate ?: LocalDate(9999, 1, 1) }, key = { it.id }) { loan ->
                ZenPanel(Modifier.fillMaxWidth().testTag("library-loan")) {
                    Text(loan.title, style = MaterialTheme.typography.titleMedium)
                    if (loan.details.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(loan.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    val date = loan.dueDate
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            ZenIcon(Symbol.CLOCK, Modifier.size(16.dp),
                                if (date != null && date < today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(date?.let { "${words.service("due")} ${words.formatDate(it, true)}" } ?: words.service("due_unknown"),
                                style = MaterialTheme.typography.bodyMedium, color = if (date != null && date < today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                        if (date != null && date <= today.plus(2, DateTimeUnit.DAY)) {
                            val overdue = date < today
                            ToneChip(words.service(if (overdue) "overdue" else "due_soon"), if (overdue) Tone.DANGER else Tone.WARN)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                    Spacer(Modifier.height(6.dp))
                    if (loan.renewable) {
                        ZenLink(words.service(if (state.renewing == loan.id) "renewing" else "renew"), { renew = loan.id }, enabled = network.connected && state.renewing == null && state.error != FailureKind.AUTHENTICATION)
                    } else {
                        Spacer(Modifier.height(4.dp))
                        SmallLabel(words.service("renew_unavailable"))
                    }
                }
            }
            state.account?.let { account ->
                item(key = "holds") {
                    Column(Modifier.testTag("library-holds"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(words.ui("reservations"))
                        val holds = account.holds
                        when {
                            holds == null -> ZenNotice(words.ui("holds_unavailable"), Tone.NEUTRAL, secondary = words.service("official_account"),
                                onSecondary = { onOpenUrl(LIBRARY_ACCOUNT) }, secondaryExternal = true)
                            holds.isEmpty() -> SmallLabel(words.ui("no_holds"))
                            else -> Column {
                                val sorted = holds.sortedWith(compareByDescending<LibraryHold> { it.ready }.thenBy { it.pickupUntil ?: LocalDate(9999, 1, 1) })
                                sorted.forEachIndexed { index, hold ->
                                    ZenListItem(GroupPosition.of(index, sorted.size), Modifier.testTag("library-hold-${hold.id}")) {
                                        ZenListText(hold.title, listOf(hold.details, hold.status).filter { it.isNotBlank() }.joinToString(" · "),
                                            meta = hold.pickupUntil?.let { "${words.ui("pickup_until")} ${words.formatDate(it, true)}" }.orEmpty(),
                                            modifier = Modifier.weight(1f))
                                        if (hold.ready) ToneChip(words.ui("pickup_ready"), Tone.SUCCESS)
                                        else ToneChip(words.ui(if (hold.kind == "ordered") "hold_ordered" else "hold_waiting"), Tone.NEUTRAL)
                                    }
                                }
                            }
                        }
                        account.feesCents?.let { fees ->
                            Box(Modifier.testTag("library-fees")) {
                                SmallLabel(if (fees > 0) "${words.ui("library_fees")}: ${words.price(fees)}" else words.ui("no_library_fees"))
                            }
                        }
                    }
                }
            }
            if (preferences.watchedBooks.isNotEmpty()) item(key = "watched") {
                Column(Modifier.testTag("library-watched"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(words.ui("watched_books"))
                    SmallLabel(words.ui("watched_books_body"))
                    Column {
                        preferences.watchedBooks.forEachIndexed { index, book ->
                            ZenListItem(GroupPosition.of(index, preferences.watchedBooks.size), Modifier.testTag("watched-${book.id}")) {
                                ZenListText(book.title, listOf(book.author, book.year).filter { it.isNotBlank() }.joinToString(" · "), modifier = Modifier.weight(1f))
                                ZenLink(words.ui("stop_watching"), { scope.launch { services.watchBook(book, false) } })
                            }
                        }
                    }
                }
            }
            item {
                ZenPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(words.service("library_reminders"), style = MaterialTheme.typography.titleSmall)
                            Text(words.service("library_reminders_body"), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(preferences.libraryReminders, onReminders, enabled = !services.preview,
                            modifier = Modifier.semantics { contentDescription = words.service("library_reminders") })
                    }
                }
            }
        }
        item {
            ZenLink(words.service("official_account"), { onOpenUrl(LIBRARY_ACCOUNT) }, external = true)
        }
    }
    state.account?.loans?.firstOrNull { it.id == renew }?.let { loan ->
        ZenDialog(onDismissRequest = { renew = null }, title = { Text(words.service("renew_book")) },
            text = { Text("${loan.title}\n\n${words.service("renew_book_body")}") },
            confirmButton = { DialogButton(words.service("renew"), { renew = null; scope.launch { services.renewLoan(loan.id) } }, primary = true) },
            dismissButton = { DialogButton(words.text("cancel"), { renew = null }) })
    }
    state.result?.let { result ->
        val key = when (result.status) { RenewalStatus.SUCCESS -> "renew_success"; RenewalStatus.DENIED -> "renew_denied"; RenewalStatus.UNCERTAIN -> "renew_uncertain" }
        ZenDialog(onDismissRequest = services::dismissLibraryResult, title = { Text(words.service(key)) },
            text = { Text(result.message.ifBlank { if (result.status == RenewalStatus.SUCCESS) words.service("your_loans") else words.service("${key}_body") }) },
            confirmButton = { DialogButton(words.text("close"), services::dismissLibraryResult) },
            neutralButton = if (result.status != RenewalStatus.UNCERTAIN) null else ({ ZenLink(words.service("official_account"), { onOpenUrl(LIBRARY_ACCOUNT) }, external = true) }))
    }
}

@Composable
private fun ServiceAge(updated: Long?, loading: Boolean, sample: Boolean, network: NetworkConditions) {
    val words = LocalWords.current
    SmallLabel(when { sample -> words.service("sample_services"); !network.connected -> words.service("service_offline")
        loading -> words.text("refreshing"); updated != null -> "${words.service("service_updated")} ${words.age(updated, LocalNow.current)}"; else -> "" })
}

@Composable
fun PublicCampusApp(services: CampusServicesRepository, network: NetworkConditions, onClose: () -> Unit,
    onConnectLibrary: () -> Unit, onDisconnectLibrary: (Boolean) -> Unit, onReminders: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit, onBackHandler: ((() -> Unit)?) -> Unit, initialRoute: String = "",
    onSignIn: () -> Unit = {}, setupAccounts: Boolean = false, onStudy: () -> Unit = {}, onCancelSetup: () -> Unit = {},
    onRefreshHandler: ((() -> Unit)?) -> Unit = {}) {
    val savedSignIns = LocalSavedSignIns.current
    val saver = remember { Saver<CampusNavigation, String>(save = { Json.encodeToString(it.prepared()) }, restore = { CampusNavigation.restore(it) }) }
    var nav by rememberSaveable(stateSaver = saver) { mutableStateOf(CampusNavigation(listOf(
        if (setupAccounts) CampusDestination(tab = "today", route = "accounts") else CampusDestination(tab = "catalogue")))) }
    val openSettings: (() -> Unit)? = if (savedSignIns == null) null else ({ nav = nav.openSettings() })
    var sliderOrigin by remember { mutableStateOf<CampusBoard?>(null) }
    LaunchedEffect(initialRoute) {
        if (initialRoute.isNotBlank()) {
            val target = initialRoute.substringBefore('#')
            if (target == "study") { onStudy(); return@LaunchedEffect }
            nav = if (target == "accounts" && setupAccounts) nav.push(CampusDestination(tab = "today", route = "accounts"))
                else if (target == "settings") nav.openSettings()
                else nav.openService(target)
        }
    }
    val back: () -> Unit = {
        if (nav.canGoBack) {
            val cancelSetup = setupAccounts && nav.current.route == "accounts"
            nav = nav.back()
            if (cancelSetup) onCancelSetup()
        } else onClose()
    }
    SideEffect { onBackHandler(back) }
    val holder = rememberSaveableStateHolder()
    val destination = nav.current
    val route = destination.route
    var switcher by rememberSaveable { mutableStateOf(false) }
    val library by services.library.collectAsState()
    val scope = rememberCoroutineScope()
    val openBoard: (CampusBoard) -> Unit = { board -> if (board == CampusBoard.STUDY) onStudy() else nav = nav.switchBoard(board) }
    val refresh: () -> Unit = { scope.launch {
        when(destination.tab) {
            "mensa" -> services.refreshMensa(true)
            "loans" -> services.refreshLibrary(true)
            "catalogue" -> services.catalogue.search.value.let { state -> state.query?.let { services.catalogue.search(it, state.results?.page ?: 1, true) } }
        }
    } }
    SideEffect { onRefreshHandler(if (route == null && destination.tab != "saved" && network.connected) refresh else null) }
    val canRefresh = route == null && destination.tab != "saved"
    val busy = (destination.tab == "loans" && (library.loading || library.renewing != null)) ||
        (destination.tab == "mensa" && services.mensa.collectAsState().value.loading)
    ZenBoardTheme(destination.board) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= Zen.wideBreakpoint
        CompositionLocalProvider(LocalWideLayout provides wide) {
        BoardSlider(destination.board, { board ->
            if (board == CampusBoard.STUDY) {
                sliderOrigin?.let { nav = nav.switchBoard(it) }
                onStudy()
            } else nav = nav.switchBoard(board)
        }, Modifier.fillMaxSize(), enabled = !wide && !switcher && !setupAccounts,
            onGesture = { sliderOrigin = if (it) nav.current.board else null }) {
            CampusShell(wide, fullWidth = false,
                rail = {
                    CampusRail(destination, openBoard, { nav = nav.openTab(it) }, onCards = { nav = nav.openCards() }, onSettings = openSettings,
                        status = if (services.preview) SyncLine(LocalWords.current.service("sample_services")) else null,
                        onRefresh = if (canRefresh) refresh else null, refreshEnabled = network.connected, refreshing = busy)
                },
                topBar = {
                    if (wide) WideTopBar(destination, if (nav.canGoBack) back else null)
                    else BoardTopBar(destination, { switcher = true }, { nav = nav.openCards() }, back,
                        onRefresh = if (canRefresh) refresh else null, refreshEnabled = network.connected, refreshing = busy, onSettings = openSettings)
                },
                bottomBar = { BoardSections(destination) { nav = nav.openTab(it) } },
            ) {
                        holder.SaveableStateProvider(when(route) { "book" -> "book:${destination.catalogueBookId}"; "accounts" -> "accounts"; else -> "${destination.board}:${destination.tab}" }) {
                            when {
                                route == "settings" && savedSignIns != null ->
                                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp)) {
                                        item { SavedSignInsSection(savedSignIns) }
                                    }
                                route == "book" -> CatalogueBookScreen(services, destination.catalogueBookId, network, onOpenUrl)
                                route == "accounts" -> ServiceAccountsScreen(null, false, services, onSignIn, {}, onConnectLibrary, onDisconnectLibrary,
                                    setupAccounts, onBoard = openBoard)
                                destination.tab == "mensa" -> MensaScreen(services, network, onOpenUrl)
                                destination.tab == "loans" -> LibraryScreen(services, network, onReminders, onOpenUrl)
                                destination.tab == "saved" -> SavedBooksScreen(services) { nav = nav.openBook(it) }
                                else -> LibraryCatalogueScreen(services, network, { nav = nav.openBook(it) }, onOpenUrl, destination.catalogueQuery)
                            }
                        }
            }
        }
        }
        }
        if (switcher) BoardSwitcher(destination.board, null, library, { switcher = false }, openBoard, { nav = nav.openCards() })
    }
}
