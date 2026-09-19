from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android_app"

def test_android_structure():
    required = [
        ANDROID / "settings.gradle.kts",
        ANDROID / "app/build.gradle.kts",
        ANDROID / "app/src/main/AndroidManifest.xml",
        ANDROID / "app/src/main/java/com/alma/mvp/MainActivity.java",
        ANDROID / "app/src/main/java/com/alma/mvp/AlmaApiClient.java",
        ANDROID / "app/src/main/java/com/alma/mvp/SecureTokenStore.java",
        ANDROID / "app/src/main/res/layout/activity_main.xml",
    ]
    assert all(p.exists() for p in required)

def test_android_uses_api_contract():
    client = (ANDROID / "app/src/main/java/com/alma/mvp/AlmaApiClient.java").read_text()
    assert '"/chat"' in client
    assert '"user_id"' in client
    assert '"session_id"' in client
    assert '"message"' in client
    assert 'getString("text")' in client
    assert '"X-ALMA-API-Key"' in client
    assert "accessToken" in client

def test_no_openai_key_in_android_source():
    text_suffixes = {".java", ".kt", ".kts", ".xml", ".properties", ".gradle", ".md", ".txt", ".json"}
    combined = "\n".join(
        p.read_text(errors="ignore")
        for p in ANDROID.rglob("*")
        if p.is_file()
        and "build" not in p.parts
        and ".gradle" not in p.parts
        and p.suffix.lower() in text_suffixes
    )
    assert "OPENAI_API_KEY=" not in combined
    assert "sk-" not in combined

if __name__ == "__main__":
    tests = [
        test_android_structure,
        test_android_uses_api_contract,
        test_no_openai_key_in_android_source,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} ANDROID CLIENT TESTS OK")
