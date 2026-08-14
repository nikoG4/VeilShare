# Privacidad de metadata de red

## Visible potencialmente al signaling/TURN

- IP;
- timing;
- reference code online;
- tamaño de signaling;
- volumen TURN;
- duración.

## No visible

Por diseño:
- filename;
- file MIME;
- thumbnail;
- PIN;
- FileKey;
- VaultKey;
- contact alias local.

## Mitigaciones futuras

- reference code hashing en logs;
- padding de frames;
- batching;
- relay privacy mode;
- optional always-TURN;
- private discovery rendezvous.

No son MVP porque añaden costo/latencia. Documentar tradeoff.
