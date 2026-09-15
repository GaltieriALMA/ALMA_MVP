import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def test_release_manifest():
    data = json.loads((ROOT / "RELEASE.json").read_text())
    assert data["product"] == "ALMA"
    assert data["release"] == "MVP 1"
    assert data["version"] == "1.0.0-rc1"
    assert data["block"] == 460
    assert data["status"] == "functional_release_candidate"

def test_release_artifacts_present():
    assert (ROOT / "RELEASE_NOTES_MVP1.md").exists()
    assert (ROOT / "MVP_ACCEPTANCE_B459.md").exists()
    assert (ROOT / "backend/api.py").exists()
    assert (ROOT / "android_app/app/src/main/java/com/alma/mvp/MainActivity.java").exists()

def test_external_pending_is_explicit():
    data = json.loads((ROOT / "RELEASE.json").read_text())
    pending = data["external_validations_pending"]
    assert len(pending) == 3

if __name__ == "__main__":
    tests = [
        test_release_manifest,
        test_release_artifacts_present,
        test_external_pending_is_explicit,
    ]
    for test in tests:
        test()
        print(f"{test.__name__}: OK")
    print(f"TOTAL: {len(tests)} B460 RELEASE TESTS OK")
