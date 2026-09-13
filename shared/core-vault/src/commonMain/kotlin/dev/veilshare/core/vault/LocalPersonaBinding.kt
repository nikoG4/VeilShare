package dev.veilshare.core.vault

import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.LocalPersonaId
import dev.veilshare.core.model.VaultId

private const val LOCAL_PERSONA_DOMAIN = "VEILSHARE/LOCAL-PERSONA-BINDING/V1/"

/**
 * Produces a stable local-only lookup token for an authenticated vault persona.
 *
 * This value is deliberately NOT a SharingContextId and MUST NOT be sent to signaling or
 * peers. The sharing layer maps it to a separately generated random SharingContextId in
 * protected state. Domain separation prevents accidental reuse as a generic VaultId hash.
 */
internal fun localPersonaIdFor(vaultId: VaultId): LocalPersonaId {
    val input = (LOCAL_PERSONA_DOMAIN + vaultId.value).encodeToByteArray()
    return try {
        LocalPersonaId(Hash.sha256(input).toHex())
    } finally {
        input.fill(0)
    }
}
