import json
import os
from pathlib import Path
from datetime import datetime, timezone
from uuid import uuid4
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from backend.recovery.safe_json_store import SafeJsonStore


class MemoryManager:
    def __init__(self, path: str | None = None):
        default = Path(__file__).resolve().parents[1] / "data" / "memories.json"
        self.path = Path(path) if path else default
        self.store = SafeJsonStore(self.path, list)

        self.supabase_url = os.getenv("SUPABASE_URL", "").strip().rstrip("/")
        self.supabase_key = os.getenv("SUPABASE_SERVICE_ROLE_KEY", "").strip()
        self.use_supabase = bool(self.supabase_url and self.supabase_key)

    def _read(self) -> list[dict]:
        data = self.store.read()
        return data if isinstance(data, list) else []

    def _write(self, data: list[dict]) -> None:
        self.store.write(data)

    def _supabase_request(self, method: str, query=None, payload=None, prefer=None):
        url = self.supabase_url + "/rest/v1/alma_memories"

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

    def _supabase_add(self, user_id: str, kind: str, content: str) -> dict:
        normalized = " ".join((content or "").lower().split())
        now = datetime.now(timezone.utc).isoformat()

        existing = self._supabase_request(
            "GET",
            {
                "select": "id,user_id,kind,content,normalized,status,reinforcement_count,created_at,updated_at",
                "user_id": "eq." + user_id,
                "status": "eq.active",
                "normalized": "eq." + normalized,
                "limit": "1",
            },
        ) or []

        if existing:
            item = existing[0]
            count = int(item.get("reinforcement_count", 1)) + 1

            updated = self._supabase_request(
                "PATCH",
                {"id": "eq." + item["id"]},
                {
                    "reinforcement_count": count,
                    "updated_at": now,
                },
                "return=representation",
            ) or []

            return updated[0] if updated else item

        created = self._supabase_request(
            "POST",
            None,
            {
                "user_id": user_id,
                "kind": kind,
                "content": content.strip(),
                "normalized": normalized,
                "status": "active",
                "reinforcement_count": 1,
            },
            "return=representation",
        ) or []

        return created[0]

    def _supabase_list_active(self, user_id: str) -> list[dict]:
        data = self._supabase_request(
            "GET",
            {
                "select": "id,user_id,kind,content,normalized,status,reinforcement_count,created_at,updated_at",
                "user_id": "eq." + user_id,
                "status": "eq.active",
                "order": "updated_at.desc",
            },
        )

        return data if isinstance(data, list) else []

    def add(self, user_id: str, kind: str, content: str) -> dict:
        if self.use_supabase:
            try:
                return self._supabase_add(user_id, kind, content)
            except Exception as exc:
                print(
                    "ALMA_MEMORY_SUPABASE_ADD_ERROR:",
                    type(exc).__name__,
                    str(exc)[:300],
                )

        data = self._read()
        normalized = " ".join((content or "").lower().split())

        for item in data:
            if (
                item.get("user_id") == user_id
                and item.get("status") == "active"
                and item.get("normalized") == normalized
            ):
                item["reinforcement_count"] = item.get("reinforcement_count", 1) + 1
                item["updated_at"] = datetime.now(timezone.utc).isoformat()
                self._write(data)
                return item

        now = datetime.now(timezone.utc).isoformat()

        item = {
            "id": str(uuid4()),
            "user_id": user_id,
            "kind": kind,
            "content": content.strip(),
            "normalized": normalized,
            "status": "active",
            "reinforcement_count": 1,
            "created_at": now,
            "updated_at": now,
        }

        data.append(item)
        self._write(data)
        return item

    def list_active(self, user_id: str) -> list[dict]:
        if self.use_supabase:
            try:
                return self._supabase_list_active(user_id)
            except Exception as exc:
                print(
                    "ALMA_MEMORY_SUPABASE_READ_ERROR:",
                    type(exc).__name__,
                    str(exc)[:300],
                )

        return [
            x for x in self._read()
            if x.get("user_id") == user_id
            and x.get("status") == "active"
        ]
