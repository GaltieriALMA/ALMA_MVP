from pathlib import Path
import tempfile
from backend.core.identity import ALMA_IDENTITY
from backend.core.personality import AlmaPersonality
from backend.memory.memory_manager import MemoryManager
from backend.memory.retrieval import MemoryRetrieval
from backend.sessions.session_manager import SessionManager
from backend.providers.mock_provider import MockProvider
from backend.validation.response_validator import ResponseValidator
from backend.conversation.context_builder import ContextBuilder
from backend.conversation.engine import ConversationEngine

class MockManager:
    def __init__(self): self.provider=MockProvider()
    def generate(self,instructions,user_message): return self.provider.generate(instructions,user_message),'mock'

def build(mem_path,sess_path):
    memory=MemoryManager(mem_path); sessions=SessionManager(sess_path)
    context=ContextBuilder(ALMA_IDENTITY,AlmaPersonality(),memory,MemoryRetrieval())
    return ConversationEngine(context,MockManager(),ResponseValidator(),memory,sessions)

def run_test():
    with tempfile.TemporaryDirectory() as tmp:
        mem=str(Path(tmp)/'memories.json'); sess=str(Path(tmp)/'sessions.json')
        e1=build(mem,sess); r1=e1.process_message('u1','s1','Mi color favorito es azul.'); assert r1['valid']
        e2=build(mem,sess); r2=e2.process_message('u1','s2','¿Cuál es mi color favorito?'); assert 'azul' in r2['text'].lower(), r2
        return r1,r2

if __name__=='__main__':
    a,b=run_test(); print('SMOKE TEST OK'); print(a); print(b)
