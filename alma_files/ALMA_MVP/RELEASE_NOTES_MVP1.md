# ALMA — MVP 1 — Release Candidate

Versión: `1.0.0-rc1`
Bloque de cierre: `460`

## Funcional y probado
- Identidad base de ALMA protegida.
- Conversación textual.
- Memoria persistente.
- Recuperación de memoria entre sesiones/reinicios.
- Aislamiento entre usuarios.
- Escritura segura y recuperación ante JSON corrupto.
- Abstracción de proveedor IA y fallback.
- API local `/health` y `/chat`.
- Contrato estable para cliente móvil.
- Código fuente de primera interfaz Android.
- Release Android configurado para impedir HTTP sin cifrar.
- Batería automatizada de regresión y aceptación.

## No se declara todavía como validado externamente
1. Llamada real al proveedor OpenAI con una API key válida.
2. Compilación e instalación de APK en dispositivo Android.
3. API desplegada públicamente mediante HTTPS.

Estas tres tareas son de puesta en marcha/despliegue y no se simulan en este release.
