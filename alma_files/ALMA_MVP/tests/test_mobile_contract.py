from mobile_client.contract import ChatPayload, ChatResult

def test_chat_payload_contract():
    payload = ChatPayload("u1", "s1", "Hola ALMA").to_dict()
    assert payload == {
        "user_id": "u1",
        "session_id": "s1",
        "message": "Hola ALMA",
    }

def test_chat_result_contract():
    result = ChatResult.from_dict({
        "text": "Hola",
        "provider": "test",
        "valid": True,
        "issues": [],
    })
    assert result.text == "Hola"
    assert result.provider == "test"
    assert result.valid is True
    assert result.issues == []

if __name__ == "__main__":
    tests = [
        test_chat_payload_contract,
        test_chat_result_contract,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} MOBILE CONTRACT TESTS OK")
