package dev.veilshare.core.vault
import kotlin.test.*
class CatalogTest { @Test fun rejectsCyclesAndPathLikeNames() = kotlinx.coroutines.test.runTest { val c=InMemoryVaultCatalog(); val a=c.createDirectory(null,VaultItemId("a"),"A"); val b=c.createDirectory(VaultDirectoryId("a"),VaultItemId("b"),"B"); assertFailsWith<IllegalArgumentException>{ c.move(a.id,VaultDirectoryId("b")) }; assertFailsWith<IllegalArgumentException>{ c.createDirectory(null,VaultItemId("x"),"../secret") } } }
