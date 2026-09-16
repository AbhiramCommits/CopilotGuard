# ADR 0002: Store the audit trail in MongoDB, relational data in PostgreSQL

- Status: Accepted
- Date: 2026-09-16

## Context

CopilotGuard produces two very different kinds of data:

1. Operational/relational state: review runs, generated tests, review comments, and
   human verdicts. This data is queried with joins (run -> tests, run -> comments), needs
   foreign-key integrity, and evolves through migrations.
2. The audit trail: every prompt sent to the model (redacted) and every raw response,
   plus model id, template version, token usage, latency, and redaction hits.

## Decision

- PostgreSQL (Spring Data JPA + Flyway) stores the relational model: `review_run`,
  `generated_test`, `review_comment`.
- MongoDB (Spring Data MongoDB) stores the immutable audit trail in the `prompt_audit`
  collection.

## Consequences

- Audit documents are write-once, never updated, and can grow without impacting the
  relational schema; they are schema-flexible, which matters because LLM response shapes
  evolve with model generations.
- The relational side keeps transactional guarantees for verdicts and metrics
  aggregation.
- Two data stores must be operated (both are already in docker-compose and CI).
- No unredacted source is ever written to the audit store: prompts are redacted before
  they are persisted, and a blocker-class secret aborts the run unless explicitly
  overridden with `allowRedactedSend=true`.
