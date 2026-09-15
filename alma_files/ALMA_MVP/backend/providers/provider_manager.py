from .mock_provider import MockProvider
from .openai_provider import OpenAIProvider

class ProviderManager:
    def __init__(self, openai_provider=None, mock_provider=None):
        self.openai = openai_provider or OpenAIProvider()
        self.mock = mock_provider or MockProvider()
        self.last_error = None

    def generate(self, instructions: str, user_message: str) -> tuple[str, str]:
        self.last_error = None

        if self.openai.available():
            try:
                return (
                    self.openai.generate(instructions, user_message),
                    "openai"
                )
            except Exception as exc:
                self.last_error = {
                    "provider": "openai",
                    "error_type": type(exc).__name__,
                    "message": str(exc)[:300],
                }

        return (
            self.mock.generate(instructions, user_message),
            "mock"
        )
