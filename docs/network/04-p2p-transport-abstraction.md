# Abstracción de transporte P2P

## Objetivo

No casar el producto con WebRTC.

```kotlin
interface PeerTransport {
    val incoming: Flow<TransportFrame>
    val state: StateFlow<TransportState>

    suspend fun connect(remote: PeerDescriptor)
    suspend fun send(frame: TransportFrame)
    suspend fun close()
}
```

## WebRTC

Implementación preferida para NAT traversal:
- Android adapter;
- iOS adapter;
- Desktop adapter.

DataChannel entrega bytes; arriba corre nuestro protocolo.

## TransportFrame

No contiene objetos de WebRTC:
```kotlin
data class TransportFrame(
    val channel: ChannelId,
    val bytes: ByteArray
)
```

## Backpressure

Transfer protocol debe respetar:
- buffered amount;
- memory limits;
- receiver ACK window.

Nunca encolar 10 GB de chunks en RAM.

## Orden

Si el transport garantiza orden, protocolo puede aprovecharlo, pero el formato debe incluir `chunkIndex` de todas maneras.

## Reconnect

Un nuevo transport puede reanudar una misma `TransferId` tras reautenticación de sesión.
