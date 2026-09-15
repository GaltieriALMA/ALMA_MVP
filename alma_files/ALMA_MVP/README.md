# ALMA MVP

Primer backend ejecutable de ALMA.

## Ejecutar
Desde la carpeta `ALMA_MVP`:

```bash
python -m backend.main
```

Sin `OPENAI_API_KEY`, usa proveedor mock para pruebas locales de arranque, sesión y memoria persistente.

## Probar con OpenAI
1. `pip install -r requirements.txt`
2. Configurar `OPENAI_API_KEY` en el entorno.
3. Ejecutar `python -m backend.main`.

La clave no debe incorporarse dentro de una futura APK.


## API local (Bloque 454)

Instalar dependencias y ejecutar desde la carpeta `ALMA_MVP`:

```bash
pip install -r requirements.txt
uvicorn backend.api:app --host 127.0.0.1 --port 8000
```

Endpoints MVP:
- `GET /health`
- `POST /chat`

La futura app Android consumirá esta capa API en lugar de contener el núcleo privado de ALMA dentro del APK.


## Mobile client contract — Bloque 456
`mobile_client/` define el contrato estable que consumirá la primera app Android.
La app móvil nunca debe contener la clave del proveedor de IA.


## Android UI — Bloque 457
Se agregó `android_app/`, una primera interfaz nativa de chat que consume `/chat`.
El código no contiene claves del proveedor de IA. El APK todavía no se compiló en este bloque.
