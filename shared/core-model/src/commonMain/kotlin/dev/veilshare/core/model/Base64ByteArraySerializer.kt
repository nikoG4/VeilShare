package dev.veilshare.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Canonical string encoding for opaque byte fields carried through the JSON signaling stack.
 *
 * Plain kotlinx.serialization JSON encodes ByteArray as a numeric array. VeilShare nests
 * opaque payloads several levels deep (TransferData -> PeerEnvelope -> RelayRequest ->
 * SignalingEnvelope), so numeric-array encoding amplifies payload size at every layer.
 * Base64 keeps the wire size bounded and predictable across KMP targets.
 */
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
