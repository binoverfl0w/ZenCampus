package io.github.binoverfl0w.zencampus.ui

import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.model.CampusServicePreferences
import io.github.binoverfl0w.zencampus.model.MealLabel
import io.github.binoverfl0w.zencampus.model.MensaMeal

/** The Studierendenwerk publishes menus in German only. In English, these give dishes, categories and labels in English. */
private fun Words.translating(preferences: CampusServicePreferences) = !german && preferences.translateMeals

/** The dish name to show: English once translated, otherwise the published German name. */
fun Words.mealName(meal: MensaMeal, preferences: CampusServicePreferences): String =
    if (translating(preferences)) meal.englishName ?: meal.name else meal.name

fun Words.mealDescription(meal: MensaMeal, preferences: CampusServicePreferences): String =
    if (translating(preferences) && meal.description.isNotBlank()) meal.englishDescription ?: meal.description else meal.description

/** The German name under a translated one, so it can be matched with the sign at the counter; null when not translated. */
fun Words.mealOriginalName(meal: MensaMeal, preferences: CampusServicePreferences): String? =
    meal.name.takeIf { translating(preferences) && meal.englishName != null && !meal.englishName.equals(meal.name.trim(), ignoreCase = true) }

/** Counter categories are a short fixed list, translated from a table; unknown ones stay as published. */
fun Words.mealCategory(category: String): String {
    if (german || category.isBlank()) return category
    val key = category.replace(Regex("\\s*-\\s*\\d+[,.]\\d{2}\\s*€?\\s*$"), "").lowercase().replace(Regex("[\\s-]+"), "")
    Regex("^tellergericht(i{1,3}|iv|v)?$").find(key)?.let { match ->
        val number = match.groupValues[1].uppercase()
        return if (number.isEmpty()) "Main dish" else "Main dish $number"
    }
    return MEAL_CATEGORIES_EN[key] ?: category
}

/** Labels by their published code. The codes are the Studierendenwerk's additive numbers and the EU allergen list. */
fun Words.mealLabel(label: MealLabel): String {
    val published = JsonApiMapper.plainText(label.name)
    if (german) return published
    return MEAL_LABELS_EN[label.code] ?: published
}

private val MEAL_CATEGORIES_EN = mapOf(
    "beilage" to "Side", "beilagen" to "Sides", "süßspeise" to "Dessert", "süssspeise" to "Dessert", "dessert" to "Dessert",
    "nachspeise" to "Dessert", "gemüsebüffet" to "Vegetable buffet", "gemüsebuffet" to "Vegetable buffet",
    "nudelbüffet" to "Pasta buffet", "nudelbuffet" to "Pasta buffet", "salatbüffet" to "Salad buffet",
    "salatbuffet" to "Salad buffet", "büffet" to "Buffet", "buffet" to "Buffet", "wokgericht" to "Wok dish",
    "pizza" to "Pizza", "pizzavegan" to "Vegan pizza", "suppe" to "Soup", "tagessuppe" to "Soup of the day",
    "eintopf" to "Stew", "aktion" to "Special", "aktionsessen" to "Special", "aktionsgericht" to "Special",
    "grill" to "Grill", "pasta" to "Pasta", "salat" to "Salad", "snack" to "Snack", "vegetarisch" to "Vegetarian",
    "vegan" to "Vegan", "abendessen" to "Dinner", "frühstück" to "Breakfast",
)

private val MEAL_LABELS_EN = mapOf(
    "1" to "Colouring", "2" to "Flavour enhancer", "3" to "Preservative", "4" to "Antioxidant", "5" to "Sulphured",
    "6" to "Blackened", "7" to "Waxed", "8" to "Phosphate", "9" to "Sweeteners", "10" to "Source of phenylalanine",
    "11" to "Laxative if eaten in excess", "12" to "With pork", "13" to "Organic", "14" to "With alcohol",
    "15" to "With beef", "16" to "Genetically modified", "17" to "With poultry", "18" to "With fish", "19" to "With game",
    "20" to "With lamb", "21" to "mensaVital", "22" to "Vegan", "23" to "Lactose-free", "24" to "Gluten-free",
    "25" to "Vegetarian", "40" to "Organic milk",
    "Gl" to "Cereals containing gluten", "Gl1" to "Wheat", "Gl2" to "Rye", "Gl3" to "Barley", "Gl4" to "Oats",
    "Gl5" to "Spelt", "Gl6" to "Kamut", "Kr" to "Crustaceans", "Ei" to "Eggs", "En" to "Peanuts", "Fi" to "Fish",
    "So" to "Soy", "La" to "Milk and dairy (lactose)", "Nu" to "Tree nuts", "Nu1" to "Almond", "Nu2" to "Hazelnut",
    "Nu3" to "Walnut", "Nu4" to "Cashew", "Nu5" to "Pecan", "Nu6" to "Brazil nut", "Nu7" to "Pistachio",
    "Nu8" to "Macadamia", "Nu9" to "Queensland nut", "Sl" to "Celery", "Sf" to "Mustard", "Se" to "Sesame",
    "Sw" to "Sulphur dioxide and sulphites", "Lu" to "Lupin", "Wt" to "Molluscs",
    "KT" to "At least 50% less CO₂ than a comparable dish", "0CO2A" to "CO₂ rating A: at least 50% better than average",
    "0CO2B" to "CO₂ rating B: better than average", "0CO2C" to "CO₂ rating C: worse than average",
)
