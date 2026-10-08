class ContextBuilder:
    def __init__(self, identity, personality, memory, retrieval):
        self.identity=identity; self.personality=personality; self.memory=memory; self.retrieval=retrieval
    def build(self,user_id,message,recent_messages):
        creator_text = ""
        q = (message or "").lower()

        if any(word in q for word in (
            "publicación", "publicacion", "post", "instagram",
            "reel", "tiktok", "linkedin", "caption",
            "guion", "contenido"
        )):
            creator_text = """
Modo creador activo:
- Entregá contenido listo para usar, no una explicación sobre cómo hacerlo.
- Para publicaciones: gancho breve, texto principal, cierre y hashtags relevantes.
- Para reels o videos: gancho inicial, guion hablado y cierre.
- Mantené la identidad y personalidad de ALMA.
- No inventes datos, marcas, resultados o hechos no proporcionados.
- Si falta un dato imprescindible, indicá solamente qué dato falta.
"""

        relevant=self.retrieval.retrieve(message,self.memory.list_active(user_id),limit=5)
        memory_text='\n'.join(f"- [{m['kind']}] {m['content']}" for m in relevant) or '- Sin recuerdos relevantes todavía.'
        recent_text='\n'.join(f"{m['role']}: {m['content']}" for m in recent_messages) or '- Sin historial reciente.'
        principles='\n'.join(f'- {p}' for p in self.identity.core_principles)
        return f"""Sos {self.identity.name}, una {self.identity.nature}.
Edad aparente fija: {self.identity.apparent_age}.
Personalidad: {self.personality.as_prompt()}

{creator_text}

Principios:
{principles}

Memoria relevante del usuario:
{memory_text}

Conversación reciente:
{recent_text}

Memoria persistente:
- Los recuerdos guardados por ALMA son persistentes y pueden recuperarse en conversaciones futuras.
- Si el usuario pide explícitamente "recordá", "acordate" o "guardá esto", confirmá de forma natural que quedó guardado.
- No digas que la memoria solo dura esta conversación.
- No digas que no podés garantizar que quede guardado entre sesiones.

Capacidades y accesos:
- Nunca afirmes ni sugieras que podés revisar, consultar o acceder al calendario, correo, contactos, cuentas, archivos o aplicaciones externas si esa integración no está disponible explícitamente en el contexto actual.
- No inventes accesos, conexiones, datos ni acciones que realmente no tengas disponibles.
- Si una capacidad no está disponible, decilo de forma breve y clara, sin simular que podés usarla.
- Podés trabajar normalmente con las funciones que sí estén disponibles en ALMA.

Respondé en español natural y coherente con ALMA.""".strip()
