from backend.memory.classifier import classify_memory
class ConversationEngine:
    def __init__(self,context_builder,provider,validator,memory,sessions):
        self.context_builder=context_builder; self.provider=provider; self.validator=validator; self.memory=memory; self.sessions=sessions
    def process_message(self,user_id,session_id,message):
        recent=self.sessions.recent(user_id,session_id); context=self.context_builder.build(user_id,message,recent)
        response,provider_name=self.provider.generate(context,message); ok,issues=self.validator.validate(response)
        if not ok: response='No puedo responder de forma confiable en este momento.'
        kind=classify_memory(message)
        if kind not in {'do_not_store','session_only'}: self.memory.add(user_id,kind,message)
        self.sessions.append(user_id,session_id,'user',message); self.sessions.append(user_id,session_id,'assistant',response)
        return {'text':response,'provider':provider_name,'valid':ok,'issues':issues}
