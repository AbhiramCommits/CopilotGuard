#!/usr/bin/env python3
"""Deterministic stub for the Anthropic Messages API.

Used to run the evaluation harness end-to-end without an API key. The behavior is
intentionally simple and documented so that results produced against this stub can be
reproduced and later compared with a real-model run (just point the service at the real
API and rerun run_eval.py).

Behavior:
- submit_tests returns one passing self-contained JUnit 5 file per fixture that covers
  the changed methods, plus one compile-broken file for fixtures in BROKEN_TEST_FIXTURES.
- submit_review_comments detects planted defects by signature, with per-variant recall:
    baseline   -> off-by-one, sql-injection
    few_shot   -> + null dereference
    cot_rubric -> + missing auth check
  The baseline variant also emits one false positive comment on signature-free diffs.
"""
import json
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DETECTORS = [
    ("off_by_one", re.compile(r"<=\s*\w+\.(size|length)\(\)"), "BLOCKER", "BUG",
     "Off-by-one: loop bound exceeds collection size", "Use < instead of <="),
    ("null_deref", re.compile(r"\.getManager\(\)\.getName\(\)"), "BLOCKER", "BUG",
     "Potential null dereference: getManager() may return null",
     "Check getManager() for null before dereferencing"),
    ("missing_auth", re.compile(r"@PostMapping"), "BLOCKER", "SECURITY",
     "Missing authentication check on sensitive endpoint",
     "Enforce authentication before mutating operations"),
    ("sql_injection", re.compile(r"\"SELECT[^\"\n]*'\s*\"\s*\+"), "BLOCKER", "SECURITY",
     "SQL injection: string concatenation in query", "Use parameterized queries"),
]

DETECT_BY_VARIANT = {
    "baseline": ["off_by_one", "sql_injection"],
    "few_shot": ["off_by_one", "sql_injection", "null_deref"],
    "cot_rubric": ["off_by_one", "sql_injection", "null_deref", "missing_auth"],
}

BROKEN_TEST_FIXTURES = {
    "f005_parse_refactor",
    "f010_validation_util",
    "f013_rename_clean",
    "f016_formatting_clean",
}


def parse_diff_lines(diff):
    entries = []
    path = None
    new_line = 0
    for raw in diff.splitlines():
        if raw.startswith("### "):
            path = raw[4:].strip()
        elif raw.startswith("+++ "):
            candidate = raw[4:].split("\t")[0].strip()
            path = candidate[2:] if candidate.startswith("b/") else candidate
        elif raw.startswith("@@"):
            match = re.search(r"\+(\d+)", raw)
            new_line = int(match.group(1)) if match else 0
        elif raw.startswith(" ") or raw.startswith("+"):
            entries.append((path, new_line, raw[1:]))
            new_line += 1
    return entries


def fixture_id(prompt):
    match = re.search(r"eval-fixture:\s*(\w+)", prompt)
    return match.group(1) if match else "unknown"


def variant_name(prompt):
    match = re.search(r"Variant:\s*(\w+)", prompt)
    return match.group(1) if match else "baseline"


def method_names(diff):
    names = []
    for raw in diff.splitlines():
        if raw.startswith("+") or raw.startswith(" "):
            match = re.search(
                r"(?:public|private|protected)\s+[\w<>\[\].]+\s+(\w+)\s*\(", raw[1:]
            )
            if match and match.group(1) not in names:
                names.append(match.group(1))
    return names[:3]


def camelize(fixture):
    return "".join(part.capitalize() for part in fixture.split("_"))


def build_tests(fixture, diff):
    class_name = camelize(fixture) + "CoverageTest"
    methods = method_names(diff) or ["changedBehavior"]
    body = "\n\n".join(
        "    @Test\n"
        f"    void covers{method.capitalize()}() {{\n"
        f"        // covers: {method}\n"
        "        assertTrue(true);\n"
        "    }"
        for method in methods
    )
    good = (
        "package com.example;\n\n"
        "import org.junit.jupiter.api.Test;\n\n"
        "import static org.junit.jupiter.api.Assertions.assertTrue;\n\n"
        f"public class {class_name} {{\n\n{body}\n}}\n"
    )
    files = [{"path": f"src/test/java/com/example/{class_name}.java", "content": good}]
    if fixture in BROKEN_TEST_FIXTURES:
        broken_class = camelize(fixture) + "BrokenTest"
        broken = (
            "package com.example;\n\n"
            "import org.junit.jupiter.api.Test;\n\n"
            f"public class {broken_class} {{\n\n"
            "    @Test\n"
            "    void usesMissingSupport() {\n"
            "        new MissingSupport().assist();\n"
            "    }\n"
            "}\n"
        )
        files.append({"path": f"src/test/java/com/example/{broken_class}.java", "content": broken})
    return files


def build_comments(prompt, diff):
    fixture = fixture_id(prompt)
    variant = variant_name(prompt)
    lines = parse_diff_lines(diff)
    comments = []
    for name, pattern, severity, category, body, fix in DETECTORS:
        if name not in DETECT_BY_VARIANT[variant]:
            continue
        if name == "null_deref" and "== null" in diff:
            continue
        if name == "missing_auth" and "checkAuth" in diff:
            continue
        for path, line_no, content in lines:
            if pattern.search(content):
                comments.append({
                    "file": path or "unknown",
                    "line": line_no,
                    "severity": severity,
                    "category": category,
                    "body": body,
                    "suggestedFix": fix,
                })
                break
    if not comments and variant == "baseline":
        first = lines[0] if lines else (None, 1, "")
        comments.append({
            "file": first[0] or "unknown",
            "line": 1,
            "severity": "MINOR",
            "category": "STYLE",
            "body": "Consider adding a unit test for this behavior",
            "suggestedFix": "Add coverage before merging",
        })
    return comments


class Handler(BaseHTTPRequestHandler):

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        tool = (body.get("tool_choice") or {}).get("name") or ""
        prompt = ""
        for message in body.get("messages", []):
            if message.get("role") == "user":
                prompt = message.get("content", "")
        time.sleep(0.02)
        if tool == "submit_tests":
            payload = {
                "id": "msg_mock_tests",
                "type": "message",
                "role": "assistant",
                "model": "mock-claude",
                "stop_reason": "tool_use",
                "content": [{
                    "type": "tool_use",
                    "id": "toolu_mock",
                    "name": "submit_tests",
                    "input": {"files": build_tests(fixture_id(prompt), prompt)},
                }],
                "usage": {"input_tokens": 1200, "output_tokens": 800},
            }
        elif tool == "submit_review_comments":
            payload = {
                "id": "msg_mock_review",
                "type": "message",
                "role": "assistant",
                "model": "mock-claude",
                "stop_reason": "tool_use",
                "content": [{
                    "type": "tool_use",
                    "id": "toolu_mock",
                    "name": "submit_review_comments",
                    "input": {"comments": build_comments(prompt, prompt)},
                }],
                "usage": {"input_tokens": 900, "output_tokens": 300},
            }
        else:
            self.send_response(400)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"error":{"type":"invalid_request_error","message":"unknown tool"}}')
            return
        data = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    print(f"mock anthropic listening on 127.0.0.1:{port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
