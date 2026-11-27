package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.text
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

fun interface CampusWeatherProvider { suspend fun current(): CampusWeather }

/** Public forecast for Augsburg's main campus; no device location or campus credentials. */
class OpenMeteoWeatherProvider(private val clientFactory: () -> HttpClient) : CampusWeatherProvider {
    private val client by lazy(clientFactory)
    override suspend fun current(): CampusWeather {
        val response = client.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", "48.336")
            parameter("longitude", "10.899")
            parameter("current", "temperature_2m,weather_code,wind_speed_10m")
            parameter("hourly", "temperature_2m,weather_code,precipitation_probability,is_day")
            parameter("daily", "temperature_2m_min,temperature_2m_max,weather_code,precipitation_probability_max")
            parameter("temperature_unit", "celsius")
            parameter("wind_speed_unit", "kmh")
            parameter("timeformat", "unixtime")
            parameter("timezone", "Europe/Berlin")
            parameter("forecast_days", "16")
            parameter("past_days", "7")
            header(HttpHeaders.Accept, "application/json")
            timeout { requestTimeoutMillis = 8_000 }
        }
        if (response.status.value !in 200..299) throw CampusException(FailureKind.SERVER)
        val body = response.bodyAsText()
        if (body.length > 131_072) throw CampusException(FailureKind.INVALID_RESPONSE)
        return parse(body)
    }

    companion object {
        internal fun parse(body: String): CampusWeather = try {
            val json = Json.parseToJsonElement(body).jsonObject
            val units = json["current_units"]!!.jsonObject
            require(units["temperature_2m"]?.jsonPrimitive?.content == "°C")
            require(units["wind_speed_10m"]?.jsonPrimitive?.content == "km/h")
            require(units["time"]?.jsonPrimitive?.content == "unixtime")
            val current = json["current"]!!.jsonObject
            val temperature = current["temperature_2m"]!!.jsonPrimitive.double
            val wind = current["wind_speed_10m"]!!.jsonPrimitive.double
            val stamp = current["time"]!!.jsonPrimitive.long
            require(temperature.isFinite() && temperature in -100.0..70.0)
            require(wind.isFinite() && wind in 0.0..500.0)
            require(stamp in 1..100_000_000_000L)
            val hourlyUnits = json["hourly_units"]!!.jsonObject
            require(hourlyUnits.text("time") == "unixtime" && hourlyUnits.text("temperature_2m") == "°C")
            require(hourlyUnits.text("precipitation_probability") == "%")
            val hour = json["hourly"]!!.jsonObject
            val hours = hour["time"]!!.jsonArray
            val temperatures = hour["temperature_2m"]!!.jsonArray
            val codes = hour["weather_code"]!!.jsonArray
            val probabilities = hour["precipitation_probability"]!!.jsonArray
            val daylight = hour["is_day"]!!.jsonArray
            require(listOf(temperatures, codes, probabilities, daylight).all { it.size == hours.size })
            val hourly = hours.indices.mapNotNull { index ->
                val temp = temperatures[index].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                require(temp.isFinite() && temp in -100.0..70.0)
                val isDay = daylight[index].jsonPrimitive.intOrNull
                require(isDay == null || isDay in 0..1)
                HourlyWeather(epochMillis(hours[index]), temp, codes[index].jsonPrimitive.intOrNull ?: -1,
                    probability(probabilities[index]), isDay?.let { it == 1 })
            }.distinctBy { it.start }.sortedBy { it.start }

            val dailyUnits = json["daily_units"]!!.jsonObject
            require(dailyUnits.text("time") == "unixtime" && dailyUnits.text("temperature_2m_min") == "°C" &&
                dailyUnits.text("temperature_2m_max") == "°C" && dailyUnits.text("precipitation_probability_max") == "%")
            val day = json["daily"]!!.jsonObject
            val days = day["time"]!!.jsonArray
            val minimums = day["temperature_2m_min"]!!.jsonArray
            val maximums = day["temperature_2m_max"]!!.jsonArray
            val dailyCodes = day["weather_code"]!!.jsonArray
            val dailyProbabilities = day["precipitation_probability_max"]!!.jsonArray
            require(listOf(minimums, maximums, dailyCodes, dailyProbabilities).all { it.size == days.size })
            // Daily timestamps encode calendar labels using the response's fixed UTC offset.
            // Converting them as instants in Berlin would duplicate a date at the October clock change.
            val dailyOffset = json["utc_offset_seconds"]?.jsonPrimitive?.intOrNull
                ?: CAMPUS_ZONE.offsetAt(Instant.fromEpochMilliseconds(stamp * 1000)).totalSeconds
            require(dailyOffset in -64_800..64_800)
            val daily = days.indices.mapNotNull { index ->
                val low = minimums[index].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val high = maximums[index].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                require(low.isFinite() && high.isFinite() && low in -100.0..70.0 && high in low..70.0)
                DailyWeather(Instant.fromEpochMilliseconds(epochMillis(days[index]) + dailyOffset * 1000L).toLocalDateTime(TimeZone.UTC).date,
                    low, high, dailyCodes[index].jsonPrimitive.intOrNull ?: -1, probability(dailyProbabilities[index]))
            }.distinctBy { it.date }.sortedBy { it.date }
            require(hourly.isNotEmpty() && daily.isNotEmpty())
            CampusWeather(temperature, current["weather_code"]!!.jsonPrimitive.int, wind, stamp * 1000, hourly, daily)
        } catch (error: Exception) { throw CampusException(FailureKind.INVALID_RESPONSE, error) }

        private fun epochMillis(value: JsonElement): Long = value.jsonPrimitive.long.let {
            require(it in 1..100_000_000_000L)
            it * 1000
        }
        private fun probability(value: JsonElement): Int? = value.jsonPrimitive.intOrNull.also {
            require(value == JsonNull || it != null && it in 0..100)
        }
    }
}
