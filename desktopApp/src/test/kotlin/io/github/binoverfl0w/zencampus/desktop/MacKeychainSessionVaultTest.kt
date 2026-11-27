package io.github.binoverfl0w.zencampus.desktop

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNull

class MacKeychainSessionVaultTest {
    @Test fun missingItemDoesNotCreateAKeychainEntry() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val vault = MacKeychainSessionVault("test-${UUID.randomUUID()}")
        assertNull(vault.read("missing"))
        vault.clear("missing")
    }
}
