# Decisiones pendientes controladas

Estas decisiones **pueden cambiar detalles**, pero no el concepto.

## P1 — KDF Argon2id por target

Necesitamos una implementación auditable y mantenida para:
- Android/JVM;
- Desktop JVM;
- iOS Native.

Regla: el contrato vive en commonMain. No usar una implementación “unsafe/prototype” solo por comodidad.

## P2 — Suite criptográfica de identidad

Candidatos:
- P-256 ECDSA + P-256 ECDH para mejor integración con hardware/OS;
- Ed25519 + X25519 si el proveedor KMP y secret stores lo soportan de forma satisfactoria.

Criterios:
- interoperabilidad;
- soporte en iOS/Android/Desktop;
- almacenamiento seguro de claves;
- facilidad de vectores de test;
- no inventar protocolo cripto.

## P3 — Implementación WebRTC Desktop

Opciones:
- wrapper de WebRTC nativo;
- libdatachannel vía JNI/native;
- otro backend probado.

El protocolo de transferencia no debe cambiar por esta decisión.

## P4 — Recovery

Opciones:
- sin recovery: máxima simplicidad, pérdida de PIN = pérdida de datos;
- recovery key offline de alta entropía;
- backup cifrado exportable.

No crear recovery basado en pregunta secreta/email.

## P5 — persistencia del mini servidor

Preferencia inicial: presencia/signaling en memoria; código derivado de fingerprint.

Si luego se requieren cuentas/múltiples dispositivos, crear ADR antes de introducir base de datos.

## P6 — apariencia señuelo

Definir lista final de utilidades:
- calculadora;
- conversor;
- notas rápidas;
- “Tools”.

Cada plataforma puede ofrecer distinto conjunto. No imitar exactamente apps del sistema.
