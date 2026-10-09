from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

parser = (
    ROOT
    / "android_app/app/src/main/java/com/alma/mvp/alarm/AlmaAlarmCommand.java"
).read_text()

main = (
    ROOT
    / "android_app/app/src/main/java/com/alma/mvp/MainActivity.java"
).read_text()

service = (
    ROOT
    / "android_app/app/src/main/java/com/alma/mvp/WakeWordService.java"
).read_text()

alarm_service = (
    ROOT
    / "android_app/app/src/main/java/com/alma/mvp/alarm/AlmaAlarmService.java"
).read_text()

assert "boolean reminderIntent" in parser
assert 'text.contains("recordame")' in parser
assert "Recordatorio programado para las" in main
assert "private boolean handleAlarmCommand(String message)" in service
assert "AlmaAlarmScheduler.schedule(" in service
assert "Recordatorio programado para las" in service
assert "Alarma o recordatorio activo" in alarm_service

print("ALARM_REMINDER_REGRESSION_OK")
