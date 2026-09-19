import os
from fastapi.testclient import TestClient
import backend.api as api_module

PAYLOAD = {
    "user_id": "auth_test",
    "session_id": "auth_test",
    "message": "Hola",
}

class FakeAlma:
    def chat(self, user_id, session_id, message):
        return {
            "text": "AUTH_OK",
            "provider": "test",
            "valid": True,
            "issues": [],
        }

def test_missing_key_rejected():
    os.environ["ALMA_API_KEY"] = "test_alma_key"
    client = TestClient(api_module.app)
    r = client.post("/chat", json=PAYLOAD)
    assert r.status_code == 401

def test_wrong_key_rejected():
    os.environ["ALMA_API_KEY"] = "test_alma_key"
    client = TestClient(api_module.app)
    r = client.post(
        "/chat",
        headers={"X-ALMA-API-Key": "wrong_key"},
        json=PAYLOAD,
    )
    assert r.status_code == 401

def test_valid_key_accepted():
    os.environ["ALMA_API_KEY"] = "test_alma_key"
    api_module.alma = FakeAlma()
    client = TestClient(api_module.app)
    r = client.post(
        "/chat",
        headers={"X-ALMA-API-Key": "test_alma_key"},
        json=PAYLOAD,
    )
    assert r.status_code == 200
    assert r.json()["text"] == "AUTH_OK"

if __name__ == "__main__":
    tests = [
        test_missing_key_rejected,
        test_wrong_key_rejected,
        test_valid_key_accepted,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} API AUTH TESTS OK")
