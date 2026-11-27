package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class CampusWeatherTest {
    private val now = 1_792_000_000_000L
    private val account = Account("a", "Alice", "alice")
    private val day = campusToday(now)
    private val midnight = day.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
    private val reading = CampusWeather(12.5, 3, 7.4, now,
        listOf(HourlyWeather(now, 12.5, 3, 10, true)), listOf(DailyWeather(day, 7.0, 16.0, 3, 10)))
    private val response = """{"current_units":{"time":"unixtime","temperature_2m":"°C","wind_speed_10m":"km/h"},
        "current":{"time":1792000000,"temperature_2m":12.5,"weather_code":3,"wind_speed_10m":7.4},
        "hourly_units":{"time":"unixtime","temperature_2m":"°C","precipitation_probability":"%"},
        "hourly":{"time":[1792000000],"temperature_2m":[12.5],"weather_code":[3],"precipitation_probability":[10],"is_day":[1]},
        "daily_units":{"time":"unixtime","temperature_2m_min":"°C","temperature_2m_max":"°C","precipitation_probability_max":"%"},
        "daily":{"time":[${midnight / 1000}],"temperature_2m_min":[7.0],"temperature_2m_max":[16.0],"weather_code":[3],"precipitation_probability_max":[10]}}"""

    @Test fun requestsOnlyTheFixedCampusAndReadsUtcTimestampsWithoutCampusCredentials() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("api.open-meteo.com", request.url.host)
            assertEquals("48.336", request.url.parameters["latitude"])
            assertEquals("10.899", request.url.parameters["longitude"])
            assertEquals("unixtime", request.url.parameters["timeformat"])
            assertEquals("Europe/Berlin", request.url.parameters["timezone"])
            assertEquals("16", request.url.parameters["forecast_days"])
            assertEquals("7", request.url.parameters["past_days"])
            assertEquals("temperature_2m,weather_code,precipitation_probability,is_day", request.url.parameters["hourly"])
            assertEquals("temperature_2m_min,temperature_2m_max,weather_code,precipitation_probability_max", request.url.parameters["daily"])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { assertEquals(reading, OpenMeteoWeatherProvider { client }.current()) }
        finally { client.close() }
    }

    @Test fun rejectsMissingValuesAndUnexpectedTemperatureUnits() {
        for (body in listOf(response.replace("°C", "°F"), response.replace("12.5", "null"), "{}")) {
            assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> {
                OpenMeteoWeatherProvider.parse(body)
            }.kind)
        }
    }

    @Test fun deduplicatesReadsCachesWeatherAndRestoresItWhileOffline() = runTest {
        var calls = 0
        val source = CampusWeatherProvider { calls++; delay(100); reading }
        val store = MemoryStore()
        val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope,
            clock = { now }, weatherProvider = source)
        coroutineScope { List(10) { launch { repo.refreshWeather() } }.joinAll() }
        assertEquals(1, calls)
        repo.close(false)
        val restored = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope,
            conditions = { NetworkConditions(connected = false) }, clock = { now + CampusRepository.WEATHER_TTL },
            weatherProvider = source)
        restored.refreshWeather()
        runCurrent()
        assertEquals(reading, restored.snapshot.value.weather)
        assertEquals(1, calls)
        restored.close(false)
    }

    @Test fun failureKeepsTheLastWeatherAndDoesNotSetACampusOrAuthenticationError() = runTest {
        var clock = now
        var calls = 0
        val source = CampusWeatherProvider {
            calls++
            if (calls > 1) throw CampusException(FailureKind.AUTHENTICATION)
            reading
        }
        val repo = CampusRepository(account, TestProvider(), MemoryStore(), MemoryMaterials(), backgroundScope,
            clock = { clock }, weatherProvider = source)
        repo.refreshWeather()
        clock += CampusRepository.WEATHER_TTL + 1
        repo.refreshWeather()
        repo.refreshWeather()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(reading, repo.snapshot.value.weather)
        assertTrue(repo.sync.value.errors.isEmpty())
        assertFalse(repo.sync.value.sessionExpired)
        assertFalse(repo.weatherLoading.value)
        repo.close(false)
    }

    @Test fun dataSaverSkipsAutomaticWeatherButAnExplicitRefreshStillWorks() = runTest {
        var calls = 0
        val repo = CampusRepository(account, TestProvider(), MemoryStore(), MemoryMaterials(), backgroundScope,
            settings = { AppSettings(dataSaver = true) }, clock = { now },
            weatherProvider = CampusWeatherProvider { calls++; reading })
        repo.refreshWeather()
        assertEquals(0, calls)
        repo.refreshWeather(force = true)
        assertEquals(1, calls)
        repo.close(false)
    }

    @Test fun visibleCalendarRefreshesMissingDaysEvenWithFreshCacheAndDataSaver() = runTest {
        val tomorrow = day.plus(1, DateTimeUnit.DAY)
        val complete = reading.copy(daily = reading.daily + DailyWeather(tomorrow, 8.0, 17.0, 2, 10))
        val store = MemoryStore()
        store.put(account.cacheScope, "weather", Json.encodeToString(reading), now)
        var calls = 0
        val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope,
            settings = { AppSettings(dataSaver = true) }, clock = { now },
            weatherProvider = CampusWeatherProvider { calls++; complete })
        repo.refreshWeather(visible = true, dates = listOf(day, tomorrow)); runCurrent()
        assertEquals(1, calls)
        assertEquals(17.0, repo.snapshot.value.weather?.day(tomorrow)?.maxC)
        repo.refreshWeather(visible = true, dates = listOf(day, tomorrow))
        assertEquals(1, calls)
        repo.close(false)
    }

    @Test fun unavailableDistantDatesDoNotTriggerRepeatedForecastRequests() = runTest {
        val store = MemoryStore()
        store.put(account.cacheScope, "weather", Json.encodeToString(reading), now)
        var calls = 0
        val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope,
            clock = { now }, weatherProvider = CampusWeatherProvider { calls++; reading })
        repo.refreshWeather(visible = true, dates = listOf(day.plus(30, DateTimeUnit.DAY)))
        assertEquals(0, calls)
        repo.close(false)
    }

    @Test fun missingCalendarForecastRefreshesAfterNetworkReturns() = runTest {
        val tomorrow = day.plus(1, DateTimeUnit.DAY)
        val store = MemoryStore()
        store.put(account.cacheScope, "weather", Json.encodeToString(reading), now)
        var connected = false
        var calls = 0
        val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope,
            conditions = { NetworkConditions(connected = connected) }, clock = { now },
            weatherProvider = CampusWeatherProvider {
                calls++; reading.copy(daily = reading.daily + DailyWeather(tomorrow, 8.0, 17.0, 2))
            })
        repo.refreshWeather(visible = true, dates = listOf(day, tomorrow))
        assertEquals(0, calls)
        connected = true
        repo.refreshWeather(visible = true, dates = listOf(day, tomorrow)); runCurrent()
        assertEquals(1, calls)
        assertNotNull(repo.snapshot.value.weather?.day(tomorrow))
        repo.close(false)
    }

    @Test fun missingSourceDaysAreThrottledUntilTheNextRetryWindow() = runTest {
        var clock = now
        var calls = 0
        val repo = CampusRepository(account, TestProvider(), MemoryStore(), MemoryMaterials(), backgroundScope,
            clock = { clock }, weatherProvider = CampusWeatherProvider { calls++; reading })
        val tomorrow = day.plus(1, DateTimeUnit.DAY)
        repo.refreshWeather(visible = true, dates = listOf(tomorrow))
        repo.refreshWeather(visible = true, dates = listOf(tomorrow))
        assertEquals(1, calls)
        clock += 5 * 60_000L + 1
        repo.refreshWeather(visible = true, dates = listOf(tomorrow))
        assertEquals(2, calls)
        repo.close(false)
    }

    @Test fun logoutRejectsAnInFlightWeatherResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope,
            clock = { now }, weatherProvider = CampusWeatherProvider { gate.await(); reading })
        val request = launch { repo.refreshWeather() }
        runCurrent()
        repo.close(purge = true)
        gate.complete(Unit)
        request.join()
        assertNull(store.read(account.cacheScope, "weather"))
    }

    @Test fun freshCurrentOnlyCacheRevalidatesForTheForecastUpgrade() = runTest {
        val store = MemoryStore()
        store.put(account.cacheScope, "weather", Json.encodeToString(reading.copy(hourly = emptyList(), daily = emptyList())), now)
        var calls = 0
        val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope,
            clock = { now }, weatherProvider = CampusWeatherProvider { calls++; reading })
        repo.refreshWeather()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(repo.snapshot.value.weather!!.hasForecast)
        repo.close(false)
    }

    @Test fun forecastDaysUseCampusDatesAndKeepBothHoursWhenTheClockGoesBack() {
        val first = JsonApiMapper.timestamp("2026-10-25T02:00:00+02:00")
        val second = JsonApiMapper.timestamp("2026-10-25T02:00:00+01:00")
        val dailyStart = JsonApiMapper.timestamp("2026-10-25T00:00:00+02:00")
        val value = Json.parseToJsonElement(response).jsonObject.toMutableMap()
        value["hourly"] = Json.parseToJsonElement("""{"time":[${first / 1000},${second / 1000}],"temperature_2m":[8,7],"weather_code":[0,3],"precipitation_probability":[0,null],"is_day":[0,0]}""")
        value["daily"] = Json.parseToJsonElement("""{"time":[${dailyStart / 1000}],"temperature_2m_min":[6],"temperature_2m_max":[12],"weather_code":[3],"precipitation_probability_max":[20]}""")
        val weather = OpenMeteoWeatherProvider.parse(JsonObject(value).toString())
        val date = LocalDate(2026, 10, 25)
        assertEquals(listOf(first, second), weather.hoursOn(date).map { it.start })
        assertEquals(date, weather.daily.single().date)
        assertEquals(false, weather.hourly.first().isDay)
        assertNull(weather.hourly.last().precipitationProbability)
        assertNull(weather.day(date.plus(7, DateTimeUnit.DAY)))
    }

    @Test fun skipsMissingForecastRowsWithoutMovingTheFollowingDayOrHour() {
        val value = Json.parseToJsonElement(response).jsonObject.toMutableMap()
        value["hourly"] = Json.parseToJsonElement("""{"time":[${now / 1000},${now / 1000 + 3600}],"temperature_2m":[null,14],"weather_code":[0,61],"precipitation_probability":[null,70],"is_day":[1,1]}""")
        value["daily"] = Json.parseToJsonElement("""{"time":[${midnight / 1000},${midnight / 1000 + 86400}],"temperature_2m_min":[null,8],"temperature_2m_max":[null,15],"weather_code":[0,61],"precipitation_probability_max":[null,80]}""")
        val weather = OpenMeteoWeatherProvider.parse(JsonObject(value).toString())
        assertEquals(now + 3_600_000L, weather.hourly.single().start)
        assertEquals(61, weather.hourly.single().weatherCode)
        assertEquals(70, weather.hourly.single().precipitationProbability)
        assertEquals(day.plus(1, DateTimeUnit.DAY), weather.daily.single().date)
        assertEquals(80, weather.daily.single().precipitationProbability)
    }

    @Test fun rejectsMismatchedForecastColumnsInsteadOfTruncatingTheForecast() {
        val value = Json.parseToJsonElement(response).jsonObject.toMutableMap()
        value["hourly"] = Json.parseToJsonElement("""{"time":[1792000000],"temperature_2m":[12.5,15],"weather_code":[3],"precipitation_probability":[10],"is_day":[1]}""")
        assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> {
            OpenMeteoWeatherProvider.parse(JsonObject(value).toString())
        }.kind)
    }

    @Test fun dailyForecastLabelsDoNotRepeatWhenTheResponseUsesAFixedSummerOffset() {
        val first = JsonApiMapper.timestamp("2026-10-25T00:00:00+02:00")
        val value = Json.parseToJsonElement(response).jsonObject.toMutableMap()
        value["utc_offset_seconds"] = JsonPrimitive(7200)
        value["daily"] = Json.parseToJsonElement("""{"time":[${first / 1000},${first / 1000 + 86400},${first / 1000 + 172800}],"temperature_2m_min":[6,7,8],"temperature_2m_max":[12,13,14],"weather_code":[3,61,2],"precipitation_probability_max":[20,70,10]}""")
        val weather = OpenMeteoWeatherProvider.parse(JsonObject(value).toString())
        assertEquals(listOf(LocalDate(2026, 10, 25), LocalDate(2026, 10, 26), LocalDate(2026, 10, 27)), weather.daily.map { it.date })
        assertEquals(61, weather.day(LocalDate(2026, 10, 26))?.weatherCode)
        assertEquals(13.0, weather.day(LocalDate(2026, 10, 26))?.maxC)
    }
}
