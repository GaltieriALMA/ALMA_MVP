from pathlib import Path
from datetime import datetime, timezone
from uuid import uuid4
from backend.recovery.safe_json_store import SafeJsonStore

class MemoryManager:
    def __init__(self, path: str | None = None):
        default = Path(__file__).resolve().parents[1] / "data" / "memories.json"
        self.path = Path(path) if path else default
        self.store = SafeJsonStore(self.path, list)

    def _read(self) -> list[dict]:
        data = self.store.read()
        return data if isinstance(data, list) else []

    def _write(self, data: list[dict]) -> None:
        self.store.write(data)

    def add(self, user_id: str, kind: str, content: str) -> dict:
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
        return [
            x for x in self._read()
            if x.get("user_id") == user_id and x.get("status") == "active"
        ]
