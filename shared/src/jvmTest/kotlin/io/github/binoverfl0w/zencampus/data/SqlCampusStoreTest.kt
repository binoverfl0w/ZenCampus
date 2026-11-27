package io.github.binoverfl0w.zencampus.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SqlCampusStoreTest {
    @Test fun sqlitePersistsUpsertsAndDeletesOnlyTheRequestedAccount() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CampusDatabase.Schema.create(driver)
            val store = SqlCampusStore(CampusDatabase(driver), Dispatchers.Unconfined)
            store.put("alice", "courses", "first", 100)
            store.put("bob", "courses", "other", 100)
            store.put("alice", "courses", "updated", 200)
            assertEquals("updated", store.read("alice", "courses")?.payload)
            assertEquals(200, store.observe("alice").first().single().updatedAt)
            store.purge("alice")
            assertTrue(store.observe("alice").first().isEmpty())
            assertEquals("other", store.read("bob", "courses")?.payload)
        } finally { driver.close() }
    }
}
