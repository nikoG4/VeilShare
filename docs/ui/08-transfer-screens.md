# Pantallas de transferencia

## Sender

States:
- Resolviendo dispositivo
- Negociando conexión
- Esperando aceptación
- Enviando
- Pausado
- Reanudando
- Verificando
- Completo
- Error/cancelado

Mostrar:
- bytes;
- porcentaje;
- velocidad aproximada;
- archivos;
- conexión directa/relay opcional.

## Receiver

Antes de aceptar:
- sender alias/fingerprint;
- file count;
- total bytes;
- nombres solo después de E2E offer.

Acciones:
- aceptar;
- rechazar.

## Background

Si iOS suspende:
- al volver, estado “Reanudando”, no “Falló” automáticamente.

## Cancel

Cancelar deja:
- chunks parciales marcados orphan/temp;
- cleanup seguro;
- no item final.
