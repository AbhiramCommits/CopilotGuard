# Resume bullets

Four claims, each backed by committed output in this repository.

1. **Built a governance gate that validates every AI-generated test before it is trusted.**
   87.5% of generated tests compile and pass in a network-less Docker sandbox, and 100% of
   compile-failing/flaky tests are rejected - there is no code path that returns
   unvalidated output (proven by `ValidationGateIntegrationTest` and the committed eval
   results in `eval/results/20260916T145510Z.json`).

2. **Cut false positives to zero while lifting defect-detection recall from 50% to 100%**
   across prompt variants (baseline 0.50 recall / 1.0 FP rate -> few-shot 0.75 recall ->
   chain-of-thought-with-rubric 1.00 recall, no false positives) on a 16-fixture benchmark
   with 4 planted security/correctness defects
   (`eval/results/20260916T145510Z.json`).

3. **92.3% line coverage on the Java service across 89 tests** (unit, MockMvc contract,
   WireMock, Testcontainers), enforced by a 75% JaCoCo gate in CI
   (`docs/coverage-report.md`, `.github/workflows/ci.yml`).

4. **Serves the full review pipeline at p95 7.9s / 0.79 rps under load at $0.0228 per
   review** - redaction -> LLM -> Docker validation -> dual-write persistence, measured
   with k6 at 5 VUs against the stubbed LLM, 51 requests, 0 failures
   (`perf/results/20260916T145121Z-summary.json`, cost from the eval results).
