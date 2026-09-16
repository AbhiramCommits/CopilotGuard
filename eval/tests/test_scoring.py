from harness.scoring import aggregate, defect_hits, score_response

DEFECT_EXPECTATION = {
    "id": "f001_off_by_one",
    "clean": False,
    "defects": [{"file": "src/main/java/com/example/Report.java", "line": 11}],
    "covered_methods": ["summarize"],
}

# Recorded service response captured from a review of f001_off_by_one.
RECORDED_DEFECT_RESPONSE = {
    "runId": 1,
    "repo": "local",
    "status": "SUCCEEDED",
    "tokenInput": 2100,
    "tokenOutput": 1100,
    "costUsd": 0.0228,
    "generatedTests": [
        {
            "filePath": "src/test/java/com/example/ReportCoverageTest.java",
            "compileStatus": "SUCCESS",
            "passStatus": "PASSED",
            "validationStatus": "PASSING",
            "accepted": True,
            "validationDetail": "tests passed in both runs",
            "content": "package com.example;\n// covers: summarize\nclass ReportCoverageTest {\n}",
        },
        {
            "filePath": "src/test/java/com/example/ReportBrokenTest.java",
            "compileStatus": "FAILURE",
            "passStatus": "PENDING",
            "validationStatus": "COMPILE_FAIL",
            "accepted": False,
            "validationDetail": "javac failed: cannot find symbol",
            "content": None,
        },
    ],
    "comments": [
        {
            "id": 1,
            "filePath": "src/main/java/com/example/Report.java",
            "line": 11,
            "severity": "BLOCKER",
            "category": "BUG",
            "body": "Off-by-one: loop bound exceeds collection size",
        },
        {
            "id": 2,
            "filePath": "src/main/java/com/example/Report.java",
            "line": 13,
            "severity": "MAJOR",
            "category": "STYLE",
            "body": "Consider logging the summary length",
        },
        {
            "id": 3,
            "filePath": "src/test/java/com/example/ReportCoverageTest.java",
            "line": None,
            "severity": "BLOCKER",
            "category": "CONVENTIONS",
            "body": "banned API 'Thread.sleep' used",
        },
    ],
}

CLEAN_EXPECTATION = {
    "id": "f013_rename_clean",
    "clean": True,
    "defects": [],
    "covered_methods": ["getNumberOfItems"],
}

RECORDED_CLEAN_RESPONSE = {
    "runId": 2,
    "repo": "local",
    "status": "SUCCEEDED",
    "tokenInput": 2100,
    "tokenOutput": 1100,
    "costUsd": 0.0228,
    "generatedTests": [
        {
            "filePath": "src/test/java/com/example/CartCoverageTest.java",
            "compileStatus": "SUCCESS",
            "passStatus": "PASSED",
            "validationStatus": "PASSING",
            "accepted": True,
            "validationDetail": "tests passed in both runs",
            "content": "package com.example;\n// covers: getNumberOfItems\nclass CartCoverageTest {\n}",
        }
    ],
    "comments": [
        {
            "id": 1,
            "filePath": "src/main/java/com/example/Cart.java",
            "line": 1,
            "severity": "MINOR",
            "category": "STYLE",
            "body": "Consider adding a unit test for this behavior",
        }
    ],
}


def test_defect_hits_respects_file_line_and_tolerance():
    comments = RECORDED_DEFECT_RESPONSE["comments"]
    defects = DEFECT_EXPECTATION["defects"]
    assert len(defect_hits(comments, defects)) == 1
    off_by_four = [{"file": defects[0]["file"], "line": 15, "category": "BUG"}]
    assert defect_hits(off_by_four, defects) == []
    wrong_file = [{"file": "src/main/java/com/example/Other.java", "line": 11, "category": "BUG"}]
    assert defect_hits(wrong_file, defects) == []


def test_defect_hits_ignores_convention_comments():
    comments = [
        {"file": "src/main/java/com/example/Report.java", "line": 11,
         "category": "CONVENTIONS", "severity": "BLOCKER"}
    ]
    assert defect_hits(comments, DEFECT_EXPECTATION["defects"]) == []


def test_score_response_recall_compile_pass_and_coverage():
    score = score_response(RECORDED_DEFECT_RESPONSE, DEFECT_EXPECTATION, 1234.5)
    assert score["compile_rate"] == 0.5
    assert score["pass_rate"] == 0.5
    assert score["recall"] == 1.0
    assert score["coverage"] == 1.0
    assert score["fp_comments"] == 0
    assert score["total_llm_comments"] == 2
    assert score["tokens_in"] == 2100
    assert score["cost_usd"] == 0.0228
    assert score["latency_ms"] == 1234.5
    assert score["error"] is None


def test_score_response_counts_false_positives_on_clean_fixtures():
    score = score_response(RECORDED_CLEAN_RESPONSE, CLEAN_EXPECTATION, 900.0)
    assert score["clean"] is True
    assert score["fp_comments"] == 1
    assert score["total_llm_comments"] == 1
    assert score["recall"] is None


def test_score_response_handles_service_errors():
    score = score_response({"error": "connection refused"}, DEFECT_EXPECTATION, 5.0)
    assert score["error"] == "connection refused"
    assert score["compile_rate"] is None
    assert score["pass_rate"] is None


def test_aggregate_computes_rates_and_means():
    scores = [
        score_response(RECORDED_DEFECT_RESPONSE, DEFECT_EXPECTATION, 1200.0),
        score_response(RECORDED_CLEAN_RESPONSE, CLEAN_EXPECTATION, 800.0),
    ]
    summary = aggregate(scores)
    assert summary["reviews"] == 2
    assert summary["errors"] == 0
    assert summary["compile_rate"] == 0.75
    assert summary["pass_rate"] == 0.75
    assert summary["recall"] == 1.0
    assert summary["fp_rate"] == 1.0
    assert summary["coverage"] == 1.0
    assert summary["mean_tokens_in"] == 2100
    assert summary["mean_cost_usd"] == 0.0228
    assert summary["mean_latency_ms"] == 1000.0


def test_aggregate_handles_empty_input():
    summary = aggregate([])
    assert summary["reviews"] == 0
    assert summary["compile_rate"] is None
    assert summary["fp_rate"] is None
