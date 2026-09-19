from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android_app"

def test_gradle_build_files_present():
    required = [
        ANDROID / "settings.gradle.kts",
        ANDROID / "build.gradle.kts",
        ANDROID / "app/build.gradle.kts",
        ANDROID / "gradlew",
        ANDROID / "gradlew.bat",
        ANDROID / "gradle/wrapper/gradle-wrapper.properties",
        ANDROID / "BUILD_ANDROID.md",
    ]
    assert all(p.exists() for p in required)

def test_release_disables_cleartext():
    build = (ANDROID / "app/build.gradle.kts").read_text()
    manifest = (ANDROID / "app/src/main/AndroidManifest.xml").read_text()
    assert 'getByName("release")' in build
    assert 'manifestPlaceholders["usesCleartext"] = "false"' in build
    assert '${usesCleartext}' in manifest

def test_no_provider_secret_in_android_tree():
    text_suffixes = {".java", ".kt", ".kts", ".xml", ".properties", ".gradle", ".md", ".txt", ".json"}
    combined = "\n".join(
        p.read_text(errors="ignore")
        for p in ANDROID.rglob("*")
        if p.is_file()
        and "build" not in p.parts
        and ".gradle" not in p.parts
        and p.suffix.lower() in text_suffixes
    )
    assert "OPENAI_API_KEY=" not in combined
    assert "sk-" not in combined

def test_wrapper_version_pinned():
    props = (ANDROID / "gradle/wrapper/gradle-wrapper.properties").read_text()
    assert "gradle-8.9-bin.zip" in props

if __name__ == "__main__":
    tests = [
        test_gradle_build_files_present,
        test_release_disables_cleartext,
        test_no_provider_secret_in_android_tree,
        test_wrapper_version_pinned,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} ANDROID BUILD-READINESS TESTS OK")
