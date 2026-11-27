package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.data.CampusProvider
import io.github.binoverfl0w.zencampus.data.CommunitySource
import io.github.binoverfl0w.zencampus.data.CourseSource
import io.github.binoverfl0w.zencampus.data.CourseworkSource
import io.github.binoverfl0w.zencampus.data.MaterialsSource
import io.github.binoverfl0w.zencampus.data.MessagingSource
import io.github.binoverfl0w.zencampus.data.OfficeHoursSource
import io.github.binoverfl0w.zencampus.data.PlannerSource
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlinx.serialization.json.*

/** Augsburg's Stud.IP JSON:API. Cookies are only sent to the fixed, trusted API origin. */
class DigiCampusProvider private constructor(connection: DigiCampusConnection) : CampusProvider,
    CourseSource by DigiCampusCourses(connection),
    PlannerSource by DigiCampusPlanner(connection),
    MaterialsSource by DigiCampusMaterials(connection),
    CourseworkSource by DigiCampusCoursework(connection),
    CommunitySource by DigiCampusCommunity(connection),
    MessagingSource by DigiCampusMessaging(connection),
    OfficeHoursSource by DigiCampusOfficeHours(connection) {
    constructor(clientFactory: () -> HttpClient, cookie: () -> String, now: () -> Long = ::nowMillis) :
        this(DigiCampusConnection(clientFactory, cookie, now))
    constructor(client: HttpClient, cookie: () -> String, now: () -> Long = ::nowMillis) : this({ client }, cookie, now)

    companion object {
        const val MAX_FILE_BYTES = DigiCampusApi.MAX_FILE_BYTES
        fun trustedPageUrl(value: String): String = DigiCampusApi.trustedPageUrl(value)
    }
}
