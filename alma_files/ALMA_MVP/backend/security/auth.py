import os
import secrets

from fastapi import Header, HTTPException


def require_api_key(
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
