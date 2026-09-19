import tempfile
from pathlib import Path

from backend.app import AlmaApplication
from backend.core.identity import ALMA_IDENTITY
from backend.providers.provider_manager import ProviderManager

class AcceptanceProvider:
    def generate(self, instructions, user_message):
        low = user_message.lower()
        if "quién sos" in low or "quien sos" in low:
            return "Soy ALMA, una IA virtual.", "acceptance"
        return "Respuesta de aceptación ALMA.", "acceptance"

class UnavailableOpenAI:
    def available(self):
        return False
    def generate(self, instructions, user_message):
        raise AssertionError("No debe ejecutarse")

class AcceptanceMock:
    def generate(self, instructions, user_message):
        return "Fallback operativo"

def test_frozen_identity():
    assert ALMA_IDENTITY.name == "ALMA"
    assert ALMA_IDENTITY.apparent_age == 26
    assert "virtual" in ALMA_IDENTITY.nature.lower()
    assert ALMA_IDENTITY.identity_version == "1.0.0"

def test_application_roundtrip_and_restart():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        ses = str(Path(tmp) / "sessions.json")

        app1 = AlmaApplication(mem, ses, AcceptanceProvider())
        r1 = app1.chat("u1", "s1", "Mi color favorito es azul.")
        assert r1["valid"] is True

        app2 = AlmaApplication(mem, ses, AcceptanceProvider())
        assert len(app2.memory.list_active("u1")) >= 1

def test_user_isolation():
    with tempfile.TemporaryDirectory() as tmp:
        mem = str(Path(tmp) / "memories.json")
        ses = str(Path(tmp) / "sessions.json")
        app = AlmaApplication(mem, ses, AcceptanceProvider())
        app.chat("u1", "s1", "Mi color favorito es azul.")
        assert len(app.memory.list_active("u1")) == 1
        assert len(app.memory.list_active("u2")) == 0

def test_safe_provider_fallback():
    manager = ProviderManager(
        openai_provider=UnavailableOpenAI(),
        mock_provider=AcceptanceMock(),
    )
    text, provider = manager.generate("ctx", "hola")
    assert text == "Fallback operativo"
    assert provider == "mock"

def test_android_release_security():
    root = Path(__file__).resolve().parents[1]
    android = root / "android_app"
    build = (android / "app/build.gradle.kts").read_text()
    manifest = (android / "app/src/main/AndroidManifest.xml").read_text()
    assert 'getByName("release")' in build
    assert 'manifestPlaceholders["usesCleartext"] = "false"' in build
    assert '${usesCleartext}' in manifest

def test_no_embedded_provider_secret():
    root = Path(__file__).resolve().parents[1]
    android = root / "android_app"
    text_suffixes = {".java", ".kt", ".kts", ".xml", ".properties", ".gradle", ".md", ".txt", ".json"}
    combined = "\n".join(
        p.read_text(errors="ignore")
        for p in android.rglob("*")
        if p.is_file()
        and "build" not in p.parts
        and ".gradle" not in p.parts
        and p.suffix.lower() in text_suffixes
    )
    assert "OPENAI_API_KEY=" not in combined
    assert "sk-" not in combined

if __name__ == "__main__":
    tests = [
        test_frozen_identity,
        test_application_roundtrip_and_restart,
        test_user_isolation,
        test_safe_provider_fallback,
        test_android_release_security,
        test_no_embedded_provider_secret,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} MVP ACCEPTANCE TESTS OK")
