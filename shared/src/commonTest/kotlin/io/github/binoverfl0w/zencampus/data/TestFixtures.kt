package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.datetime.*
import kotlin.test.*

/** In-memory fakes shared by the repository and feature tests. */
internal class MemoryStore : CampusStore {
    private val state = MutableStateFlow<Map<String, List<CacheRecord>>>(emptyMap())
    override fun observe(scope: String): Flow<List<CacheRecord>> = state.map { it[scope].orEmpty() }.distinctUntilChanged()
    override suspend fun read(scope: String, key: String): CacheRecord? = state.value[scope]?.firstOrNull { it.key == key }
    override suspend fun put(scope: String, key: String, payload: String, updatedAt: Long) {
        state.update { it + (scope to (it[scope].orEmpty().filterNot { record -> record.key == key } + CacheRecord(key, payload, updatedAt))) }
    }
    override suspend fun remove(scope: String, key: String) { state.update { it + (scope to it[scope].orEmpty().filterNot { record -> record.key == key }) } }
    override suspend fun purge(scope: String) { state.update { it - scope } }
}

internal class TestProvider : CampusProvider {
    var courseCalls = 0
    var downloadCalls = 0
    var offeredCourses = listOf(Course("c", "Algorithms"))
    var offeredCourseEvents = emptyList<CampusEvent>()
    var offeredPersonalEvents = emptyList<CampusEvent>()
    var offeredScheduledEvents = emptyList<CampusEvent>()
    var offeredAccount = Account("alice", "Alice Student", "alice")
    var personalCalls = 0
    var scheduleCalls = 0
    var personalFailure: FailureKind? = null
    var courseFailure: FailureKind? = null
    var folderFailure: FailureKind? = null
    var courseGate: CompletableDeferred<Unit>? = null
    var failDownload = false
    var downloadFailure: FailureKind? = null
    override suspend fun account() = offeredAccount
    override suspend fun semesters() = emptyList<Semester>()
    override suspend fun courses(userId: String, semesterId: String?): List<Course> {
        courseCalls++
        courseGate?.let { withContext(NonCancellable) { it.await() } }
        delay(100)
        courseFailure?.let { throw CampusException(it) }
        return offeredCourses
    }
    override suspend fun course(id: String) = offeredCourses.first { it.id == id }
    override suspend fun events(courseId: String) = offeredCourseEvents
    override suspend fun personalEvents(userId: String, from: Long): List<CampusEvent> {
        personalCalls++
        personalFailure?.let { throw CampusException(it) }
        return offeredPersonalEvents
    }
    override suspend fun scheduledEvents(userId: String, from: Long, semesters: List<Semester>): List<CampusEvent> {
        scheduleCalls++
        return offeredScheduledEvents
    }
    override suspend fun announcements(courseId: String) = emptyList<Announcement>()
    override suspend fun folders(courseId: String, parentId: String?): List<CampusFolder> {
        folderFailure?.let { throw CampusException(it) }
        return listOf(CampusFolder("child", courseId, "Child", parentId.orEmpty()))
    }
    override suspend fun documents(courseId: String, folderId: String) = listOf(Document("notes", courseId, folderId, "notes.pdf"))
    override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long {
        downloadCalls++
        write(byteArrayOf(1, 2, 3, 4))
        downloadFailure?.let { throw CampusException(it) }
        if (failDownload) throw CampusException(FailureKind.OFFLINE)
        return 4
    }
}

internal class MemoryMaterials : MaterialStorage {
    val files = mutableMapOf<String, ByteArray>()
    val opened = mutableListOf<String>()
    var aborted = 0
    private var next = 0
    override suspend fun create(scope: String, document: Document): MaterialWriter {
        val path = "$scope/${document.id}/${next++}"
        val chunks = mutableListOf<ByteArray>()
        return object : MaterialWriter {
            override suspend fun write(bytes: ByteArray) { chunks += bytes }
            override suspend fun finish(): String {
                files[path] = chunks.flatMap { it.toList() }.toByteArray()
                return path
            }
            override suspend fun abort() { files.remove(path); aborted++ }
        }
    }
    override suspend fun exists(path: String) = files.containsKey(path)
    override suspend fun remove(path: String) { files.remove(path) }
    override suspend fun clear(scope: String) { files.keys.filter { it.startsWith(scope + "/") }.forEach(files::remove) }
    override suspend fun open(path: String, mimeType: String, displayName: String) {
        check(files.containsKey(path))
        opened += path
    }
    /** Synced files by their visible path ("destination/folder/name"). */
    val synced = mutableMapOf<String, ByteArray>()
    var syncsDefault = true
    override val syncsWithoutFolder: Boolean get() = syncsDefault
    override suspend fun copyInto(path: String, writer: MaterialWriter) { writer.write(files[path] ?: throw CampusException(FailureKind.STORAGE)) }
    override suspend fun syncWriter(destination: MaterialDestination?, folders: List<String>, name: String, mimeType: String): MaterialWriter {
        val path = (listOf(destination?.label ?: "Downloads/ZenCampus") + folders + name).joinToString("/")
        val chunks = mutableListOf<ByteArray>()
        return object : MaterialWriter {
            override suspend fun write(bytes: ByteArray) { chunks += bytes }
            override suspend fun finish(): String { synced[path] = chunks.flatMap { it.toList() }.toByteArray(); return path }
            override suspend fun abort() { aborted++ }
        }
    }
}
