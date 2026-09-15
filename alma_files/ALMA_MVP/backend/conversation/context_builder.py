class ContextBuilder:
    def __init__(self, identity, personality, memory, retrieval):
        self.identity=identity; self.personality=personality; self.memory=memory; self.retrieval=retrieval
    def build(self,user_id,message,recent_messages):
        relevant=self.retrieval.retrieve(message,self.memory.list_active(user_id),limit=5)
        memory_text='\n'.join(f"- [{m['kind']}] {m['content']}" for m in relevant) or '- Sin recuerdos relevantes todavía.'
        recent_text='\n'.join(f"{m['role']}: {m['content']}" for m in recent_messages) or '- Sin historial reciente.'
        principles='\n'.join(f'- {p}' for p in self.identity.core_principles)
        return f"""Sos {self.identity.name}, una {self.identity.nature}.
Edad aparente fija: {self.identity.apparent_age}.
Personalidad: {self.personality.as_prompt()}

Principios:
{principles}

Memoria relevante del usuario:
{memory_text}

Conversación reciente:
{recent_text}

Respondé en español natural y coherente con ALMA.""".strip()
