package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import dev.veilshare.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FileKeyWrappingTest { @Test fun `wrap binds vault item and blob context`()=runTest { val c=DesktopProductionCrypto.create(); val vmk=VaultKeyGenerator(c.random).generate(); val fk=FileKeyGenerator(c.random).generate(); val wrap=FileKeyWrapping(DesktopProductionCrypto.keyDeriver(),c.cipher); val v=VaultId("v");val i=VaultItemId("i");val b=BlobId("b");val sealed=wrap.wrap(vmk,fk,v,i,b); assertEquals(fk.material.copy().toList(),wrap.unwrap(vmk,sealed,v,i,b).material.copy().toList()); expectFail { wrap.unwrap(vmk,sealed,VaultId("other"),i,b) }; expectFail { wrap.unwrap(vmk,sealed,v,VaultItemId("other"),b) }; expectFail { wrap.unwrap(vmk,sealed,v,i,BlobId("other")) }; val bad=sealed.copy(ciphertext=sealed.ciphertext.copyOf().also{it[0]=(it[0].toInt() xor 1).toByte()}); expectFail { wrap.unwrap(vmk,bad,v,i,b) }; fk.material.close();vmk.material.close() } }
private suspend fun expectFail(block:suspend()->Unit) { var failed=false;try{block()}catch(_:Exception){failed=true};assertTrue(failed) }
