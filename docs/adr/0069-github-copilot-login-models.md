# 0069 — GitHub Copilot models on the user's own login

- Status: accepted
- Date: 2026-11-18
- Deciders: owner
- Related: ADR 0040 (personal runners), 0048 and 0050 (login models, extended here), 0065 (cost at API prices);
  docs/manual/personal-runner.md#copilot

## Context

A GitHub Copilot subscription offers models of several vendors. OpenCode, already in the runner image, has a
`github-copilot` provider that GitHub supports for Copilot subscriptions. Benchmarks could not use it.

## Decision

- **Runtime `COPILOT_LOGIN`** (route OWN_LOGIN, harness OpenCode), provider `GITHUB_COPILOT` (never the
  gateway). Rules of `CLAUDE_CODE_LOGIN`: personal, the creator's LOCAL_LOGIN personal runner, private work, nothing
  billed; coding needs OpenCode; it never judges. `ModelRuntime.loginHarness` replaces Claude-only checks.
- **The login stays on the computer.** `opencode auth login` stores a `github-copilot` OAuth entry in OpenCode's
  `auth.json`, mounted read-only into the runner-controller. The controller reports `copilotLogin` per lease and
  hands only that entry to OpenCode jobs with a Copilot model (bundle file `copilot-login`); the runner deletes it
  and sets it only as `OPENCODE_AUTH_CONTENT` (OpenCode 1.18.32). Egress `api.githubcopilot.com`.
- **Prompt tasks**: OpenCode with an agent whose prompt is the system message, all tools denied, one step.
- **Cost**: spent 0; OpenCode's figure is dropped. Tokens are priced like the same model in the catalogue, else
  the vendor list price (`ApiPricing`, cited), else the Claude family, else the profile's own pricing; a profile
  without any is refused on save (`pricing.*`).

## Alternatives considered

- `GITHUB_TOKEN`: OpenCode's Copilot plugin uses only an OAuth `auth.json` entry.
- A nullable `apiCost`: a migration and every reader changed for a case the save rule prevents.

## Consequences

- A model not enabled in the user's Copilot settings fails with a hint where to enable it.
- A price lost later values attempts at 0; their leaderboard row has `apiCostKnown = false`: a dash, never the
  cheapest.
