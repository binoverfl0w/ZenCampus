package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageRepositoryTest {
    @Test fun cachesInboxForTheSameAccountAndClearsItOnPermissionLossAndSignOut() = runTest {
        val account = Account("messages", "Student", "messages")
        val store = MemoryStore()
        var failure: FailureKind? = null
        var calls = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun messages(userId: String, sent: Boolean): List<CampusMessage> {
                calls++
                failure?.let { throw CampusException(it) }
                return listOf(CampusMessage("one", "Hello", "Body", 1, false, "Alice"))
            }
        }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        repo.refreshMessages(); runCurrent()
        assertEquals("Hello", repo.snapshot.value.messages["inbox"]?.single()?.subject)
        repo.refreshMessages(); assertEquals(1, calls)
        failure = FailureKind.PERMISSION
        repo.refreshMessages(force = true); runCurrent()
        assertNull(repo.snapshot.value.messages["inbox"])
        assertEquals(FailureKind.PERMISSION, repo.sync.value.errors["messages:inbox"])
        failure = null
        repo.refreshMessages(force = true); runCurrent()
        assertEquals(1, repo.snapshot.value.messages["inbox"]?.size)
        repo.close(true)
        assertNull(store.read(account.cacheScope, "messages:inbox"))
    }
    @Test fun uncertainMessageSendSurvivesRestartAndPreventsDuplicatePost() = runTest {
        val account = Account("messages", "Student", "messages")
        val store = MemoryStore(); var calls = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun sendMessage(recipientId: String, subject: String, body: String): WriteResult<Unit> {
                calls++; return WriteResult.Uncertain
            }
        }
        val recipient = CampusPerson("u1", "Alice")
        val first = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        first.sendMessage(recipient, "Question", "Body", "draft-1"); runCurrent()
        assertEquals(WriteStatus.UNCERTAIN, first.snapshot.value.messageWrite?.status)
        first.close(false)
        val restored = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        runCurrent(); restored.sendMessage(recipient, "Question", "Body", "draft-2"); runCurrent()
        assertEquals(1, calls)
        assertEquals(WriteStatus.UNCERTAIN, restored.snapshot.value.messageWrite?.status)
        restored.acknowledgeWrite("write:message")
        restored.sendMessage(recipient, "Question", "Body", "draft-3"); runCurrent()
        assertEquals(2, calls)
        restored.close(true)
        assertNull(store.read(account.cacheScope, "write:message"))
    }

}
