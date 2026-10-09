# 0020 - Provider rate limits with an interactive reserve, and supply-chain guardrails

Status: accepted - 2026-09-27

## Context
With many users, benchmarks (batch) can starve pipeline steps and code reviews (interactive) of provider
quota. The reference egress gateway limits calls with sliding windows, reserves part of the budget for
interactive calls, checks a batch's capacity before it starts and answers `429` with `Retry-After`. Its CI
also gates on secret scanning, dependency CVEs with expiring suppressions, and an SBOM.

## Decision
- The `llm-gateway` gets a **weighted sliding-window limiter per provider** (requests and tokens per
  minute, configurable), with call priority `INTERACTIVE` (workflow runs, CI executions, model tests) or
  `BATCH` (benchmark attempts) and a reserved interactive share (default 20%). Short waits are absorbed;
  longer ones fail with a retryable `IntegrationException` that the benchmark executor retries later. It
  stays a module, not a separate service: the platform has one outbound caller.
- **CI guardrails**: TruffleHog secret scan, Trivy dependency and image scan producing a CycloneDX SBOM and
  failing on fixable HIGH/CRITICAL findings (suppressions need an expiry date and a reason), detekt with bans
  on `println`, `Thread.sleep` and argument-less `Instant.now()`, frontend dependency-cruiser rules for
  feature isolation. Each rule has an ID and a documented negative test (`docs/architecture/guardrails.md`).

## Consequences
- Limits are in-memory per API replica; with several replicas each gets a share of the configured budget
  (documented). A shared store (Redis, bucket4j) is a later option.
