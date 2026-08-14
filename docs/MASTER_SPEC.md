# Especificación maestra de producto

## 1. Problema

Crear una aplicación multiplataforma que permita a una persona:

1. importar archivos/multimedia a una bóveda local cifrada;
2. mantener una apariencia discreta;
3. disponer de un acceso real y otro señuelo;
4. borrar opcionalmente el original después de validar la importación;
5. compartir contenido directamente con otro usuario mediante un código de referencia;
6. guardar destinatarios frecuentes;
7. evitar que un servidor central sea repositorio del contenido.

## 2. Plataformas

### Objetivo principal
- Android
- iOS
- Desktop JVM (Windows/macOS/Linux)

### Arquitectura
- Kotlin Multiplatform
- Compose Multiplatform
- AdaptiveKt para primitivas adaptativas, detrás de un wrapper propio
- Ktor JVM para señalización
- transporte P2P detrás de una interfaz común

## 3. Personas/escenarios

### Usuario A — privacidad casual
Quiere que abrir el teléfono no revele una app llamada “Secret Vault”.

### Usuario B — coacción
Conoce un PIN señuelo que abre una bóveda funcional con contenido inocuo.

### Usuario C — transferencia privada
Quiere enviar varios GB sin subirlos a almacenamiento permanente de terceros.

### Usuario D — ecosistema personal
Quiere enviar entre móvil y PC usando dispositivos frecuentes.

## 4. Onboarding

Orden recomendado:

1. presentación breve de privacidad;
2. elegir apariencia disponible para la plataforma;
3. crear PIN real;
4. crear PIN señuelo (recomendado, opcional según producto);
5. advertencia: perder PIN/recovery puede perder acceso;
6. generar identidad del dispositivo;
7. mostrar código/QR;
8. entrar a bóveda vacía.

No pedir permisos masivos en onboarding. Solicitar permisos justo cuando la función los necesita.

## 5. Apariencia discreta

### Android
Puede cambiar launcher alias/nombre/icono dentro de límites técnicos y de políticas.

### iOS
Usar nombre neutro de publicación; permitir iconos alternativos donde UIKit lo permita. No depender de cambio dinámico del display name.

### Desktop
Nombre/ícono puede ser neutro; launcher/shortcut configurable por instalación, pero no se debe prometer stealth absoluto.

### UX
El modo discreto puede mostrar una utilidad realmente funcional (calculadora/conversor/notas mínimas), siempre que la funcionalidad de bóveda esté declarada en la distribución/review.

## 6. Desbloqueo

Métodos:
- PIN real;
- PIN señuelo;
- biometría opcional para bóveda real;
- recovery key opcional (decisión de producto, ver seguridad).

Comportamiento:
- PIN real → abre una de las bóvedas cifradas;
- PIN señuelo → abre la otra;
- UI no debe mostrar “PIN incorrecto para bóveda real”;
- tiempos de respuesta deben evitar diferencias obvias entre real/señuelo;
- política de intentos debe evitar DoS fácil sin fingir protección absoluta.

## 7. Bóveda

Capacidades:
- carpetas virtuales;
- galería;
- vista de archivos;
- búsqueda sobre índice cifrado/descifrado solo en sesión;
- favoritos;
- importación múltiple;
- exportación;
- compartir;
- borrar;
- mover/renombrar;
- miniaturas cifradas;
- viewer interno donde sea razonable.

En reposo:
- nombres opacos de blobs;
- manifest cifrado;
- DB/índice cifrado o contenido sensible cifrado por campo;
- ninguna miniatura en caché pública.

## 8. Importación

1. usuario elige archivos con picker nativo;
2. app obtiene stream/handle;
3. genera file key;
4. cifra por chunks;
5. guarda manifest cifrado;
6. verifica autenticación e integridad;
7. marca importación completa;
8. recién entonces pregunta/permite eliminar original;
9. si borrado falla, mostrar estado honesto.

Una importación incompleta nunca aparece como archivo válido.

## 9. Compartir

1. elegir uno/más archivos;
2. elegir contacto frecuente o introducir código;
3. resolver presencia;
4. verificar identidad/fingerprint;
5. negociar sesión P2P;
6. establecer E2E app-level;
7. enviar manifests y chunks;
8. receptor puede aceptar/rechazar;
9. receptor cifra directamente hacia su bóveda; evitar archivo temporal en claro;
10. ACK y verificación final;
11. si conexión cae, reanudar.

## 10. Código de referencia

Preferencia: código derivado de fingerprint público, no secreto.

Ejemplo visual:
`M7QK-2P9D-V4TX-H8CN`

Propiedades:
- tipeable;
- QR recomendado;
- checksum;
- no confiere autenticidad por sí solo;
- la clave/fingerprint completo se pinnea tras verificación.

## 11. Contactos frecuentes

Campos lógicos:
- id local;
- alias local;
- referencia;
- fingerprint completo;
- public keys;
- verificado sí/no;
- fecha de última verificación;
- preferencia de auto-aceptación (por defecto no);
- metadata almacenada dentro de la bóveda/almacenamiento cifrado.

No sincronizar contactos al servidor por defecto.

## 12. Servidor

Servidor mínimo Ktor:

- WebSocket de presencia/señalización;
- challenge-response para acreditar posesión de la identidad;
- forwarding efímero;
- rate limit;
- endpoint opcional para credenciales TURN;
- no persistencia necesaria para códigos si el código deriva de la clave pública;
- logs minimizados.

## 13. Offline

P2P-only implica:
- receptor offline = no entrega;
- UI permite “intentar cuando esté online” solamente si la app cliente vuelve a comprobar, no si servidor almacena archivo;
- no fingir mensajería diferida.

## 14. Restricciones iOS

- lifecycle/background puede suspender P2P;
- transferencias grandes requieren app activa o diseño de reanudación;
- iconos alternativos sí; no diseñar alrededor de cambio arbitrario del display name;
- App Review debe poder acceder y comprender la funcionalidad.

## 15. Restricciones Android

- OEMs pueden alterar comportamiento de launcher/caché;
- background moderno requiere diseño conforme a APIs vigentes;
- alias de activity puede cambiar la presencia en launcher, pero no el package id;
- Play policy exige transparencia del comportamiento.

## 16. Restricciones Desktop

- no hay un Secure Enclave uniforme en Windows/macOS/Linux;
- el backend de secretos es por SO;
- permisos/filesystem varían;
- WebRTC nativo/JVM requiere implementación específica; protocolo no depende de una librería concreta.

## 17. Criterio de éxito

El producto debe poder demostrar:

- mismo archivo importado/descifrado entre plataformas con vectores de test;
- bóvedas real/señuelo independientes;
- server compromise no revela contenido;
- transferencia interrumpida se reanuda;
- receptor nunca necesita recibir plaintext en storage temporal;
- feature UI central compartida en commonMain;
- platformMain contiene integración, no negocio duplicado.
