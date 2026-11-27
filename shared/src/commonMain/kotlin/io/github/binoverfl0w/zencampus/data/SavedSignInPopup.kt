package io.github.binoverfl0w.zencampus.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun initialSavedSignIn(entries: List<SavedSignIn>, kind: SignInKind, expired: Boolean): SavedSignIn? {
    val matching = entries.filter { it.kind == kind }
    return when {
        expired -> matching.maxByOrNull { it.lastUsed ?: Long.MIN_VALUE }
        else -> matching.singleOrNull()
    }
}

/** A popup owns its attempt. Platform clients call it on the UI thread, for the completed main frame only. */
class SavedSignInPopup internal constructor(
    private val store: SavedSignInStore,
    private val scope: CoroutineScope,
    val kind: SignInKind,
    expired: Boolean,
    private val rejection: String,
    allowAutomaticFill: Boolean = true,
    private val fixtureOrigin: ((String) -> String?)? = null,
) {
    val entries: List<SavedSignIn> get() = store.entries.value.filter { it.kind == kind }
    val updates get() = store.entries
    private var attempt: SignInAttempt? = null
    private var armed: SavedSignIn? = null
    private var markedUsed = false
    val selected: SavedSignIn? get() = armed?.let { previous ->
        entries.find { it.id == previous.id && it.username == previous.username && it.password == previous.password }
    }

    init {
        if (allowAutomaticFill) initialSavedSignIn(entries, kind, expired)?.let { pick(it.id) }
    }

    fun siteForPage(url: String?) = url?.let(kind::origin) ?: kind.defaultSite

    fun pick(id: String): Boolean = pick(id, restart = false)

    private fun pick(id: String, restart: Boolean): Boolean {
        val entry = entries.find { it.id == id } ?: return false
        if (!restart && selected?.id == id) return true
        armed = entry
        markedUsed = false
        val origin = fixtureOrigin ?: kind::origin
        attempt = when (kind) {
            SignInKind.STUDY -> MicrosoftSignInAttempt(entry, rejection, origin, fixtureOrigin != null)
            SignInKind.LIBRARY -> LibrarySignInAttempt(entry, rejection, origin, fixtureOrigin != null)
        }
        return true
    }

    /** Save and arm in the same popup. Completion runs on the popup's UI scope. */
    fun save(site: String, username: String, password: String, label: String?, id: String?, completed: (Boolean) -> Unit) {
        scope.launch {
            val entry = trySavedSignIn { store.save(kind, site, username, password, label, id) }
            completed(entry != null && pick(entry.id, restart = true))
        }
    }

    fun scriptForPage(url: String): String? {
        val entry = selected ?: return if (armed != null) stopScriptForPage(url) else null
        val script = attempt?.scriptForPage(url) ?: return stopScriptForPage(url)
        if (!markedUsed) {
            markedUsed = true
            scope.launch { trySavedSignIn { store.markUsed(entry) } }
        }
        return script
    }

    fun stopScriptForPage(url: String): String? {
        val origin = (fixtureOrigin ?: kind::origin)(url) ?: return null
        return """
            (() => {
                if (window !== window.top || location.origin !== ${scriptString(origin)}) return;
                window['zencampus.signin.controller']?.stop();
            })();
        """.trimIndent()
    }
}

internal interface SignInAttempt {
    fun scriptForPage(url: String): String?
}
