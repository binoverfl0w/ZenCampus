package io.github.binoverfl0w.zencampus.data.services

import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.model.CampusException
import io.github.binoverfl0w.zencampus.model.FailureKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.*

/** Turns German menu text into English. Only public menu text is sent, never anything about the student. */
fun interface MealTranslator {
    /** Throws [CampusException] with [QUOTA_STATUS] when the service will not translate more today. */
    suspend fun english(german: String): String

    companion object {
        const val QUOTA_STATUS = 429
        /** MyMemory refuses longer queries. */
        const val MAX_BYTES = 450
    }
}

/**
 * MyMemory's free endpoint: no key, roughly 5,000 characters a day per network address. A week's menu is about a
 * third of that, and translations are cached, so later weeks mostly ask for new dishes only.
 */
class MyMemoryTranslator(private val client: () -> HttpClient) : MealTranslator {
    override suspend fun english(german: String): String {
        val response = client().get(MYMEMORY_URL) {
            parameter("q", german)
            parameter("langpair", "de|en")
        }
        if (response.status.value == MealTranslator.QUOTA_STATUS) throw CampusException(FailureKind.SERVER, httpStatus = MealTranslator.QUOTA_STATUS)
        return parseMyMemory(german, response.campusHtml())
    }

    companion object {
        const val MYMEMORY_URL = "https://api.mymemory.translated.net/get"

        internal fun parseMyMemory(german: String, body: String): String {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val status = (root["responseStatus"] as? JsonPrimitive)?.contentOrNull
            if ((root["quotaFinished"] as? JsonPrimitive)?.booleanOrNull == true || status == MealTranslator.QUOTA_STATUS.toString())
                throw CampusException(FailureKind.SERVER, httpStatus = MealTranslator.QUOTA_STATUS)
            if (status != "200") throw CampusException(FailureKind.SERVER)
            val text = ((root["responseData"] as? JsonObject)?.get("translatedText") as? JsonPrimitive)?.contentOrNull
            return cleanTranslation(german, text) ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        }

        /** Plain text only; service warnings and runaway answers are not translations. */
        internal fun cleanTranslation(german: String, english: String?): String? {
            val text = JsonApiMapper.plainText(english ?: return null).replace(Regex("\\s+"), " ").trim()
            if (text.isBlank() || text.contains("MYMEMORY", ignoreCase = true) || text.length > german.length * 3 + 40) return null
            return text
        }
    }
}

/** Fixed translations for the sample menu, so preview mode shows the feature without the network. */
class DemoMealTranslator : MealTranslator {
    override suspend fun english(german: String): String = DEMO[german] ?: throw CampusException(FailureKind.NOT_FOUND)

    private companion object {
        val DEMO = mapOf(
            "Gemüse-Curry" to "Vegetable curry", "Mit Reis und frischen Kräutern" to "With rice and fresh herbs",
            "Pasta mit Tomaten und Mozzarella" to "Pasta with tomatoes and mozzarella",
            "Fischfilet mit Kartoffeln" to "Fish fillet with potatoes", "Gemüse-Büffet" to "Vegetable buffet",
        )
    }
}
