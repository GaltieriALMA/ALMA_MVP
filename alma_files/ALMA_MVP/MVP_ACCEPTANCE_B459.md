# ALMA MVP — Bloque 459 — Acceptance Gate

Estado de Release Candidate previo al Bloque 460.

Criterios cubiertos por pruebas automatizadas:
- Identidad ALMA congelada: nombre, edad aparente, naturaleza virtual y versión.
- Conversación ejecutable.
- Memoria persistente después de reconstruir la aplicación.
- Aislamiento entre usuarios.
- Recuperación de memoria/sesión dañada.
- Fallback seguro del proveedor.
- Contrato API.
- Contrato cliente móvil.
- Flujo API extremo a extremo.
- Estructura Android.
- Ausencia de secretos del proveedor dentro de Android.
- Release Android sin HTTP claro.

Pendientes externos, no falsificados:
- Llamada real a OpenAI requiere una API key válida.
- Compilación/instalación APK requiere toolchain Android/Gradle/ADB disponible.
- Publicación remota de API requiere hosting HTTPS.

El Bloque 460 puede cerrar el MVP funcional de backend/aplicación y emitir el paquete Release Candidate,
sin afirmar como realizadas las tres validaciones externas anteriores.
