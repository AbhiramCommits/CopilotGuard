# Contributing

## Prerequisites

- Java 21 (Temurin) and Maven 3.9+
- Docker (for Testcontainers integration tests and the validation gate)
- Python 3.12 for the evaluation harness
- `git` for workspace checkout in the validation gate

## Building and testing the service

```sh
cd service
mvn spotless:apply     # format the code (AOSP style, enforced in CI)
mvn verify             # unit + integration tests, Spotless check, JaCoCo 75% line gate
```

`mvn verify` runs Testcontainers-based tests (PostgreSQL 16, MongoDB 7, WireMock for the
Anthropic API) and the validation gate, which executes generated tests inside a real
Docker container. No API key is required.

## Python evaluation harness

```sh
cd eval
python3.12 -m venv .venv
.venv/bin/pip install -e ".[dev]" ruff
.venv/bin/ruff check .    # lint
.venv/bin/pytest -q       # scoring tests on recorded responses, no API key needed
```

Running the full evaluation against a live service:

```sh
docker compose up -d postgres mongo
python3 mock_anthropic.py 8099 &          # deterministic model stub for hermetic runs
# start the service pointed at the mock, then:
.venv/bin/python run_eval.py --base-url http://localhost:8080
```

Results land in `eval/results/` as JSON, Markdown, and a PNG chart.

## Pull request checklist

- [ ] `mvn spotless:apply` and `mvn verify` pass in `service/`.
- [ ] `ruff check .` and `pytest` pass in `eval/`.
- [ ] New endpoints are covered by MockMvc contract tests in `ApiContractTest`.
- [ ] Changes to the LLM client have WireMock tests (no API key).
- [ ] Changes to the prompt library update `prompts/prompts.yaml` (id, version, purpose,
      changelog) and are reflected in `eval/prompts/`.
- [ ] Anything that could merge or commit AI output goes through the validation gate;
      there is intentionally no code path that does otherwise.

## Governance rules (non-negotiable)

- Unvalidated AI output is never returned as accepted and never merged.
- Blocker-class secrets in a diff abort the run unless `allowRedactedSend=true`.
- Every prompt and response is persisted (redacted) in the audit store.
- Human verdicts (`ACCEPT`/`REJECT`) drive the override metrics; they are the only way
  a review comment is ever dismissed.

## Architecture decisions

See `docs/adr/` for the record of significant decisions.
