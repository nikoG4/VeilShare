# Performance para archivos grandes

## Objetivo

20 GB debe ser posible sin 20 GB RAM.

## Pipeline

Sender:
`VaultReader -> decrypt vault chunk -> transfer encrypt -> bounded buffer -> transport`

Receiver:
`transport -> transfer decrypt -> bounded buffer -> vault encrypt -> BlobWriter -> ACK`

## Memory budget

Configurable por plataforma:
- compact mobile: 16–64 MiB pipeline;
- desktop: 64–256 MiB según settings.

No reservar en función del tamaño total.

## Parallelism

Un archivo:
- pocos chunks in-flight.

Varios archivos:
- limitar concurrency;
- priorizar latencia vs throughput.

## Hash

Streaming hash incremental.

## UI progress

Bytes durably ACKed, no bytes simplemente “queued”.
