package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.io.path.*
import kotlin.test.*

private const val INTEROP_REAL_PIN = "510001"
private const val INTEROP_DECOY_PIN = "510002"

/** Generates the checked synthetic fixture only when VEIL_DESKTOP_FIXTURE_OUT is set; otherwise verifies it. */
class DesktopInteropFixtureTest {
    @Test fun desktopProductionFixtureIsReadableWithoutReserialization() = runTest {
        val configured = System.getenv("VEIL_DESKTOP_FIXTURE_OUT")
        val fixture = configured?.let(Path::of) ?: Path.of("src/androidInstrumentedTest/assets/desktop-produced-vault.zip")
        if (configured != null) generateDesktopFixture(fixture)
        assertTrue(fixture.isRegularFile(), "Missing Desktop-produced interop fixture: $fixture")
        verifyFixtureOnDesktop(fixture, expectAndroidMutation = false)
    }

    @Test fun androidProducedFixtureOpensOnDesktopWhenMaterialized() = runTest {
        val fixture = Path.of("src/desktopTest/resources/android-produced-vault.zip")
        assertTrue(fixture.isRegularFile(), "Generate the Android production fixture on the emulator first")
        verifyFixtureOnDesktop(fixture, expectAndroidMutation = false)
    }

    @Test fun desktopAndroidDesktopMutationRoundTripIsReadable() = runTest {
        val fixture = Path.of("src/desktopTest/resources/android-mutated-desktop-vault.zip")
        assertTrue(fixture.isRegularFile(), "Generate the Android mutation fixture on the emulator first")
        verifyFixtureOnDesktop(fixture, expectAndroidMutation = true)
    }
}

private suspend fun generateDesktopFixture(output: Path) {
    val root=Files.createTempDirectory("desktop-interop-")
    val crypto=DesktopProductionCrypto.create();val policy=Argon2Policy(Argon2Parameters(8192,1,1));val wrapper=InteropDesktopWrapper(crypto.cipher)
    CreateVaultSetUseCase(DesktopVaultSlotStore(root),crypto.random,crypto.passwordKdf,wrapper,crypto.cipher,policy,DesktopVaultCatalogBootstrap(root,DesktopProductionCrypto.keyDeriver(),crypto.cipher)).create(SensitiveChars(INTEROP_REAL_PIN.toCharArray()),SensitiveChars(INTEROP_DECOY_PIN.toCharArray()))
    val real=openDesktop(root,INTEROP_REAL_PIN,policy);val realA="DESKTOP_A_CONTENT_741".encodeToByteArray();val realB=ByteArray(VeilCryptoSuites.v1.chunkBytes+37){(it*17+3).toByte()}
    val importer=ImportCoordinator(real.crypto.random,real.crypto.cipher,real.blobs,real.catalogStore,real.journal,FileKeyWrapping(DesktopProductionCrypto.keyDeriver(),real.crypto.cipher))
    val (one,_)=importer.import(real.session,real.snapshot,InteropDesktopSource("DESKTOP_A.txt",realA));val (two,_)=importer.import(real.session,one,InteropDesktopSource("DESKTOP_B.bin",realB));real.snapshot=two
    val realNs=real.session.descriptor.blobNamespace;real.close()
    val decoy=openDesktop(root,INTEROP_DECOY_PIN,policy);val decoyBytes="DESKTOP_DECOY_CONTENT_742".encodeToByteArray();ImportCoordinator(decoy.crypto.random,decoy.crypto.cipher,decoy.blobs,decoy.catalogStore,decoy.journal,FileKeyWrapping(DesktopProductionCrypto.keyDeriver(),decoy.crypto.cipher)).import(decoy.session,decoy.snapshot,InteropDesktopSource("DESKTOP_D.txt",decoyBytes));val decoyNs=decoy.session.descriptor.blobNamespace;decoy.close()
    val manifest=Properties().apply { setProperty("producer","desktop");setProperty("real.pin",INTEROP_REAL_PIN);setProperty("decoy.pin",INTEROP_DECOY_PIN);setProperty("real.namespace",realNs);setProperty("decoy.namespace",decoyNs);setProperty("DESKTOP_A.txt.sha256",sha256(realA));setProperty("DESKTOP_B.bin.sha256",sha256(realB));setProperty("DESKTOP_D.txt.sha256",sha256(decoyBytes)) };recordArtifactHashes(root,manifest)
    output.parent?.createDirectories();writeFixture(root,output,manifest)
}

private suspend fun verifyFixtureOnDesktop(fixture:Path,expectAndroidMutation:Boolean){
    val unpacked=Files.createTempDirectory("interop-consume-");val manifest=unpackFixture(fixture,unpacked);verifyArtifactHashes(unpacked,manifest);val policy=Argon2Policy(Argon2Parameters(8192,1,1))
    val androidProduced=manifest.getProperty("producer")=="android"
    val realNames=if(androidProduced)listOf("ANDROID_A.txt","ANDROID_B.bin") else listOf("DESKTOP_A.txt","DESKTOP_B.bin")+(if(expectAndroidMutation)listOf("ANDROID_C.txt")else emptyList())
    val real=openDesktop(unpacked,manifest.getProperty("real.pin"),policy);assertEquals(manifest.getProperty("real.namespace"),real.session.descriptor.blobNamespace);verifyEntries(real,manifest,realNames);real.close()
    val decoy=openDesktop(unpacked,manifest.getProperty("decoy.pin"),policy);assertEquals(manifest.getProperty("decoy.namespace"),decoy.session.descriptor.blobNamespace);verifyEntries(decoy,manifest,listOf(if(androidProduced)"ANDROID_D.txt" else "DESKTOP_D.txt"));decoy.close()
}

private suspend fun verifyEntries(runtime:DesktopInteropRuntime,manifest:Properties,names:List<String>){names.forEach{name->val entry=runtime.snapshot.entries.filterIsInstance<VaultItem.File>().single{it.displayName==name};val digest=MessageDigest.getInstance("SHA-256");runtime.read(entry){digest.update(it)};assertEquals(manifest.getProperty("$name.sha256"),digest.digest().toHex())}}

private suspend fun openDesktop(root:Path,pin:String,policy:Argon2Policy):DesktopInteropRuntime{val crypto=DesktopProductionCrypto.create();val opened=assertIs<DesktopOpenResult.Ready>(DesktopVaultRepository(root,UnlockVaultUseCase(DesktopVaultSlotStore(root),crypto.passwordKdf,InteropDesktopWrapper(crypto.cipher),crypto.cipher,policy),DesktopProductionCrypto.keyDeriver(),crypto.cipher).open(SensitiveChars(pin.toCharArray())));return DesktopInteropRuntime(crypto,opened.session,opened.catalog,DesktopEncryptedCatalogStore(root,opened.session.descriptor.vaultId,CatalogCrypto(DesktopProductionCrypto.keyDeriver(),crypto.cipher)),DesktopVaultJournal(root),DesktopBlobStore(root.resolve("blobs"),opened.session.descriptor.blobNamespace))}
private data class DesktopInteropRuntime(val crypto:ProductionCryptoComponents,val session:VaultSession,var snapshot:CatalogSnapshot,val catalogStore:DesktopEncryptedCatalogStore,val journal:DesktopVaultJournal,val blobs:DesktopBlobStore){suspend fun read(file:VaultItem.File,consume:suspend(ByteArray)->Unit){val key=FileKeyWrapping(DesktopProductionCrypto.keyDeriver(),crypto.cipher).unwrap(session.key(),requireNotNull(file.wrappedFileKey),session.descriptor.vaultId,file.id,file.blobId);val handle=blobs.open(file.blobId);try{EncryptedBlobReader(crypto.cipher).read(handle,key,file.id.value.encodeToByteArray(),consume)}finally{handle.close();key.material.close()}};fun close()=session.close()}
private class InteropDesktopSource(override val displayName:String,private val bytes:ByteArray):ImportSource{override val mimeHint:String?="application/octet-stream";override val sizeHint=bytes.size.toLong();override suspend fun openRead()=object:ImportReadHandle{var offset=0;override suspend fun read(maxBytes:Int):ByteArray{if(offset>=bytes.size)return ByteArray(0);val end=minOf(bytes.size,offset+maxBytes);return bytes.copyOfRange(offset,end).also{offset=end}};override suspend fun close()=Unit}}
private class InteropDesktopWrapper(private val cipher:AuthenticatedCipher):KeyWrapper{override suspend fun wrap(kek:KeyEncryptionKey,vaultKey:VaultKey,aad:ByteArray)=cipher.seal(kek.material,vaultKey.material.copy(),aad);override suspend fun unwrap(kek:KeyEncryptionKey,wrapped:SealedBytes,aad:ByteArray)=VaultKey(SensitiveBytes(cipher.open(kek.material,wrapped,aad)))}

private fun writeFixture(root:Path,output:Path,manifest:Properties){ZipOutputStream(output.outputStream()).use{zip->val manifestBytes=java.io.ByteArrayOutputStream().also{manifest.store(it,"VeilShare synthetic cross-platform fixture")}.toByteArray();zip.putNextEntry(ZipEntry("manifest.properties"));zip.write(manifestBytes);zip.closeEntry();Files.walk(root).use{paths->paths.filter{it.isRegularFile()}.sorted().forEach{path->zip.putNextEntry(ZipEntry("root/${root.relativize(path).invariantSeparatorsPathString}"));path.inputStream().use{it.copyTo(zip)};zip.closeEntry()}}}}
private fun unpackFixture(zipPath:Path,target:Path):Properties{val props=Properties();ZipFile(zipPath.toFile()).use{zip->zip.entries().asSequence().forEach{entry->if(entry.name=="manifest.properties")zip.getInputStream(entry).use(props::load)else if(entry.name.startsWith("root/")&&!entry.isDirectory){val relative=entry.name.removePrefix("root/");require(!relative.contains(".."));val out=target.resolve(relative).normalize();require(out.startsWith(target));out.parent.createDirectories();zip.getInputStream(entry).use{input->out.outputStream().use(input::copyTo)}}}};return props}
private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
private fun ByteArray.toHex()=joinToString(""){it.toUByte().toString(16).padStart(2,'0')}
private fun recordArtifactHashes(root:Path,properties:Properties){properties.stringPropertyNames().filter{it.startsWith("artifact.")}.forEach(properties::remove);Files.walk(root).use{paths->paths.filter{it.isRegularFile()}.forEach{path->properties.setProperty("artifact.${root.relativize(path).invariantSeparatorsPathString}.sha256",sha256(Files.readAllBytes(path)))}}}
private fun verifyArtifactHashes(root:Path,properties:Properties){properties.stringPropertyNames().filter{it.startsWith("artifact.")&&it.endsWith(".sha256")}.forEach{key->val relative=key.removePrefix("artifact.").removeSuffix(".sha256");assertEquals(properties.getProperty(key),sha256(Files.readAllBytes(root.resolve(relative))),relative)}}
