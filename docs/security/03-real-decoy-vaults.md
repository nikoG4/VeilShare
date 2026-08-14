# Bóveda real y señuelo

## Propósito

Permitir que dos PINes válidos abran experiencias funcionales distintas sin que una sea un simple filtro UI.

## Diseño de slots

Ejemplo:
```text
bootstrap/
  slot-4f9d.bin
  slot-b12c.bin
```

Ambos tienen formato indistinguible.

Unlock:

1. normalizar entrada según política;
2. ejecutar KDF contra cada slot de forma controlada;
3. intentar AEAD unwrap;
4. exactamente un slot debería autenticar;
5. descriptor descifrado define persona/política;
6. abrir su índice.

## Timing

Evitar:
- “PIN señuelo abre en 20ms”;
- “PIN real abre en 1s”.

La KDF domina el tiempo. Los intentos deben usar parámetros comparables.

## Contenido señuelo

Debe ser funcional:
- importar;
- borrar;
- renombrar;
- abrir;
- compartir opcionalmente si producto lo desea.

Un señuelo con 3 archivos hardcodeados es sospechoso y conceptualmente incorrecto.

## Notificaciones

Con vault locked:
- jamás indicar “Transferencia de bóveda real”.
- texto genérico o ninguna notificación sensible.

## Biometría

Política sugerida:
- biometría abre únicamente vault real;
- no mostrar en UI señuelo una pista de que existe biometría real;
- permitir deshabilitarla.

## Limitación

Dos slots cifrados pueden ser observables por análisis forense. Este diseño no pretende hidden volumes tipo deniable filesystem.
