# Android implementation guide

## Launcher/disguise

Usar componentes/aliases declarados en manifest y un `DisguiseController` Android para seleccionar el launcher component permitido.

Requisitos:
- nombres/iconos propios;
- no imitar apps de sistema/operador;
- package id permanece;
- tolerar launchers OEM que cachean iconos.

## Storage

Vault:
- internal app storage;
- blobs opacos;
- no external storage salvo export explícito.

Import:
- Photo Picker para media cuando sea adecuado;
- Storage Access Framework para documentos.

## Secret store

Android Keystore:
- wrapping/identity keys;
- auth-bound keys para biometría;
- detectar hardware-backed si se desea mostrar seguridad.

## Biometrics

`BiometricPrompt` encapsulado.

## Screenshot/recents

Aplicar protección de ventana a las pantallas sensibles.
La surface señuelo puede comportarse como utilidad normal.

## Background transfer

Diseñar con las APIs de background actuales al momento de implementación. No asumir servicios infinitos. Transfer state machine common debe tolerar pausa/cancelación.

## Delete original

Solo después de import commit.
Manejar:
- permission needed;
- recoverable security exceptions;
- user cancel;
- provider unsupported.

## Share intent

Permitir “Enviar a VeilShare”:
- recibe handle;
- app pide unlock;
- importa;
- nunca muestra nombre en recents/notificación sin policy.
