# Definition of Done

Una tarea está DONE cuando:

1. Implementación compila en targets afectados.
2. Tests comunes pasan.
3. Tests específicos pasan.
4. No hay secretos en logs.
5. Errores están mapeados.
6. Cancelación/lifecycle considerado.
7. Documentación/ADR actualizado si cambia contrato.
8. No añade dependencia sin justificar.
9. No reduce una propiedad de seguridad.
10. No tiene TODO crítico.
11. Se verificó interoperabilidad si toca formato/protocolo.
12. Se documentó limitación conocida.

Para iOS:
- build real en macOS requerido para DONE.

Para cifrado:
- vector cross-platform requerido.

Para transferencia:
- prueba de desconexión/reanudación requerida.
