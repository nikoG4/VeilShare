# Modo offline y envío diferido

## MVP P2P puro

Si receptor está offline:
- no hay entrega;
- no hay archivo esperando en server.

UI:
`El dispositivo no está disponible. Intentar nuevamente.`

## “Enviar cuando aparezca”

Puede ser una tarea local:
- guardar intención cifrada;
- cliente revisa presencia cuando esté activo/permitido;
- al aparecer, inicia transferencia.

No implica subir archivo.

## Future: relay temporal persistente

Solo con ADR y feature explícita:
- ciphertext upload;
- key E2E fuera del servidor;
- TTL;
- delete after fetch;
- threat model ampliado.

No introducirlo para “arreglar” background iOS sin comunicar el cambio de arquitectura.
