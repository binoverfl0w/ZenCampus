package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.data.CommunitySource
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

/** DigiCampus: Forum, Blubber, wiki and Courseware. */
internal class DigiCampusCommunity(connection: DigiCampusConnection) : DigiCampusApi(connection), CommunitySource {
    override suspend fun community(courseId: String, section: String, itemId: String): List<CommunityItem> {
        if (courseId.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
        fun name(resource: JsonObject?): String {
            val a = resource?.attributes() ?: return ""
            return JsonApiMapper.plainText(a.text("formatted-name").ifBlank {
                listOf(a.text("given-name"), a.text("family-name")).filter { it.isNotBlank() }.joinToString(" ")
            }.ifBlank { a.text("username") })
        }
        fun time(a: JsonObject, key: String): Long = a.text(key).takeIf { it.isNotBlank() }?.let(JsonApiMapper::timestamp) ?: 0L
        return when (section) {
            "people" -> {
                if (itemId.isNotBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
                val page = collection("courses/${courseId.path()}/memberships", mapOf("include" to "user"))
                val users = page.included.filter { it.text("type") == "users" }.associateBy { it.text("id") }
                page.resources.map { resource ->
                    if (resource.text("type") != "course-memberships") throw CampusException(FailureKind.INVALID_RESPONSE)
                    val userId = resource.relatedId("user")
                    val user = users[userId] ?: throw CampusException(FailureKind.INVALID_RESPONSE)
                    CommunityItem(resource.requiredId(), name(user), secondary = resource.attributes().text("permission"), personId = userId)
                }.filter { it.title.isNotBlank() }.sortedBy { it.title.lowercase() }
            }
            "group-members" -> {
                // DigiCampus's API never exposes group members to students (no route, no include). The official
                // "Participants → Groups" page loads them as a fragment; read exactly that, for this course and group.
                if (itemId.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
                groupMembers(authenticatedFragment("$DIGICAMPUS_ORIGIN/dispatch.php/course/statusgroups/getgroup/${itemId.path()}?cid=${courseId.path()}"))
            }
            "groups" -> {
                if (itemId.isNotBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
                collection("courses/${courseId.path()}/status-groups").resources.map { resource ->
                    if (resource.text("type") != "status-groups") throw CampusException(FailureKind.INVALID_RESPONSE)
                    val a = resource.attributes()
                    // The route cannot include users; members come as ids the student may see, named from the course's people.
                    val members = ((resource.relation("members")?.get("data")) as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.takeIf { ref -> ref.text("type") == "users" }?.text("id") }
                        .filter { it.isNotBlank() }.distinct()
                    val size = (a["size"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                    CommunityItem(resource.requiredId(), JsonApiMapper.plainText(a.text("full-name").ifBlank { a.text("name") }),
                        body = JsonApiMapper.plainText(a.text("description")),
                        // "members/size" (size 0 means unlimited) and whether students may join themselves.
                        secondary = listOfNotNull("${members.size}${size?.let { "/$it" }.orEmpty()}", "selfassign".takeIf { a.flag("selfassign") }).joinToString("|"),
                        children = members.map { CommunityItem(it, "", personId = it) })
                }.filter { it.title.isNotBlank() }
            }
            "forum" -> {
                val page = if (itemId.isBlank()) collection("courses/${courseId.path()}/forum-discussions")
                    else collection("forum-discussions/${itemId.path()}/postings", mapOf("include" to "author"))
                val users = page.included.filter { it.text("type") == "users" }.associateBy { it.text("id") }
                page.resources.map { resource ->
                    val a = resource.attributes()
                    if (itemId.isBlank()) {
                        if (resource.text("type") != "forum-discussions") throw CampusException(FailureKind.INVALID_RESPONSE)
                        val meta = resource["meta"] as? JsonObject
                        val recent = meta?.text("recent-activity").orEmpty()
                        CommunityItem(resource.requiredId(), JsonApiMapper.plainText(a.text("title")),
                            timestamp = if (recent.isNotBlank()) JsonApiMapper.timestamp(recent) else time(a, "chdate"),
                            unread = (meta?.get("unread-postings-count") as? JsonPrimitive)?.intOrNull)
                    } else {
                        if (resource.text("type") != "forum-postings") throw CampusException(FailureKind.INVALID_RESPONSE)
                        val author = if (a.flag("anonymous", false)) "" else name(users[resource.relatedId("author")])
                        CommunityItem(resource.requiredId(), author, body = JsonApiMapper.plainText(a.text("content-html").ifBlank { a.text("content") }), timestamp = time(a, "mkdate"))
                    }
                }
            }
            "blubber" -> {
                val page = if (itemId.isBlank()) collection("courses/${courseId.path()}/blubber-threads", mapOf("include" to "author"))
                    else collection("blubber-threads/${itemId.path()}/comments", mapOf("include" to "author"))
                val users = page.included.filter { it.text("type") == "users" }.associateBy { it.text("id") }
                page.resources.map { resource ->
                    val a = resource.attributes()
                    val body = JsonApiMapper.plainText(a.text("content-html").ifBlank { a.text("content") })
                    if (itemId.isBlank()) {
                        if (resource.text("type") != "blubber-threads") throw CampusException(FailureKind.INVALID_RESPONSE)
                        val comments = ((resource["relationships"] as? JsonObject)?.get("comments") as? JsonObject)
                        val unread = ((comments?.get("meta") as? JsonObject)?.get("unseen-comments") as? JsonPrimitive)?.intOrNull
                        // A course's default Blubber thread has no text of its own; its conversation is in the comments.
                        val title = body.lineSequence().firstOrNull { it.isNotBlank() }?.take(180)
                            ?: JsonApiMapper.plainText(a.text("name")).ifBlank { "Blubber" }
                        CommunityItem(resource.requiredId(), title, body,
                            secondary = name(users[resource.relatedId("author")]),
                            timestamp = time(a, "latest-activity"), unread = unread, canReply = a.flag("is-commentable", false))
                    } else {
                        if (resource.text("type") != "blubber-comments") throw CampusException(FailureKind.INVALID_RESPONSE)
                        CommunityItem(resource.requiredId(), name(users[resource.relatedId("author")]), body,
                            timestamp = time(a, "mkdate"))
                    }
                }
            }
            "wiki" -> {
                if (itemId.isNotBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
                collection("courses/${courseId.path()}/wiki-pages").resources.map { resource ->
                    if (resource.text("type") != "wiki-pages") throw CampusException(FailureKind.INVALID_RESPONSE)
                    val a = resource.attributes()
                    CommunityItem(resource.requiredId(), JsonApiMapper.plainText(a.text("name")), JsonApiMapper.plainText(a.text("content")), timestamp = time(a, "chdate"))
                }
            }
            "courseware" -> {
                if (itemId.isBlank()) {
                    val page = collection("courses/${courseId.path()}/courseware-units", mapOf("include" to "structural-element"))
                    val elements = page.included.filter { it.text("type") == "courseware-structural-elements" }.associateBy { it.text("id") }
                    page.resources.mapNotNull { unit ->
                        if (unit.text("type") != "courseware-units") throw CampusException(FailureKind.INVALID_RESPONSE)
                        if (!unit.attributes().flag("can-read", true)) return@mapNotNull null
                        val elementId = unit.relatedId("structural-element")
                        val element = elements[elementId] ?: throw CampusException(FailureKind.INVALID_RESPONSE)
                        if (!element.attributes().flag("can-visit", true)) return@mapNotNull null
                        CommunityItem(elementId, JsonApiMapper.plainText(element.attributes().text("title")), secondary = unit.requiredId())
                    }
                } else {
                    val page = collection("courseware-structural-elements/${itemId.path()}", mapOf("include" to "children,containers.blocks"), paginated = false)
                    val element = page.resources.singleOrNull()?.takeIf { it.text("id") == itemId && it.text("type") == "courseware-structural-elements" }
                        ?: throw CampusException(FailureKind.INVALID_RESPONSE)
                    if (!element.attributes().flag("can-visit", true)) throw CampusException(FailureKind.PERMISSION)
                    val included = page.included.associateBy { it.text("type") to it.text("id") }
                    var incomplete = false
                    fun refs(resource: JsonObject, relation: String): List<JsonObject> {
                        val data = ((resource["relationships"] as? JsonObject)?.get(relation) as? JsonObject)?.get("data")
                        if (data !is JsonArray) { incomplete = true; return emptyList() }
                        return data.map { it as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE) }
                    }
                    fun resolved(ref: JsonObject) = included[ref.text("type") to ref.text("id")]
                    fun position(ref: JsonObject) = resolved(ref)?.attributes()?.number("position") ?: 0L
                    val children = refs(element, "children").sortedBy(::position).mapNotNull { ref ->
                        val child = resolved(ref) ?: run { incomplete = true; return@mapNotNull null }
                        if (child.text("type") != "courseware-structural-elements") throw CampusException(FailureKind.INVALID_RESPONSE)
                        if (!child.attributes().flag("can-visit", true)) null
                        else CommunityItem(child.requiredId(), JsonApiMapper.plainText(child.attributes().text("title")))
                    }
                    val parts = mutableListOf<String>()
                    val contentParts = mutableListOf<CoursewarePart>()
                    fun safeWeb(value: String): Boolean = (value.startsWith("https://") || value.startsWith("http://")) &&
                        runCatching { Url(value).let { it.host.isNotBlank() && it.user.isNullOrEmpty() && it.password.isNullOrEmpty() } }.getOrDefault(false)
                    refs(element, "containers").sortedBy(::position).forEach { containerRef ->
                        val container = resolved(containerRef) ?: run { incomplete = true; return@forEach }
                        if (container.text("type") != "courseware-containers") throw CampusException(FailureKind.INVALID_RESPONSE)
                        if (!container.attributes().flag("visible", true)) return@forEach
                        refs(container, "blocks").sortedBy(::position).forEach blockLoop@ { blockRef ->
                            val block = resolved(blockRef) ?: run { incomplete = true; return@blockLoop }
                            if (block.text("type") != "courseware-blocks") throw CampusException(FailureKind.INVALID_RESPONSE)
                            val a = block.attributes()
                            if (!a.flag("visible", true)) return@blockLoop
                            val payload = a["payload"] as? JsonObject ?: JsonObject(emptyMap())
                            val type = a.text("block-type")
                            when (type) {
                                "text" -> {
                                    val html = payload.text("text")
                                    if (Regex("<(img|video|audio|table)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) incomplete = true
                                    JsonApiMapper.plainText(html).takeIf { it.isNotBlank() }?.let { text ->
                                        parts.add(text); contentParts.add(CoursewarePart("text", text = text))
                                    }
                                }
                                "headline" -> contentParts.add(CoursewarePart("headline", title = JsonApiMapper.plainText(payload.text("title")),
                                    text = JsonApiMapper.plainText(listOf(payload.text("subtitle"), payload.text("second_subtitle")).filter { it.isNotBlank() }.joinToString("\n"))))
                                "link" -> {
                                    val url = payload.text("url")
                                    if (safeWeb(url))
                                        contentParts.add(CoursewarePart("link", title = JsonApiMapper.plainText(payload.text("title")).ifBlank { url }, target = url))
                                    else incomplete = true
                                }
                                "key-point" -> JsonApiMapper.plainText(payload.text("text")).takeIf { it.isNotBlank() }?.let {
                                    parts.add(it); contentParts.add(CoursewarePart("keypoint", text = it))
                                }
                                "typewriter" -> JsonApiMapper.plainText(payload.text("text")).takeIf { it.isNotBlank() }?.let {
                                    parts.add(it); contentParts.add(CoursewarePart("text", text = it))
                                }
                                // Code keeps its exact whitespace; only the language label is plain text.
                                "code" -> payload.text("content").takeIf { it.isNotBlank() }?.let {
                                    contentParts.add(CoursewarePart("code", title = JsonApiMapper.plainText(payload.text("lang")), text = it.take(20_000)))
                                }
                                // Courseware stores this as epoch milliseconds (a countdown or a date).
                                "date" -> (payload["timestamp"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
                                    ?.takeIf { it > 0 }?.let { contentParts.add(CoursewarePart("date", target = it.toString())) } ?: run { incomplete = true }
                                "embed", "iframe" -> {
                                    val url = payload.text("url")
                                    if (safeWeb(url)) contentParts.add(CoursewarePart("link", title = JsonApiMapper.plainText(payload.text("title")).ifBlank { url }, target = url))
                                    else incomplete = true
                                }
                                // The table of contents repeats the child chapters, which are listed natively.
                                "table-of-contents" -> Unit
                                "document", "download", "audio", "video" -> {
                                    val id = payload.text("file_id")
                                    val web = payload.text("web_url")
                                    val allowed = type != "document" || payload.text("downloadable") != "false"
                                    if (id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && allowed)
                                        contentParts.add(CoursewarePart("file", title = JsonApiMapper.plainText(payload.text("title")).ifBlank { type }, target = id, blockId = block.requiredId()))
                                    else if (id.isBlank() && safeWeb(web))
                                        contentParts.add(CoursewarePart("link", title = JsonApiMapper.plainText(payload.text("title")).ifBlank { type }, target = web))
                                    else incomplete = true
                                }
                                else -> incomplete = true
                            }
                        }
                    }

                    listOf(CommunityItem(itemId, JsonApiMapper.plainText(element.attributes().text("title")), parts.joinToString("\n\n"), children = children, incomplete = incomplete, parts = contentParts))
                }
            }
            else -> throw CampusException(FailureKind.NOT_FOUND)
        }
    }

    override suspend fun coursewareFile(courseId: String, blockId: String, fileId: String): Document {
        val resource = collection("courseware-blocks/${blockId.path()}/file-refs").resources.singleOrNull { it.text("id") == fileId }
            ?: throw CampusException(FailureKind.NOT_FOUND)
        if (resource.text("type") != "file-refs" || !resource.attributes().flag("is-downloadable", false)) throw CampusException(FailureKind.PERMISSION)
        return JsonApiMapper.document(resource, courseId, resource.relatedId("parent"))
    }

    override suspend fun forumSubscriptions(courseId: String): List<ForumSubscription> =
        collection("courses/${courseId.path()}/forum-subscriptions", mapOf("include" to "subject")).resources.mapNotNull { resource ->
            if (resource.text("type") != "forum-subscriptions") throw CampusException(FailureKind.INVALID_RESPONSE)
            val subject = ((resource["relationships"] as? JsonObject)?.get("subject") as? JsonObject)?.get("data") as? JsonObject
            if (subject == null) throw CampusException(FailureKind.INVALID_RESPONSE)
            if (subject.text("type") != "forum-discussions") null
            else ForumSubscription(resource.requiredId(), subject.requiredId())
        }

    override suspend fun forumReactions(postId: String): List<ForumReaction> =
        collection("forum-postings/${postId.path()}/reactions", mapOf("include" to "user")).resources.map { resource ->
            if (resource.text("type") != "forum-posting-reactions") throw CampusException(FailureKind.INVALID_RESPONSE)
            val userId = resource.relatedId("user").ifBlank { throw CampusException(FailureKind.INVALID_RESPONSE) }
            ForumReaction(resource.requiredId(), resource.attributes().text("emoji"), userId)
        }

    override suspend fun changeForumSubscription(courseId: String, discussionId: String, subscriptionId: String?): WriteResult<Unit> {
        if (courseId.isBlank() || discussionId.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        if (subscriptionId != null) return forumDelete("forum-subscriptions", subscriptionId)
        val payload = buildJsonObject { putJsonObject("data") {
            put("type", "forum-subscriptions"); putJsonObject("attributes") { put("notification-type", "all") }
            putJsonObject("relationships") {
                putJsonObject("range") { putJsonObject("data") { put("type", "courses"); put("id", courseId) } }
                putJsonObject("subject") { putJsonObject("data") { put("type", "forum-discussions"); put("id", discussionId) } }
            }
        } }.toString()
        return forumCreate("forum-subscriptions", payload)
    }

    override suspend fun changeForumReaction(postId: String, reactionId: String?): WriteResult<Unit> {
        if (postId.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        if (reactionId != null) return forumDelete("forum-posting-reactions", reactionId)
        val payload = buildJsonObject { putJsonObject("data") {
            put("type", "forum-posting-reactions"); putJsonObject("attributes") { put("emoji", "👍") }
            putJsonObject("relationships") { putJsonObject("posting") { putJsonObject("data") { put("type", "forum-postings"); put("id", postId) } } }
        } }.toString()
        return forumCreate("forum-posting-reactions", payload)
    }

    override suspend fun postForumReply(discussionId: String, body: String): WriteResult<Unit> {
        if (discussionId.isBlank() || body.isBlank() || body.length > 32_000) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val safeBody = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
        val response = try {
            htmlClient.post("$DIGICAMPUS_API/forum-postings") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json"))
                setBody(buildJsonObject { putJsonObject("data") {
                    put("type", "forum-postings"); putJsonObject("attributes") { put("content", safeBody); put("anonymous", false) }
                    putJsonObject("relationships") { putJsonObject("discussion") { putJsonObject("data") {
                        put("type", "forum-discussions"); put("id", discussionId)
                    } } }
                } }.toString())
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 413, 415, 422)) return WriteResult.Rejected(writeFailure(status))
        if (status != 201) return WriteResult.Uncertain
        val location = response.headers[HttpHeaders.Location] ?: return WriteResult.Uncertain
        val url = try { Url(if (location.startsWith("/")) DIGICAMPUS_ORIGIN + location else location) } catch (_: Exception) { return WriteResult.Uncertain }
        if (url.protocol != URLProtocol.HTTPS || url.host != Url(DIGICAMPUS_API).host || url.port != 443 ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.parameters.names().isNotEmpty() || url.fragment.isNotBlank() ||
            !Regex("/jsonapi.php/v1/forum-postings/[a-zA-Z0-9_-]+").matches(url.encodedPath)) return WriteResult.Uncertain
        return WriteResult.Confirmed(Unit)
    }

    override suspend fun postBlubberReply(threadId: String, body: String): WriteResult<Unit> {
        if (threadId.isBlank() || body.isBlank() || body.length > 32_000) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val response = try {
            htmlClient.post("$DIGICAMPUS_API/blubber-threads/${threadId.path()}/comments") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json"))
                setBody(buildJsonObject { putJsonObject("data") {
                    put("type", "blubber-comments"); putJsonObject("attributes") { put("content", body) }
                } }.toString())
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 413, 415, 422)) return WriteResult.Rejected(writeFailure(status))
        if (status != 201) return WriteResult.Uncertain
        val location = response.headers[HttpHeaders.Location] ?: return WriteResult.Uncertain
        val url = try { Url(if (location.startsWith("/")) DIGICAMPUS_ORIGIN + location else location) } catch (_: Exception) { return WriteResult.Uncertain }
        if (url.protocol != URLProtocol.HTTPS || url.host != Url(DIGICAMPUS_API).host || url.port != 443 ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.parameters.names().isNotEmpty() || url.fragment.isNotBlank() ||
            !Regex("/jsonapi.php/v1/blubber-comments/[a-zA-Z0-9_-]+").matches(url.encodedPath)) return WriteResult.Uncertain
        return WriteResult.Confirmed(Unit)
    }

    override suspend fun postBlubberThread(courseId: String, body: String): WriteResult<Unit> {
        if (courseId.isBlank() || body.isBlank() || body.length > 32_000) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val response = try {
            htmlClient.post("$DIGICAMPUS_API/blubber-threads") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json"))
                setBody(buildJsonObject { putJsonObject("data") {
                    put("type", "blubber-threads"); putJsonObject("attributes") {
                        put("context-type", "course"); put("context-id", courseId); put("content", body); put("is-visible-in-stream", true)
                    }
                } }.toString())
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 413, 415, 422)) return WriteResult.Rejected(writeFailure(status))
        if (status != 201) return WriteResult.Uncertain
        val location = response.headers[HttpHeaders.Location] ?: return WriteResult.Uncertain
        val url = try { Url(if (location.startsWith("/")) DIGICAMPUS_ORIGIN + location else location) } catch (_: Exception) { return WriteResult.Uncertain }
        if (url.protocol != URLProtocol.HTTPS || url.host != Url(DIGICAMPUS_API).host || url.port != 443 ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.parameters.names().isNotEmpty() || url.fragment.isNotBlank() ||
            !Regex("/jsonapi.php/v1/blubber-threads/[a-zA-Z0-9_-]+").matches(url.encodedPath)) return WriteResult.Uncertain
        return WriteResult.Confirmed(Unit)
    }

    override suspend fun coursewareBookmarks(userId: String): List<String> =
        collection("users/${userId.path()}/courseware-bookmarks").resources.map { resource ->
            if (resource.text("type") != "courseware-structural-elements") throw CampusException(FailureKind.INVALID_RESPONSE)
            resource.requiredId()
        }

    override suspend fun setCoursewareBookmark(userId: String, elementId: String, selected: Boolean): WriteResult<Unit> {
        if (userId.isBlank() || elementId.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val payload = buildJsonObject { putJsonArray("data") {
            add(buildJsonObject { put("type", "courseware-structural-elements"); put("id", elementId) })
        } }.toString()
        val url = "$DIGICAMPUS_API/users/${userId.path()}/relationships/courseware-bookmarks"
        val response = try {
            htmlClient.request(url) {
                method = if (selected) HttpMethod.Post else HttpMethod.Delete
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json")); setBody(payload)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 422)) return WriteResult.Rejected(writeFailure(status))
        return if (status == 204) WriteResult.Confirmed(Unit) else WriteResult.Uncertain
    }

    override suspend fun coursewareProgress(unitId: String): Map<String, Double> {
        if (unitId.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
        val data = getJson("$DIGICAMPUS_API/courseware-units/${unitId.path()}/courseware-user-progresses")
        return data.map { (id, entry) ->
            val value = (entry as? JsonObject)?.get("progress") as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val cumulative = (value["cumulative"] as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            id to cumulative
        }.toMap()
    }
}

/** Item standing for members hidden by their privacy settings; [CommunityItem.unread] carries how many. */
const val HIDDEN_MEMBERS_ID = "__hidden_members__"

/**
 * Members of a group fragment: one table row per visible member (profile link with the name), and a footer
 * "+ N invisible persons" / "+ N unsichtbare Personen". Names are shown as DigiCampus lists them.
 */
internal fun groupMembers(html: String): List<CommunityItem> {
    val page = com.fleeksoft.ksoup.Ksoup.parse(html)
    val members = page.select("table tbody tr").mapNotNull { row ->
        val link = row.select("a[href*=dispatch.php/profile]").firstOrNull() ?: return@mapNotNull null
        // DigiCampus lists "Last, First"; show "First Last" like the People list.
        val name = link.text().trim().split(", ").let { parts -> if (parts.size == 2) "${parts[1]} ${parts[0]}" else parts.joinToString(", ") }
        val username = Regex("[?&]username=([^&#]+)").find(link.attr("href"))?.groupValues?.get(1).orEmpty()
        if (name.isBlank()) null else CommunityItem(username.ifBlank { name }, name, secondary = username)
    }.distinctBy { it.id }
    val hidden = Regex("\\+\\s*(\\d+)\\s+(?:invisible|unsichtbar)", RegexOption.IGNORE_CASE)
        .find(page.select("tfoot").text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return members + listOfNotNull(CommunityItem(HIDDEN_MEMBERS_ID, "", unread = hidden).takeIf { hidden > 0 })
}
