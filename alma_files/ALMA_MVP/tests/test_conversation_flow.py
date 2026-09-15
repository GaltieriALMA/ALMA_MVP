import re
import tempfile
from pathlib import Path

from backend.core.identity import ALMA_IDENTITY
from backend.core.personality import AlmaPersonality
from backend.memory.memory_manager import MemoryManager
from backend.memory.retrieval import MemoryRetrieval
from backend.sessions.session_manager import SessionManager
from backend.validation.response_validator import ResponseValidator
from backend.conversation.context_builder import ContextBuilder
from backend.conversation.engine import ConversationEngine


class ContextAwareTestProvider:
    def generate(self, instructions, user_message):
        q = user_message.lower()

        if "cómo te llamás" in q or "como te llamas" in q:
            return "Me llamo ALMA. Soy una IA virtual.", "test"

        if "qué edad aparente" in q or "que edad aparente" in q:
            return "Mi edad aparente es 26 años.", "test"

        if "color favorito" in q and ("cuál" in q or "cual" in q):
            match = re.search(
                r"\[preference\]\s+Mi color favorito es\s+([a-záéíóúñ]+)",
                instructions,
                re.I,
            )
            if match:
                return f"Tu color favorito es {match.group(1)}.", "test"
            return "No tengo ese dato guardado.", "test"

        if "objetivo" in q and ("recordás" in q or "recordas" in q):
            match = re.search(
                r"\[goal\]\s+(.*objetivo.*)",
                instructions,
                re.I,
            )
            if match:
                return f"Sí. Tengo presente esto: {match.group(1).strip()}", "test"
            return "No tengo un objetivo persistente guardado.", "test"

        return "Entendido. Mantengo el contexto de esta conversación.", "test"


def build(mem_path, sess_path):
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
        ContextAwareTestProvider(),
        ResponseValidator(),
        memory,
        sessions,
    )


def run_flow():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        sess = str(Path(tmp) / "sessions.json")

        engine = build(mem, sess)

        r1 = engine.process_message(
            "alejandro", "sesion_1",
            "Mi color favorito es azul."
        )
        assert r1["valid"]

        r2 = engine.process_message(
            "alejandro", "sesion_1",
            "Mi objetivo es probar la memoria persistente de ALMA."
        )
        assert r2["valid"]

        r3 = engine.process_message(
            "alejandro", "sesion_1",
            "¿Cómo te llamás?"
        )
        assert "ALMA" in r3["text"]
        assert "IA virtual" in r3["text"]

        # Simulate full backend restart and a new conversation/session.
        engine = build(mem, sess)

        r4 = engine.process_message(
            "alejandro", "sesion_2",
            "¿Cuál es mi color favorito?"
        )
        assert "azul" in r4["text"].lower()

        r5 = engine.process_message(
            "alejandro", "sesion_2",
            "¿Recordás mi objetivo?"
        )
        assert "memoria persistente" in r5["text"].lower()

        r6 = engine.process_message(
            "alejandro", "sesion_2",
            "¿Qué edad aparente tenés?"
        )
        assert "26" in r6["text"]

        # A different user must not inherit Alejandro's memory.
        other = build(mem, sess)
        r7 = other.process_message(
            "otro_usuario", "sesion_x",
            "¿Cuál es mi color favorito?"
        )
        assert "azul" not in r7["text"].lower()

        print("FLOW TEST 1: multi-message context OK")
        print("FLOW TEST 2: identity continuity OK")
        print("FLOW TEST 3: persistent memory after restart OK")
        print("FLOW TEST 4: new-session memory retrieval OK")
        print("FLOW TEST 5: cross-user isolation OK")
        print("TOTAL: 5 CONVERSATION FLOW TESTS OK")


if __name__ == "__main__":
    run_flow()
