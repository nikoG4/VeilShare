package dev.veilshare.core.crypto

import java.security.SecureRandom as JcaSecureRandom
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters as BcArgon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.HKDFParameters

/** Same Bouncy Castle raw Argon2id derivation as the Desktop adapter; Android device verification remains required. */
class AndroidSecureRandom : SecureRandom { private val delegate=JcaSecureRandom(); override fun bytes(size:Int)=ByteArray(size).also(delegate::nextBytes) }
class AndroidArgon2idPasswordKdf : PasswordKdf { override suspend fun derive(secret:SensitiveChars,salt:Salt,policy:Argon2Policy):KeyEncryptionKey { val password=secret.copy().concatToString().encodeToByteArray(); try { val p=policy.parameters; val params=BcArgon2Parameters.Builder(BcArgon2Parameters.ARGON2_id).withVersion(BcArgon2Parameters.ARGON2_VERSION_13).withSalt(salt.bytes).withMemoryAsKB(p.memoryKiB).withIterations(p.iterations).withParallelism(p.parallelism).build(); return KeyEncryptionKey(SensitiveBytes(ByteArray(policy.outputBytes).also{Argon2BytesGenerator().apply{init(params)}.generateBytes(password,it)})) } finally { password.fill(0) } } }
class AndroidChaCha20Poly1305Cipher(private val random:SecureRandom=AndroidSecureRandom()):AuthenticatedCipher { override suspend fun seal(key:SensitiveBytes,plaintext:ByteArray,aad:ByteArray):SealedBytes { val nonce=Nonce(random.bytes(12)); return sealWithNonce(key,nonce,plaintext,aad) }; override suspend fun sealWithNonce(key:SensitiveBytes,nonce:Nonce,plaintext:ByteArray,aad:ByteArray):SealedBytes { require(nonce.bytes.size==12); return SealedBytes(nonce,crypt(true,key.copy(),nonce.bytes,plaintext,aad)) }; override suspend fun open(key:SensitiveBytes,sealed:SealedBytes,aad:ByteArray):ByteArray { require(sealed.nonce.bytes.size==12); return crypt(false,key.copy(),sealed.nonce.bytes,sealed.ciphertext,aad) }; private fun crypt(encrypt:Boolean,key:ByteArray,nonce:ByteArray,input:ByteArray,aad:ByteArray):ByteArray { require(key.size==32); val c=ChaCha20Poly1305(); c.init(encrypt,AEADParameters(KeyParameter(key),128,nonce,aad)); val out=ByteArray(c.getOutputSize(input.size)); try { val n=c.processBytes(input,0,input.size,out,0); return out.copyOf(n+c.doFinal(out,n)) } catch(e:InvalidCipherTextException){throw SecurityException("AEAD authentication failed",e)} finally {key.fill(0)} } }
class AndroidHkdfSha256KeyDeriver : KeyDeriver {
    override suspend fun derive(ikm: SensitiveBytes, context: ByteArray, outputBytes: Int): SensitiveBytes {
        require(outputBytes in 1..255 * 32)
        val material=ikm.copy(); val salt="VEIL/V1/HKDF-SALT".encodeToByteArray()
        try { return SensitiveBytes(ByteArray(outputBytes).also { HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(material,salt,context)) }.generateBytes(it,0,outputBytes) }) }
        finally { material.fill(0); salt.fill(0) }
    }
}
object AndroidProductionCrypto {
    fun create() = ProductionCryptoComponents(AndroidArgon2idPasswordKdf(), AndroidChaCha20Poly1305Cipher(), AndroidSecureRandom())
    fun keyDeriver(): KeyDeriver = AndroidHkdfSha256KeyDeriver()
}
