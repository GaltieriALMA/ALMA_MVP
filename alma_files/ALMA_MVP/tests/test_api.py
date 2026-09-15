import tempfile
from pathlib import Path

from backend.app import AlmaApplication

class DeterministicProvider:
    def generate(self, instructions, user_message):
        return "Hola. Soy ALMA y el backend está operativo.", "test"

def test_shared_application_core():
    with tempfile.TemporaryDirectory() as tmp:
        alma = AlmaApplication(
            memory_path=str(Path(tmp) / "memories.json"),
            session_path=str(Path(tmp) / "sessions.json"),
            provider=DeterministicProvider(),
        )
        result = alma.chat("u1", "s1", "Hola")
        assert result["valid"] is True
        assert result["provider"] == "test"
        assert "ALMA" in result["text"]

def test_api_module_contract():
    from backend.api import app
    routes = {route.path for route in app.routes}
    assert "/health" in routes
    assert "/chat" in routes

if __name__ == "__main__":
    tests = [
        test_shared_application_core,
        test_api_module_contract,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} API TESTS OK")
