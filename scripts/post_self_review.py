#!/usr/bin/env python3
"""Post a CopilotGuard self-review back to the pull request.

Reads the review response produced by the CopilotGuard service (JSON written by the
copilotguard.yml workflow) and posts, via the GitHub REST API:

- every governance-accepted review comment (severity, category, body, suggested fix), and
- the validated test files: only tests classified PASSING (accepted output) are posted;
  compile-failing, failing, and flaky tests are never shared.

Unvalidated AI output never reaches the PR.

Environment:
  GITHUB_TOKEN      - token for the GitHub API
  GITHUB_REPOSITORY - owner/repo of the repository
  PR_NUMBER         - pull request number
"""
import json
import os
import sys
import urllib.error
import urllib.request


def github_api(path, payload):
    token = os.environ["GITHUB_TOKEN"]
    repository = os.environ["GITHUB_REPOSITORY"]
    url = f"https://api.github.com/repos/{repository}/{path}"
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
    except urllib.error.HTTPError as exc:
        print(f"warning: failed to post to {path}: {exc.code} {exc.read()[:200]!r}")


def main():
    if len(sys.argv) != 2:
        print("usage: post_self_review.py <review.json>", file=sys.stderr)
        return 1
    pr_number = os.environ["PR_NUMBER"]
    with open(sys.argv[1], encoding="utf-8") as handle:
        review = json.load(handle)

    status = review.get("status")
    if status != "SUCCEEDED":
        print(f"review did not succeed (status={status}); nothing posted", file=sys.stderr)
        return 1

    posted_comments = 0
    for comment in review.get("comments", []):
        if comment.get("category") == "CONVENTIONS":
            continue
        body = f"**[CopilotGuard] {comment.get('severity')} / {comment.get('category')}** "
        body += f"in `{comment.get('filePath')}`"
        if comment.get("line") is not None:
            body += f" (line {comment.get('line')})"
        body += f"\n\n{comment.get('body', '')}"
        github_api(f"issues/{pr_number}/comments", {"body": body})
        posted_comments += 1

    accepted_tests = [
        test
        for test in review.get("generatedTests", [])
        if test.get("accepted") and test.get("content")
    ]
    rejected_tests = [
        test
        for test in review.get("generatedTests", [])
        if not test.get("accepted")
    ]
    if accepted_tests:
        lines = ["**[CopilotGuard] Validated test files (compiled and passed in Docker):**", ""]
        for test in accepted_tests:
            lines.append(f"`{test.get('filePath')}`")
            lines.append("```java")
            lines.append(test.get("content"))
            lines.append("```")
            lines.append("")
        github_api(f"issues/{pr_number}/comments", {"body": "\n".join(lines)})
    if rejected_tests:
        lines = ["**[CopilotGuard] Rejected test files (never merged):**", ""]
        for test in rejected_tests:
            lines.append(
                f"- `{test.get('filePath')}` - {test.get('validationStatus')}: "
                f"{test.get('validationDetail', '')}"
            )
        github_api(f"issues/{pr_number}/comments", {"body": "\n".join(lines)})

    print(f"posted {posted_comments} comments, {len(accepted_tests)} validated tests")
    return 0


if __name__ == "__main__":
    sys.exit(main())
