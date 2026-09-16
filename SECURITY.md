# Security policy and threat model

## What CopilotGuard does

CopilotGuard sends **source code from diffs** to a third-party model provider (Anthropic),
generates tests and review comments, executes the generated tests in a sandbox, and
stores an immutable audit trail.

The core risk is straightforward: **code is the most sensitive asset most teams own,
and this service sends parts of it to an external API.** This document describes the
threat model and the controls that address it.

## Threat model

| Threat | Impact | Mitigation |
| --- | --- | --- |
| Secrets in the diff leak to the model provider | Credential exfiltration | Redaction chain runs before anything leaves: AWS keys, PEM private keys, JWTs, bearer tokens, credential connection strings, GitHub/Anthropic tokens, emails, SSN-shaped numbers, Luhn-checked card numbers. Blocker-class secrets **abort the run** unless the caller explicitly sets `allowRedactedSend=true`. |
| Provider (or a compromised provider) retains or misuses prompts | Confidentiality of proprietary code | Only the diff is sent, never the full repository; secrets are redacted to stable placeholders; the README documents exactly what is transmitted. |
| Model output poisons the repository | Supply-chain style injection of bad tests/comments | Hard validation gate: generated tests are compiled and executed twice in a network-less, read-only-rootfs Docker container; only PASSING tests are returned. There is **no code path that merges or commits AI output**. |
| Prompt injection inside the diff manipulates the model | Wrong review output | Deterministic convention checks are enforced in code after generation - the LLM suggestion is never the only check. |
| Unauthorized callers trigger expensive runs | Cost abuse | API-key authentication on write endpoints (`X-API-Key`, enabled when `COPILOTGUARD_API_KEY` is set), per-key Bucket4j rate limiting, and a per-run USD cost ceiling. |
| Audit trail tampering | Loss of accountability | Audit documents are write-once in MongoDB; no update path exists. Unredacted source is never persisted. |
| Secrets in configuration or images | Credential exfiltration | Secrets come only from environment variables (`.env` is gitignored; `.env.example` contains no values). No secrets are baked into images or the repository. |

## Operational requirements

- Set `ANTHROPIC_API_KEY` and `COPILOTGUARD_API_KEY` from a secret manager; never commit them.
- The provider API key should be scoped to the minimal model access needed.
- Review the `prompt_audit` collection access controls; it contains code-derived content.
- Keep `copilotguard.cost.max-usd-per-run` and `copilotguard.rate-limit.*` tuned to your
  budget; they are the blast-radius limiters for runaway usage.
- The validation sandbox runs untrusted generated code: it is network-disabled and
  resource-limited by design; do not weaken those limits.

## Reporting vulnerabilities

Do not open a public issue. Report suspected vulnerabilities privately to the
repository maintainers with a description, affected versions, and reproduction steps.
