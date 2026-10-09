import os
import secrets
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

from backend.security.sources import router as sources_router

_EVENTS = deque(maxlen=500)
_LOCK = Lock()

router.include_router(sources_router)

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


def _require_api_key(
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    expected = os.getenv("ALMA_API_KEY", "").strip()

    if not expected:
        raise HTTPException(
            status_code=503,
            detail="ALMA_API_KEY no configurada.",
        )

    if (
        not x_alma_api_key
        or not secrets.compare_digest(
            x_alma_api_key,
            expected,
        )
    ):
        raise HTTPException(
            status_code=401,
            detail="No autorizado.",
        )


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


@router.post("/events")
def receive_security_event(
    request: SecurityEventRequest,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    _require_api_key(x_alma_api_key)

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

    return event


@router.get("/events/recent")
def recent_security_events(
    limit: int = Query(default=20, ge=1, le=100),
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    _require_api_key(x_alma_api_key)

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
    _require_api_key(x_alma_api_key)

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
