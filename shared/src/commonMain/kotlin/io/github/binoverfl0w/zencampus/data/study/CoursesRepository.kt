package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.COURSE_TTL
import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.EVENT_TTL
import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.WEATHER_TTL
import io.github.binoverfl0w.zencampus.model.*
import kotlin.reflect.typeOf
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

/**
 * Profile, semesters, courses, this week, weather, holidays, announcements and course preferences.
 *
 * Part of [CampusRepository]: each operation runs with the repository as receiver (`with(repo)`), so the shared
 * cache engine, provider, store and other areas are reached exactly as from the repository itself.
 */
internal class CoursesRepository(private val repo: CampusRepository) {
    private val weatherMutex = Mutex()

    private var lastWeatherAttempt: Long? = null

    private val _weatherLoading = MutableStateFlow(false)

    val weatherLoading: StateFlow<Boolean> = _weatherLoading.asStateFlow()

    /** Weather never delays campus refresh or turns a weather failure into a sign-in error. */
    suspend fun refreshWeather(force: Boolean = false, visible: Boolean = false, dates: List<LocalDate> = emptyList()) { with(repo) {
        val source = weatherProvider ?: return
        weatherMutex.withLock {
            if (!active || !conditions().connected || (!force && !visible && settings().dataSaver)) return
            val cached = store.read(account.cacheScope, "weather")
            val weather = cached.decode<CampusWeather>()
            val today = campusToday(clock())
            // The forecast covers the previous seven days and today plus fifteen days.
            // Dates outside that range cannot be filled by retrying this endpoint.
            val expectedDates = dates.filter { it >= today.plus(-7, DateTimeUnit.DAY) && it <= today.plus(15, DateTimeUnit.DAY) }
            val coversDates = expectedDates.all { weather?.day(it) != null }
            if (!force && isFresh(cached?.updatedAt, clock(), WEATHER_TTL) && weather?.hasForecast == true && coversDates) return
            if (!force && isFresh(lastWeatherAttempt, clock(), 5 * 60_000L)) return
            lastWeatherAttempt = clock()
            _weatherLoading.value = true
            try {
                val weather = source.current()
                currentCoroutineContext().ensureActive()
                commit("weather", json.encodeToString(weather))
            } catch (error: CancellationException) {
                lastWeatherAttempt = null
                throw error
            } catch (_: Exception) {
                // Keep the last forecast and its observation time; campus content remains usable.
            } finally { _weatherLoading.value = false }
        }
    } }

    suspend fun refreshToday(force: Boolean = false) { with(repo) {
        supervisorScope {
            listOf(launch { refreshProfile(force) }, launch { refreshSemesters(force) }).joinAll()
        }
        val semesters = stored<List<Semester>>("semesters").orEmpty()
        if (semesters.isNotEmpty()) updatePreferences {
            if (it.semesterId == null) it.copy(semesterId = currentSemester(semesters, clock())?.id.orEmpty()) else it
        }
        supervisorScope {
            listOf(
                launch { refreshCourses(force) },
                launch { ensureWeek(campusToday(clock()), force) },
                launch { refreshHolidays(force) },
            ).joinAll()
        }
        // Only the three courses most relevant to this dashboard are warmed.
        val courses = selectedStoredCourses()
        val prefs = stored<AccountPreferences>("preferences") ?: AccountPreferences()
        val wanted = (prefs.pinnedCourses.toList() + prefs.recentCourses + courses.take(3).map { it.id })
            .distinct().filter { id -> courses.any { it.id == id } }.take(3)
        if (force || conditions().canPrefetch(settings())) {
            supervisorScope { wanted.map { id ->
                launch {
                    refreshAnnouncements(id, force, background = !force)
                    refreshCoursework(id, force, background = !force)
                    refreshCourseTools(id, force, background = !force)
                }
            }.joinAll() }
        }
    } }

    /** Explicit updates view: bounded concurrency and existing source freshness rules. */
    suspend fun refreshStudyUpdates(force: Boolean = false) = with(repo) { supervisorScope {
        val courses = selectedStoredCourses()
        launch { refreshMessages(force = force) }
        launch { refresh("activity", 5 * 60_000L, force) { provider.activity(account.id) } }
        courses.map { course -> launch {
            courseworkSlots.withPermit {
                refreshAnnouncements(course.id, force)
                refreshCourseMaterials(course.id, force)
                refreshCourseTools(course.id, force)
                val tools = stored<CourseTools>(CacheKeys.tools(course.id))
                if (tools?.sections?.any { it.id.contains("forum", true) || it.sourceUrl.contains("/forum", true) } == true)
                    refreshCommunity(course.id, "forum", force = force)
                if (tools?.sections?.any { it.id.contains("blubber", true) || it.sourceUrl.contains("/blubber", true) } == true)
                    refreshCommunity(course.id, "blubber", force = force)
            }
        } }.joinAll()
    }
    }

    suspend fun refreshSemesters(force: Boolean = false) = with(repo) {
        refresh("semesters", COURSE_TTL, force) { provider.semesters() }
    }

    suspend fun refreshProfile(force: Boolean = false) = with(repo) { refresh("profile", COURSE_TTL, force) {
        provider.account().also {
            if (it.cacheScope != account.cacheScope) throw CampusException(FailureKind.AUTHENTICATION)
        }
    }
    }

    suspend fun refreshCourses(force: Boolean = false) { with(repo) {
        val semester = (stored<AccountPreferences>("preferences") ?: AccountPreferences()).semesterId
        refresh(CacheKeys.courses(semester.orEmpty()), COURSE_TTL, force) { provider.courses(account.id, semester) }
    } }

    suspend fun ensureWeek(date: kotlinx.datetime.LocalDate, force: Boolean = false, background: Boolean = false) { with(repo) {
        val from = weekDates(date).first().atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        val scheduleKey = "schedule:$from"
        val firstTimetableRead = store.read(account.cacheScope, scheduleKey) == null
        val semesters = stored<List<Semester>>("semesters").orEmpty()
        supervisorScope {
            listOf(
                // Revalidate old event snapshots when upgrading from the events-only connector.
                launch { refresh("personal:$from", EVENT_TTL, force || firstTimetableRead, background) { provider.personalEvents(account.id, from) } },
                launch { refresh(scheduleKey, EVENT_TTL, force, background) { provider.scheduledEvents(account.id, from, semesters) } },
            ).joinAll()
        }
        trimHistory("personal:", 12)
        trimHistory("schedule:", 12)
    } }

    suspend fun preparePlannerWeek(date: kotlinx.datetime.LocalDate, force: Boolean = false) { with(repo) {
        suspend fun covered(day: kotlinx.datetime.LocalDate): Boolean {
            val first = weekDates(day).first()
            val last = first.plus(7, DateTimeUnit.DAY)
            val records = store.observe(account.cacheScope).first()
            return listOf("personal:", "schedule:").all { prefix -> records.any { record ->
                val from = record.key.takeIf { it.startsWith(prefix) }?.substringAfter(':')?.toLongOrNull()
                val start = from?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(CAMPUS_ZONE).date }
                start != null && start <= first && start.plus(CALENDAR_WINDOW_DAYS, DateTimeUnit.DAY) >= last &&
                    isFresh(record.updatedAt, clock(), EVENT_TTL) && record.decode<List<CampusEvent>>() != null
            } }
        }
        if (force || !covered(date)) ensureWeek(date, force)
        // The foreground 15-day window already covers the following week.
        if (conditions().canPrefetch(settings())) {
            val previous = date.plus(-7, DateTimeUnit.DAY)
            if (!covered(previous)) ensureWeek(previous, background = true)
        }
    } }

    /**
     * Opening a course: its own page (details, dates, top folder) honours [force]; what the Study sync also keeps
     * (updates, coursework, tools, file list) is only read here when its cache is stale, so it never refetches twice.
     */
    suspend fun visitCourse(id: String, force: Boolean = false) { with(repo) {
        updatePreferences { it.copy(recentCourses = (listOf(id) + it.recentCourses.filter { old -> old != id }).take(8)) }
        supervisorScope {
            listOf(
                launch { refresh(CacheKeys.course(id), COURSE_TTL, force) { provider.course(id) } },
                launch { refresh(CacheKeys.events(id), EVENT_TTL, force) { provider.events(id) } },
                launch { refreshAnnouncements(id) },
                launch { refreshFolder(id, null, force); refreshCourseMaterials(id) },
                launch { refreshCoursework(id) },
                launch { refreshCourseTools(id) },
            ).joinAll()
        }
    } }

    suspend fun refreshAnnouncements(id: String, force: Boolean = false, background: Boolean = false) = with(repo) {
        refresh(CacheKeys.news(id), EVENT_TTL, force, background) { provider.announcements(id) }
    }

    /** Holidays for this and next year; they change rarely, so one fetch per week is enough. */
    suspend fun refreshHolidays(force: Boolean = false) = with(repo) { supervisorScope {
        val year = campusToday(clock()).year
        listOf(year, year + 1).map { target -> launch {
            // Holidays are a nicety: an installation without the route simply has none.
            refresh(CacheKeys.holidays(target), 7L * 86_400_000, force) {
                try { provider.holidays(target) } catch (e: CampusException) { if (e.kind == FailureKind.NOT_FOUND) emptyList() else throw e }
            }
        } }.joinAll()
    }
    }

    suspend fun refreshNewsComments(newsId: String, force: Boolean = false) = with(repo) {
        refresh(CacheKeys.newsComments(newsId), 5 * 60_000L, force) { provider.newsComments(newsId) }
    }

    suspend fun postNewsComment(announcement: Announcement, content: String, requestId: String) { with(repo) {
        val key = "write:news-comment:${announcement.id}"
        studentWrite(key, announcement.courseId, announcement.id, announcement.title, requestId,
            action = { provider.postNewsComment(announcement.id, content) }) { null }
        if (stored<CourseworkWrite>(key)?.status == WriteStatus.CONFIRMED) refreshNewsComments(announcement.id, force = true)
    } }

    suspend fun refreshStudyGroups(force: Boolean = false) = with(repo) { refresh("studygroups", 86_400_000L, force) { provider.studyGroupProposals() }
    }

    suspend fun exportCalendar(action: MaterialAction, officeHoursLabel: String) { with(repo) {
        val key = "calendar-export"
        try {
            coordinator.run(key) {
                checkActive()
                _errors.update { it - key }
                materialFeedback.update { it.copy(exports = it.exports - key) }
                val current = snapshot.value
                val from = clock() - 30L * 86_400_000
                val events = current.events.filter { it.end >= from } +
                    current.consultationBookings.filter { it.end >= from }.map { it.asEvent(officeHoursLabel) }
                val bytes = calendarIcs(events, current.courses, clock()).encodeToByteArray()
                val document = Document("calendar-export", "", "", "ZenCampus.ics", bytes.size.toLong(), "text/calendar", clock())
                val writer = materials.create(account.cacheScope, document)
                val path = try { writer.write(bytes); writer.finish() } catch (e: Throwable) { writer.abort(); throw e }
                currentCoroutineContext().ensureActive(); checkActive()
                when (action) {
                    MaterialAction.SHARE -> materials.share(path, "text/calendar", document.name)
                    MaterialAction.SAVE_TO_DOWNLOADS -> materials.saveToDownloads(path, "text/calendar", document.name)?.let { location ->
                        materialFeedback.update { it.copy(exports = it.exports + (key to location)) }
                    }
                    else -> materials.open(path, "text/calendar", document.name)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { recordError(key, e) }
    } }

    suspend fun selectSemester(id: String) { with(repo) {
        updatePreferences { it.copy(semesterId = id) }
        refreshCourses()
        // Another semester means other courses: sync them now instead of when each screen opens.
        requestStudySync(now = true)
    } }

    suspend fun toggleCoursePin(id: String) = with(repo) { updatePreferences {
        it.copy(pinnedCourses = if (id in it.pinnedCourses) it.pinnedCourses - id else it.pinnedCourses + id)
    }
    }

    suspend fun saveCourseGroup(id: String?, title: String, courseIds: Set<String>, previousMembers: Set<String> = emptySet()) { with(repo) {
        val key = id ?: "group-${clock()}-${kotlin.random.Random.nextLong().toULong().toString(16)}"
        updatePreferences { it.saveCourseGroup(key, title, courseIds, previousMembers) }
    } }

    suspend fun separateCourses(courseIds: Set<String>) = with(repo) { updatePreferences { it.separateCourses(courseIds) }
    }

    suspend fun markRead(id: String) = with(repo) { updatePreferences {
        it.copy(readAnnouncements = (it.readAnnouncements + id).takeLastSet(500))
    }
    }
}
