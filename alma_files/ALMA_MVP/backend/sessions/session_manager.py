import json
import os
from pathlib import Path
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from backend.recovery.safe_json_store import SafeJsonStore


class SessionManager:
    def __init__(self, path: str | None = None):
        default = Path(__file__).resolve().parents[1] / "data" / "sessions.json"
        self.path = Path(path) if path else default
        self.store = SafeJsonStore(self.path, dict)

        self.supabase_url = os.getenv("SUPABASE_URL", "").strip().rstrip("/")
        self.supabase_key = os.getenv("SUPABASE_SERVICE_ROLE_KEY", "").strip()
        self.use_supabase = bool(self.supabase_url and self.supabase_key)

    def _read(self) -> dict:
        data = self.store.read()
        return data if isinstance(data, dict) else {}

    def _write(self, data: dict) -> None:
        self.store.write(data)

    def _supabase_request(self, method: str, query=None, payload=None, prefer=None):
        url = self.supabase_url + "/rest/v1/alma_sessions"

        if query:
            url += "?" + urlencode(query)

        body = None
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")

        headers = {
            "apikey": self.supabase_key,
            "Content-Type": "application/json",
        }

        if not self.supabase_key.startswith("sb_secret_"):
            headers["Authorization"] = "Bearer " + self.supabase_key

        if prefer:
            headers["Prefer"] = prefer

        request = Request(
            url,
            data=body,
            method=method,
            headers=headers,
        )

        with urlopen(request, timeout=12) as response:
            raw = response.read().decode("utf-8").strip()
            return json.loads(raw) if raw else None

    def append(self, user_id: str, session_id: str, role: str, content: str) -> None:
        message = {
            "role": role,
            "content": content,
            "at": datetime.now(timezone.utc).isoformat(),
        }

        if self.use_supabase:
            try:
                existing = self._supabase_request(
                    "GET",
                    {
                        "select": "id,messages",
                        "user_id": "eq." + user_id,
                        "session_id": "eq." + session_id,
                        "limit": "1",
                    },
                ) or []

                if existing:
                    item = existing[0]
                    messages = item.get("messages") or []
                    if not isinstance(messages, list):
                        messages = []

                    messages = (messages + [message])[-20:]

                    self._supabase_request(
                        "PATCH",
                        {"id": "eq." + item["id"]},
                        {
                            "messages": messages,
                            "updated_at": datetime.now(timezone.utc).isoformat(),
                        },
                        "return=minimal",
                    )
                else:
                    self._supabase_request(
                        "POST",
                        None,
                        {
                            "user_id": user_id,
                            "session_id": session_id,
                            "messages": [message],
                            "updated_at": datetime.now(timezone.utc).isoformat(),
                        },
                        "return=minimal",
                    )
                return

            except Exception as exc:
                print(
                    "ALMA_SESSION_SUPABASE_APPEND_ERROR:",
                    type(exc).__name__,
                    str(exc)[:300],
                )

        data = self._read()
        key = f"{user_id}::{session_id}"
        session = data.setdefault(key, {"messages": [], "updated_at": None})
        session["messages"].append(message)
        session["messages"] = session["messages"][-20:]
        session["updated_at"] = datetime.now(timezone.utc).isoformat()
        self._write(data)

    def recent(self, user_id: str, session_id: str, limit: int = 12) -> list[dict]:
        if self.use_supabase:
            try:
                rows = self._supabase_request(
                    "GET",
                    {
                        "select": "messages",
                        "user_id": "eq." + user_id,
                        "session_id": "eq." + session_id,
                        "limit": "1",
                    },
                ) or []

                if rows:
                    messages = rows[0].get("messages") or []
                    if isinstance(messages, list):
                        return messages[-limit:]

            except Exception as exc:
                print(
                    "ALMA_SESSION_SUPABASE_READ_ERROR:",
                    type(exc).__name__,
                    str(exc)[:300],
                )

        data = self._read()
        key = f"{user_id}::{session_id}"
        return data.get(key, {}).get("messages", [])[-limit:]
