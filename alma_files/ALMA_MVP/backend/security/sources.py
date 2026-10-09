from threading import Lock
from typing import Literal
from uuid import uuid4

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

from backend.security.auth import require_api_key


router = APIRouter(
    prefix="/sources",
    tags=["security-sources"],
)

_LOCK = Lock()
_SOURCES: dict[str, dict] = {}

SourceKind = Literal[
    "camera",
    "nvr",
    "vms",
    "perimeter",
    "access",
    "alarm",
    "fire",
    "panic",
    "intercom",
    "other",
]

ConnectionMode = Literal[
    "webhook",
    "rtsp",
    "onvif",
    "vendor_api",
    "manual",
]


class SecuritySourceRequest(BaseModel):
    name: str = Field(min_length=1, max_length=128)
    kind: SourceKind
    zone: str = Field(min_length=1, max_length=256)
    connection_mode: ConnectionMode
    endpoint: str | None = Field(
        default=None,
        max_length=1000,
    )
    secret_ref: str | None = Field(
        default=None,
        max_length=128,
    )
    capabilities: list[str] = Field(
        default_factory=list,
        max_length=30,
    )
    enabled: bool = True


def get_source_record(source_id: str) -> dict | None:
    with _LOCK:
        item = _SOURCES.get(source_id)

        if item is None:
            return None

        return dict(item)


def _clean_capabilities(values: list[str]) -> list[str]:
    allowed = {
        "live_view",
        "snapshot",
        "recording",
        "playback",
        "ptz",
        "audio",
        "motion_events",
        "object_events",
        "perimeter_events",
        "alarm_events",
        "access_events",
        "open_access",
        "close_access",
        "arm",
        "disarm",
        "siren",
        "intercom",
    }

    clean = []

    for value in values:
        item = (value or "").strip().lower()

        if item in allowed and item not in clean:
            clean.append(item)

    return clean


@router.post("")
def register_source(
    request: SecuritySourceRequest,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    source_id = str(uuid4())

    item = {
        "source_id": source_id,
        "name": request.name.strip(),
        "kind": request.kind,
        "zone": request.zone.strip(),
        "connection_mode": request.connection_mode,
        "endpoint": (
            request.endpoint.strip()
            if request.endpoint
            else None
        ),
        "secret_ref": (
            request.secret_ref.strip()
            if request.secret_ref
            else None
        ),
        "capabilities": _clean_capabilities(
            request.capabilities
        ),
        "enabled": request.enabled,
        "status": "registered",
    }

    with _LOCK:
        _SOURCES[source_id] = item

    return item


@router.get("")
def list_sources(
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        items = list(_SOURCES.values())

    return {
        "count": len(items),
        "sources": items,
    }


@router.get("/{source_id}")
def get_source(
    source_id: str,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        item = _SOURCES.get(source_id)

    if item is None:
        raise HTTPException(
            status_code=404,
            detail="Fuente de seguridad no encontrada.",
        )

    return item


@router.post("/{source_id}/enable")
def enable_source(
    source_id: str,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        item = _SOURCES.get(source_id)

        if item is None:
            raise HTTPException(
                status_code=404,
                detail="Fuente de seguridad no encontrada.",
            )

        item["enabled"] = True
        item["status"] = "registered"

        return dict(item)


@router.post("/{source_id}/disable")
def disable_source(
    source_id: str,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        item = _SOURCES.get(source_id)

        if item is None:
            raise HTTPException(
                status_code=404,
                detail="Fuente de seguridad no encontrada.",
            )

        item["enabled"] = False
        item["status"] = "disabled"

        return dict(item)


@router.get("/{source_id}/capabilities")
def source_capabilities(
    source_id: str,
    x_alma_api_key: str | None = Header(
        default=None,
        alias="X-ALMA-API-Key",
    ),
):
    require_api_key(x_alma_api_key)

    with _LOCK:
        item = _SOURCES.get(source_id)

    if item is None:
        raise HTTPException(
            status_code=404,
            detail="Fuente de seguridad no encontrada.",
        )

    return {
        "source_id": source_id,
        "name": item["name"],
        "enabled": item["enabled"],
        "capabilities": item["capabilities"],
    }
