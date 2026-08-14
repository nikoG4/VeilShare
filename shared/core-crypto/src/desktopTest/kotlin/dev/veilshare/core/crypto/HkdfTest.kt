package dev.veilshare.core.crypto
import kotlinx.coroutines.test.runTest
import kotlin.test.*
class HkdfTest { @Test fun contextsProduceDifferentCatalogAndWrapKeys()=runTest { val ikm=SensitiveBytes(ByteArray(32){it.toByte()}); val d=JvmHkdfSha256KeyDeriver(); val catalog=d.derive(ikm,CryptoContexts.Catalog); val wrap=d.derive(ikm,CryptoContexts.FileKeyWrap); assertFalse(catalog.copy().contentEquals(wrap.copy())); catalog.close(); wrap.close(); ikm.close() } }
