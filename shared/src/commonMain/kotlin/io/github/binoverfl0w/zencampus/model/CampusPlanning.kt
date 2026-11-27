package io.github.binoverfl0w.zencampus.model

import io.ktor.http.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable

const val CAMPUS_ROOMS_URL = "$DIGICAMPUS_ORIGIN/dispatch.php/resources/search/rooms"
@Serializable data class CampusRoom(val id: String, val name: String, val description: String = "", val seats: Int? = null,
    val bookingUrl: String = "") {
    val sourceUrl: String get() = bookingUrl.ifBlank { "$DIGICAMPUS_ORIGIN/dispatch.php/resources/room/index/$id" }
}
fun campusRoomDirectoryUrl(query: String): String = URLBuilder(CAMPUS_ROOMS_URL).apply {
    parameters.append("room_search", "1")
    parameters.append("special__room_name", query.trim().take(120))
}.buildString()
const val CAMPUS_ROOM_SEARCH_VERSION = 1
@Serializable data class CampusRoomResults(val query: String, val rooms: List<CampusRoom>, val version: Int = 0)
@Serializable data class CampusRoomWeek(val room: CampusRoom, val from: Long, val until: Long, val entries: List<PlannerEntry>)
@Serializable data class LecturerCourses(val query: String, val semesterId: String, val courses: List<Course>, val complete: Boolean, val total: Int? = null)
fun campusRoomSearchKey(query: String) = "planner-campus-rooms:${CampusRoomQuery(query).cacheText.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }}"
fun campusRoomWeekKey(roomId: String, monday: LocalDate) = "planner-room-week:$roomId:$monday"
fun lecturerCoursesKey(query: String, semesterId: String) = PlannerSearch(query, PlannerSearchKind.LECTURER, semesterId).key.replace("planner-search:", "planner-lecturer:")
/** Touching and overlapping bookings form one busy interval, including preparation and locks. */
fun roomBusyUntil(entries: List<PlannerEntry>, now: Long): Long? {
    var until: Long? = null
    for (entry in entries.filter { !it.event.cancelled && it.event.end > it.event.start }.sortedBy { it.event.start }) {
        if (entry.event.start > (until ?: now)) break
        if (entry.event.end > now) until = maxOf(until ?: now, entry.event.end)
    }
    return until
}
fun validCampusRoom(room: CampusRoom): Boolean {
    if (!Regex("[A-Za-z0-9_-]{1,100}").matches(room.id)) return false
    val url = runCatching { Url(room.bookingUrl) }.getOrNull() ?: return false
    return url.protocol == URLProtocol.HTTPS && url.host == "digicampus.uni-augsburg.de" && url.port == 443 &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.parameters.isEmpty() &&
        url.encodedPath in listOf("/dispatch.php/resources/room_planning/booking_plan/${room.id}", "/dispatch.php/resources/resource/booking_plan/${room.id}")
}
