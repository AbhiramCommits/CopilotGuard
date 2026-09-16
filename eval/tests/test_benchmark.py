from pathlib import Path

from harness.fixtures import load_fixtures

FIXTURES_DIR = Path(__file__).resolve().parent.parent / "fixtures"


def test_benchmark_has_fifteen_to_twenty_fixtures():
    fixtures = load_fixtures(FIXTURES_DIR)
    assert 15 <= len(fixtures) <= 20


def test_benchmark_has_defect_and_clean_fixtures():
    fixtures = load_fixtures(FIXTURES_DIR)
    defect_fixtures = [manifest for _, _, manifest in fixtures if manifest.get("defects")]
    clean_fixtures = [manifest for _, _, manifest in fixtures if manifest.get("clean")]
    assert len(defect_fixtures) >= 4
    assert len(clean_fixtures) >= 4


def test_manifests_declare_expectations():
    for _, _, manifest in load_fixtures(FIXTURES_DIR):
        assert manifest["id"]
        assert "clean" in manifest
        assert "covered_methods" in manifest
        for defect in manifest.get("defects", []):
            assert defect["file"]
            assert isinstance(defect["line"], int)


def test_diffs_carry_fixture_markers():
    for fixture_id, diff, _ in load_fixtures(FIXTURES_DIR):
        assert f"eval-fixture: {fixture_id}" in diff
