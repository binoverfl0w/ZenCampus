package io.github.binoverfl0w.zencampus.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosSqlCampusStoreTest {
    @Test fun storesAndPurgesAccountData() = runTest {
        val driver = NativeSqliteDriver(CampusDatabase.Schema, ":memory:")
        try {
            val store = SqlCampusStore(CampusDatabase(driver))
            store.put("account-a", "courses", "payload", 123L)
            store.put("account-b", "courses", "other", 456L)
            assertEquals(CacheRecord("courses", "payload", 123L), store.read("account-a", "courses"))
            store.purge("account-a")
            assertNull(store.read("account-a", "courses"))
            assertEquals("other", store.read("account-b", "courses")?.payload)
        } finally {
            driver.close()
        }
    }
}
