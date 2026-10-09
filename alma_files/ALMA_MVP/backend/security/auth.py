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


def require_source_token(
    secret_ref: str,
    supplied_token: str | None,
):
    ref = (secret_ref or "").strip()

    if not ref:
        raise HTTPException(
            status_code=503,
            detail="La fuente no tiene credencial configurada.",
        )

    expected = os.getenv(ref, "").strip()

    if not expected:
        raise HTTPException(
            status_code=503,
            detail="La credencial de la fuente no está configurada en el servidor.",
        )

    if (
        not supplied_token
        or not secrets.compare_digest(
            supplied_token,
            expected,
        )
    ):
        raise HTTPException(
            status_code=401,
            detail="Fuente de seguridad no autorizada.",
        )
