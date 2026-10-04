import os
import base64
import io
import wave
import secrets
import json
import re
import unicodedata
from urllib.parse import urlencode
from urllib.request import urlopen
import yfinance as yf
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
    file_base64: str | None = None
    file_name: str | None = None
    file_mime_type: str | None = None

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

MARKET_ALIASES = {
    "bitcoin": "BTC-USD",
    "btc": "BTC-USD",
    "ethereum": "ETH-USD",
    "ether": "ETH-USD",
    "eth": "ETH-USD",
    "apple": "AAPL",
    "aapl": "AAPL",
    "nvidia": "NVDA",
    "nvda": "NVDA",
    "tesla": "TSLA",
    "microsoft": "MSFT",
    "amazon": "AMZN",
    "meta": "META",
    "google": "GOOGL",
    "alphabet": "GOOGL",
    "galicia": "GGAL.BA",
    "ggal": "GGAL.BA",
    "ypf": "YPF",
    "merval": "^MERV",
    "sp500": "^GSPC",
    "s&p 500": "^GSPC",
    "nasdaq": "^IXIC",
    "dow jones": "^DJI",
}

def normalize_market_text(text: str) -> str:
    value = unicodedata.normalize("NFD", (text or "").lower())
    return "".join(
        c for c in value
        if unicodedata.category(c) != "Mn"
    )

def detect_market_symbol(message: str):
    text = normalize_market_text(message)

    for alias, symbol in MARKET_ALIASES.items():
        if re.search(r"(?<!\\w)" + re.escape(alias) + r"(?!\\w)", text):
            return symbol, alias

    return None, None

def market_answer(message: str):
    symbol, alias = detect_market_symbol(message)

    if not symbol:
        return None

    try:
        ticker = yf.Ticker(symbol)

        history = ticker.history(
            period="6mo",
            interval="1d",
            auto_adjust=False
        )

        if history is None or history.empty:
            return None

        closes = history["Close"].dropna()

        if len(closes) < 30:
            return None

        price = float(closes.iloc[-1])
        previous = float(closes.iloc[-2])

        change = price - previous
        pct = (change / previous * 100) if previous else 0.0

        last = history.iloc[-1]

        high = float(last["High"])
        low = float(last["Low"])

        try:
            volume = int(last["Volume"])
        except Exception:
            volume = 0

        sma20 = float(closes.tail(20).mean())
        sma50 = (
            float(closes.tail(50).mean())
            if len(closes) >= 50
            else None
        )

        delta = closes.diff()
        gains = delta.clip(lower=0)
        losses = -delta.clip(upper=0)

        avg_gain = gains.ewm(
            alpha=1/14,
            adjust=False
        ).mean()

        avg_loss = losses.ewm(
            alpha=1/14,
            adjust=False
        ).mean()

        last_gain = float(avg_gain.iloc[-1])
        last_loss = float(avg_loss.iloc[-1])

        if last_loss == 0:
            rsi = 100.0
        else:
            rs = last_gain / last_loss
            rsi = 100 - (100 / (1 + rs))

        ema12 = closes.ewm(
            span=12,
            adjust=False
        ).mean()

        ema26 = closes.ewm(
            span=26,
            adjust=False
        ).mean()

        macd_series = ema12 - ema26

        signal_series = macd_series.ewm(
            span=9,
            adjust=False
        ).mean()

        macd = float(macd_series.iloc[-1])
        signal = float(signal_series.iloc[-1])

        recent = history.tail(20)

        support = float(
            recent["Low"].dropna().min()
        )

        resistance = float(
            recent["High"].dropna().max()
        )

        try:
            currency = ticker.fast_info.get("currency") or ""
        except Exception:
            currency = ""

        if change > 0:
            movement = f"subió {abs(pct):.2f}%"
        elif change < 0:
            movement = f"bajó {abs(pct):.2f}%"
        else:
            movement = "quedó sin cambios"

        trend = "neutral"

        if sma50 is not None:
            if price > sma20 > sma50:
                trend = "alcista"
            elif price < sma20 < sma50:
                trend = "bajista"

        if rsi >= 70:
            rsi_text = f"RSI {rsi:.1f}, zona de sobrecompra"
        elif rsi <= 30:
            rsi_text = f"RSI {rsi:.1f}, zona de sobreventa"
        else:
            rsi_text = f"RSI {rsi:.1f}, zona neutral"

        if macd > signal:
            macd_text = "MACD con señal alcista"
        elif macd < signal:
            macd_text = "MACD con señal bajista"
        else:
            macd_text = "MACD neutral"

        name = alias.upper() if len(alias) <= 5 else alias.title()

        volume_text = (
            f"{volume:,}"
            if volume > 0
            else "no disponible"
        )

        score = 0
        reasons = []

        if trend == "alcista":
            score += 1
            reasons.append("precio y medias con estructura alcista")
        elif trend == "bajista":
            score -= 1
            reasons.append("precio y medias con estructura bajista")

        if macd > signal:
            score += 1
            reasons.append("MACD positivo")
        elif macd < signal:
            score -= 1
            reasons.append("MACD negativo")

        if rsi >= 70:
            score -= 1
            reasons.append("RSI elevado")
        elif rsi <= 30:
            score += 1
            reasons.append("RSI en sobreventa")

        if score >= 2:
            bias = "alcista"
        elif score <= -2:
            bias = "bajista"
        else:
            bias = "neutral"

        reason_text = ", ".join(reasons)

        return (
            f"{name} cotiza en {price:,.2f} {currency}. "
            f"En el último dato disponible {movement}. "
            f"Máximo del día: {high:,.2f}. "
            f"Mínimo del día: {low:,.2f}. "
            f"Volumen: {volume_text}. "
            f"Tendencia técnica: {trend}. "
            f"{rsi_text}. "
            f"{macd_text}. "
            f"Soporte aproximado: {support:,.2f}. "
            f"Resistencia aproximada: {resistance:,.2f}. "
            f"Sesgo técnico actual: {bias}. "
            f"Motivos: {reason_text}."
        )

    except Exception:
        return None


@app.post("/chat", response_model=ChatResponse)
def chat(
    request: ChatRequest,
    x_alma_api_key: str | None = Header(default=None, alias="X-ALMA-API-Key"),
):
    require_api_key(x_alma_api_key)
    try:
        if request.file_base64:
            from openai import OpenAI
            client = OpenAI(
                api_key=alma.provider.openai.api_key,
                timeout=60.0
            )
            context = alma.context_builder.build(
                request.user_id,
                request.message,
                alma.sessions.recent(request.user_id, request.session_id)
            )
            response = client.responses.create(
                model=alma.provider.openai.model,
                instructions=context,
                input=[{
                    "role": "user",
                    "content": [
                        {
                            "type": "input_file",
                            "filename": request.file_name or "archivo",
                            "file_data": request.file_base64
                        },
                        {
                            "type": "input_text",
                            "text": request.message
                        }
                    ]
                }]
            )
            return ChatResponse(
                text=response.output_text.strip(),
                provider="openai",
                valid=True,
                issues=[]
            )

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



@app.get("/market/quote")
def market_quote(
    symbol: str,
    x_alma_api_key: str | None = Header(default=None, alias="X-ALMA-API-Key"),
):
    require_api_key(x_alma_api_key)

    ticker_symbol = (symbol or "").strip().upper()

    if not ticker_symbol:
        raise HTTPException(
            status_code=400,
            detail="Falta el símbolo."
        )

    try:
        ticker = yf.Ticker(ticker_symbol)

        history = ticker.history(
            period="5d",
            interval="1d",
            auto_adjust=False
        )

        if history is None or history.empty:
            raise HTTPException(
                status_code=404,
                detail="No encontré datos de mercado para ese símbolo."
            )

        closes = history["Close"].dropna()

        if closes.empty:
            raise HTTPException(
                status_code=404,
                detail="No encontré precio para ese símbolo."
            )

        price = float(closes.iloc[-1])

        previous_close = (
            float(closes.iloc[-2])
            if len(closes) >= 2
            else price
        )

        change = price - previous_close

        change_pct = (
            (change / previous_close) * 100
            if previous_close
            else 0.0
        )

        currency = None

        try:
            currency = ticker.fast_info.get("currency")
        except Exception:
            pass

        return {
            "symbol": ticker_symbol,
            "price": round(price, 6),
            "previous_close": round(previous_close, 6),
            "change": round(change, 6),
            "change_pct": round(change_pct, 3),
            "currency": currency,
            "source": "Yahoo Finance",
        }

    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail="ALMA no pudo obtener datos de mercado."
        ) from exc
