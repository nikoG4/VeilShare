package dev.veilshare.core.model

enum class SharingRole {
    SENDER,
    RECEIVER,
}

enum class SharingState {
    IDLE,
    READY,
    RESOLVING_PEER,
    CREATING_SESSION,
    WAITING_FOR_PEER,
    AWAITING_DECISION,
    NEGOTIATING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
    EXPIRED,
}

enum class SharingEvent {
    PREPARE,
    START_LOOKUP,
    LOOKUP_FOUND,
    LOOKUP_NOT_FOUND,
    SESSION_CREATED,
    OFFER_RECEIVED,
    ACCEPT,
    REJECT,
    START_TRANSFER,
    TRANSFER_DONE,
    VERIFY_OK,
    CANCEL,
    TIMEOUT,
    DISCONNECT,
    PROTOCOL_ERROR,
}

data class SharingTransition(
    val role: SharingRole,
    val from: SharingState,
    val event: SharingEvent,
    val to: SharingState,
)

class SharingStateMachine(private val role: SharingRole) {
    var state: SharingState = SharingState.IDLE
        private set

    fun apply(event: SharingEvent): SharingTransition {
        val next = transition(state, event)
        val result = SharingTransition(role, state, event, next)
        state = next
        return result
    }

    private fun transition(current: SharingState, event: SharingEvent): SharingState {
        if (current.isTerminal()) throw IllegalStateException("Terminal state cannot transition")
        return when (event) {
            SharingEvent.CANCEL -> SharingState.CANCELLED
            SharingEvent.TIMEOUT -> SharingState.EXPIRED
            SharingEvent.DISCONNECT,
            SharingEvent.PROTOCOL_ERROR -> SharingState.FAILED
            else -> roleTransition(current, event)
        }
    }

    private fun roleTransition(current: SharingState, event: SharingEvent): SharingState = when (role) {
        SharingRole.SENDER -> senderTransition(current, event)
        SharingRole.RECEIVER -> receiverTransition(current, event)
    }

    private fun senderTransition(current: SharingState, event: SharingEvent): SharingState = when (current to event) {
        SharingState.IDLE to SharingEvent.PREPARE -> SharingState.READY
        SharingState.READY to SharingEvent.START_LOOKUP -> SharingState.RESOLVING_PEER
        SharingState.RESOLVING_PEER to SharingEvent.LOOKUP_FOUND -> SharingState.CREATING_SESSION
        SharingState.RESOLVING_PEER to SharingEvent.LOOKUP_NOT_FOUND -> SharingState.FAILED
        SharingState.CREATING_SESSION to SharingEvent.SESSION_CREATED -> SharingState.WAITING_FOR_PEER
        SharingState.WAITING_FOR_PEER to SharingEvent.ACCEPT -> SharingState.NEGOTIATING
        SharingState.WAITING_FOR_PEER to SharingEvent.REJECT -> SharingState.REJECTED
        SharingState.NEGOTIATING to SharingEvent.START_TRANSFER -> SharingState.TRANSFERRING
        SharingState.TRANSFERRING to SharingEvent.TRANSFER_DONE -> SharingState.VERIFYING
        SharingState.VERIFYING to SharingEvent.VERIFY_OK -> SharingState.COMPLETED
        else -> throw IllegalStateException("Invalid sender transition: $current + $event")
    }

    private fun receiverTransition(current: SharingState, event: SharingEvent): SharingState = when (current to event) {
        SharingState.IDLE to SharingEvent.PREPARE -> SharingState.READY
        SharingState.READY to SharingEvent.OFFER_RECEIVED -> SharingState.AWAITING_DECISION
        SharingState.AWAITING_DECISION to SharingEvent.ACCEPT -> SharingState.NEGOTIATING
        SharingState.AWAITING_DECISION to SharingEvent.REJECT -> SharingState.REJECTED
        SharingState.NEGOTIATING to SharingEvent.START_TRANSFER -> SharingState.TRANSFERRING
        SharingState.TRANSFERRING to SharingEvent.TRANSFER_DONE -> SharingState.VERIFYING
        SharingState.VERIFYING to SharingEvent.VERIFY_OK -> SharingState.COMPLETED
        else -> throw IllegalStateException("Invalid receiver transition: $current + $event")
    }

    private fun SharingState.isTerminal(): Boolean =
        this == SharingState.COMPLETED ||
            this == SharingState.REJECTED ||
            this == SharingState.CANCELLED ||
            this == SharingState.FAILED ||
            this == SharingState.EXPIRED
}
