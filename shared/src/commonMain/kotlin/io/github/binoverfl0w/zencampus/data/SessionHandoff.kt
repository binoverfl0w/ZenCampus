package io.github.binoverfl0w.zencampus.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Called on the UI thread. A session can become authenticated without its cookie changing. */
class SessionHandoff(
    private val scope: CoroutineScope,
    private val readCookie: () -> String,
    private val verify: suspend (cookie: String, userRequested: Boolean) -> Boolean,
    private val onAuthenticated: () -> Unit,
) {
    private var runner: Job? = null
    private var pendingUserRequest: Boolean? = null
    private var authenticated = false

    fun request(userRequested: Boolean = false) {
        if (authenticated) return
        // Navigation during a guest-session probe must trigger a fresh check afterward.
        pendingUserRequest = (pendingUserRequest ?: false) || userRequested
        if (runner?.isActive == true) return
        runner = scope.launch {
            while (!authenticated) {
                val requested = pendingUserRequest ?: break
                pendingUserRequest = null
                val cookie = readCookie()
                if (cookie.isBlank() && !requested) continue
                if (verify(cookie, requested)) {
                    authenticated = true
                    pendingUserRequest = null
                    onAuthenticated()
                }
            }
        }
    }
}
