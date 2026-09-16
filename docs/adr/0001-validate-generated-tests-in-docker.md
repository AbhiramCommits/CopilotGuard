# ADR 0001: Validate generated tests in an ephemeral Docker container

- Status: Accepted
- Date: 2026-09-16

## Context

The LLM generates unit and integration tests for the changed code. Model output is
probabilistic: generated tests can fail to compile, assert the wrong behavior, or be
nondeterministic. If unvalidated tests were merged or returned to callers, they would
silently erode trust in the pipeline and could be checked into real repositories.

We considered three validation strategies:

1. Static linting of the generated source only.
2. Trusting the model's own claim that tests pass.
3. Actually compiling and running the tests in an isolated environment.

## Decision

We run every generated test file in an ephemeral Docker container with the checked-out
repository:

- **COMPILE**: `javac` compiles the repository sources plus the generated test against
  the JUnit platform console launcher.
- **RUN**: the compiled tests are executed twice via the JUnit console launcher and the
  Surefire-style XML reports are parsed.
- Classification: `COMPILE_FAIL`, `TEST_FAIL`, `FLAKY` (differing result across the two
  runs), or `PASSING`.

Only `PASSING` tests are returned as accepted output. Rejected tests are persisted with
their failure reason and surfaced to the caller. The container is sandboxed: no network,
read-only root filesystem, CPU/memory limits, and a wall-clock timeout that kills it.

## Consequences

- The pipeline can guarantee "tests that compile and pass" rather than "tests the model
  claims pass". This is the hard trust boundary of the product.
- Every review pays the cost of container startup plus compile/run time (~2-10s).
- Validation requires Docker on the service host and a checkout of the target repo
  (cloned at the PR head SHA; raw diffs use a synthetic workspace and can only validate
  self-contained tests).
- FLAKY classification catches nondeterministic tests before they reach developers.
