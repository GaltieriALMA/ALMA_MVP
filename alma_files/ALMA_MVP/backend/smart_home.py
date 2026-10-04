import re
import unicodedata

def _normalize(text: str) -> str:
    value = unicodedata.normalize("NFD", (text or "").lower())
    return "".join(
        c for c in value
        if unicodedata.category(c) != "Mn"
    ).strip()

def detect_smart_home_intent(message: str):
    text = _normalize(message)

    actions = [
        ("turn_on", r"\b(prende|encende|enciende|activa)\b"),
        ("turn_off", r"\b(apaga|desactiva)\b"),
        ("set_temperature", r"\b(pon|pone|configura).*(\d{2})\s*(grados)?\b"),
    ]

    devices = {
        "luz": ["luz", "luces", "lampara"],
        "tv": ["televisor", "tele", "tv"],
        "aire": ["aire", "aire acondicionado"],
        "enchufe": ["enchufe", "toma"],
        "persiana": ["persiana", "persianas"],
    }

    action = None
    value = None

    for name, pattern in actions:
        match = re.search(pattern, text)
        if match:
            action = name
            if name == "set_temperature":
                numbers = re.findall(r"\b\d{2}\b", text)
                if numbers:
                    value = int(numbers[-1])
            break

    target = None
    for device, aliases in devices.items():
        if any(alias in text for alias in aliases):
            target = device
            break

    if not action or not target:
        return None

    return {
        "action": action,
        "target": target,
        "value": value,
    }
