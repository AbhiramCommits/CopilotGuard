LINE_TOLERANCE = 3


def defect_hits(comments, defects, tolerance=LINE_TOLERANCE):
    hits = []
    for defect in defects:
        for comment in comments:
            if comment.get("category") == "CONVENTIONS":
                continue
            line = comment.get("line")
            if (comment.get("filePath") == defect["file"]
                    and line is not None
                    and abs(line - defect["line"]) <= tolerance):
                hits.append(defect)
                break
    return hits


def score_response(response, expectation, latency_ms):
    if isinstance(response.get("error"), str):
        return {
            "fixture": expectation["id"],
            "clean": expectation.get("clean", False),
            "error": response["error"],
            "compile_rate": None,
            "pass_rate": None,
            "recall": None,
            "coverage": None,
            "fp_comments": 0,
            "total_llm_comments": 0,
            "tokens_in": 0,
            "tokens_out": 0,
            "cost_usd": 0.0,
            "latency_ms": latency_ms or 0.0,
        }
    tests = response.get("generatedTests") or []
    total = len(tests)
    compile_ok = sum(1 for test in tests if test.get("compileStatus") == "SUCCESS")
    passed = sum(1 for test in tests if test.get("passStatus") == "PASSED")
    accepted_contents = [
        test.get("content") or "" for test in tests if test.get("accepted") and test.get("content")
    ]
    comments = response.get("comments") or []
    llm_comments = [comment for comment in comments if comment.get("category") != "CONVENTIONS"]
    defects = expectation.get("defects") or []
    hits = defect_hits(llm_comments, defects)
    covered_methods = expectation.get("covered_methods") or []
    covered = [method for method in covered_methods
               if any(method in content for content in accepted_contents)]
    fp_comments = len(llm_comments) if expectation.get("clean") else 0
    return {
        "fixture": expectation["id"],
        "clean": expectation.get("clean", False),
        "error": None,
        "compile_rate": compile_ok / total if total else None,
        "pass_rate": passed / total if total else None,
        "recall": len(hits) / len(defects) if defects else None,
        "coverage": len(covered) / len(covered_methods) if covered_methods else None,
        "fp_comments": fp_comments,
        "total_llm_comments": len(llm_comments),
        "tokens_in": response.get("tokenInput", 0),
        "tokens_out": response.get("tokenOutput", 0),
        "cost_usd": float(response.get("costUsd") or 0),
        "latency_ms": latency_ms or 0.0,
    }


def _mean(values):
    values = [value for value in values if value is not None]
    return sum(values) / len(values) if values else None


def aggregate(scores):
    defect_scores = [score for score in scores if score.get("recall") is not None]
    clean_scores = [score for score in scores if score.get("clean")]
    fp_comments = sum(score["fp_comments"] for score in clean_scores)
    total_clean_comments = sum(score["total_llm_comments"] for score in clean_scores)
    return {
        "reviews": len(scores),
        "errors": sum(1 for score in scores if score.get("error")),
        "compile_rate": _mean([score["compile_rate"] for score in scores]),
        "pass_rate": _mean([score["pass_rate"] for score in scores]),
        "recall": _mean([score["recall"] for score in defect_scores]),
        "fp_rate": fp_comments / total_clean_comments if total_clean_comments else None,
        "coverage": _mean([score["coverage"] for score in scores if score.get("coverage") is not None]),
        "mean_tokens_in": _mean([score["tokens_in"] for score in scores]),
        "mean_tokens_out": _mean([score["tokens_out"] for score in scores]),
        "mean_cost_usd": _mean([score["cost_usd"] for score in scores]),
        "mean_latency_ms": _mean([score["latency_ms"] for score in scores]),
    }
