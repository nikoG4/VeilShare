package dev.veilshare.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopOwnedPlaintextCacheTest {
    @Test fun startupAndLockCleanupStayInsideDedicatedOpaqueRoot() {
        val parent=Files.createTempDirectory("desktop-cache-policy-")
        val unrelated=Files.writeString(parent.resolve("unrelated.txt"),"keep")
        val root=parent.resolve("owned")
        Files.createDirectories(root.resolve("nested"))
        Files.writeString(root.resolve("nested").resolve("stale.txt"),"plaintext")

        val cache=DesktopOwnedPlaintextCache(root)
        assertTrue(Files.exists(unrelated))
        assertTrue(Files.list(root).use { !it.findAny().isPresent })

        val opened=cache.create(".txt")
        assertTrue(Files.exists(opened))
        assertFalse(opened.fileName.toString().contains("secret",ignoreCase=true))
        assertTrue(cache.cleanup())
        assertFalse(Files.exists(opened))
        assertTrue(Files.exists(unrelated))
    }
}
