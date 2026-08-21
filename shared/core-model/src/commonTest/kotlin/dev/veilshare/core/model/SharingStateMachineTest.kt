package dev.veilshare.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharingStateMachineTest {
    @Test fun senderHappyPathCompletes() {
        val machine = SharingStateMachine(SharingRole.SENDER)

        machine.apply(SharingEvent.PREPARE)
        machine.apply(SharingEvent.START_LOOKUP)
        machine.apply(SharingEvent.LOOKUP_FOUND)
        machine.apply(SharingEvent.SESSION_CREATED)
        machine.apply(SharingEvent.ACCEPT)
        machine.apply(SharingEvent.START_TRANSFER)
        machine.apply(SharingEvent.TRANSFER_DONE)
        machine.apply(SharingEvent.VERIFY_OK)

        assertEquals(SharingState.COMPLETED, machine.state)
    }

    @Test fun receiverHappyPathCompletes() {
        val machine = SharingStateMachine(SharingRole.RECEIVER)

        machine.apply(SharingEvent.PREPARE)
        machine.apply(SharingEvent.OFFER_RECEIVED)
        machine.apply(SharingEvent.ACCEPT)
        machine.apply(SharingEvent.START_TRANSFER)
        machine.apply(SharingEvent.TRANSFER_DONE)
        machine.apply(SharingEvent.VERIFY_OK)

        assertEquals(SharingState.COMPLETED, machine.state)
    }

    @Test fun rejectCancelTimeoutAndDisconnectAreExplicit() {
        assertEquals(SharingState.REJECTED, senderAtWaiting().apply(SharingEvent.REJECT).to)
        assertEquals(SharingState.CANCELLED, senderAtWaiting().apply(SharingEvent.CANCEL).to)
        assertEquals(SharingState.EXPIRED, senderAtWaiting().apply(SharingEvent.TIMEOUT).to)
        assertEquals(SharingState.FAILED, senderAtWaiting().apply(SharingEvent.DISCONNECT).to)
    }

    @Test fun invalidTransitionFailsClosed() {
        val machine = SharingStateMachine(SharingRole.SENDER)

        assertFailsWith<IllegalStateException> { machine.apply(SharingEvent.START_TRANSFER) }
    }

    @Test fun terminalStateCannotTransition() {
        val machine = SharingStateMachine(SharingRole.SENDER)
        machine.apply(SharingEvent.CANCEL)

        assertFailsWith<IllegalStateException> { machine.apply(SharingEvent.PREPARE) }
    }

    private fun senderAtWaiting(): SharingStateMachine {
        val machine = SharingStateMachine(SharingRole.SENDER)
        machine.apply(SharingEvent.PREPARE)
        machine.apply(SharingEvent.START_LOOKUP)
        machine.apply(SharingEvent.LOOKUP_FOUND)
        machine.apply(SharingEvent.SESSION_CREATED)
        return machine
    }
}
