package io.github.binoverfl0w.zencampus.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

const val MENSA_DEFAULT_LOCATION = "aug_universitaetsstr_uni"
const val MENSA_URL = "https://augsburg.my-mensa.de/essen.php?lang=de&mensa=$MENSA_DEFAULT_LOCATION"

/** Studierendenwerk Augsburg locations on the menu site; keys are the site's own location parameters. */
@Serializable data class MensaLocation(val key: String, val name: String)
val MENSA_LOCATIONS = listOf(
    MensaLocation(MENSA_DEFAULT_LOCATION, "Mensa Uni Augsburg"),
    MensaLocation("aug_friedbergerstr_fh", "Mensa TH Augsburg"),
    MensaLocation("aug_baumgartner_fh", "Cafeteria TH Augsburg, Brunnenlech"),
    MensaLocation("aug_imbiss", "Imbisswagen Zentralbibliothek"),
)
fun mensaUrl(location: String): String {
    val key = MENSA_LOCATIONS.firstOrNull { it.key == location }?.key ?: MENSA_DEFAULT_LOCATION
    return "https://augsburg.my-mensa.de/essen.php?lang=de&mensa=$key"
}
const val LIBRARY_ORIGIN = "https://opac.bibliothek.uni-augsburg.de"
const val LIBRARY_BASE = "$LIBRARY_ORIGIN/TouchPoint/"
const val LIBRARY_START = "${LIBRARY_BASE}start.do?View=uba&Language=de"
const val LIBRARY_ACCOUNT = "${LIBRARY_BASE}userAccount.do?methodToCall=showAccount&accountTyp=loaned"

@Serializable enum class MealFilter { ALL, VEGETARIAN, VEGAN }
@Serializable enum class MealPriceGroup { STUDENT, STAFF, GUEST }
@Serializable enum class MenuAvailability { PUBLISHED, CLOSED }

@Serializable data class MealPrices(val studentCents: Int? = null, val staffCents: Int? = null, val guestCents: Int? = null,
    val per100g: Boolean = false) {
    fun forGroup(group: MealPriceGroup): Int? = when (group) {
        MealPriceGroup.STUDENT -> studentCents; MealPriceGroup.STAFF -> staffCents; MealPriceGroup.GUEST -> guestCents
    }
}
@Serializable data class MealLabel(val code: String, val name: String)
@Serializable data class MensaMeal(
    val id: String, val name: String, val description: String = "", val category: String = "",
    val prices: MealPrices = MealPrices(), val vegan: Boolean = false, val vegetarian: Boolean = false,
    val labels: List<MealLabel> = emptyList(), val sourceUrl: String = MENSA_URL,
    /** English [name] and [description] from the translation service; null until translated. */
    val englishName: String? = null, val englishDescription: String? = null,
) {
    fun matches(filter: MealFilter): Boolean = when (filter) {
        MealFilter.ALL -> true; MealFilter.VEGETARIAN -> vegetarian || vegan; MealFilter.VEGAN -> vegan
    }
}
@Serializable data class MensaDay(val date: LocalDate, val meals: List<MensaMeal>,
    val availability: MenuAvailability = MenuAvailability.PUBLISHED)
@Serializable data class MensaMenu(val restaurant: String, val days: List<MensaDay>, val fetchedAt: Long,
    val sourceUrl: String = MENSA_URL, val sample: Boolean = false, val location: String = MENSA_DEFAULT_LOCATION)
@Serializable data class CampusServicePreferences(val mealFilter: MealFilter = MealFilter.ALL,
    val priceGroup: MealPriceGroup = MealPriceGroup.STUDENT, val libraryReminders: Boolean = false,
    val mensaLocation: String = MENSA_DEFAULT_LOCATION,
    /** Normalised dish names (see [favoriteKey]). */
    val favoriteMeals: Set<String> = emptySet(),
    /** Label codes (allergens, additives) whose dishes are hidden. */
    val excludedLabels: Set<String> = emptySet(),
    val favoriteAlerts: Boolean = false,
    /** Catalogue books to report when a copy becomes available. */
    val watchedBooks: List<CatalogueBook> = emptyList(),
    /** Show dish names in English when the app is in English. The menu is only published in German. */
    val translateMeals: Boolean = true)

/** Menu text as sent for translation and looked up in the translation cache. */
fun menuText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

/** The menu with every dish whose name or description is in [english] (keyed by [menuText]) translated. */
fun MensaMenu.translatedWith(english: Map<String, String>): MensaMenu = copy(days = days.map { day ->
    day.copy(meals = day.meals.map { meal ->
        meal.copy(englishName = english[menuText(meal.name)] ?: meal.englishName,
            englishDescription = menuText(meal.description).takeIf { it.isNotEmpty() }?.let { english[it] } ?: meal.englishDescription)
    })
})

/** Dish names vary in spacing and case between days; favourites match on this normalised form. */
fun favoriteKey(name: String): String = name.lowercase().replace(Regex("\\s+"), " ").trim()
fun MensaMeal.isFavorite(preferences: CampusServicePreferences): Boolean = favoriteKey(name) in preferences.favoriteMeals
fun MensaMeal.visibleWith(preferences: CampusServicePreferences): Boolean =
    matches(preferences.mealFilter) && labels.none { it.code in preferences.excludedLabels }

/** Favourite dishes on [date]'s menu after the user's filters. */
fun favoriteMealsOn(menu: MensaMenu?, date: LocalDate, preferences: CampusServicePreferences): List<MensaMeal> =
    menu?.days?.firstOrNull { it.date == date }?.meals.orEmpty().filter { it.isFavorite(preferences) && it.visibleWith(preferences) }

/** Every label code that appears in the loaded menu, with its readable name, for the exclusion filter. */
fun menuLabels(menu: MensaMenu?): List<MealLabel> =
    menu?.days.orEmpty().flatMap { day -> day.meals.flatMap { it.labels } }
        .filter { it.code.isNotBlank() }.distinctBy { it.code }.sortedBy { it.name.ifBlank { it.code }.lowercase() }

@Serializable data class LibraryLoan(
    val id: String, val title: String, val details: String = "", val dueDate: LocalDate? = null,
    val renewable: Boolean = false, val status: String = "",
)
@Serializable data class LibraryAccount(val label: String = "", val loans: List<LibraryLoan> = emptyList(),
    val fetchedAt: Long, val sample: Boolean = false,
    /** Reservations and orders; null when the account pages could not be read. */
    val holds: List<LibraryHold>? = null,
    /** Outstanding fees in cents; null when unknown. */
    val feesCents: Int? = null)

/** A reserved or ordered item. [ready] when the library reports it ready for pickup. */
@Serializable data class LibraryHold(val id: String, val title: String, val details: String = "", val status: String = "",
    val ready: Boolean = false, val pickupUntil: LocalDate? = null, val kind: String = "requested")

private val READY_WORDS = listOf("abholbereit", "bereitgestellt", "liegt bereit", "zur abholung", "abholung bis", "ready for pickup", "available for pickup")
fun holdIsReady(status: String): Boolean = status.lowercase().let { text -> READY_WORDS.any { it in text } }

private val UNAVAILABLE_WORDS = listOf("entliehen", "ausgeliehen", "vorgemerkt", "bestellt", "vermisst", "nicht verfügbar", "nicht ausleihbar",
    "in bearbeitung", "im geschäftsgang", "on loan", "checked out", "missing")
private val AVAILABLE_WORDS = listOf("verfügbar", "ausleihbar", "am standort", "freihand", "available", "präsenz")
/** Whether a catalogue copy's status reads as available to take or use now. Ambiguous text counts as unavailable. */
fun copyAvailable(status: String): Boolean {
    val text = status.lowercase()
    if (UNAVAILABLE_WORDS.any { it in text }) return false
    return AVAILABLE_WORDS.any { it in text }
}
enum class RenewalStatus { SUCCESS, DENIED, UNCERTAIN }
data class LibraryRenewal(val status: RenewalStatus, val account: LibraryAccount?, val message: String = "")
data class MensaState(val menu: MensaMenu? = null, val loading: Boolean = false, val error: FailureKind? = null)
data class LibraryState(val connected: Boolean = false, val connectionId: String? = null,
    val account: LibraryAccount? = null, val loading: Boolean = false, val error: FailureKind? = null,
    val renewing: String? = null, val result: LibraryRenewal? = null)

fun dueSoonLoans(account: LibraryAccount?, today: LocalDate): List<LibraryLoan> = account?.loans.orEmpty()
    .filter { it.dueDate?.let { date -> date <= today.plus(2, DateTimeUnit.DAY) } == true }
    .sortedBy { it.dueDate }
