from backend.core.identity import ALMA_IDENTITY
from backend.core.personality import AlmaPersonality
from backend.memory.memory_manager import MemoryManager
from backend.memory.retrieval import MemoryRetrieval
from backend.sessions.session_manager import SessionManager
from backend.providers.provider_manager import ProviderManager
from backend.conversation.context_builder import ContextBuilder
from backend.validation.response_validator import ResponseValidator
from backend.conversation.engine import ConversationEngine

class AlmaApplication:
    def __init__(
        self,
        memory_path: str | None = None,
        session_path: str | None = None,
        provider=None,
    ):
        self.identity = ALMA_IDENTITY
        self.personality = AlmaPersonality()
        self.memory = MemoryManager(memory_path)
        self.retrieval = MemoryRetrieval()
        self.sessions = SessionManager(session_path)
        self.provider = provider or ProviderManager()
        self.validator = ResponseValidator()

        self.context_builder = ContextBuilder(
            identity=self.identity,
            personality=self.personality,
            memory=self.memory,
            retrieval=self.retrieval,
        )

        self.engine = ConversationEngine(
            context_builder=self.context_builder,
            provider=self.provider,
            validator=self.validator,
            memory=self.memory,
            sessions=self.sessions,
        )

    def chat(self, user_id: str, session_id: str, message: str) -> dict:
        return self.engine.process_message(user_id, session_id, message)
