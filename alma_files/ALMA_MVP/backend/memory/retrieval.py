import re

def _tokens(text):
    return set(re.findall(r'[a-záéíóúñ0-9]+', (text or '').lower()))

class MemoryRetrieval:
    def retrieve(self, message, memories, limit=5):
        q = _tokens(message); scored=[]
        for memory in memories:
            m = _tokens(memory.get('content',''))
            overlap = len(q & m)
            score = overlap + (0.2 if memory.get('kind') in {'preference','decision','goal'} else 0)
            scored.append((score,memory))
        scored.sort(key=lambda x:x[0], reverse=True)
        return [m for score,m in scored[:limit] if score>0]
