package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.data.services.MealTranslator
import io.github.binoverfl0w.zencampus.data.services.MensaProvider
import io.github.binoverfl0w.zencampus.data.services.MyMemoryTranslator
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.mealCategory
import io.github.binoverfl0w.zencampus.ui.mealDescription
import io.github.binoverfl0w.zencampus.ui.mealLabel
import io.github.binoverfl0w.zencampus.ui.mealName
import io.github.binoverfl0w.zencampus.ui.mealOriginalName
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

/** English dish names: the MyMemory answer, the cache that keeps them across refreshes, and what the UI shows. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealTranslationTest {
    private val now = 1_792_000_000_000L
    private val day = LocalDate(2026, 11, 17)
    private fun menu(vararg meals: MensaMeal) = MensaMenu("Mensa Uni Augsburg", listOf(MensaDay(day, meals.toList())), now)
    private val beef = MensaMeal("a", "Rindergeschnetzeltes  mit Gemüse", "mit Reis", "Tellergericht II",
        labels = listOf(MealLabel("Gl1", "Weizen"), MealLabel("La", "Milch und Milchprodukte (Laktose)")))
    private val pizza = MensaMeal("b", "Pizza", category = "Pizza vegan")

    private class FakeTranslator(val answers: Map<String, String>, var failure: CampusException? = null) : MealTranslator {
        val asked = mutableListOf<String>()
        override suspend fun english(german: String): String {
            asked += german
            failure?.let { throw it }
            return answers[german] ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        }
    }

    private fun repository(store: CampusStore, translator: MealTranslator, scope: kotlinx.coroutines.CoroutineScope, menu: () -> MensaMenu,
        clock: () -> Long = { now }) =
        CampusServicesRepository(store, object : MensaProvider { override suspend fun menu() = menu() }, scope, clock = clock, translator = translator)

    @Test fun myMemoryAnswersAreCheckedBeforeUse() {
        val ok = """{"responseData":{"translatedText":"Sliced beef with vegetables","match":0.85},"quotaFinished":false,"responseStatus":200}"""
        assertEquals("Sliced beef with vegetables", MyMemoryTranslator.parseMyMemory("Rindergeschnetzeltes mit Gemüse", ok))
        val entity = """{"responseData":{"translatedText":"Chef&#39;s <b>salad</b>"},"responseStatus":"200"}"""
        assertEquals("Chef's salad", MyMemoryTranslator.parseMyMemory("Salat", entity))
        val quota = """{"responseData":{"translatedText":"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY"},"quotaFinished":true,"responseStatus":429}"""
        assertEquals(MealTranslator.QUOTA_STATUS, assertFailsWith<CampusException> { MyMemoryTranslator.parseMyMemory("Salat", quota) }.httpStatus)
        val warning = """{"responseData":{"translatedText":"MYMEMORY WARNING: something"},"responseStatus":200}"""
        assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> { MyMemoryTranslator.parseMyMemory("Salat", warning) }.kind)
        assertFailsWith<CampusException> { MyMemoryTranslator.parseMyMemory("Salat", """{"responseStatus":403,"responseDetails":"bad pair"}""") }
        assertFailsWith<CampusException> { MyMemoryTranslator.parseMyMemory("Salat", "<html>") }
        assertNull(MyMemoryTranslator.cleanTranslation("Salat", "x".repeat(200)), "a runaway answer is not a translation")
    }

    @Test fun onlyTheDishTextGoesToMyMemory() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("api.mymemory.translated.net", request.url.host)
            assertEquals("Kässpätzle mit Röstzwiebeln", request.url.parameters["q"])
            assertEquals("de|en", request.url.parameters["langpair"])
            assertNull(request.headers[HttpHeaders.Cookie]); assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"responseData":{"translatedText":"Cheese spaetzle with fried onions"},"responseStatus":200}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { assertEquals("Cheese spaetzle with fried onions", MyMemoryTranslator({ client }).english("Kässpätzle mit Röstzwiebeln")) }
        finally { client.close() }
    }

    @Test fun translationsAreCachedAndAppliedToLaterMenus() = runTest {
        val store = MemoryStore()
        val translator = FakeTranslator(mapOf("Rindergeschnetzeltes mit Gemüse" to "Sliced beef with vegetables", "mit Reis" to "with rice"))
        var served = menu(beef, pizza)
        val repo = repository(store, translator, backgroundScope, { served })
        repo.refreshMensa(); runCurrent()
        repo.translateMenu(); runCurrent()
        val meal = repo.mensa.value.menu!!.days.single().meals.first()
        assertEquals("Sliced beef with vegetables", meal.englishName)
        assertEquals("with rice", meal.englishDescription)
        assertNull(repo.mensa.value.menu!!.days.single().meals[1].englishName, "Pizza had no usable answer")
        assertEquals(setOf("Rindergeschnetzeltes mit Gemüse", "mit Reis", "Pizza"), translator.asked.toSet())

        // A refreshed menu takes known translations from the cache; only new text is asked for.
        translator.asked.clear()
        served = menu(beef.copy(id = "c"))
        repo.refreshMensa(force = true); runCurrent()
        assertEquals("Sliced beef with vegetables", repo.mensa.value.menu!!.days.single().meals.single().englishName)
        repo.translateMenu()
        assertEquals(emptyList(), translator.asked)
        assertEquals(now, store.read(repo.publicScope, "mensa")!!.updatedAt)
    }

    @Test fun aFailureOrUsedQuotaPausesTranslationAndTheSwitchTurnsItOff() = runTest {
        val store = MemoryStore()
        var clock = now
        val translator = FakeTranslator(mapOf("Pizza" to "Pizza"), CampusException(FailureKind.SERVER, httpStatus = MealTranslator.QUOTA_STATUS))
        val repo = repository(store, translator, backgroundScope, { menu(beef, pizza) }, { clock })
        repo.refreshMensa(); runCurrent()
        repo.translateMenu()
        val asked = translator.asked.size
        translator.failure = null
        clock += CampusServicesRepository.FAILURE_PAUSE
        repo.translateMenu()
        assertEquals(asked, translator.asked.size, "the quota pause is longer than a failure pause")
        clock += CampusServicesRepository.QUOTA_PAUSE
        repo.setPreferences { it.copy(translateMeals = false) }
        repo.translateMenu()
        assertEquals(asked, translator.asked.size, "switched off")
        repo.setPreferences { it.copy(translateMeals = true) }
        repo.translateMenu(); runCurrent()
        assertEquals("Pizza", repo.mensa.value.menu!!.days.single().meals[1].englishName)
    }

    @Test fun englishShowsTranslationsWithTheGermanNameAndGermanShowsThePublishedMenu() {
        val english = Words(false)
        val german = Words(true)
        val on = CampusServicePreferences()
        val translated = beef.copy(englishName = "Sliced beef with vegetables", englishDescription = "with rice")
        assertEquals("Sliced beef with vegetables", english.mealName(translated, on))
        assertEquals("with rice", english.mealDescription(translated, on))
        assertEquals(beef.name, english.mealOriginalName(translated, on))
        assertNull(english.mealOriginalName(pizza.copy(englishName = "pizza"), on), "same word in both languages")
        assertEquals(beef.name, english.mealName(beef, on), "not translated yet")
        assertEquals(beef.name, german.mealName(translated, on))
        assertNull(german.mealOriginalName(translated, on))
        val off = on.copy(translateMeals = false)
        assertEquals(beef.name, english.mealName(translated, off))
        assertNull(english.mealOriginalName(translated, off))

        assertEquals("Main dish II", english.mealCategory("Tellergericht II - 3,90"))
        assertEquals("Main dish", english.mealCategory("Tellergericht"))
        assertEquals("Vegetable buffet", english.mealCategory("Gemüse Büffet"))
        assertEquals("Wok dish", english.mealCategory("WOK Gericht"))
        assertEquals("Vegan pizza", english.mealCategory("Pizza vegan"))
        assertEquals("Hausmannskost", english.mealCategory("Hausmannskost"), "unknown categories stay as published")
        assertEquals("Tellergericht II", german.mealCategory("Tellergericht II"))

        assertEquals("Wheat", english.mealLabel(MealLabel("Gl1", "Weizen")))
        assertEquals("With pork", english.mealLabel(MealLabel("12", "mit Schweinefleisch")))
        assertEquals("Neu", english.mealLabel(MealLabel("X9", "Neu")), "unknown codes keep the published name")
        assertEquals("Weizen", german.mealLabel(MealLabel("Gl1", "Weizen")))
    }
}
