from backend.providers.provider_manager import ProviderManager

class FakeOpenAI:
    def __init__(self, available=True, fail=False):
        self._available = available
        self.fail = fail

    def available(self):
        return self._available

    def generate(self, instructions, user_message):
        if self.fail:
            raise RuntimeError("simulated provider failure")
        return "Respuesta real simulada"

class FakeMock:
    def generate(self, instructions, user_message):
        return "Respuesta fallback"

def test_primary_provider_selected():
    manager = ProviderManager(
        openai_provider=FakeOpenAI(available=True, fail=False),
        mock_provider=FakeMock(),
    )
    text, provider = manager.generate("ctx", "hola")
    assert provider == "openai"
    assert text == "Respuesta real simulada"
    assert manager.last_error is None

def test_fallback_when_key_unavailable():
    manager = ProviderManager(
        openai_provider=FakeOpenAI(available=False),
        mock_provider=FakeMock(),
    )
    text, provider = manager.generate("ctx", "hola")
    assert provider == "mock"
    assert text == "Respuesta fallback"

def test_fallback_when_primary_fails():
    manager = ProviderManager(
        openai_provider=FakeOpenAI(available=True, fail=True),
        mock_provider=FakeMock(),
    )
    text, provider = manager.generate("ctx", "hola")
    assert provider == "mock"
    assert text == "Respuesta fallback"
    assert manager.last_error["provider"] == "openai"

if __name__ == "__main__":
    tests = [
        test_primary_provider_selected,
        test_fallback_when_key_unavailable,
        test_fallback_when_primary_fails,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} PROVIDER TESTS OK")
