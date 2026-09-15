import re
from .base_provider import BaseProvider
class MockProvider(BaseProvider):
    def generate(self,instructions,user_message):
        text=(user_message or '').strip()
        m=re.search(r'mi color favorito es\s+([a-záéíóúñ]+)',text,flags=re.I)
        if m: return f'Perfecto. Voy a tener presente que tu color favorito es {m.group(1)}.'
        if 'cuál es mi color favorito' in text.lower() or 'cual es mi color favorito' in text.lower():
            m2=re.search(r'\[preference\]\s+Mi color favorito es\s+([a-záéíóúñ]+)',instructions,flags=re.I)
            return f'Tu color favorito es {m2.group(1)}.' if m2 else 'Todavía no tengo guardado tu color favorito.'
        return f'Estoy funcionando en modo de prueba. Recibí: {text}'
