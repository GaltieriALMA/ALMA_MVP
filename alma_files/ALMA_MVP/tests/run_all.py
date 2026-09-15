import subprocess
import sys
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV = os.environ.copy()
ENV["PYTHONPATH"] = str(ROOT)

TESTS = [
    ROOT / "tests" / "test_smoke.py",
    ROOT / "tests" / "test_provider_integration.py",
    ROOT / "tests" / "test_conversation_flow.py",
    ROOT / "tests" / "test_recovery.py",
    ROOT / "tests" / "test_api.py",
    ROOT / "tests" / "test_mobile_contract.py",
    ROOT / "tests" / "test_android_client.py",
    ROOT / "tests" / "test_android_build_readiness.py",
    ROOT / "tests" / "test_mvp_acceptance.py",
    ROOT / "tests" / "test_release_b460.py",
    ROOT / "tests" / "test_api_e2e.py",
]

for test in TESTS:
    result = subprocess.run(
        [sys.executable, str(test)],
        cwd=ROOT,
        env=ENV,
        capture_output=True,
        text=True,
    )
    print(f"=== {test.name} ===")
    print(result.stdout.strip())
    if result.returncode != 0:
        print(result.stderr)
        raise SystemExit(result.returncode)

print("ALL B460 RELEASE TESTS PASSED")
