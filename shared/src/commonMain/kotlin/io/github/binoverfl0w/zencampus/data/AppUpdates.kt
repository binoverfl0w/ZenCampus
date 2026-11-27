package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A downloaded update being written to disk; the platform hashes it on the way. */
interface UpdateWriter {
    suspend fun write(bytes: ByteArray, count: Int)
    /** Closes the file and returns its SHA-256 as lowercase hex. */
    suspend fun finish(): String
    suspend fun abort()
}

enum class UpdateInstall {
    /** The system installer is showing its confirmation (Android). */
    STARTED,
    /** The student must first allow ZenCampus to install apps; the system settings page is open. */
    NEEDS_PERMISSION,
    /** The new version is unpacked and replaces this one when the app restarts (desktop). */
    RESTART,
}

/** What differs between Android and desktop updates. */
interface UpdatePlatform {
    /** The release asset this installation can install by itself, or null when updating means downloading by hand. */
    fun installable(assets: List<ReleaseAsset>): ReleaseAsset?
    suspend fun writer(asset: ReleaseAsset): UpdateWriter
    /** Hands the verified download to the system installer (Android) or stages it for a restart (desktop). */
    suspend fun install(asset: ReleaseAsset): UpdateInstall
    /** Quits so the staged update can replace this version, then starts the new one. */
    fun restart() {}
    fun openPage(url: String)
    fun readState(): String?
    fun writeState(value: String)
}

enum class UpdateFailure { OFFLINE, UNVERIFIED, DOWNLOAD, INSTALL }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Current(val checkedAt: Long) : UpdateState
    /** [asset] is null when this installation cannot update itself; the release page is offered instead. */
    data class Available(val release: AppRelease, val asset: ReleaseAsset?) : UpdateState
    data class Downloading(val release: AppRelease, val asset: ReleaseAsset, val done: Long, val total: Long) : UpdateState
    /** Downloaded and verified. [restart] means the desktop app has staged it and applies it on restart. */
    data class Ready(val release: AppRelease, val asset: ReleaseAsset, val restart: Boolean, val needsPermission: Boolean = false) : UpdateState
    data class Failed(val failure: UpdateFailure, val release: AppRelease?) : UpdateState
}

@Serializable
private data class UpdateMemory(val lastCheck: Long = 0, val dismissed: String? = null)

/**
 * Checks GitHub Releases for a newer version and installs it. One automatic check a day while "check for updates" is
 * on, plus the manual check in Settings. Downloads are verified against the SHA-256 published with the release before
 * anything is installed; Android additionally refuses an APK that is not signed with this app's key.
 */
class AppUpdates(
    private val client: () -> HttpClient,
    /** owner/name of the GitHub repository; blank disables updates (development builds). */
    private val repository: String,
    currentVersion: String,
    private val platform: UpdatePlatform,
    private val scope: CoroutineScope,
    private val automatic: () -> Boolean,
    private val clock: () -> Long = ::nowMillis,
) {
    val configured: Boolean = repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
    val version: String = currentVersion
    private val current = AppVersion.parse(currentVersion)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var memory = runCatching { platform.readState()?.let { json.decodeFromString<UpdateMemory>(it) } }.getOrNull() ?: UpdateMemory()
    private val _dismissed = MutableStateFlow(memory.dismissed)
    /** The version whose update prompt the student closed; it stays in Settings but does not pop up again. */
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()
    private var work: Job? = null

    /** Starts the daily automatic check; it does nothing while the setting is off. */
    fun start(): Job = scope.launch {
        while (isActive) {
            if (configured && automatic() && clock() - memory.lastCheck >= CHECK_INTERVAL) check()
            delay(WAKE_INTERVAL)
        }
    }

    /** Looks for a newer release now. Does nothing while a download is under way. */
    suspend fun check() {
        if (!configured || _state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = latest()
            remember(memory.copy(lastCheck = clock()))
            val newer = release?.takeIf { current == null || (AppVersion.parse(it.version) ?: return@takeIf false) > current }
            ZenLog.info(TAG, if (newer == null) "up to date ($version)" else "version ${newer.version} available")
            if (newer == null) UpdateState.Current(clock()) else UpdateState.Available(newer, platform.installable(newer.assets))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ZenLog.warn(TAG, "update check failed", e)
            UpdateState.Failed(UpdateFailure.OFFLINE, null)
        }
    }

    fun checkNow() { if (work?.isActive != true) work = scope.launch { check() } }

    /** Keeps the prompt for [release] from opening again. */
    fun dismiss(release: AppRelease) {
        _dismissed.value = release.version
        remember(memory.copy(dismissed = release.version))
    }

    /** Downloads, verifies and installs the available update. */
    fun update() {
        val available = _state.value
        if (work?.isActive == true) return
        work = scope.launch {
            when (available) {
                is UpdateState.Available -> available.asset?.let { download(available.release, it) } ?: platform.openPage(available.release.page)
                is UpdateState.Ready -> if (available.restart) platform.restart() else install(available.release, available.asset)
                is UpdateState.Failed -> check()
                else -> {}
            }
        }
    }

    fun openReleasePage() {
        when (val state = _state.value) {
            is UpdateState.Available -> platform.openPage(state.release.page)
            is UpdateState.Downloading -> platform.openPage(state.release.page)
            is UpdateState.Ready -> platform.openPage(state.release.page)
            is UpdateState.Failed -> platform.openPage(state.release?.page ?: "https://github.com/$repository/releases/latest")
            else -> platform.openPage("https://github.com/$repository/releases/latest")
        }
    }

    /** Stops a download in progress; the partial file is deleted. */
    fun cancel() {
        val state = _state.value
        work?.cancel()
        if (state is UpdateState.Downloading) _state.value = UpdateState.Available(state.release, state.asset)
    }

    /** The Android installer reported back; anything but success leaves the verified download ready to try again. */
    fun installFinished(success: Boolean) {
        val state = _state.value as? UpdateState.Ready ?: return
        if (!success) {
            ZenLog.warn(TAG, "installation did not complete")
            _state.value = state.copy(needsPermission = false)
        }
    }

    private suspend fun latest(): AppRelease? = fetch("https://api.github.com/repos/$repository/releases/latest", api = true) { response ->
        when (val status = response.status.value) {
            in 200..299 -> parseGitHubRelease(response.bodyAsText()) ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            404 -> null // No published release yet.
            else -> throw CampusException(FailureKind.SERVER, httpStatus = status)
        }
    }

    private suspend fun download(release: AppRelease, asset: ReleaseAsset) {
        val expected = try { asset.sha256 ?: publishedDigest(release, asset) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (expected == null) {
            ZenLog.warn(TAG, "release ${release.version} has no checksum for its download")
            _state.value = UpdateState.Failed(UpdateFailure.UNVERIFIED, release); return
        }
        _state.value = UpdateState.Downloading(release, asset, 0, asset.size)
        val writer = try { platform.writer(asset) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ZenLog.warn(TAG, "update file could not be created", e)
            _state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD, release); return
        }
        var finished = false
        try {
            val digest = fetch(asset.url, api = false) { response ->
                if (response.status.value !in 200..299) throw CampusException(FailureKind.SERVER, httpStatus = response.status.value)
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var reported = 0L
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    done += count
                    if (asset.size > 0 && done > asset.size + SIZE_SLACK || done > MAX_DOWNLOAD) throw CampusException(FailureKind.INVALID_RESPONSE)
                    writer.write(buffer, count)
                    if (done - reported >= PROGRESS_STEP) { reported = done; _state.value = UpdateState.Downloading(release, asset, done, asset.size) }
                }
                writer.finish().also { finished = true }
            }
            if (digest != expected) {
                ZenLog.warn(TAG, "download of ${release.version} did not match its checksum")
                _state.value = UpdateState.Failed(UpdateFailure.UNVERIFIED, release); return
            }
            ZenLog.info(TAG, "downloaded and verified ${release.version}")
            install(release, asset)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { if (!finished) writer.abort() }
            throw e
        } catch (e: Exception) {
            if (!finished) writer.abort()
            ZenLog.warn(TAG, "update download failed", e)
            _state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD, release)
        }
    }

    private suspend fun install(release: AppRelease, asset: ReleaseAsset) {
        _state.value = try {
            when (platform.install(asset)) {
                UpdateInstall.STARTED -> UpdateState.Ready(release, asset, restart = false)
                UpdateInstall.NEEDS_PERMISSION -> UpdateState.Ready(release, asset, restart = false, needsPermission = true)
                UpdateInstall.RESTART -> UpdateState.Ready(release, asset, restart = true)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ZenLog.warn(TAG, "update could not be installed", e)
            UpdateState.Failed(UpdateFailure.INSTALL, release)
        }
    }

    /** The digest from the release's SHA256SUMS file, for releases whose assets carry no digest of their own. */
    private suspend fun publishedDigest(release: AppRelease, asset: ReleaseAsset): String? {
        val sums = release.assets.firstOrNull { it.name == SHA256_SUMS } ?: return null
        return fetch(sums.url, api = false) { response ->
            if (response.status.value !in 200..299) null else parseSha256Sums(response.bodyAsText().take(64 * 1024))[asset.name]
        }
    }

    /**
     * One GET that follows GitHub's download redirects itself: the shared client never follows redirects, so a
     * sign-in cookie can never travel with one. Only HTTPS targets are accepted.
     */
    private suspend fun <T> fetch(url: String, api: Boolean, read: suspend (HttpResponse) -> T): T {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val parsed = Url(target)
            if (parsed.protocol != URLProtocol.HTTPS) throw CampusException(FailureKind.INVALID_RESPONSE)
            val next = client().prepareGet(target) {
                header(HttpHeaders.UserAgent, "ZenCampus-Updater")
                if (api) { header(HttpHeaders.Accept, "application/vnd.github+json"); header("X-GitHub-Api-Version", "2022-11-28") }
                else timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT; socketTimeoutMillis = 60_000 }
            }.execute { response ->
                if (response.status.value in 300..399) Redirect(response.headers[HttpHeaders.Location]?.let { parsed.resolve(it) })
                else Done(read(response))
            }
            when (next) {
                is Done -> return next.value
                is Redirect -> target = next.location ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            }
        }
        throw CampusException(FailureKind.INVALID_RESPONSE)
    }

    private sealed interface Step<out T>
    private class Done<T>(val value: T) : Step<T>
    private class Redirect(val location: String?) : Step<Nothing>

    private fun Url.resolve(location: String): String = when {
        location.startsWith("https://") || location.startsWith("http://") -> location
        location.startsWith("/") -> "${protocol.name}://$host$location"
        else -> location
    }

    private fun remember(next: UpdateMemory) {
        memory = next
        runCatching { platform.writeState(json.encodeToString(UpdateMemory.serializer(), next)) }
    }

    companion object {
        const val TAG = "update"
        const val CHECK_INTERVAL = 24L * 60 * 60 * 1000
        private const val WAKE_INTERVAL = 60L * 60 * 1000
        private const val DOWNLOAD_TIMEOUT = 30L * 60 * 1000
        private const val PROGRESS_STEP = 256L * 1024
        private const val SIZE_SLACK = 1024L * 1024
        private const val MAX_DOWNLOAD = 1024L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
    }
}
