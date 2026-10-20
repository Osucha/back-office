# 0048 — Benchmarking Claude Code on its user's own login

- Status: accepted
- Date: 2026-10-20
- Deciders: owner
- Related: ADR 0040 (personal runners, LOCAL_LOGIN), 0045 (model profiles from providers), 0046 (benchmark execution
  targets), 0047 (benchmarks that measure); docs/manual/evaluation.md

## Context

A personal runner can already run Claude Code with its owner's subscription login (`claude setup-token`,
`CLAUDE_CODE_OAUTH_TOKEN`, ADR 0040). Benchmarks could not use it: every candidate is a model profile, and profiles
come only from connected API providers. Pasting the subscription token into an Anthropic provider fails ("401 invalid
x-api-key"): it is not an API key, and Anthropic allows subscription tokens only in Claude Code itself, so Back Office
must not call the API with it.

## Decision

1. **A second way to reach a model.** `ModelProfile.runtime` (evaluation V206) is `API` (the gateway and a provider
   connection, as before) or `CLAUDE_CODE_LOGIN`: Claude Code on the benchmark creator's personal runner, signed in
   with their own login. Such a profile has provider ANTHROPIC and the model id Claude Code gets as `--model`
   (`opus`, `sonnet`, `haiku` or a full id); it needs no provider connection and costs nothing. The UI offers it as a
   model source "Claude Code (your login)".
2. **Where it may run.** A benchmark with a login candidate must use the PERSONAL execution target, the Claude Code
   harness and coding tasks only, and starts only when the creator's personal pool uses LOCAL_LOGIN
   (`ExecutionTarget.localLogin`). A login model never judges: the judge is called through the gateway. Prompt
   tasks through Claude Code (a prompt mode of the runner) are a later step.
3. **Checked, not called.** The model test and the preflight of a login profile check the caller's personal runner
   (exists, LOCAL_LOGIN, online) instead of calling the API.
4. **Nothing billed.** A coding job that ran on the user's own login reports `billing = SUBSCRIPTION`; its attempt
   costs 0 (Claude Code's own figure is an estimate). Token counts are kept.
5. **Not for workflows.** The `ModelCatalog` port does not expose login profiles, so workflow LLM steps cannot pick a
   model the gateway cannot call.
6. **A clear error for the wrong token.** An Anthropic provider connection refuses a key starting with `sk-ant-oat`
   and says what it is and what to use instead.

Rejected: a proxy that turns the subscription token into an API endpoint (against Anthropic's terms, breaks at any
time); one profile per user (the profile only names the model; whose login runs it follows from the benchmark's
creator and their personal runner).

## Consequences

- A login candidate measures "Claude Code with model X", including Claude Code's own system prompt and start-up time;
  compare it with API candidates on outcomes, not on response times.
- Subscription usage windows can run out during a long benchmark; the affected attempts fail and can be retried later.
- The subscription is personal: a login profile is usable by everyone, but each benchmark runs on its creator's own
  runner and login, never on the shared fleet.
