package dev.veilshare.core.model
import kotlin.test.Test
import kotlin.test.assertFailsWith
class IdentifiersTest { @Test fun identifiersRejectBlankValues() { assertFailsWith<IllegalArgumentException> { VaultId(" ") } }; @Test fun formatVersionsAreExplicit() { kotlin.test.assertEquals(1, FormatVersions.VAULT) } }
