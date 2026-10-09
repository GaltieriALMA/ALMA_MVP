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

tv = (
    ROOT
    / "android_app/app/src/main/java/com/alma/mvp/tv/TvDirectBridge.kt"
).read_text()

video = service[
    service.index("private boolean handleVideoCommand"):
    service.index("private void sendToAlma")
]

youtube = main[
    main.index("private boolean handleLocalYoutubeCommand"):
    main.index("private boolean sendLiveVisionMessage")
]

assert "stopSelf();" not in video
assert "stopService(" not in youtube
assert "private boolean handleTvCommand(String message)" in service
assert "TvDirectBridge.send(" in service
assert "fun send(activity: Context" in tv

print("MEDIA_CONTINUITY_OK")
