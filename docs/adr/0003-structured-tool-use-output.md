# ADR 0003: Force structured output with tool use instead of string parsing

- Status: Accepted
- Date: 2026-09-16

## Context

The pipeline needs machine-readable output from the model in two places:

1. Generated test files (`{path, content}`).
2. Review comments (`{file, line, severity, category, body, suggestedFix}`).

Naive approaches - asking for JSON in the prompt and parsing the assistant text - are
fragile: models wrap JSON in markdown fences, add prose around it, or truncate it, and
the failure modes are unparseable blobs that poison the rest of the pipeline.

## Decision

We use Anthropic tool use with a JSON input schema per operation and force the call with
`tool_choice: {type: "tool", name: ...}`:

- `submit_tests` with an input schema for `{files: [{path, content}]}`.
- `submit_review_comments` with an input schema whose `severity` is constrained to
  `BLOCKER|MAJOR|MINOR` and `category` to the fixed enum, so invalid values are rejected
  before persistence.

Responses are validated against the schema in code; a missing or malformed tool-call
block fails the review run rather than producing partial data. Retries with exponential
backoff handle 429/5xx.

## Consequences

- Downstream code consumes typed records, not strings; deterministic convention checks
  and DB enums are the second line of defense.
- Output shape is guaranteed by the schema, and enum drift is caught immediately.
- We depend on Anthropic tool-use semantics (and the mock in `eval/mock_anthropic.py`
  mirrors them), which couples the client to a specific provider API version.
