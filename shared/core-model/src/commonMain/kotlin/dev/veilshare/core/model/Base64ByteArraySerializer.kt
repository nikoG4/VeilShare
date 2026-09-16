package dev.veilshare.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Canonical compact encoding for opaque byte fields carried through signaling JSON. */
@OptIn(ExperimentalEncodingApi::class)
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("VeilShareBase64ByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.Default.encode(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.Default.decode(decoder.decodeString())
    }
}
