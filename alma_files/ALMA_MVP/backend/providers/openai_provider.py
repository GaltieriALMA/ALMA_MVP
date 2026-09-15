import os
from .base_provider import BaseProvider

class OpenAIProvider(BaseProvider):
    def __init__(self, model: str | None = None):
        try:
            from dotenv import load_dotenv
            load_dotenv()
        except Exception:
            pass

        self.model = model or os.getenv("OPENAI_MODEL", "gpt-5.6")
        self.api_key = os.getenv("OPENAI_API_KEY")

    def available(self) -> bool:
        return bool(self.api_key and self.api_key.strip())

    def generate(self, instructions: str, user_message: str) -> str:
        if not self.available():
            raise RuntimeError("OPENAI_API_KEY no configurada.")

        from openai import OpenAI

        client = OpenAI(api_key=self.api_key, timeout=30.0)
        response = client.responses.create(
            model=self.model,
            instructions=instructions,
            input=user_message,
        )

        text = getattr(response, "output_text", None)
        if not text or not text.strip():
            raise RuntimeError("El proveedor devolvió una respuesta vacía.")

        return text.strip()
