def classify_memory(message: str) -> str:
    text = (message or '').lower()
    if any(k in text for k in ('no recuerdes','no guardes','olvidá esto','olvida esto')):
        return 'do_not_store'
    if any(k in text for k in ('prefiero','favorito','me gusta','no me gusta')):
        return 'preference'
    if any(k in text for k in ('decidí','decidi','queda aprobado','aprobado')):
        return 'decision'
    if any(k in text for k in ('objetivo','meta','quiero lograr')):
        return 'goal'
    return 'session_only'
