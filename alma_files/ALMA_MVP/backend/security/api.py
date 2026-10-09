from collections import deque
from datetime import datetime, timezone
from threading import Lock
from typing import Literal
from uuid import uuid4

from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel, Field


router = APIRouter(
    prefix="/security",
    tags=["security"],
)

from backend.security.auth import require_api_key, require_source_token
from backend.security.sources import router as sources_router, get_source_record
from backend.security.alerts import router as alerts_router, create_alert_for_event

_EVENTS = deque(maxlen=500)
_LOCK = Lock()

router.include_router(sources_router)
router.include_router(alerts_router)

SourceType = Literal[
    "camera",
    "nvr",
    "vms",
    "perimeter",
    "access",
    "alarm",
    "fire",
    "panic",
    "intercom",
    "analytics",
    "other",
]

Severity = Literal[
    "info",
    "low",
    "medium",
    "high",
    "critical",
]


class SecurityWebhookRequest(BaseModel):
    event_type: str = Field(
        min_length=1,
        max_length=128,
    )
    description: str = Field(
        min_length=1,
        max_length=1200,
    )
    severity: Severity = "medium"
    confidence: float | None = Field(
        default=None,
        ge=0.0,
        le=1.0,
    )
    occurred_at: datetime | None = None


class SecurityEventRequest(BaseModel):
    source_type: SourceType
    source_id: str = Field(min_length=1, max_length=128)
    zone: str = Field(min_length=1, max_length=256)
    event_type: str = Field(min_length=1, max_length=128)
    description: str = Field(min_length=1, max_length=1200)
    severity: Severity = "medium"
    confidence: float | None = Field(
        default=None,
        ge=0.0,
        le=1.0,
    )
    occurred_at: datetime | None = None


def _utc_iso(value: datetime | None) -> str:
    if value is None:
        value = datetime.now(timezone.utc)
    elif value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    else:
        value = value.astimezone(timezone.utc)

    return value.isoformat()


def evaluate_event(event: SecurityEventRequest) -> dict:
    event_type = event.event_type.strip().lower()

    verified_triggers = {
        "panic_button",
        "fire_alarm",
        "forced_entry",
        "perimeter_intrusion",
    }

    alert_now = (
        event.severity in {"high", "critical"}
        or event_type in verified_triggers
    )

    recommend_911_confirmation = (
        event.severity == "critical"
        and event_type in verified_triggers
        and event.source_type != "analytics"
    )

    return {
        "alert_now": alert_now,
        "notify_monitoring_center": alert_now,
        "recommend_911_confirmation":
            recommend_911_confirmation,
        "requires_human_confirmation_for_911": True,
        "automatic_911_call": False,
    }


def _record_event(
    request: SecurityEventRequest
) -> dict:
    decision = evaluate_event(request)

    event = {
        "event_id": str(uuid4()),
        "source_type": request.source_type,
        "source_id": request.source_id.strip(),
        "zone": request.zone.strip(),
        "event_type": request.event_type.strip(),
        "description": request.description.strip(),
        "severity": request.severity,
        "confidence": request.confidence,
        "occurred_at": _utc_iso(request.occurred_at),
        "received_at": _utc_iso(None),
        "decision": decision,
    }

    with _LOCK:
        _EVENTS.appendleft(event)

    alert = create_alert_for_event(event)

    if alert is not None:
        event["alert_id"] = alert["alert_id"]

    return event


@router.post("/events")
def receive_security_event(
    request: SecurityEventRequest,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)
    return _record_event(request)


@router.post("/ingest/{source_id}")
def ingest_source_event(
    source_id: str,
    request: SecurityWebhookRequest,
    x_alma_source_token: str | None = Header(
        default=None,
        alias="X-ALMA-Source-Token",
    ),
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    source = get_source_record(source_id)

    if source is None:
        raise HTTPException(
            status_code=404,
            detail="Fuente de seguridad no registrada.",
        )

    if not source.get("enabled", False):
        raise HTTPException(
            status_code=409,
            detail="Fuente de seguridad deshabilitada.",
        )

    secret_ref = (
        source.get("secret_ref") or ""
    ).strip()

    if secret_ref:
        require_source_token(
            secret_ref,
            x_alma_source_token,
        )
    else:
        require_api_key(x_alma_api_key)

    event = SecurityEventRequest(
        source_type=source["kind"],
        source_id=source_id,
        zone=source["zone"],
        event_type=request.event_type,
        description=request.description,
        severity=request.severity,
        confidence=request.confidence,
        occurred_at=request.occurred_at,
    )

    recorded = _record_event(event)

    return {
        "accepted": True,
        "source_name": source["name"],
        "event": recorded,
    }


@router.get("/events/recent")
def recent_security_events(
    limit: int = Query(default=20, ge=1, le=100),
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        items = list(_EVENTS)[:limit]

    return {
        "count": len(items),
        "events": items,
    }


@router.get("/status")
def security_status(
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        buffered = len(_EVENTS)

    return {
        "status": "ready",
        "module": "ALMA Seguridad",
        "events_buffered": buffered,
        "supported_sources": [
            "camera",
            "nvr",
            "vms",
            "perimeter",
            "access",
            "alarm",
            "fire",
            "panic",
            "intercom",
            "analytics",
        ],
        "safety": {
            "automatic_911_call": False,
            "human_confirmation_required": True,
        },
    }
