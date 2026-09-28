import os
import base64
import io
import wave
import secrets
import json
import re
from urllib.parse import urlencode
from urllib.request import urlopen
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
    image_base64: str | None = None
    mime_type: str = "image/jpeg"

class ChatResponse(BaseModel):
    text: str
    provider: str
    valid: bool
    issues: list[str]
class TTSRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)

class TranscriptionRequest(BaseModel):
    audio_base64: str = Field(min_length=8, max_length=2000000)
    sample_rate: int = Field(default=16000, ge=8000, le=48000)
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

@app.post("/transcribe")
def transcribe_audio(
    request: TranscriptionRequest,
    x_alma_api_key: str | None = Header(default=None, alias="X-ALMA-API-Key"),
):
    require_api_key(x_alma_api_key)

    try:
        pcm = base64.b64decode(request.audio_base64, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="Audio inválido.") from exc

    if len(pcm) < 400 or len(pcm) % 2 != 0:
        raise HTTPException(status_code=400, detail="Audio PCM inválido.")

    wav_buffer = io.BytesIO()
    with wave.open(wav_buffer, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(request.sample_rate)
        wav_file.writeframes(pcm)

    audio_file = io.BytesIO(wav_buffer.getvalue())
    audio_file.name = "alma.wav"

    try:
        from openai import OpenAI

        client = OpenAI(
            api_key=alma.provider.openai.api_key,
            timeout=60.0,
        )

        model = os.getenv(
            "OPENAI_TRANSCRIBE_MODEL",
            "gpt-transcribe",
        ).strip() or "gpt-transcribe"

        transcript = client.audio.transcriptions.create(
            model=model,
            file=audio_file,
            prompt=(
                "Conversación natural en español rioplatense de Argentina. "
                "Transcribir exactamente lo dicho. "
                "Palabras frecuentes: ALMA, YouTube."
            ),
            extra_body={
                "languages": ["es"],
                "keywords": [
                    "ALMA",
                    "YouTube",
                    "poneme",
                    "mostrame",
                    "buscame",
                    "reproducí",
                ],
            },
        )

        text = (transcript.text or "").strip()

        if not text:
            raise HTTPException(
                status_code=422,
                detail="No se detectó voz.",
            )

        return {
            "text": text,
            "model": model,
        }

    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail="ALMA no pudo transcribir el audio.",
        ) from exc

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
        if request.image_base64:
            from openai import OpenAI
            client = OpenAI(api_key=alma.provider.openai.api_key, timeout=60.0)
            context = alma.context_builder.build(request.user_id, request.message, alma.sessions.recent(request.user_id, request.session_id))
            response = client.responses.create(model=alma.provider.openai.model, instructions=context, input=[{"role": "user", "content": [{"type": "input_text", "text": request.message}, {"type": "input_image", "image_url": f"data:{request.mime_type};base64,{request.image_base64}"}]}])
            return ChatResponse(text=response.output_text.strip(), provider="openai", valid=True, issues=[])
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

@app.get("/youtube/search")
def youtube_search(
    q: str,
    x_alma_api_key: str | None = Header(default=None, alias="X-ALMA-API-Key"),
):
    require_api_key(x_alma_api_key)

    query = (q or "").strip()
    if not query:
        raise HTTPException(status_code=400, detail="Falta la búsqueda.")

    youtube_key = os.getenv("YOUTUBE_API_KEY", "").strip()

    if youtube_key:
        params = urlencode({
            "part": "snippet",
            "type": "video",
            "maxResults": 1,
            "q": query,
            "key": youtube_key,
        })

        try:
            with urlopen(
                "https://www.googleapis.com/youtube/v3/search?" + params,
                timeout=15,
            ) as response:
                data = json.load(response)

            items = data.get("items") or []

            if items:
                item = items[0]
                video_id = (
                    ((item.get("id") or {}).get("videoId") or "")
                    .strip()
                )

                if video_id:
                    return {
                        "video_id": video_id,
                        "title": (
                            (item.get("snippet") or {}).get("title")
                            or query
                        ).strip(),
                    }
        except Exception:
            pass

    try:
        from openai import OpenAI

        client = OpenAI(
            api_key=alma.provider.openai.api_key,
            timeout=45.0,
        )

        search_model = os.getenv(
            "OPENAI_SEARCH_MODEL",
            "gpt-5.5",
        ).strip() or "gpt-5.5"

        response = client.responses.create(
            model=search_model,
            tools=[{
                "type": "web_search",
                "filters": {
                    "allowed_domains": ["youtube.com"],
                },
            }],
            tool_choice="required",
            include=["web_search_call.action.sources"],
            input=(
                "Buscá en YouTube el video que mejor coincida con: "
                + query
                + ". Priorizá el video oficial cuando exista. "
                  "Necesito un enlace directo reproducible del video."
            ),
        )

        raw = json.dumps(
            response.model_dump(),
            ensure_ascii=False,
        )

        patterns = [
            r'youtube\.com/watch\?[^"\\\s]*?v=([A-Za-z0-9_-]{11})',
            r'youtu\.be/([A-Za-z0-9_-]{11})',
            r'youtube\.com/shorts/([A-Za-z0-9_-]{11})',
        ]

        for pattern in patterns:
            match = re.search(pattern, raw)
            if match:
                return {
                    "video_id": match.group(1),
                    "title": query,
                }

    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail="ALMA no pudo buscar el video en YouTube.",
        ) from exc

    raise HTTPException(
        status_code=404,
        detail="No encontré un video reproducible.",
    )

