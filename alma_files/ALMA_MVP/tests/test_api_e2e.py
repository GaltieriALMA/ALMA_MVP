import os
import tempfile
from pathlib import Path
from fastapi.testclient import TestClient
import backend.api as api_module
from backend.app import AlmaApplication
from backend.providers.mock_provider import MockProvider

class MockManager:
    def __init__(self):
        self.mock = MockProvider()
    def generate(self, instructions, user_message):
        return self.mock.generate(instructions, user_message), 'mock'

def fresh_app(tmp):
    return AlmaApplication(
        memory_path=str(Path(tmp)/'memories.json'),
        session_path=str(Path(tmp)/'sessions.json'),
        provider=MockManager(),
    )

def test_health_and_mobile_style_chat_roundtrip():
    with tempfile.TemporaryDirectory() as tmp:
        api_module.alma = fresh_app(tmp)
        os.environ["ALMA_API_KEY"] = "test_alma_key"
        client = TestClient(api_module.app)
        health = client.get('/health')
        assert health.status_code == 200
        assert health.json()['status'] == 'ok'

        first = client.post('/chat',
            headers={"X-ALMA-API-Key": "test_alma_key"},
            json={
            'user_id':'mobile_user', 'session_id':'phone_session_1',
            'message':'Mi color favorito es azul.'
        })
        assert first.status_code == 200
        assert first.json()['valid'] is True

        # Simula cierre/reinicio del backend manteniendo almacenamiento persistente.
        api_module.alma = fresh_app(tmp)
        second = client.post('/chat',
            headers={"X-ALMA-API-Key": "test_alma_key"},
            json={
            'user_id':'mobile_user', 'session_id':'phone_session_2',
            'message':'¿Cuál es mi color favorito?'
        })
        assert second.status_code == 200
        assert 'azul' in second.json()['text'].lower()

def test_api_rejects_invalid_payload():
    os.environ["ALMA_API_KEY"] = "test_alma_key"
    client = TestClient(api_module.app)
    response = client.post('/chat',
        headers={"X-ALMA-API-Key": "test_alma_key"},
        json={
        'user_id':'u', 'session_id':'s', 'message':''
    })
    assert response.status_code == 422

if __name__ == '__main__':
    tests=[test_health_and_mobile_style_chat_roundtrip, test_api_rejects_invalid_payload]
    for t in tests:
        t(); print(f'{t.__name__}: OK')
    print(f'TOTAL: {len(tests)} API E2E TESTS OK')
