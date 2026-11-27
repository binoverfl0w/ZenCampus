package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.data.CourseworkSource
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

/** DigiCampus: Courseware tasks, peer review, course tools and Vips. */
internal class DigiCampusCoursework(connection: DigiCampusConnection) : DigiCampusApi(connection), CourseworkSource {
    override suspend fun courseTools(courseId: String): CourseTools =
        CourseToolPages.tools(authenticatedCourseHtml(courseOverviewUrl(courseId)), courseId)

    override suspend fun vipsSheets(courseId: String): VipsSheets =
        CourseToolPages.vips(authenticatedCourseHtml(vipsSheetsUrl(courseId)), courseId)

    override suspend fun vipsResults(courseId: String): List<VipsResult> =
        CourseToolPages.vipsResults(authenticatedCourseHtml(vipsResultsUrl(courseId)), courseId)

    override suspend fun coursework(courseId: String): List<CourseworkTask> {
        // This index requires cid; pagination parameters are forbidden by its route contract.
        val page = collection("courseware-tasks", mapOf("filter[cid]" to courseId,
            "include" to "task-group,structural-element"), paginated = false)
        return page.resources.filter { CourseworkMapper.isSolvable(it) }
            .map { CourseworkMapper.task(it, page.included, courseId) }.orderedCoursework()
    }

    override suspend fun courseworkDetail(courseId: String, taskId: String): CourseworkDetail {
        // Includes read task content without calling StructuralElementsShow, which records a visit.
        val page = collection("courseware-tasks/${taskId.path()}", mapOf(
            "include" to "task-group,task-feedback,structural-element.containers.blocks"), paginated = false)
        val resource = page.resources.singleOrNull()?.takeIf { it.text("id") == taskId }
            ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        if (!CourseworkMapper.isSolvable(resource)) throw CampusException(FailureKind.PERMISSION)
        return CourseworkMapper.detail(resource, page.included, courseId)
    }

    override suspend fun peerReviews(taskId: String): List<PeerReview> {
        val page = collection("courseware-tasks/${taskId.path()}/peer-reviews", mapOf("include" to "process"))
        val processes = page.included.filter { it.text("type") == "courseware-peer-review-processes" }.associateBy { it.text("id") }
        return page.resources.map { resource ->
            if (resource.text("type") != "courseware-peer-reviews") throw CampusException(FailureKind.INVALID_RESPONSE)
            val a = resource.attributes()
            val assessment = a["assessment"] as? JsonObject
            val process = processes[resource.relatedId("process")]?.attributes()
            val config = process?.get("configuration") as? JsonObject
            val payload = config?.get("payload") as? JsonObject
            val criteria = payload?.get("criteria") as? JsonArray
            val sections = mutableListOf<CourseworkSection>()
            val type = config?.text("type").orEmpty()
            when (type) {
                "freetext" -> assessment?.text("answer")?.takeIf { it.isNotBlank() }?.let { sections += CourseworkSection(text = JsonApiMapper.plainText(it)) }
                "form", "table" -> (assessment?.get("answers") as? JsonArray).orEmpty().forEachIndexed { index, answer ->
                    val criterion = (criteria?.getOrNull(index) as? JsonObject)?.text("text").orEmpty()
                    val text = when (answer) {
                        is JsonPrimitive -> answer.contentOrNull.orEmpty()
                        is JsonObject -> answer.text("text")
                        else -> ""
                    }
                    val rating = ((answer as? JsonObject)?.get("rating") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..3 }
                    if (text.isNotBlank() || rating != null) sections += CourseworkSection(JsonApiMapper.plainText(criterion), JsonApiMapper.plainText(text), rating)
                }
            }
            PeerReview(resource.requiredId(), a.flag("is-reviewer", false), a.flag("is-submitter", false),
                assessment != null, sections, assessment != null && (type !in setOf("freetext", "form", "table") ||
                    (type in setOf("form", "table") && criteria == null)))
        }
    }

    override suspend fun submitCoursework(courseId: String, taskId: String): WriteResult<CourseworkDetail> {
        val before = try { courseworkDetail(courseId, taskId) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return WriteResult.Rejected((e as? CampusException)?.kind ?: FailureKind.OFFLINE) }
        if (before.task.submitted) return WriteResult.Confirmed(before)
        if (!before.task.canSolve || before.task.due?.let { it >= now() } != true) return WriteResult.Rejected(FailureKind.PERMISSION)
        val response = try {
            // Mutations are sent exactly once; the normal GET retry helper is deliberately bypassed.
            client.patch("$DIGICAMPUS_API/courseware-tasks/${taskId.path()}") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json"))
                setBody(buildJsonObject { putJsonObject("data") { put("type", "courseware-tasks"); put("id", taskId)
                    putJsonObject("attributes") { put("submitted", true) } } }.toString())
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val verified = try { courseworkDetail(courseId, taskId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (verified?.task?.submitted == true) return WriteResult.Confirmed(verified)
        val status = response?.status?.value
        if (status != null && status in setOf(400, 401, 403, 404, 409, 413, 415, 422)) return WriteResult.Rejected(writeFailure(status))
        if (status != null && status in 200..299 && verified != null) return WriteResult.Rejected(FailureKind.PERMISSION)
        return WriteResult.Uncertain
    }

}
