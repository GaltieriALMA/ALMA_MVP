# ALMA Android — contrato MVP 1

La app Android no contiene el núcleo privado de ALMA ni la API key.

## Flujo
Android -> HTTPS -> ALMA API -> ALMA Core -> proveedor IA

## Health
GET /health

Respuesta:
```json
{
  "status": "ok",
  "service": "ALMA",
  "identity_version": "1.0.0"
}
```

## Chat
POST /chat

Solicitud:
```json
{
  "user_id": "usuario",
  "session_id": "sesion",
  "message": "Hola ALMA"
}
```

Respuesta:
```json
{
  "text": "respuesta",
  "provider": "openai",
  "valid": true,
  "issues": []
}
```

## Reglas Android
- Nunca almacenar `OPENAI_API_KEY` dentro del APK.
- En producción usar HTTPS.
- `user_id` debe provenir de autenticación, no de un texto editable por el usuario.
- `session_id` identifica la conversación, no la identidad del usuario.
- La memoria persistente vive del lado backend.
- La UI debe tolerar desconexión y permitir reintento sin inventar respuestas.
