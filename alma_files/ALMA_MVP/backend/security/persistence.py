import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def enabled():
    if os.getenv("ALMA_SECURITY_STORAGE", "").lower() == "memory":
        return False
    return bool(
        os.getenv("SUPABASE_URL", "").strip()
        and os.getenv("SUPABASE_SERVICE_ROLE_KEY", "").strip()
    )


def backend_name():
    return "supabase" if enabled() else "memory"


def request(table, method="GET", params=None, data=None, prefer=None):
    base = os.getenv("SUPABASE_URL", "").strip().rstrip("/")
    key = os.getenv("SUPABASE_SERVICE_ROLE_KEY", "").strip()

    if not base or not key:
        raise RuntimeError("Supabase no configurado")

    url = f"{base}/rest/v1/{table}"

    if params:
        url += "?" + urlencode(params)

    body = None if data is None else json.dumps(data).encode("utf-8")

    headers = {
        "apikey": key,
        "Content-Type": "application/json",
        "Accept": "application/json",
    }

    if not key.startswith("sb_secret_"):
        headers["Authorization"] = "Bearer " + key

    if prefer:
        headers["Prefer"] = prefer

    req = Request(url, data=body, method=method, headers=headers)

    with urlopen(req, timeout=12) as response:
        raw = response.read().decode("utf-8").strip()
        return json.loads(raw) if raw else None


def upsert(table, key, item):
    return request(
        table,
        "POST",
        {"on_conflict": key},
        item,
        "resolution=merge-duplicates,return=minimal",
    )


def one(table, key, value):
    rows = request(
        table,
        "GET",
        {
            "select": "*",
            key: "eq." + str(value),
            "limit": "1",
        },
    ) or []

    return rows[0] if rows else None


def many(table, limit=100, order=None, extra=None):
    params = {
        "select": "*",
        "limit": str(limit),
    }

    if order:
        params["order"] = order

    if extra:
        params.update(extra)

    rows = request(table, "GET", params)

    return rows if isinstance(rows, list) else []


def save_source(item):
    upsert("alma_security_sources", "source_id", item)


def get_source(source_id):
    return one("alma_security_sources", "source_id", source_id)


def list_sources():
    return many("alma_security_sources", 500, "created_at.asc")


def save_event(item):
    upsert("alma_security_events", "event_id", item)


def list_events(limit=50):
    return many("alma_security_events", limit, "received_at.desc")


def save_alert(item):
    upsert("alma_security_alerts", "alert_id", item)


def get_alert(alert_id):
    return one("alma_security_alerts", "alert_id", alert_id)


def list_open_alerts(limit=50):
    return many(
        "alma_security_alerts",
        limit,
        "created_at.desc",
        {"status": "not.in.(dismissed,closed)"},
    )


def save_delivery(item):
    request(
        "alma_security_deliveries",
        "POST",
        data=item,
        prefer="return=minimal",
    )


def list_deliveries(limit=50):
    return many(
        "alma_security_deliveries",
        limit,
        "attempted_at.desc",
    )
