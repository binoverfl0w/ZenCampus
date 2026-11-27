package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.data.MaterialsSource
import io.github.binoverfl0w.zencampus.data.RetryableDownloadException
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
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

/** DigiCampus: Course folders, file lists, downloads and submission uploads. */
internal class DigiCampusMaterials(connection: DigiCampusConnection) : DigiCampusApi(connection), MaterialsSource {
    private val folderTreeMutex = Mutex()
    private val folderTrees = mutableMapOf<String, Pair<Long, MaterialFolderTree>>()
    override suspend fun assignmentFolders(courseId: String, force: Boolean): List<CampusFolder> =
        folderTree(courseId, refresh = force).submissionFolders()

    override suspend fun uploadAssignment(draft: UploadDraft, ownerId: String): WriteResult<Document> {
        if (draft.bytes.isEmpty() || draft.bytes.size > MAX_UPLOAD_BYTES || draft.name.isBlank()
            || draft.name.any { it < ' ' || it in "\"\\/" }) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        // Re-read permissions and course ownership immediately before the upload.
        val folder = try {
            val page = collection("folders/${draft.folderId.path()}", paginated = false)
            val resource = page.resources.singleOrNull() ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            requireFolderCourse(resource, draft.courseId)
            JsonApiMapper.folder(resource, draft.courseId, "")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return WriteResult.Rejected((e as? CampusException)?.kind ?: FailureKind.OFFLINE)
        }
        if (folder.writable != true) return WriteResult.Rejected(FailureKind.PERMISSION)
        val response = try {
            client.post("$DIGICAMPUS_API/folders/${draft.folderId.path()}/file-refs") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                setBody(MultiPartFormDataContent(formData {
                    append("file", draft.bytes, Headers.build {
                        append(HttpHeaders.ContentType, draft.mimeType.takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) } ?: "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"${draft.name}\"")
                    })
                }))
                timeout { requestTimeoutMillis = 120_000 }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 413, 415, 422)) return WriteResult.Rejected(writeFailure(status))
        if (status != 201) return WriteResult.Uncertain
        // Stud.IP returns a Location, not a JSON receipt. Never follow it with credentials unchecked.
        val location = response.headers[HttpHeaders.Location] ?: return WriteResult.Uncertain
        val receiptUrl = try { Url(if (location.startsWith("/")) DIGICAMPUS_ORIGIN + location else location) } catch (_: Exception) { return WriteResult.Uncertain }
        if (receiptUrl.protocol != URLProtocol.HTTPS || receiptUrl.host != Url(DIGICAMPUS_API).host || receiptUrl.port != 443
            || !receiptUrl.user.isNullOrEmpty() || !receiptUrl.password.isNullOrEmpty() || receiptUrl.parameters.names().isNotEmpty()
            || !receiptUrl.fragment.isBlank() || !Regex("/jsonapi.php/v1/file-refs/[a-zA-Z0-9_-]+").matches(receiptUrl.encodedPath)) return WriteResult.Uncertain
        return try {
            val page = collection("file-refs/${receiptUrl.encodedPath.substringAfterLast('/')}", paginated = false)
            val resource = page.resources.singleOrNull() ?: return WriteResult.Uncertain
            if (resource.text("type") != "file-refs" || resource.relatedId("parent") != draft.folderId
                || resource.relatedId("owner") != ownerId) return WriteResult.Uncertain
            requireFolderCourse(resource, draft.courseId)
            val doc = JsonApiMapper.document(resource, draft.courseId, draft.folderId)
            if (doc.name != draft.name || doc.size != draft.bytes.size.toLong()) WriteResult.Uncertain else WriteResult.Confirmed(doc)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { WriteResult.Uncertain }
    }

    override suspend fun folders(courseId: String, parentId: String?): List<CampusFolder> {
        return folderTree(courseId, refresh = parentId == null).childrenOf(parentId)
    }

    override suspend fun folderContent(courseId: String, parentId: String?, force: Boolean): FolderContent {
        val tree = folderTree(courseId, refresh = force || parentId == null)
        val current = if (parentId == null) tree.root?.let { tree.folder(it.id) } else tree.folder(parentId)
        val files = if (current != null && current.readable && current.fileCount != 0)
            documents(courseId, current.id) else emptyList()
        return FolderContent(tree.childrenOf(parentId), files, current, version = 2)
    }

    private suspend fun folderTree(courseId: String, refresh: Boolean): MaterialFolderTree = folderTreeMutex.withLock {
        val cached = folderTrees[courseId]
        if (!refresh && cached != null && isFresh(cached.first, now(), CampusRepository.FILE_TTL)) return@withLock cached.second
        val tree = MaterialFolderTree(collection("courses/${courseId.path()}/folders").resources, courseId)
        folderTrees[courseId] = now() to tree
        if (folderTrees.size > 12) folderTrees.entries.minByOrNull { it.value.first }?.let { folderTrees.remove(it.key) }
        tree
    }

    override suspend fun documents(courseId: String, folderId: String): List<Document> =
        collection("folders/${folderId.path()}/file-refs", mapOf("include" to "owner")).resources.map { JsonApiMapper.document(it, courseId, folderId) }

    override suspend fun courseMaterials(courseId: String, force: Boolean): CourseMaterials {
        var tree = folderTree(courseId, refresh = force)
        // This paginated endpoint already traverses accessible descendants on the server.
        // Read metadata once, rather than crawling each folder or downloading the files.
        val files = collection("courses/${courseId.path()}/file-refs", mapOf("include" to "owner")).resources.mapNotNull { resource ->
            if (resource.text("type") != "file-refs") throw CampusException(FailureKind.INVALID_RESPONSE)
            val range = resource.relation("range")?.get("data") as? JsonObject
            if (range != null && (range.text("type") != "courses" || range.text("id") != courseId))
                throw CampusException(FailureKind.INVALID_RESPONSE)
            val parent = resource.relation("parent")?.get("data") as? JsonObject
            if (parent?.text("type") != "folders" || parent.text("id").isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
            if (!resource.attributes().flag("is-readable", true)) return@mapNotNull null
            JsonApiMapper.document(resource, courseId, parent.text("id"))
        }
        // A new folder may have appeared since the tree was cached; keep its breadcrumb accurate.
        val known = tree.allFolders().map { it.id }.toSet()
        if (files.any { it.folderId !in known }) tree = folderTree(courseId, refresh = true)
        return CourseMaterials(courseId, tree.allFolders(), files)
    }

    override suspend fun linkedDocument(courseId: String, url: String): Document {
        val link = VipsLinks.attachment(url, courseId) ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        val metadata = try {
            val resource = collection("file-refs/${link.id.path()}", paginated = false).resources.singleOrNull()
                ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            if (resource.requiredId() != link.id || resource.text("type") != "file-refs") throw CampusException(FailureKind.INVALID_RESPONSE)
            JsonApiMapper.document(resource, courseId, resource.relatedId("parent").orEmpty())
        } catch (error: CampusException) {
            // Some plugin folders expose a download to students but no JSON:API metadata.
            // The published download still performs Stud.IP's own access checks.
            if (error.kind !in setOf(FailureKind.PERMISSION, FailureKind.NOT_FOUND)) throw error
            Document(link.id, courseId, VIPS_ATTACHMENTS, link.name)
        }
        return metadata.copy(name = metadata.name.ifBlank { link.name }, externalUrl = link.url, downloadUrl = link.url)
    }

    override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long {
        if (!document.downloadable) throw CampusException(FailureKind.PERMISSION)
        val url = if (document.downloadUrl.isBlank()) "$DIGICAMPUS_API/file-refs/${document.id.path()}/content" else {
            val link = VipsLinks.attachment(document.downloadUrl, document.courseId)
            if (link == null || link.id != document.id) throw CampusException(FailureKind.INVALID_RESPONSE)
            link.url
        }
        var total = 0L
        try {
            htmlClient.prepareGet(url) {
                header(HttpHeaders.Cookie, cookie())
                header(HttpHeaders.Accept, "*/*")
                timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 45_000 }
            }.execute { response ->
                if (response.status.value in 300..399) throw CampusException(FailureKind.EXTERNAL_LINK)
                val status = response.status.value
                if (status in setOf(408, 429, 500, 502, 503, 504)) {
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]
                    val seconds = retryAfter?.trim()?.toLongOrNull()
                    // Do not retry early when a server asks for a long wait or an HTTP-date.
                    if (retryAfter == null || (seconds != null && seconds in 0..10))
                        throw RetryableDownloadException(FailureKind.SERVER, httpStatus = status, retryAfterMillis = seconds?.times(1000))
                }
                validateStatus(status)
                if (response.status != HttpStatusCode.OK) throw CampusException(FailureKind.INVALID_RESPONSE)
                if (response.contentType()?.contentSubtype == "html") throw CampusException(FailureKind.AUTHENTICATION)
                val announced = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (announced != null && announced > MAX_FILE_BYTES) throw CampusException(FailureKind.STORAGE)
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > MAX_FILE_BYTES) throw CampusException(FailureKind.STORAGE)
                    try { write(buffer.copyOf(count)) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { throw CampusException(FailureKind.STORAGE, error) }
                }
                if (announced != null && announced != total) throw RetryableDownloadException(FailureKind.INVALID_RESPONSE)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw RetryableDownloadException(FailureKind.OFFLINE, error)
        }
        return total
    }

}
