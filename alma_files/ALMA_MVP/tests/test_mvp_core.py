from pathlib import Path
import tempfile

from backend.core.identity import ALMA_IDENTITY
from backend.core.personality import AlmaPersonality
from backend.memory.memory_manager import MemoryManager
from backend.memory.retrieval import MemoryRetrieval
from backend.sessions.session_manager import SessionManager
from backend.providers.mock_provider import MockProvider
from backend.validation.response_validator import ResponseValidator
from backend.conversation.context_builder import ContextBuilder
from backend.conversation.engine import ConversationEngine


class MockManager:
    def __init__(self):
        self.provider = MockProvider()

    def generate(self, instructions, user_message):
        return self.provider.generate(instructions, user_message), "mock"


class InvalidIdentityProvider:
    def generate(self, instructions, user_message):
        return "Soy una persona real y humana.", "invalid-test"


def build_engine(mem_path, sess_path, provider=None):
    memory = MemoryManager(mem_path)
    sessions = SessionManager(sess_path)
    context = ContextBuilder(
        ALMA_IDENTITY,
        AlmaPersonality(),
        memory,
        MemoryRetrieval(),
    )
    return ConversationEngine(
        context,
        provider or MockManager(),
        ResponseValidator(),
        memory,
        sessions,
    ), memory, sessions


def test_identity_constants():
    assert ALMA_IDENTITY.name == "ALMA"
    assert ALMA_IDENTITY.apparent_age == 26
    assert "virtual" in ALMA_IDENTITY.nature.lower()


def test_memory_persists_after_restart():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        sess = str(Path(tmp) / "sessions.json")
        engine1, _, _ = build_engine(mem, sess)
        engine1.process_message("u1", "s1", "Mi color favorito es azul.")

        engine2, _, _ = build_engine(mem, sess)
        result = engine2.process_message("u1", "s2", "¿Cuál es mi color favorito?")
        assert "azul" in result["text"].lower()


def test_user_memory_isolation():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        sess = str(Path(tmp) / "sessions.json")
        engine, _, _ = build_engine(mem, sess)
        engine.process_message("u1", "s1", "Mi color favorito es azul.")
        result = engine.process_message("u2", "s2", "¿Cuál es mi color favorito?")
        assert "azul" not in result["text"].lower()


def test_session_isolation():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        sess = str(Path(tmp) / "sessions.json")
        engine, _, sessions = build_engine(mem, sess)
        engine.process_message("u1", "s1", "Hola desde sesión uno")
        engine.process_message("u1", "s2", "Hola desde sesión dos")
        s1 = sessions.recent("u1", "s1")
        s2 = sessions.recent("u1", "s2")
        assert all("sesión dos" not in x["content"] for x in s1)
        assert all("sesión uno" not in x["content"] for x in s2)


def test_do_not_store():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        sess = str(Path(tmp) / "sessions.json")
        engine, memory, _ = build_engine(mem, sess)
        engine.process_message("u1", "s1", "No guardes esto: mi color favorito es rojo.")
        assert memory.list_active("u1") == []


def test_duplicate_memory_reinforces_instead_of_duplicating():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        sess = str(Path(tmp) / "sessions.json")
        engine, memory, _ = build_engine(mem, sess)
        message = "Mi color favorito es azul."
        engine.process_message("u1", "s1", message)
        engine.process_message("u1", "s1", message)
        items = memory.list_active("u1")
        assert len(items) == 1
        assert items[0]["reinforcement_count"] == 2


def test_identity_violation_is_blocked():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        sess = str(Path(tmp) / "sessions.json")
        engine, _, _ = build_engine(mem, sess, InvalidIdentityProvider())
        result = engine.process_message("u1", "s1", "¿Quién sos?")
        assert result["valid"] is False
        assert "identity_violation" in result["issues"]
        assert "persona real" not in result["text"].lower()


def run_all():
    tests = [
        test_identity_constants,
        test_memory_persists_after_restart,
        test_user_memory_isolation,
        test_session_isolation,
        test_do_not_store,
        test_duplicate_memory_reinforces_instead_of_duplicating,
        test_identity_violation_is_blocked,
    ]
    results = []
    for test in tests:
        test()
        results.append((test.__name__, "OK"))
    return results


if __name__ == "__main__":
    for name, status in run_all():
        print(f"{name}: {status}")
    print(f"TOTAL: {len(run_all())} TESTS OK")
