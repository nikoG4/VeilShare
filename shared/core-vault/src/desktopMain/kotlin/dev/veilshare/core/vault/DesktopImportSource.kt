package dev.veilshare.core.vault

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class DesktopImportSource(private val path:Path, override val mimeHint:String?=Files.probeContentType(path)) : ImportSource {
    override val displayName:String get()=path.fileName.toString(); override val sizeHint:Long? get()=Files.size(path)
    override suspend fun openRead():ImportReadHandle { val stream=Files.newInputStream(path,StandardOpenOption.READ); return object:ImportReadHandle { override suspend fun read(maxBytes:Int):ByteArray { val b=ByteArray(maxBytes); val n=stream.read(b); return if(n<0) ByteArray(0) else b.copyOf(n) }; override suspend fun close(){stream.close()} } }
}
