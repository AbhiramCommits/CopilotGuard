#!/usr/bin/env python3
"""Run the CopilotGuard evaluation: every prompt variant x every fixture against the service.

Scoring per fixture: generated-test compile rate, pass rate, defect-detection recall,
false-positive rate on clean diffs, coverage of expected methods, tokens, cost, latency.
Results are written to eval/results/<timestamp>.json plus a Markdown table and a
matplotlib bar chart (pass rate and detection rate per variant).
"""
import argparse
import datetime
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

from harness.fixtures import load_fixtures
from harness.scoring import aggregate, score_response
from harness.service_client import ServiceClient

VARIANTS = [
    {"name": "baseline", "test_template": "generate_tests", "review_template": "review_diff"},
    {"name": "few_shot", "test_template": "generate_tests_few_shot", "review_template": "review_diff_few_shot"},
    {"name": "cot_rubric", "test_template": "generate_tests_cot", "review_template": "review_diff_cot"},
]

CONVENTIONS_YAML = """naming:
  testClassNamePattern: ".*(Test|IT|ITs|Tests)$"
bannedApis: []
requiredTestAnnotations:
  - org.junit.jupiter.api.Test
maxMethodLength: 100
"""

METRIC_COLUMNS = [
    ("reviews", "reviews", "{}"),
    ("compile_rate", "compile rate", "{:.3f}"),
    ("pass_rate", "pass rate", "{:.3f}"),
    ("recall", "defect recall", "{:.3f}"),
    ("fp_rate", "false-positive rate", "{:.3f}"),
    ("coverage", "method coverage", "{:.3f}"),
    ("mean_tokens_in", "mean tokens in", "{:.1f}"),
    ("mean_tokens_out", "mean tokens out", "{:.1f}"),
    ("mean_cost_usd", "mean cost (USD)", "{:.4f}"),
    ("mean_latency_ms", "mean latency (ms)", "{:.1f}"),
]


def render_markdown(result):
    lines = [
        "# CopilotGuard evaluation",
        "",
        f"- generated at: {result['generated_at']}",
        f"- service: {result['base_url']}",
        f"- model: deterministic local stub (eval/mock_anthropic.py); rerun with a real "
        f"ANTHROPIC_API_KEY for production numbers",
        "",
        "## Summary per variant",
        "",
        "| variant | " + " | ".join(label for _, label, _ in METRIC_COLUMNS) + " |",
        "| --- | " + " | ".join("---" for _ in METRIC_COLUMNS) + " |",
    ]
    for variant in VARIANTS:
        aggregate_row = result["aggregates"][variant["name"]]
        cells = []
        for key, _, fmt in METRIC_COLUMNS:
            value = aggregate_row.get(key)
            cells.append("n/a" if value is None else fmt.format(value))
        lines.append(f"| {variant['name']} | " + " | ".join(cells) + " |")
    lines.append("")
    lines.append("## Per-fixture details")
    lines.append("")
    lines.append("| variant | fixture | error | compile rate | pass rate | recall | coverage | fp comments | "
                 "tokens in | tokens out | cost (USD) | latency (ms) |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for row in result["rows"]:
        lines.append(
            f"| {row['variant']} | {row['fixture']} | {row.get('error') or ''} | "
            f"{_fmt(row.get('compile_rate'))} | {_fmt(row.get('pass_rate'))} | "
            f"{_fmt(row.get('recall'))} | {_fmt(row.get('coverage'))} | {row['fp_comments']} | "
            f"{row['tokens_in']} | {row['tokens_out']} | {row['cost_usd']:.4f} | "
            f"{row['latency_ms']:.1f} |"
        )
    return "\n".join(lines) + "\n"


def _fmt(value):
    return "n/a" if value is None else f"{value:.3f}"


def render_chart(result, path):
    names = [variant["name"] for variant in VARIANTS]
    pass_rates = [result["aggregates"][name].get("pass_rate") or 0.0 for name in names]
    detection_rates = [result["aggregates"][name].get("recall") or 0.0 for name in names]
    x = range(len(names))
    width = 0.35
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.5))
    axes[0].bar(x, pass_rates, width, color="#4c78a8", label="generated-test pass rate")
    axes[1].bar(x, detection_rates, width, color="#e45756", label="defect detection rate")
    for ax, values in ((axes[0], pass_rates), (axes[1], detection_rates)):
        ax.set_xticks(list(x))
        ax.set_xticklabels(names)
        ax.set_ylim(0, 1.05)
        ax.grid(axis="y", alpha=0.3)
        for i, value in enumerate(values):
            ax.text(i, value + 0.02, f"{value:.2f}", ha="center")
    axes[0].set_title("Generated-test pass rate")
    axes[1].set_title("Defect detection rate (recall)")
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--fixtures-dir", default="fixtures")
    parser.add_argument("--results-dir", default="results")
    args = parser.parse_args()

    fixtures = load_fixtures(args.fixtures_dir)
    client = ServiceClient(args.base_url)
    rows = []
    for variant in VARIANTS:
        for fixture_id, diff, manifest in fixtures:
            response, latency_ms = client.create_review(
                diff, CONVENTIONS_YAML, variant["test_template"], variant["review_template"]
            )
            score = score_response(response, manifest, latency_ms)
            score["variant"] = variant["name"]
            rows.append(score)
            print(
                f"{variant['name']} {fixture_id}: pass_rate={score['pass_rate']} "
                f"recall={score['recall']} fp={score['fp_comments']} error={score['error']}",
                flush=True,
            )

    result = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "base_url": args.base_url,
        "model": "mock-claude (eval/mock_anthropic.py)",
        "variants": VARIANTS,
        "rows": rows,
        "aggregates": {variant["name"]: aggregate(
            [row for row in rows if row["variant"] == variant["name"]]) for variant in VARIANTS},
    }

    results_dir = Path(args.results_dir)
    results_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    json_path = results_dir / f"{timestamp}.json"
    md_path = results_dir / f"{timestamp}.md"
    chart_path = results_dir / f"{timestamp}.png"
    json_path.write_text(json.dumps(result, indent=2), encoding="utf-8")
    md_path.write_text(render_markdown(result), encoding="utf-8")
    render_chart(result, chart_path)
    print(f"wrote {json_path}")
    print(f"wrote {md_path}")
    print(f"wrote {chart_path}")


if __name__ == "__main__":
    main()
