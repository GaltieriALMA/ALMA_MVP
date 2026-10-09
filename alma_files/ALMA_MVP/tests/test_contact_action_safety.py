from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

service = (
    ROOT
    / "android_app/app/src/main/java/com/alma/mvp/WakeWordService.java"
).read_text()

main = (
    ROOT
    / "android_app/app/src/main/java/com/alma/mvp/MainActivity.java"
).read_text()

assert "ACTION_CONFIRMATION_TTL_MS = 45000L" in service
assert "pendingCallStartedAt" in service
assert "pendingMessageStartedAt" in service
assert "La confirmación del mensaje venció" in service
assert "La confirmación de la llamada venció" in service

message_block = main[
    main.index("private void openContactMessage"):
    main.index("private boolean handleCallCommand")
]

assert "stopService(" not in message_block

call_block = main[
    main.index("private void performConfirmedCall"):
    main.index("private void openWhatsAppContact")
]

assert "CALL_PHONE" in call_block
assert "stopService(" in call_block
assert "Intent.ACTION_CALL" in call_block

print("CONTACT_ACTION_SAFETY_OK")
