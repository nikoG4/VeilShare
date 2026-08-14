# Transfer fault injection

Simular:

- drop every Nth frame;
- duplicate chunks;
- reorder if transport permits;
- disconnect at 1%, 50%, 99%;
- receiver crash after durable write before ACK;
- sender crash after send before ACK;
- corrupted chunk;
- identity change on resume;
- TURN-only path;
- very slow receiver;
- disk full;
- target vault locked mid-transfer.

## Expected

No corrupción silenciosa.
No file final hasta commit.
Resume no reusa session keys de forma insegura.
Disk full produce pausa/error recuperable.
