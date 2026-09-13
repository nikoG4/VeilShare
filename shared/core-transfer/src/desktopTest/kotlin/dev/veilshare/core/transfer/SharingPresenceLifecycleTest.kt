package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.identity.InMemorySharingIdentityStore
import dev.veilshare.core.identity.InMemorySharingPresenceStore
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharingPresenceLifecycleTest {
    @Test
    fun `rotation revokes old identity routes before registering replacement`() = runTest {
        val fixture = fixture()
        val context = SharingContextId("ctx-rotate")
        val first = fixture.lifecycle.ensureRegistered(context)
        fixture.client.events.clear()

        val rotated = fixture.lifecycle.rotateAndRegister(context)

        assertEquals(first.identity.identityId, rotated.identity.identityId)
        assertNotEquals(first.presence.referenceCode, rotated.presence.referenceCode)
        assertEquals(
            listOf(
                "unregister:${first.identity.identityId.value}",
                "register:${rotated.presence.referenceCode.value}",
            ),
            fixture.client.events,
        )
    }

    @Test
    fun `failed unregister leaves local reference code unchanged`() = runTest {
        val fixture = fixture()
        val context = SharingContextId("ctx-unregister-fail")
        val first = fixture.lifecycle.ensureRegistered(context)
        fixture.client.failUnregister = true

        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.rotateAndRegister(context)
        }

        val stillCurrent = fixture.presence.getOrCreate(context)
        assertEquals(first.presence.referenceCode, stillCurrent.referenceCode)
    }

    @Test
    fun `failed replacement register leaves old route revoked and retry reuses new code`() = runTest {
        val fixture = fixture()
        val context = SharingContextId("ctx-register-fail")
        val first = fixture.lifecycle.ensureRegistered(context)
        fixture.client.events.clear()
        fixture.client.failNextRegister = true

        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.rotateAndRegister(context)
        }

        val replacement = fixture.presence.getOrCreate(context)
        assertNotEquals(first.presence.referenceCode, replacement.referenceCode)
        assertTrue(fixture.client.events.first().startsWith("unregister:"))

        fixture.client.events.clear()
        val recovered = fixture.lifecycle.ensureRegistered(context)
        assertEquals(replacement.referenceCode, recovered.presence.referenceCode)
        assertEquals(listOf("register:${replacement.referenceCode.value}"), fixture.client.events)
    }

    @Test
    fun `deactivate preserves local code for later re-registration`() = runTest {
        val fixture = fixture()
        val context = SharingContextId("ctx-deactivate")
        val first = fixture.lifecycle.ensureRegistered(context)
        fixture.client.events.clear()

        assertTrue(fixture.lifecycle.deactivate(context))
        val current = fixture.presence.getOrCreate(context)
        assertEquals(first.presence.referenceCode, current.referenceCode)

        fixture.client.events.clear()
        val restored = fixture.lifecycle.ensureRegistered(context)
        assertEquals(first.presence.referenceCode, restored.presence.referenceCode)
        assertEquals(listOf("register:${first.presence.referenceCode.value}"), fixture.client.events)
    }

    private fun fixture(): Fixture {
        val random = CountingRandom()
        val identities = SharingIdentityManager(
            store = InMemorySharingIdentityStore(),
            random = random,
            signer = JvmEd25519Signer(),
        )
        val presence = SharingPresenceManager(
            store = InMemorySharingPresenceStore(),
            random = random,
        )
        val client = CapturingSignalingClient()
        return Fixture(
            client = client,
            presence = presence,
            lifecycle = SharingPresenceLifecycle(client, identities, presence),
        )
    }

    private data class Fixture(
        val client: CapturingSignalingClient,
        val presence: SharingPresenceManager,
        val lifecycle: SharingPresenceLifecycle,
    )

    private class CapturingSignalingClient : SignalingClient {
        private val flow = MutableSharedFlow<SignalingEnvelope>()
        override val incoming: Flow<SignalingEnvelope> = flow
        val events = mutableListOf<String>()
        var failUnregister = false
        var failNextRegister = false

        override suspend fun connect() = Unit

        override suspend fun register(request: RegisterRequest) {
            if (failNextRegister) {
                failNextRegister = false
                throw IllegalStateException("register failed")
            }
            events += "register:${request.referenceCode.value}"
        }

        override suspend fun unregister(request: UnregisterRequest) {
            if (failUnregister) throw IllegalStateException("unregister failed")
            events += "unregister:${request.sharingIdentityId.value}"
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse =
            LookupResponse(LookupStatus.NOT_FOUND)

        override suspend fun relay(request: RelayRequest) = Unit
        override suspend fun close() = Unit
    }

    private class CountingRandom : RandomBytesSource {
        private var counter = 1
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 37 + index) and 0xff).toByte() }
        }
    }
}
