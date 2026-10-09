import json
import os
from datetime import datetime, timezone
from threading import Lock, Thread
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from backend.security.persistence import (
    enabled as persistence_enabled,
    list_deliveries as persistent_list_deliveries,
    save_delivery,
)


_LOCK = Lock()
_DELIVERIES: list[dict] = []
_MAX_DELIVERIES = 500


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _save_delivery(item: dict):
    with _LOCK:
        _DELIVERIES.insert(0, item)

        if len(_DELIVERIES) > _MAX_DELIVERIES:
            del _DELIVERIES[_MAX_DELIVERIES:]

    if persistence_enabled():
        try:
            save_delivery(item)
        except Exception as exc:
            print(
                "ALMA_SECURITY_DELIVERY_SAVE_ERROR:",
                type(exc).__name__,
            )


def recent_deliveries(limit: int = 50) -> list[dict]:
    if persistence_enabled():
        try:
            return persistent_list_deliveries(limit)
        except Exception as exc:
            print(
                "ALMA_SECURITY_DELIVERY_READ_ERROR:",
                type(exc).__name__,
            )

    with _LOCK:
        return [
            dict(item)
            for item in _DELIVERIES[:limit]
        ]


def _send(alert: dict):
    url = os.getenv(
        "ALMA_MONITORING_WEBHOOK_URL",
        "",
    ).strip()

    token = os.getenv(
        "ALMA_MONITORING_WEBHOOK_TOKEN",
        "",
    ).strip()

    delivery = {
        "alert_id": alert.get("alert_id"),
        "attempted_at": _now(),
        "destination": "monitoring_center",
        "status": "pending",
    }

    if not url:
        delivery["status"] = "not_configured"
        _save_delivery(delivery)
        return

    body = {
        "type": "alma_security_alert",
        "alert_id": alert.get("alert_id"),
        "event_id": alert.get("event_id"),
        "severity": alert.get("severity"),
        "zone": alert.get("zone"),
        "event_type": alert.get("event_type"),
        "description": alert.get("description"),
        "created_at": alert.get("created_at"),
        "requires_human_confirmation_for_911":
            alert.get(
                "requires_human_confirmation_for_911",
                True,
            ),
        "automatic_911_call": False,
    }

    headers = {
        "Content-Type": "application/json",
        "User-Agent": "ALMA-Security/1.0",
    }

    if token:
        headers["Authorization"] = (
            "Bearer " + token
        )

    request = Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )

    try:
        with urlopen(
            request,
            timeout=5,
        ) as response:
            code = int(
                getattr(response, "status", 200)
            )

        delivery["http_status"] = code
        delivery["status"] = (
            "delivered"
            if 200 <= code < 300
            else "failed"
        )

    except HTTPError as exc:
        delivery["status"] = "failed"
        delivery["http_status"] = exc.code
        delivery["error"] = "http_error"

    except URLError:
        delivery["status"] = "failed"
        delivery["error"] = "network_error"

    except Exception:
        delivery["status"] = "failed"
        delivery["error"] = "unexpected_error"

    _save_delivery(delivery)


def notify_monitoring_center(
    alert: dict
):
    if not alert.get(
        "notify_monitoring_center",
        False,
    ):
        return

    Thread(
        target=_send,
        args=(dict(alert),),
        daemon=True,
    ).start()
