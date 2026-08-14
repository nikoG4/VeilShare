package dev.veilshare.core.vault
import kotlin.test.*
import java.io.RandomAccessFile
import java.nio.file.Files
class DesktopBlobStoreTest {
    @Test fun writesOpaqueBlobThroughStreamingHandle() = kotlinx.coroutines.test.runTest { val root=Files.createTempDirectory("veilshare-test-"); val store=DesktopBlobStore(root,"abcd1234"); val w=store.create(); w.write("hello ".encodeToByteArray()); w.write("world".encodeToByteArray()); val id=w.commit(); assertFalse(id.value.contains("hello")); assertEquals("hello world",store.open(id).readAt(0,64).decodeToString()); store.delete(id); assertFalse(store.exists(id)) }

    @Test fun sparseBlobLargerThanTwoGiBStillUsesBoundedReads() = kotlinx.coroutines.test.runTest {
        val root=Files.createTempDirectory("veilshare-large-")
        val namespace="abcd1234"
        val id=dev.veilshare.core.model.BlobId("0123456789abcdef0123456789abcdef")
        val path=root.resolve(namespace).also(Files::createDirectories).resolve("${id.value}.vblob")
        RandomAccessFile(path.toFile(),"rw").use { file ->
            file.setLength(Int.MAX_VALUE.toLong()+4096L)
            file.seek(0)
            file.write(byteArrayOf(1,2,3,4))
        }
        val handle=DesktopBlobStore(root,namespace).open(id)
        try { assertContentEquals(byteArrayOf(1,2,3,4,0,0,0,0),handle.readAt(0,8)) }
        finally { handle.close() }
    }
}
