package io.github.binoverfl0w.zencampus.model

import kotlin.test.*

class StudyUpdatesTest {
    @Test fun firstSuccessfulSourceIsBaselineAndFailuresPreserveItsPreviousBaseline() {
        val first = updateNotificationDelta(emptyMap(), mapOf("inbox" to setOf("a")))
        assertEquals(0, first.newCount)
        val failed = updateNotificationDelta(first.checkpoints, emptyMap())
        assertEquals(setOf("a"), failed.checkpoints["inbox"])
        val later = updateNotificationDelta(failed.checkpoints, mapOf("inbox" to setOf("a", "b"), "new-course" to setOf("old")))
        assertEquals(1, later.newCount)
        assertEquals(setOf("old"), later.checkpoints["new-course"])
    }
    @Test fun staleOrFailedSourcesCannotGenerateNotifications() {
        val account = Account("a", "Student", "a")
        val now = 1_000_000L
        val snapshot = CampusSnapshot(account, messages = mapOf("inbox" to listOf(CampusMessage("m", "Subject", "", now - 1, false))), updatedAt = mapOf("messages:inbox" to now - 1))
        assertEquals(1, notificationSources(snapshot, emptyMap(), now)["messages:inbox"]?.size)
        assertFalse(notificationSources(snapshot, mapOf("messages:inbox" to FailureKind.OFFLINE), now).containsKey("messages:inbox"))
        assertFalse(notificationSources(snapshot, emptyMap(), now + 5 * 60_000L).containsKey("messages:inbox"))
    }
}
