from pathlib import Path

import yaml


def load_fixtures(fixtures_dir):
    fixtures_dir = Path(fixtures_dir)
    fixtures = []
    for diff_file in sorted(fixtures_dir.glob("*.diff")):
        manifest_file = diff_file.with_suffix(".yaml")
        if not manifest_file.exists():
            raise FileNotFoundError(f"missing manifest for {diff_file}")
        manifest = yaml.safe_load(manifest_file.read_text(encoding="utf-8"))
        fixtures.append((manifest["id"], diff_file.read_text(encoding="utf-8"), manifest))
    return fixtures
