package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.data.services.DemoMensaProvider
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.test.*

class SavedSignInTest {
    private val account = Account("fixture-a", "Fixture Student", "fixture")
    private val owner = account.cacheScope
    private val legacyKey = "remembered-sign-in:$owner"

    @Test fun literalsRoundTripWithoutJavascriptOrHtmlDelimiters() {
        val value = "a\"b\\c</script>\n\r\t\u2028\u2029 Grüße 🐱"
        val literal = scriptString(value)
        assertEquals(value, Json.decodeFromString<String>(literal))
        assertFalse(literal.contains("</script>"))
        assertFalse(literal.contains('\u2028'))
        assertFalse(literal.contains('\u2029'))
        assertTrue(literal.contains("Grüße"))
        for (script in listOf(
            microsoftSignInScript(STUDY_SIGN_IN_ORIGIN, "fixture", value, value, "fixture"),
            librarySignInScript(LIBRARY_ORIGIN, "fixture", value, value, "fixture"),
        )) {
            assertFalse(script.contains("</script>"))
            assertTrue(script.contains("let password = $literal;"))
            assertTrue(script.contains("location.hostname !== host || location.origin !== origin"))
            assertTrue(script.contains("window !== window.top"))
            assertTrue(script.indexOf("sessionStorage.setItem(key, attempt + ':submitted')") in 1 until script.lastIndexOf(".click()"))
        }
    }

    @Test fun anAttemptOnlyUsesItsSavedOrigin() {
        val attempt = MicrosoftSignInAttempt(SavedSignIn("test", SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, "student@example.test", "fixture"), "Rejected")
        for (url in listOf(
            "https://auth.rz.uni-augsburg.de/login", "https://login.microsoftonline.com.example.test/",
            "https://login.microsoftonline.com@evil.test/", "https://student@login.microsoftonline.com/",
            "http://login.microsoftonline.com/", "https://login.microsoftonline.com:444/", "//login.microsoftonline.com/",
            "https://login.microsoft.com/password",
        )) assertNull(attempt.scriptForPage(url), url)
        val script = assertNotNull(attempt.scriptForPage("https://LOGIN.MICROSOFTONLINE.COM:443/oauth"))
        assertEquals(script, attempt.scriptForPage("https://login.microsoftonline.com/password"))
        for (host in listOf("login.microsoftonline.com", "login.microsoft.com", "login.live.com", "login.windows.net")) {
            assertEquals("https://$host", microsoftSignInOrigin("https://$host/oauth"))
        }
        val library = LibrarySignInAttempt(SavedSignIn("test", SignInKind.LIBRARY, LIBRARY_ORIGIN, "12345", "fixture"), "Rejected")
        assertNull(library.scriptForPage("$STUDY_SIGN_IN_ORIGIN/login"))
        assertNull(library.scriptForPage("https://opac.bibliothek.uni-augsburg.de:444/TouchPoint/login.do"))
        assertNull(library.scriptForPage("https://opac.bibliothek.uni-augsburg.de.example.test/TouchPoint/login.do"))
        assertNull(library.scriptForPage("https://someone@opac.bibliothek.uni-augsburg.de/TouchPoint/login.do"))
        val libraryScript = assertNotNull(library.scriptForPage("${LIBRARY_BASE}login.do"))
        assertEquals(libraryScript, library.scriptForPage(LIBRARY_ACCOUNT))
        assertTrue(libraryScript.contains("form#LoginBean"))
    }

    @Test fun addEditRenameDeleteAndRemoveAll() = runBlocking {
        val secrets = FakeSecrets()
        val vault = secrets.savedSignInStore
        val study = vault.save(SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, " student@example.test ", "fixture", null)
        val library = vault.save(SignInKind.LIBRARY, LIBRARY_ORIGIN, "12345", "library fixture", " Reading ")
        assertEquals("Reading", library.label)
        assertEquals("student@example.test", study.username)
        assertFailsWith<IllegalArgumentException> { vault.save(SignInKind.STUDY, LIBRARY_ORIGIN, "12345", "secret", null) }
        val edited = vault.save(study.kind, study.site, "updated@example.test", "new fixture", "Campus", study.id)
        assertEquals(study.id, edited.id)
        assertEquals("new fixture", edited.password)
        val keepPassword = vault.save(study.kind, study.site, edited.username, "", "Other label", study.id)
        assertEquals(edited.password, keepPassword.password)
        vault.rename(study.id, "")
        assertNull(assertNotNull(vault.entries.value.find { it.id == study.id }).label)
        assertEquals(2, secrets.newStore().load().size)
        vault.delete(library.id)
        assertEquals(listOf(study.id), vault.entries.value.map { it.id })
        vault.removeAll()
        assertTrue(secrets.newStore().load().isEmpty())
    }

    @Test fun migrationRetriesDeletionWithoutDuplicatingTheEntryAndRetainsTheExpiryClaim() = runBlocking {
        val secrets = FakeSecrets()
        secrets.values[legacyKey] = """{"email":"student@example.test","password":"fixture","autoOpenedFor":"old-session"}"""
        secrets.failDelete = true
        assertFailsWith<IllegalStateException> { secrets.savedSignInStore.load(owner) }
        assertNotNull(secrets.values[legacyKey])
        secrets.failDelete = false
        val restarted = secrets.newStore()
        val migrated = restarted.load(owner).single()
        assertEquals(SignInKind.STUDY, migrated.kind)
        assertEquals(STUDY_SIGN_IN_ORIGIN, migrated.site)
        assertEquals("student@example.test", migrated.username)
        assertEquals("fixture", migrated.password)
        assertNull(secrets.values[legacyKey])
        assertFalse(restarted.claimExpiry("old-session") { true })
        assertEquals(migrated.id, restarted.load(owner).single().id)
    }

    @Test fun chooserUsesOnlyItsKindAndExpiryPrefersTheLastUsedEntry() = runBlocking {
        val vault = FakeSecrets().savedSignInStore
        val first = vault.save(SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, "one@example.test", "fixture one", null)
        val library = vault.save(SignInKind.LIBRARY, LIBRARY_ORIGIN, "12345", "library fixture", null)
        assertEquals(first.id, SavedSignInPopup(vault, this, SignInKind.STUDY, false, "Rejected").selected?.id)
        assertEquals(library.id, SavedSignInPopup(vault, this, SignInKind.LIBRARY, false, "Rejected").selected?.id)

        val second = vault.save(SignInKind.STUDY, "https://login.microsoft.com", "two@example.test", "fixture two", null)
        val popup = SavedSignInPopup(vault, this, SignInKind.STUDY, false, "Rejected")
        assertNull(popup.selected)
        assertFalse(popup.pick(library.id))
        assertTrue(popup.pick(second.id))
        assertFalse(assertNotNull(popup.scriptForPage(STUDY_SIGN_IN_ORIGIN)).contains("fixture two"))
        assertNull(popup.scriptForPage(LIBRARY_ORIGIN))
        assertTrue(assertNotNull(popup.scriptForPage(second.site)).contains("fixture two"))
        vault.markUsed(second)
        vault.markUsed(library)
        assertEquals(second.id, SavedSignInPopup(vault, this, SignInKind.STUDY, true, "Rejected").selected?.id)

        val saved = CompletableDeferred<Boolean>()
        popup.save(STUDY_SIGN_IN_ORIGIN, "added@example.test", "added fixture", "Added", null, saved::complete)
        assertTrue(saved.await())
        assertEquals("added@example.test", popup.selected?.username)
        assertTrue(assertNotNull(popup.scriptForPage(STUDY_SIGN_IN_ORIGIN)).contains("added fixture"))
    }

    @Test fun signOutAndLibraryDisconnectCanKeepDeviceEntriesOrRemoveThem() = runBlocking {
        val job = SupervisorJob(coroutineContext.job)
        val fixtureScope = CoroutineScope(coroutineContext + job)
        val secrets = FakeSecrets()
        val vault = secrets.savedSignInStore
        val session = session(fixtureScope, secrets)
        try {
            val study = vault.save(SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, "student@example.test", "first fixture", null)
            val library = vault.save(SignInKind.LIBRARY, LIBRARY_ORIGIN, "12345", "library fixture", null)
            session.restore(SavedSession(account, "fixture-session"), null, initializeAccount = false)
            session.signOut(removeSavedSignIns = false)
            assertNull(session.repository.value)
            assertEquals(study.id, session.openSavedSignIn(fixtureScope, SignInKind.STUDY, false, "Rejected")?.selected?.id)
            session.disconnectLibrary(preview = false, removeSavedSignIns = false)
            assertTrue(vault.entries.value.any { it.id == library.id })
            session.disconnectLibrary(preview = false)
            assertEquals(listOf(study.id), vault.entries.value.map { it.id })
            val other = Account("fixture-b", "Different Student", "other")
            session.restore(SavedSession(other, "other-session"), null, initializeAccount = false)
            assertEquals(study.id, vault.entries.value.single().id)
            secrets.failWrite = true
            assertFailsWith<IllegalStateException> { session.signOut() }
            assertEquals(other, session.repository.value?.account)
            secrets.failWrite = false
            session.signOut()
            assertNull(session.repository.value)
            assertTrue(secrets.newStore().load().isEmpty())
        } finally { job.cancelAndJoin() }
    }

    @Test fun onlyOneForegroundOnlineOpenPerVerifiedSessionIncludingAfterRelaunch() = runBlocking {
        val job = SupervisorJob(coroutineContext.job)
        val fixtureScope = CoroutineScope(coroutineContext + job)
        val secrets = FakeSecrets()
        var online = false
        fun create() = session(fixtureScope, secrets) { NetworkConditions(connected = online) }
        suspend fun CampusSession.expire() {
            val repo = repository.value!!
            repo.recordError("courses", CampusException(FailureKind.AUTHENTICATION))
            withTimeout(5_000) { repo.sync.first { it.sessionExpired } }
        }

        try {
            val current = SavedSession(account, "fixture-session")
            val first = create()
            first.restore(current, null, initializeAccount = false)
            first.expire()
            assertFalse(first.claimAutomaticSignIn(owner) { true })
            val vault = secrets.savedSignInStore
            vault.save(SignInKind.LIBRARY, LIBRARY_ORIGIN, "12345", "fixture", null)
            online = true
            assertFalse(first.claimAutomaticSignIn(owner) { true })
            val study = vault.save(SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, "student@example.test", "fixture", null)
            online = false
            assertFalse(first.claimAutomaticSignIn(owner) { true })
            online = true
            assertFalse(first.claimAutomaticSignIn(owner) { false })
            assertTrue(first.claimAutomaticSignIn(owner) { true })
            assertFalse(first.claimAutomaticSignIn(owner) { true })
            vault.save(study.kind, study.site, study.username, "updated fixture", null, study.id)
            val relaunched = create()
            relaunched.restore(current, null, initializeAccount = false)
            relaunched.expire()
            assertFalse(relaunched.claimAutomaticSignIn(owner) { true })
            val renewed = create()
            renewed.restore(current.copy(generation = "new-session"), null, initializeAccount = false)
            assertFalse(renewed.claimAutomaticSignIn(owner) { true })
            renewed.expire()
            assertTrue(renewed.claimAutomaticSignIn(owner) { true })
        } finally { job.cancelAndJoin() }
    }

    private fun session(scope: CoroutineScope, secrets: FakeSecrets, network: () -> NetworkConditions = { NetworkConditions() }): CampusSession {
        val cache = MemoryStore()
        val services = CampusServicesRepository(cache, DemoMensaProvider(), scope)
        return CampusSession(scope, { error("Fixture must not contact a server") }, cache, MemoryMaterials(), secrets,
            network, { AppSettings() }, services, services)
    }

    private class FakeSecrets : SessionPlatform {
        val values = mutableMapOf<String, String>()
        var failDelete = false
        var failWrite = false
        fun newStore() = SavedSignInStore(values::get, { key, value ->
            check(!failWrite)
            values[key] = value
        }, { key ->
            check(!failDelete)
            values.remove(key)
        })
        override val savedSignInStore = newStore()
        override suspend fun saveAccount(saved: SavedSession) = Unit
        override suspend fun clearAccount() = Unit
        override suspend fun saveLibrary(session: LibrarySession) = Unit
        override suspend fun clearLibrary() = Unit
    }
}
