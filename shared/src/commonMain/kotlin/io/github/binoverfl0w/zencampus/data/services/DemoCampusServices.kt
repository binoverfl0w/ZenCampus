package io.github.binoverfl0w.zencampus.data.services

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

class DemoMensaProvider(private val clock: () -> Long = ::nowMillis) : MensaProvider {
    override suspend fun menu(): MensaMenu = menu(MENSA_DEFAULT_LOCATION)
    override suspend fun menu(location: String): MensaMenu {
        val today = campusToday(clock())
        val place = MENSA_LOCATIONS.firstOrNull { it.key == location } ?: throw CampusException(FailureKind.NOT_FOUND)
        return MensaMenu(place.name, (-3..10).map { offset ->
            val day = today.plus(offset, DateTimeUnit.DAY)
            val closed = day.dayOfWeek.ordinal >= 5
            MensaDay(day, if (closed) emptyList() else listOf(
                MensaMeal("$day:vegetables", "Gemüse-Curry", "Mit Reis und frischen Kräutern", "Tellergericht",
                    MealPrices(390, 520, 780), vegan = true, vegetarian = true,
                    labels = listOf(MealLabel("22", "Vegan"), MealLabel("So", "Soja"))),
                MensaMeal("$day:pasta", "Pasta mit Tomaten und Mozzarella", category = "Tellergericht",
                    prices = MealPrices(420, 560, 840), vegetarian = true,
                    labels = listOf(MealLabel("25", "Vegetarisch"), MealLabel("Gl", "Gluten"), MealLabel("Mi", "Milch"))),
                MensaMeal("$day:fish", "Fischfilet mit Kartoffeln", category = "Tellergericht", prices = MealPrices(460, 605, 920),
                    labels = listOf(MealLabel("18", "Fisch"))),
                MensaMeal("$day:buffet", "Gemüse-Büffet", category = "Büffet", prices = MealPrices(95, 120, 190, true), vegan = true, vegetarian = true),
            ), if (closed) MenuAvailability.CLOSED else MenuAvailability.PUBLISHED)
        }, clock(), sample = true, location = place.key, sourceUrl = mensaUrl(place.key))
    }
}

class DemoLibraryProvider(private val clock: () -> Long = ::nowMillis) : LibraryProvider {
    private var value = LibraryAccount("Alex Morgan", listOf(
        LibraryLoan("algorithms-book", "Introduction to Algorithms", "Cormen · Central library", campusToday(clock()).plus(2, DateTimeUnit.DAY), true),
        LibraryLoan("systems-book", "Operating System Concepts", "Silberschatz · Central library", campusToday(clock()).plus(8, DateTimeUnit.DAY), false),
    ), clock(), sample = true, holds = listOf(
        LibraryHold("record:networks", "Computer Networking: A Top-Down Approach", "Kurose · Central library",
            "Abholbereit bis ${campusToday(clock()).plus(5, DateTimeUnit.DAY)}", ready = true, pickupUntil = campusToday(clock()).plus(5, DateTimeUnit.DAY)),
        LibraryHold("record:compilers", "Compilers: Principles, Techniques, and Tools", "Aho · Central library", "Vorgemerkt · Position 2"),
    ), feesCents = 0)
    override suspend fun account() = value.copy(fetchedAt = clock())
    override suspend fun renew(loanId: String): LibraryRenewal {
        val loan = value.loans.singleOrNull { it.id == loanId && it.renewable }
            ?: return LibraryRenewal(RenewalStatus.DENIED, account())
        value = value.copy(loans = value.loans.map { if (it.id == loanId) it.copy(dueDate = loan.dueDate?.plus(28, DateTimeUnit.DAY), renewable = false) else it }, fetchedAt = clock())
        return LibraryRenewal(RenewalStatus.SUCCESS, value)
    }
}

class DemoCatalogueProvider(private val clock: () -> Long = ::nowMillis) : CatalogueProvider {
    private val books = listOf(
        CatalogueBook("sample-algorithms", "Introduction to algorithms", "Cormen, Thomas H.", "2022", "Fourth edition", "Buch", "9780262046305"),
        CatalogueBook("sample-systems", "Operating System Concepts", "Silberschatz, Abraham", "2018", "Tenth edition", "Buch", "9781119320913"),
    )
    override suspend fun search(query: CatalogueQuery, page: Int): CatalogueResults {
        val found = books.filter { book -> (when (query.field) {
            CatalogueField.ALL -> "${book.title} ${book.author} ${book.isbn}"
            CatalogueField.TITLE -> book.title; CatalogueField.AUTHOR -> book.author; CatalogueField.ISBN -> book.isbn
        }).contains(query.text, true) }
        return CatalogueResults(query, page, if (page == 1) found else emptyList(), found.size, fetchedAt = clock(), sample = true)
    }
    override suspend fun record(book: CatalogueBook) = CatalogueRecord(book,
        listOf(CatalogueProperty("Titel", book.title), CatalogueProperty("Autor", book.author), CatalogueProperty("Ausgabe", book.edition), CatalogueProperty("ISBN", book.isbn)),
        copies = listOf(CatalogueCopy("81/ST 130 C811(4)", "Informatik", "UB", "Am Standort (Freihandbestand)"),
            CatalogueCopy("40/ST 134 C811(4)", "Wirtschaft", "UB", "entliehen (keine Vormerkung möglich)")),
        fetchedAt = clock(), sample = true, holdingsFetchedAt = clock())
}
