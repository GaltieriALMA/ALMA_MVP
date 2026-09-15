import json
import shutil
from pathlib import Path

class SafeJsonStore:
    def __init__(self, path, default_factory):
        self.path = Path(path)
        self.backup_path = self.path.with_suffix(self.path.suffix + ".bak")
        self.default_factory = default_factory
        self.path.parent.mkdir(parents=True, exist_ok=True)

        if not self.path.exists():
            self.write(self.default_factory())

    def read(self):
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except Exception:
            if self.backup_path.exists():
                try:
                    recovered = json.loads(
                        self.backup_path.read_text(encoding="utf-8")
                    )
                    # Restore a valid primary copy from the backup.
                    self._atomic_write_primary(recovered, update_backup=False)
                    return recovered
                except Exception:
                    pass
            return self.default_factory()

    def _atomic_write_primary(self, data, update_backup=True):
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        payload = json.dumps(data, ensure_ascii=False, indent=2)

        tmp.write_text(payload, encoding="utf-8")

        # Validate temp content before touching current primary.
        json.loads(tmp.read_text(encoding="utf-8"))

        if update_backup and self.path.exists():
            try:
                current = json.loads(self.path.read_text(encoding="utf-8"))
                self.backup_path.write_text(
                    json.dumps(current, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )
            except Exception:
                # Never overwrite a known-good backup with corrupt primary data.
                pass

        tmp.replace(self.path)

    def write(self, data):
        self._atomic_write_primary(data, update_backup=True)
