# NAT, STUN y TURN

## Regla

P2P directo preferido, no garantizado.

## Flujo

```text
Peer A ── ICE/STUN ── Peer B
   \                  /
    \__ TURN relay __/
       si necesario
```

TURN:
- transporta paquetes;
- no requiere almacenar archivos;
- aumenta costo/bandwidth del servidor;
- puede observar metadata de red/tamaño/tiempo, pero no contenido E2E.

## Infraestructura

Separar:
- Ktor signaling;
- STUN/TURN (por ejemplo servicio dedicado);
- credenciales TURN de corta duración.

## Seguridad

No hardcodear credenciales TURN permanentes en cliente.
Ktor emite credenciales temporales tras autenticación/rate limit.

## UX

Estados:
- “Conexión directa”
- “Conexión por relay”
solo si se desea transparencia técnica; no revelar detalles innecesarios.
