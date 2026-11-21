package dev.veilshare.core.vault

import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * Restores an authenticated backup into an isolated private stage. Nothing is
 * installed until BOTH independent vault credentials have opened successfully.
 * The caller must obtain the credentials privately and must not persist them.
 */
internal object AndroidVaultRestoreVerifier {
    suspend fun verifyStage(
        stage: File,
        primaryCredential: CharArray,
        decoyCredential: CharArray,
    ) {
        require(stage.isDirectory && !Files.isSymbolicLink(stage.toPath()))
        require(primaryCredential.isNotEmpty() && decoyCredential.isNotEmpty())
        require(!primaryCredential.contentEquals(decoyCredential))
        val service = AndroidLocalVaultService(stage)
        check(service.storageState() == LocalStorageState.READY) {
            "Backup must contain both vault slots"
        }
        val primary = service.unlock(primaryCredential)
        check(primary is LocalUnlockResult.Ready) { "Primary credential did not authenticate" }
        try {
            val decoy = service.unlock(decoyCredential)
            check(decoy is LocalUnlockResult.Ready) { "Decoy credential did not authenticate" }
            try {
                check(primary.personaId != null && decoy.personaId != null &&
                    primary.personaId != decoy.personaId) {
                    "Both credentials must open distinct vaults"
                }
            } finally {
                decoy.vault.close()
            }
        } finally {
            primary.vault.close()
        }
    }

    /**
     * Import is strictly stage-only. The UI/SAF coordinator must ensure no live
     * vault session exists, and must separately implement no-clobber promotion.
     * A failed verification never touches the installed vault root.
     */
    suspend fun importAndVerify(
        source: InputStream,
        stage: File,
        recoveryKey: ByteArray,
        primaryCredential: CharArray,
        decoyCredential: CharArray,
    ) {
        require(stage.isDirectory && stage.list()?.isEmpty() == true)
        try {
            AndroidVaultBackupCodec.importToStage(source, stage, recoveryKey)
            verifyStage(stage, primaryCredential, decoyCredential)
        } catch (failure: Exception) {
            // Only delete contents inside the caller-owned, isolated stage.
            // Never use this method with the installed vault root.
            stage.deleteRecursively()
            throw failure
        }
    }
}
