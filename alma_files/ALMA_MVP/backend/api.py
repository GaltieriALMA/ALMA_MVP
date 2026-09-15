from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from backend.app import AlmaApplication

app = FastAPI(
    title="ALMA MVP API",
    version="1.0.0",
)

alma = AlmaApplication()

class ChatRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    session_id: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=12000)

class ChatResponse(BaseModel):
    text: str
    provider: str
    valid: bool
    issues: list[str]

@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "ALMA",
        "identity_version": alma.identity.identity_version,
    }

@app.post("/chat", response_model=ChatResponse)
def chat(request: ChatRequest):
    try:
        result = alma.chat(
            user_id=request.user_id,
            session_id=request.session_id,
            message=request.message,
        )
        return ChatResponse(**result)
    except Exception as exc:
        raise HTTPException(
            status_code=500,
            detail="ALMA no pudo procesar el mensaje."
        ) from exc
