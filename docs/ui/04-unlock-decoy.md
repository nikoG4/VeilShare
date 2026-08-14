# Unlock y señuelo

## Entrada

La surface discreta activa unlock.
PIN no debe mostrarse en logs/accessibility como texto normal.

## Resultado

- valid PIN A → VaultSession A
- valid PIN B → VaultSession B
- invalid → error genérico

La UI no sabe cuál es “real” hasta descriptor descifrado; incluso entonces usar `PersonaPolicy`, no condicionales dispersos.

## Errores

“Código incorrecto” genérico.

## Delay

No añadir delays arbitrarios que permitan distinguir ramas. El costo viene de KDF.

## Biometría

Puede ofrecerse desde una affordance discreta configurable.
Al desbloquear real no debe mostrar texto que revele decoy.
