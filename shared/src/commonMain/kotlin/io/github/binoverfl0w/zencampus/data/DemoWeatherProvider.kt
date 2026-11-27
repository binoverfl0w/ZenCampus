package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.datetime.*

/** Forecasts are sample data, generated for campus-local dates rather than a fixed calendar week. */
class DemoWeatherProvider(private val clock: () -> Long = ::nowMillis) : CampusWeatherProvider {
    override suspend fun current(): CampusWeather {
        val today = campusToday(clock())
        val days = (-7..15).map { offset ->
            val date = today.plus(offset, DateTimeUnit.DAY)
            val rain = (offset + 7) % 4 == 0
            DailyWeather(date, 7.0 + (offset + 7) % 3, if (rain) 15.0 else 18.0, if (rain) 61 else 2, if (rain) 70 else 10)
        }
        val hourly = days.flatMap { day ->
            val start = day.date.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
            val end = day.date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
            (start until end step 3_600_000L).map { stamp ->
                val hour = kotlin.time.Instant.fromEpochMilliseconds(stamp).toLocalDateTime(CAMPUS_ZONE).hour
                HourlyWeather(stamp, if (hour in 10..18) day.maxC else day.minC + 2,
                    day.weatherCode, day.precipitationProbability, hour in 7..18)
            }
        }
        return CampusWeather(17.0, 2, 9.0, clock(), hourly, days)
    }
}
