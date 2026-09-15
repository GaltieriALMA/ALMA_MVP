import json
import tempfile
from pathlib import Path

from backend.memory.memory_manager import MemoryManager
from backend.sessions.session_manager import SessionManager

def test_memory_recovers_from_corrupt_primary():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "memories.json"
        manager = MemoryManager(str(path))
        manager.add("u1", "preference", "Mi color favorito es azul.")
        manager.add("u1", "goal", "Mi objetivo es probar ALMA.")

        # Second write creates a backup containing the first valid state.
        path.write_text("{CORRUPT", encoding="utf-8")

        recovered = MemoryManager(str(path)).list_active("u1")
        assert any("color favorito" in x["content"].lower() for x in recovered)

        # Primary must have been restored as valid JSON.
        json.loads(path.read_text(encoding="utf-8"))

def test_session_recovers_from_corrupt_primary():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "sessions.json"
        manager = SessionManager(str(path))
        manager.append("u1", "s1", "user", "hola")
        manager.append("u1", "s1", "assistant", "hola, soy ALMA")

        path.write_text("not-json", encoding="utf-8")

        recovered = SessionManager(str(path)).recent("u1", "s1")
        assert len(recovered) >= 1
        json.loads(path.read_text(encoding="utf-8"))

def test_invalid_primary_does_not_destroy_good_backup():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "memories.json"
        manager = MemoryManager(str(path))
        manager.add("u1", "preference", "Me gusta el café.")
        manager.add("u1", "goal", "Mi objetivo es continuidad.")

        backup = Path(str(path) + ".bak")
        before = backup.read_text(encoding="utf-8")

        path.write_text("BROKEN", encoding="utf-8")
        MemoryManager(str(path)).list_active("u1")

        assert backup.read_text(encoding="utf-8") == before

if __name__ == "__main__":
    tests = [
        test_memory_recovers_from_corrupt_primary,
        test_session_recovers_from_corrupt_primary,
        test_invalid_primary_does_not_destroy_good_backup,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} RECOVERY TESTS OK")
