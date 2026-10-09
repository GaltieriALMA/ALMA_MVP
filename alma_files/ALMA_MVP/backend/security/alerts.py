from collections import deque
from datetime import datetime, timezone
from threading import Lock
from uuid import uuid4

from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel, Field

from backend.security.auth import require_api_key
from backend.security.monitoring import notify_monitoring_center, recent_deliveries


router = APIRouter(
    prefix="/alerts",
    tags=["security-alerts"],
)

_LOCK = Lock()
_ALERTS = deque(maxlen=500)
_ALERTS_BY_ID: dict[str, dict] = {}


class AlertActionRequest(BaseModel):
    operator: str = Field(min_length=1, max_length=128)
    note: str | None = Field(default=None, max_length=500)


class EmergencyConfirmationRequest(BaseModel):
    operator: str = Field(min_length=1, max_length=128)
    confirmed: bool
    note: str | None = Field(default=None, max_length=500)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def create_alert_for_event(event: dict) -> dict | None:
    decision = event.get("decision") or {}

    if not decision.get("alert_now", False):
        return None

    alert = {
        "alert_id": str(uuid4()),
        "event_id": event["event_id"],
        "source_type": event["source_type"],
        "source_id": event["source_id"],
        "zone": event["zone"],
        "event_type": event["event_type"],
        "description": event["description"],
        "severity": event["severity"],
        "status": "open",
        "created_at": _now(),
        "acknowledged_at": None,
        "acknowledged_by": None,
        "dismissed_at": None,
        "dismissed_by": None,
        "notify_monitoring_center": bool(
            decision.get("notify_monitoring_center", False)
        ),
        "recommend_911_confirmation": bool(
            decision.get("recommend_911_confirmation", False)
        ),
        "requires_human_confirmation_for_911": True,
        "automatic_911_call": False,
        "emergency_confirmation": "pending"
        if decision.get("recommend_911_confirmation", False)
        else "not_required",
        "next_action": "notify_monitoring_center"
        if decision.get("notify_monitoring_center", False)
        else "review",
    }

    with _LOCK:
        if len(_ALERTS) >= _ALERTS.maxlen:
            old = _ALERTS[-1]
            _ALERTS_BY_ID.pop(old["alert_id"], None)

        _ALERTS.appendleft(alert)
        _ALERTS_BY_ID[alert["alert_id"]] = alert

    result = dict(alert)

    notify_monitoring_center(result)

    return result


@router.get("/deliveries")
def monitoring_deliveries(
    limit: int = Query(
        default=50,
        ge=1,
        le=100,
    ),
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    items = recent_deliveries(limit)

    return {
        "count": len(items),
        "deliveries": items,
    }


@router.get("/open")
def open_alerts(
    limit: int = Query(default=50, ge=1, le=100),
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        items = [
            dict(item)
            for item in _ALERTS
            if item["status"] not in {"dismissed", "closed"}
        ][:limit]

    return {
        "count": len(items),
        "alerts": items,
    }


@router.post("/{alert_id}/acknowledge")
def acknowledge_alert(
    alert_id: str,
    request: AlertActionRequest,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        alert = _ALERTS_BY_ID.get(alert_id)

        if alert is None:
            raise HTTPException(
                status_code=404,
                detail="Alerta de seguridad no encontrada.",
            )

        alert["status"] = "acknowledged"
        alert["acknowledged_at"] = _now()
        alert["acknowledged_by"] = request.operator.strip()

        return dict(alert)


@router.post("/{alert_id}/confirm-emergency")
def confirm_emergency(
    alert_id: str,
    request: EmergencyConfirmationRequest,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        alert = _ALERTS_BY_ID.get(alert_id)

        if alert is None:
            raise HTTPException(
                status_code=404,
                detail="Alerta de seguridad no encontrada.",
            )

        if not alert["recommend_911_confirmation"]:
            raise HTTPException(
                status_code=409,
                detail="Esta alerta no requiere escalamiento de emergencia.",
            )

        alert["emergency_confirmed_by"] = request.operator.strip()
        alert["emergency_confirmed_at"] = _now()

        if request.confirmed:
            alert["emergency_confirmation"] = "confirmed"
            alert["status"] = "emergency_confirmed"
            alert["next_action"] = "contact_emergency_services"
        else:
            alert["emergency_confirmation"] = "declined"
            alert["status"] = "acknowledged"
            alert["next_action"] = "monitor"

        return dict(alert)
