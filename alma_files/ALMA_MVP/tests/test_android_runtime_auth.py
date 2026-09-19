from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android_app"

def test_no_static_client_token_in_build():
    build = (ANDROID / "app/build.gradle.kts").read_text()
    assert "ALMA_CLIENT_TOKEN" not in build

def test_runtime_auth_header_present():
    client = (
        ANDROID /
        "app/src/main/java/com/alma/mvp/AlmaApiClient.java"
    ).read_text()
    assert '"X-ALMA-API-Key"' in client
    assert "accessToken" in client

def test_secure_token_store_uses_keystore():
    store = (
        ANDROID /
        "app/src/main/java/com/alma/mvp/SecureTokenStore.java"
    ).read_text()
    assert "AndroidKeyStore" in store
    assert "AES/GCM/NoPadding" in store

if __name__ == "__main__":
    tests = [
        test_no_static_client_token_in_build,
        test_runtime_auth_header_present,
        test_secure_token_store_uses_keystore,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} ANDROID RUNTIME AUTH TESTS OK")
