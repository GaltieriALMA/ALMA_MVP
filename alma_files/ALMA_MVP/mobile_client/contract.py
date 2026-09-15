from dataclasses import dataclass

API_VERSION = "1.0"
CHAT_PATH = "/chat"
HEALTH_PATH = "/health"

@dataclass(frozen=True)
class ChatPayload:
    user_id: str
    session_id: str
    message: str

    def to_dict(self) -> dict:
        return {
            "user_id": self.user_id,
            "session_id": self.session_id,
            "message": self.message,
        }

@dataclass(frozen=True)
class ChatResult:
    text: str
    provider: str
    valid: bool
    issues: list[str]

    @classmethod
    def from_dict(cls, data: dict):
        return cls(
            text=str(data["text"]),
            provider=str(data["provider"]),
            valid=bool(data["valid"]),
            issues=list(data.get("issues", [])),
        )
