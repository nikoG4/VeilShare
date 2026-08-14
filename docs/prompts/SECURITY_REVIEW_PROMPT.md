# Prompt de revisión de seguridad

Actúa como reviewer, no como implementador.

Revisa:
1. threat model;
2. key hierarchy;
3. KDF;
4. nonce lifecycle;
5. AEAD AAD;
6. real/decoy isolation;
7. secret store;
8. memory/logging;
9. import/delete;
10. transfer handshake;
11. identity pinning;
12. resume;
13. server compromise.

Busca específicamente:
- plaintext metadata;
- key reuse;
- nonce reuse;
- downgrade;
- TOCTOU;
- ACK before durable write;
- path traversal;
- una sola vault disfrazada;
- keys en logs;
- strings sensibles;
- errores fail-open.

Produce findings con:
Severity / Evidence / Impact / Fix / Test.
No cambies código salvo que se te pida.
