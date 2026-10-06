import re
import unicodedata


def _normalize(text):
    value = unicodedata.normalize("NFD", (text or "").lower())
    return "".join(
        c for c in value
        if unicodedata.category(c) != "Mn"
    )


def _tokens(text):
    return set(re.findall(r"[a-z0-9]+", _normalize(text)))


class MemoryRetrieval:
    def retrieve(self, message, memories, limit=5):
        if not memories:
            return []

        query_text = _normalize(message)
        q = _tokens(message)

        ordered = sorted(
            memories,
            key=lambda m: m.get("updated_at", ""),
            reverse=True,
        )

        scored = []

        for memory in ordered:
            m = _tokens(memory.get("content", ""))
            overlap = len(q & m)

            bonus = 0.0
            if memory.get("kind") == "explicit_memory":
                bonus = 0.5
            elif memory.get("kind") in {"preference", "decision", "goal"}:
                bonus = 0.2

            if overlap > 0:
                scored.append((overlap + bonus, memory))

        if scored:
            scored.sort(key=lambda x: x[0], reverse=True)
            return [m for _, m in scored[:limit]]

        recall_intent = any(x in query_text for x in (
            "record",
            "acord",
            "memoria",
            "que te dije",
            "que te pedi",
            "cual era",
            "cual es mi",
            "te acordas",
        ))

        if recall_intent:
            durable = [
                m for m in ordered
                if m.get("kind") in {
                    "explicit_memory",
                    "preference",
                    "decision",
                    "goal",
                }
            ]
            return durable[:limit]

        return []
