import os
import secrets
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel, Field
from backend.app import AlmaApplication
from fastapi.responses import Response
from backend.providers.tts_provider import ElevenLabsTTSProvider
app = FastAPI(
    title="ALMA MVP API",
    version="1.0.0",
)

alma = AlmaApplication()
tts = ElevenLabsTTSProvider()

class ChatRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    session_id: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=12000)

class ChatResponse(BaseModel):
    text: str
    provider: str
    valid: bool
    issues: list[str]
class TTSRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "ALMA",
        "identity_version": alma.identity.identity_version,
    }

def require_api_key(x_alma_api_key: str | None = Header(default=None, alias="X-ALMA-API-Key")):
    expected = os.getenv("ALMA_API_KEY", "").strip()
    if not expected:
        raise HTTPException(status_code=503, detail="ALMA_API_KEY no configurada.")
    if not x_alma_api_key or not secrets.compare_digest(x_alma_api_key, expected):
        raise HTTPException(status_code=401, detail="No autorizado.")
@app.post("/tts")
def text_to_speech(
    request: TTSRequest,
    x_alma_api_key: str | None = Header(default=None, alias="X-ALMA-API-Key"),
):
    require_api_key(x_alma_api_key)
    if not tts.available():
        raise HTTPException(status_code=503, detail="ElevenLabs no configurado.")
    try:
        audio = tts.generate(request.text)
        return Response(content=audio, media_type="audio/mpeg")
    except Exception as exc:
        raise HTTPException(status_code=502, detail="ALMA no pudo generar la voz.") from exc
@app.post("/chat", response_model=ChatResponse)
def chat(
    request: ChatRequest,
    x_alma_api_key: str | None = Header(default=None, alias="X-ALMA-API-Key"),
):
    require_api_key(x_alma_api_key)
    try:
        result = alma.chat(
            user_id=request.user_id,
            session_id=request.session_id,
            message=request.message,
        )
        return ChatResponse(**result)
    except Exception as exc:
        raise HTTPException(
            status_code=500,
            detail="ALMA no pudo procesar el mensaje."
        ) from exc
