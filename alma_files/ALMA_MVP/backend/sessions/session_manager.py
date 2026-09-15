from pathlib import Path
from datetime import datetime, timezone
from backend.recovery.safe_json_store import SafeJsonStore

class SessionManager:
    def __init__(self, path: str | None = None):
        default = Path(__file__).resolve().parents[1] / "data" / "sessions.json"
        self.path = Path(path) if path else default
        self.store = SafeJsonStore(self.path, dict)

    def _read(self) -> dict:
        data = self.store.read()
        return data if isinstance(data, dict) else {}

    def _write(self, data: dict) -> None:
        self.store.write(data)

    def append(self, user_id: str, session_id: str, role: str, content: str) -> None:
        data = self._read()
        key = f"{user_id}::{session_id}"
        session = data.setdefault(key, {"messages": [], "updated_at": None})
        session["messages"].append({
            "role": role,
            "content": content,
            "at": datetime.now(timezone.utc).isoformat(),
        })
        session["messages"] = session["messages"][-20:]
        session["updated_at"] = datetime.now(timezone.utc).isoformat()
        self._write(data)

    def recent(self, user_id: str, session_id: str, limit: int = 12) -> list[dict]:
        data = self._read()
        key = f"{user_id}::{session_id}"
        return data.get(key, {}).get("messages", [])[-limit:]
