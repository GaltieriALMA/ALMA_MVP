from backend.memory.classifier import classify_memory


class ConversationEngine:
    def __init__(self, context_builder, provider, validator, memory, sessions):
        self.context_builder = context_builder
        self.provider = provider
        self.validator = validator
        self.memory = memory
        self.sessions = sessions

    def _is_memory_update(self, message: str) -> bool:
        text = (message or "").lower()

        return any(marker in text for marker in (
            "al final",
            "ahora es",
            "ahora tengo",
            "en realidad",
            "ya no",
            "cambió",
            "cambio de",
            "cancelé",
            "cancele",
            "cancelado",
            "no a las",
        ))

    def process_message(self, user_id, session_id, message):
        recent = self.sessions.recent(user_id, session_id)

        context = self.context_builder.build(
            user_id,
            message,
            recent,
        )

        response, provider_name = self.provider.generate(
            context,
            message,
        )

        ok, issues = self.validator.validate(response)

        if not ok:
            response = "No puedo responder de forma confiable en este momento."

        kind = classify_memory(message)

        if kind not in {"do_not_store", "session_only"}:
            if self._is_memory_update(message):
                self.memory.supersede_related(
                    user_id,
                    message,
                )

            self.memory.add(
                user_id,
                kind,
                message,
            )

        self.sessions.append(
            user_id,
            session_id,
            "user",
            message,
        )

        self.sessions.append(
            user_id,
            session_id,
            "assistant",
            response,
        )

        return {
            "text": response,
            "provider": provider_name,
            "valid": ok,
            "issues": issues,
        }
