package dev.veilshare.core.vault

import android.content.Context
import java.io.File

/** Authoritative vault root under app-private, no-backup storage. */
object AndroidVaultStorage {
    fun privateRoot(context: Context): File = File(context.noBackupFilesDir, "v-8e61c4a0").also {
        check(it.mkdirs() || it.isDirectory)
    }
}
