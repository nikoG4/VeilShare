package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.ContactId
import dev.veilshare.core.model.Fingerprint
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.securestore.AeadStateProtector
import dev.veilshare.core.securestore.InMemoryAtomicStateStorage
import dev.veilshare.core.securestore.ProtectedStateStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PersistentContactUniquenessTest {
    @Test
    fun `store rejects duplicate identity ownership`() = runTest {
        val fixture = fixture()
        try {
            val first = contact("contact-a", "same-identity", "2345-6789-ABCD-EFGH", 1)
            val second = contact("contact-b", "same-identity", "2345-6789-ABCD-EFGJ", 2)
            fixture.store.replace(first)
            assertFailsWith<IllegalArgumentException> { fixture.store.replace(second) }
        } finally {
            fixture.protector.close()
        }
    }

    @Test
    fun `store rejects duplicate reference code ownership`() = runTest {
        val fixture = fixture()
        try {
            val first = contact("contact-a", "identity-a", "2345-6789-ABCD-EFGK", 3)
            val second = contact("contact-b", "identity-b", "2345-6789-ABCD-EFGK", 4)
            fixture.store.replace(first)
            assertFailsWith<IllegalArgumentException> { fixture.store.replace(second) }
        } finally {
            fixture.protector.close()
        }
    }

    private fun fixture(): Fixture {
        val crypto = DesktopProductionCrypto.create()
        val protector = AeadStateProtector(
            crypto.cipher,
            SensitiveBytes(ByteArray(32) { index -> (index * 3 + 29).toByte() }),
        )
        val protected = ProtectedStateStore(InMemoryAtomicStateStorage(), protector)
        return Fixture(PersistentTrustedContactStore(protected), protector)
    }

    private fun contact(
        contactId: String,
        identityId: String,
        route: String,
        marker: Int,
    ): TrustedContact {
        val key = ByteArray(32) { index -> (marker * 31 + index).toByte() }
        return TrustedContact(
            contactId = ContactId(contactId),
            alias = "Alias $marker",
            sharingIdentityId = SharingIdentityId(identityId),
            pinnedPublicKey = key,
            fingerprint = Fingerprint(Hash.sha256(key).toHex()),
            verificationMethod = ContactVerificationMethod.QR_CODE,
            referenceCode = ReferenceCodes.parse(route),
        )
    }

    private data class Fixture(
        val store: PersistentTrustedContactStore,
        val protector: AeadStateProtector,
    )
}
