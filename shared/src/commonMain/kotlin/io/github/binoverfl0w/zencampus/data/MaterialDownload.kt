package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.CampusException
import io.github.binoverfl0w.zencampus.model.FailureKind
import kotlinx.coroutines.delay

/** Only an interrupted or temporarily rejected GET is safe to restart automatically. */
internal class RetryableDownloadException(
    kind: FailureKind, cause: Throwable? = null, httpStatus: Int? = null,
    override val retryAfterMillis: Long? = null,
) : CampusException(kind, cause, httpStatus, retryAfterMillis)

/** The caller owns a fresh writer per attempt; a streamed response must never be appended twice. */
internal suspend fun <T> retryMaterialDownload(transfer: suspend () -> T): T {
    repeat(3) { attempt ->
        try { return transfer() }
        catch (error: RetryableDownloadException) {
            if (attempt == 2) throw error
            delay(error.retryAfterMillis ?: 300L * (attempt + 1))
        }
    }
    error("Unreachable download attempt")
}
