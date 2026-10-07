def classify_memory(message: str) -> str:
    text = (message or "").lower().strip()

    if "?" in text:
        return "session_only"

    if any(k in text for k in (
        "no recuerdes",
        "no guardes",
        "olvidá esto",
        "olvida esto",
    )):
        return "do_not_store"

    if any(k in text for k in (
        "recordá",
        "recorda",
        "recuerda",
        "acordate",
        "guardá esto",
        "guarda esto",
        "te pido que recuerdes",
    )):
        return "explicit_memory"

    if any(k in text for k in (
        "prefiero",
        "favorito",
        "me gusta",
        "no me gusta",
    )):
        return "preference"

    if any(k in text for k in (
        "decidí",
        "decidi",
        "queda aprobado",
        "aprobado",
    )):
        return "decision"

    if any(k in text for k in (
        "objetivo",
        "meta",
        "quiero lograr",
    )):
        return "goal"

    if any(k in text for k in (
        "mañana",
        "pasado mañana",
        "esta tarde",
        "esta noche",
        "reunión",
        "reunion",
        "turno",
        "cita",
        "tengo que",
        "voy a",
        "después",
        "despues",
        "antes de",
        "al final",
        "ya no",
        "cambió",
        "cambio",
        "cancelé",
        "cancele",
        "cancelado",
    )):
        return "context_memory"

    return "session_only"
