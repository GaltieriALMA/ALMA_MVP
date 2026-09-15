PROTECTED_FIELDS = {'name','apparent_age','nature','identity_version','core_principles'}

def identity_violation(text: str) -> bool:
    lowered = (text or '').lower()
    return any(term in lowered for term in ('soy una persona real','soy humana','soy un ser humano'))
