package dev.veilshare.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentifiersTest {
    @Test fun identifiersRejectBlankValues() {
        assertFailsWith<IllegalArgumentException> { VaultId(" ") }
    }

    @Test fun formatVersionsAreExplicit() {
        assertEquals(1, FormatVersions.VAULT)
        assertEquals(1, FormatVersions.SHARING)
    }

    @Test fun referenceCodeNormalizesHumanInput() {
        val parsed = ReferenceCodes.parse("2345 6789 abcd efgh")

        assertEquals("2345-6789-ABCD-EFGH", parsed.value)
    }

    @Test fun referenceCodeRejectsMalformedInput() {
        assertFailsWith<IllegalArgumentException> { ReferenceCodes.parse("short") }
        assertFailsWith<IllegalArgumentException> { ReferenceCodes.parse("2345-6789-ABCD-EFGI") }
        assertFailsWith<IllegalArgumentException> { ReferenceCode("23456789ABCDEFGH") }
    }

    @Test fun referenceCodeGenerationUsesInjectedEntropy() {
        val first = ReferenceCodes.generate(CountingEntropy(0))
        val second = ReferenceCodes.generate(CountingEntropy(1))

        assertEquals("222J-62S6-2N52-G42B", first.value)
        kotlin.test.assertNotEquals(first, second)
    }

    @Test fun opaqueIdsUseIndependentTypes() {
        val entropy = CountingEntropy(7)

        assertEquals(32, OpaqueIds.sessionId(entropy).value.length)
        assertEquals(32, OpaqueIds.transferId(entropy).value.length)
        assertEquals(32, OpaqueIds.messageId(entropy).value.length)
        assertEquals(32, OpaqueIds.connectionId(entropy).value.length)
        assertEquals(32, OpaqueIds.sharingIdentityId(entropy).value.length)
    }
}

class CountingEntropy(seed: Int) : RandomBytesSource {
    private var next = seed

    override fun nextBytes(size: Int): ByteArray =
        ByteArray(size) { (next++ and 0xff).toByte() }
}
