import os

from fastapi.testclient import TestClient

import backend.security.alerts as alerts
import backend.security.api as security_api
import backend.security.monitoring as monitoring
import backend.security.sources as sources
from backend.api import app


API_KEY = "alma_test_key"


def reset_state():
    os.environ["ALMA_API_KEY"] = API_KEY
    os.environ.pop("ALMA_MONITORING_WEBHOOK_URL", None)
    os.environ.pop("ALMA_MONITORING_WEBHOOK_TOKEN", None)
    os.environ.pop("ALMA_TEST_SOURCE_TOKEN", None)

    with sources._LOCK:
        sources._SOURCES.clear()

    with security_api._LOCK:
        security_api._EVENTS.clear()

    with alerts._LOCK:
        alerts._ALERTS.clear()
        alerts._ALERTS_BY_ID.clear()

    with monitoring._LOCK:
        monitoring._DELIVERIES.clear()


def headers():
    return {"X-ALMA-API-Key": API_KEY}


def test_security_requires_auth():
    reset_state()
    client = TestClient(app)

    r = client.get("/security/status")
    assert r.status_code == 401

    r = client.get("/security/status", headers=headers())
    assert r.status_code == 200

    body = r.json()
    assert body["status"] == "ready"
    assert body["safety"]["automatic_911_call"] is False
    assert body["safety"]["human_confirmation_required"] is True


def test_register_ingest_alert_and_confirmation():
    reset_state()
    client = TestClient(app)

    r = client.post(
        "/security/sources",
        headers=headers(),
        json={
            "name": "Camara acceso principal",
            "kind": "camera",
            "zone": "Acceso principal",
            "connection_mode": "webhook",
            "capabilities": [
                "live_view",
                "motion_events",
            ],
            "enabled": True,
        },
    )

    assert r.status_code == 200
    source_id = r.json()["source_id"]

    r = client.post(
        f"/security/ingest/{source_id}",
        headers=headers(),
        json={
            "event_type": "forced_entry",
            "description": "Ingreso forzado detectado",
            "severity": "critical",
            "confidence": 0.97,
        },
    )

    assert r.status_code == 200

    event = r.json()["event"]

    assert event["decision"]["alert_now"] is True
    assert event["decision"]["automatic_911_call"] is False

    alert_id = event["alert_id"]

    r = client.get(
        "/security/alerts/open",
        headers=headers(),
    )

    assert r.status_code == 200

    found = [
        item
        for item in r.json()["alerts"]
        if item["alert_id"] == alert_id
    ]

    assert len(found) == 1
    assert found[0]["automatic_911_call"] is False
    assert found[0]["requires_human_confirmation_for_911"] is True

    r = client.post(
        f"/security/alerts/{alert_id}/confirm-emergency",
        headers=headers(),
        json={
            "operator": "operador_test",
            "confirmed": True,
            "note": "Prueba automatizada",
        },
    )

    assert r.status_code == 200

    body = r.json()

    assert body["emergency_confirmation"] == "confirmed"
    assert body["next_action"] == "contact_emergency_services"
    assert body["automatic_911_call"] is False


def test_source_token_is_enforced():
    reset_state()

    os.environ["ALMA_TEST_SOURCE_TOKEN"] = "source_secret"

    client = TestClient(app)

    r = client.post(
        "/security/sources",
        headers=headers(),
        json={
            "name": "NVR perimetral",
            "kind": "nvr",
            "zone": "Perimetro",
            "connection_mode": "webhook",
            "secret_ref": "ALMA_TEST_SOURCE_TOKEN",
            "capabilities": ["perimeter_events"],
            "enabled": True,
        },
    )

    assert r.status_code == 200
    source_id = r.json()["source_id"]

    r = client.post(
        f"/security/ingest/{source_id}",
        headers=headers(),
        json={
            "event_type": "perimeter_intrusion",
            "description": "Sin token",
            "severity": "high",
        },
    )

    assert r.status_code == 401

    r = client.post(
        f"/security/ingest/{source_id}",
        headers={
            "X-ALMA-Source-Token": "source_secret"
        },
        json={
            "event_type": "perimeter_intrusion",
            "description": "Con token",
            "severity": "high",
        },
    )

    assert r.status_code == 200


def test_analytics_source():
    reset_state()
    client = TestClient(app)

    r = client.post(
        "/security/sources",
        headers=headers(),
        json={
            "name": "Analitica de video",
            "kind": "analytics",
            "zone": "Perimetro",
            "connection_mode": "vendor_api",
            "capabilities": ["object_events"],
            "enabled": True,
        },
    )

    assert r.status_code == 200
    assert r.json()["kind"] == "analytics"


def test_monitoring_destination_not_configured():
    reset_state()

    monitoring._send({
        "alert_id": "TEST-ALERT",
        "event_id": "TEST-EVENT",
        "severity": "critical",
        "zone": "Acceso",
        "event_type": "forced_entry",
        "description": "Prueba",
        "created_at": monitoring._now(),
        "requires_human_confirmation_for_911": True,
    })

    items = monitoring.recent_deliveries()

    assert len(items) == 1
    assert items[0]["status"] == "not_configured"


if __name__ == "__main__":
    tests = [
        test_security_requires_auth,
        test_register_ingest_alert_and_confirmation,
        test_source_token_is_enforced,
        test_analytics_source,
        test_monitoring_destination_not_configured,
    ]

    for test in tests:
        test()
        print(f"{test.__name__}: OK")

    print(f"TOTAL: {len(tests)} SECURITY TESTS OK")
