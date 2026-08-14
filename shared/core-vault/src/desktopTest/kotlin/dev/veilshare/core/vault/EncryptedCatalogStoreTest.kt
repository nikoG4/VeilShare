package dev.veilshare.core.vault
import dev.veilshare.core.crypto.*
import dev.veilshare.core.model.VaultId
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*
class EncryptedCatalogStoreTest { @Test fun encryptedEmptyCatalogReopensWithoutPlaintext()=runTest { val root=Files.createTempDirectory("veil-catalog-"); val p=DesktopProductionCrypto.create(); val key=VaultKey(SensitiveBytes(ByteArray(32){it.toByte()})); val store=DesktopEncryptedCatalogStore(root,VaultId("0123456789abcdef"),CatalogCrypto(DesktopProductionCrypto.keyDeriver(),p.cipher)); store.createEmpty(key); assertTrue(store.exists()); assertEquals(CatalogSnapshot(),DesktopEncryptedCatalogStore(root,VaultId("0123456789abcdef"),CatalogCrypto(DesktopProductionCrypto.keyDeriver(),p.cipher)).load(key)); assertFalse(Files.readAllBytes(root.resolve("catalogs").toFile().listFiles()!!.single().toPath()).containsSequence("CatalogSnapshot".encodeToByteArray())); key.material.close() }; private fun ByteArray.containsSequence(n:ByteArray)=indices.any { i -> i+n.size<=size && n.indices.all { this[i+it]==n[it] } } }
