import json
from urllib import request, error
from .contract import ChatPayload, ChatResult, CHAT_PATH, HEALTH_PATH

class AlmaMobileClient:
    def __init__(self, base_url: str, timeout: float = 20.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _json_request(self, path: str, method: str = "GET", payload=None):
        body = None
        headers = {"Accept": "application/json"}

        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"

        req = request.Request(
            self.base_url + path,
            data=body,
            headers=headers,
            method=method,
        )

        try:
            with request.urlopen(req, timeout=self.timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            raise RuntimeError(f"ALMA API HTTP {exc.code}") from exc
        except error.URLError as exc:
            raise RuntimeError("No se pudo conectar con ALMA API") from exc

    def health(self) -> dict:
        return self._json_request(HEALTH_PATH)

    def chat(self, user_id: str, session_id: str, message: str) -> ChatResult:
        clean = (message or "").strip()
        if not clean:
            raise ValueError("El mensaje no puede estar vacío.")

        payload = ChatPayload(
            user_id=user_id,
            session_id=session_id,
            message=clean,
        )

        data = self._json_request(
            CHAT_PATH,
            method="POST",
            payload=payload.to_dict(),
        )

        return ChatResult.from_dict(data)
