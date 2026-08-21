package dev.veilshare.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharingProtocolTest {
    @Test fun envelopeRejectsUnsupportedVersion() {
        assertFailsWith<IllegalArgumentException> {
            SignalingEnvelope(
                protocolVersion = 2,
                messageId = MessageId("m"),
                type = MessageType.PING,
            )
        }
    }

    @Test fun envelopeRejectsOversizedPayload() {
        assertFailsWith<IllegalArgumentException> {
            SignalingEnvelope(
                protocolVersion = SharingProtocol.VERSION,
                messageId = MessageId("m"),
                type = MessageType.RELAY,
                payload = ByteArray(SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES + 1),
            )
        }
    }

    @Test fun relayCarriesOpaquePayloadOnly() {
        val relay = RelayRequest(
            toReferenceCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH"),
            sessionId = SessionId("session"),
            opaquePayload = byteArrayOf(1, 2, 3),
        )

        assertEquals(3, relay.opaquePayload.size)
    }

    @Test fun lookupResponseValidatesFoundShape() {
        assertFailsWith<IllegalArgumentException> {
            LookupResponse(status = LookupStatus.FOUND)
        }
        assertFailsWith<IllegalArgumentException> {
            LookupResponse(
                status = LookupStatus.NOT_FOUND,
                sharingIdentityId = SharingIdentityId("identity"),
                sharingPublicKey = "key",
            )
        }
    }

    @Test fun peerEnvelopeRejectsUnsupportedVersionAndOversize() {
        assertFailsWith<IllegalArgumentException> {
            PeerEnvelope(
                protocolVersion = 3,
                messageType = PeerMessageType.SESSION_HELLO,
                sessionId = SessionId("session"),
                transferId = TransferId("transfer"),
                payload = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PeerEnvelope(
                protocolVersion = SharingProtocol.VERSION,
                messageType = PeerMessageType.DATA,
                sessionId = SessionId("session"),
                transferId = TransferId("transfer"),
                payload = ByteArray(SharingProtocol.MAX_PEER_PAYLOAD_BYTES + 1),
            )
        }
    }
}
