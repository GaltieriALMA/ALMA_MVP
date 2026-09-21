import json
import os
from urllib.parse import quote
from urllib.request import Request, urlopen
from urllib.error import HTTPError

class ElevenLabsTTSProvider:
    def __init__(self):
        self.api_key = os.getenv("ELEVENLABS_API_KEY", "").strip()
        self.voice_id = os.getenv("ELEVENLABS_VOICE_ID", "").strip()
        self.model_id = os.getenv("ELEVENLABS_MODEL", "eleven_multilingual_v2").strip()

    def available(self) -> bool:
        return bool(self.api_key and self.voice_id)

    def generate(self, text: str) -> bytes:
        if not self.available():
            raise RuntimeError("ElevenLabs TTS no configurado.")

        url = (
            "https://api.elevenlabs.io/v1/text-to-speech/"
            + quote(self.voice_id, safe="")
            + "?output_format=mp3_44100_128"
        )

        payload = json.dumps({
            "text": text,
            "model_id": self.model_id,
            "voice_settings": {
    "stability": 0.89,
    "similarity_boost": 0.74,
    "style": 0.0,
    "use_speaker_boost": True,
    "speed": 0.76
},
        }).encode("utf-8")

        request = Request(
            url,
            data=payload,
            method="POST",
            headers={
                "xi-api-key": self.api_key,
                "Content-Type": "application/json",
                "Accept": "audio/mpeg",
            },
        )

        try:
            with urlopen(request, timeout=60) as response:
                return response.read()
        except HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")[:500]
            print("ELEVENLABS_HTTP_ERROR:", exc.code, body)
            raise
