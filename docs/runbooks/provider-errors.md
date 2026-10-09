# Runbook: LLM provider errors

## When to use this runbook

An LLM-backed operation failed with a provider error: a pipeline `LLM` step failed, a code review
ended `FAILED`, benchmark attempts show `FAILED` with an `HTTP …` message, or
`POST /api/v1/model-profiles/{id}/test` returned `ok: false`. All provider calls go through the
`llm-gateway` module, so the symptoms and fixes are the same everywhere.

## How errors surface

- The gateway wraps every provider failure in `IntegrationException(system = <provider>)`. Over REST it
  is a `502` problem detail with `system` and `retryable`; on an attempt it is the `error` field; in a
  pipeline it is the step failure message.
- Message shape: `anthropic: HTTP 429 (rate limited): <provider message>`. The parenthesised hint is
  the classification below.
- Retryable errors (429, 5xx, connection failures) are retried automatically up to
  `backoffice.llm.max-retries` (default 2) with jittered backoff; the error you see is the last attempt.
- Logs: `WARN provider call failed system=… attempt=… retryIn=…ms message=…` per retry;
  `DEBUG llm call provider=… model=… input=… output=… latencyMs=… cost=…` per success
  (`LOG_LEVEL=DEBUG` for `pl.mos.backoffice`). Prompts are never logged unless `AI_LOG_PROMPTS=true`.
- Every successful call is in the ledger: `GET /api/v1/usage/entries?runId=…|benchmarkId=…`.

## Quick checks

```
GET /api/v1/model-profiles/providers        -> configured flag per provider
POST /api/v1/model-profiles/{id}/test       -> smallest possible call with that exact profile
kubectl -n <ns> get secret back-office-provider-keys -o yaml   (Helm) / env of the api pod
```

If `test` succeeds but real calls fail, the difference is size (context, max tokens), structured
output or concurrency, not credentials.

## 401 / 403 - authentication failed, forbidden

Symptoms: `HTTP 401 (authentication failed, check the API key)` or `HTTP 403 (forbidden, …)`; happens
immediately, never retried; `providers` shows `configured: true`.

Checks:

1. The key is set for the right provider: `spring.ai.anthropic.api-key` / `ANTHROPIC_API_KEY`,
   `spring.ai.openai.api-key`, `spring.ai.google.genai.api-key`, `spring.ai.mistralai.api-key`,
   `backoffice.llm.openai-compatible.api-key`. A key pasted with a trailing newline or quote counts as set.
2. 403 with a valid key: the key belongs to an organisation or project without access to that model
   (OpenAI project keys, Anthropic workspaces), or the model needs an allow-list.
3. OPENAI_COMPATIBLE: the base URL and key must come from the same vendor (OpenRouter key with a Groq URL gives 401).

Fix: rotate or correct the secret, restart the api pod (keys are read at startup), re-run `test`.

## 429 - rate limited

Symptoms: `HTTP 429 (rate limited)` after retries; several attempts of one benchmark fail within seconds
of each other; often only one provider is affected.

Checks:

1. Concurrency: `backoffice.llm.concurrency-per-provider` (default 4) and
   `backoffice.evaluation.concurrency-per-provider` (default 2). A benchmark with 3 models on one
   provider and repetitions runs up to `concurrency-per-provider` attempts in parallel, each with a
   candidate and a judge call.
2. Tokens per minute, not requests: long contexts or high `maxOutputTokens` hit TPM limits first.
3. Provider dashboard: monthly spend cap reached also returns 429 on some vendors.

Fix: lower concurrency for benchmarks (`EVAL_CONCURRENCY_PER_PROVIDER=1`), spread candidates over
providers, raise the tier with the vendor, or configure the Back Office rate limiter below so calls are
paced before the vendor refuses them. Benchmark attempts that still end in a 429 are re-queued with
backoff; attempts already `FAILED` stay failed (create a new benchmark or a subset via `taskIds`).

## Back Office rate limiter

The gateway's own limiter (ADR 0020, `backoffice.llm.rate-limits.<provider>.requests-per-minute`,
`.tokens-per-minute`, `.interactive-reserve` default 0.2, `.max-wait` default `PT2S`) refuses a call
before it reaches the provider when the sliding one-minute window is full.

Symptoms:

- Error message `anthropic: rate limit reached for batch calls (requests/min 40, tokens/min 64000); capacity frees in 23s`
  (`retryable: true` in the `502` problem detail). No `HTTP` code: the provider was never called and
  nothing is billed or booked in the ledger.
- `batch` in the message: a benchmark attempt hit the budget minus the interactive reserve; the executor
  logs `attempt re-queued after retryable error attemptId=... retry=N delayMs=...` and tries again later.
  After `backoffice.evaluation.retry.max-attempts` re-queues the attempt fails with this message.
- `interactive` in the message: a pipeline step, code review or model test waited longer than
  `max-wait`; the whole budget (including the reserve) is in use. The step fails and can be retried.
- Calls take up to `max-wait` longer than usual: short waits are absorbed silently.

Checks and fixes:

1. Compare the configured limits with the vendor tier; with N API replicas each replica needs 1/N of the
   vendor quota (limits are in memory per replica).
2. Token budgets fill up fastest with large `maxOutputTokens`: the estimate is prompt characters / 4 plus
   `maxOutputTokens` and is corrected only after the call returns. Lower `maxOutputTokens` on profiles
   that never use it.
3. Interactive work starving: raise `interactive-reserve` (benchmarks get less) or run benchmarks
   off-hours. Benchmarks too slow: lower `interactive-reserve` or raise the limits.
4. A typo in the provider key (`backoffice.llm.rate-limits.antropic`) fails the startup with
   `unknown provider`; remove the entry to disable limiting for a provider.

## 5xx - provider error

Symptoms: `HTTP 500/502/503/529 (provider error)`; retried twice; often transient and clustered in time.

Checks:

1. Provider status page.
2. The same profile works via `test` a minute later.
3. Anthropic 529 (overloaded) behaves like 503; Ollama 500 usually means the model ran out of memory
   (`ollama ps`, server log).

Fix: wait and re-run; for benchmarks start a new one over the failed tasks. If it persists for one model
only, the model id may be deprecated and the provider answers 5xx instead of 404 for it.

## Model not found / bad request

Symptoms: `HTTP 404 (not found, check the model id)` or `HTTP 400 (request rejected): …model…`; never
retried.

Checks:

1. `ref.modelId` on the profile: exact vendor id (`claude-sonnet-5`, `gpt-5`, `gemini-2.5-pro`,
   `mistral-large-latest`, `qwen3:32b`). No display names, no provider prefix.
2. Ollama: the model is pulled (`ollama list`); the gateway does not pull models.
3. 400 mentioning `temperature`, `reasoning_effort`, `thinking` or `max_tokens`: option not supported by
   that model. The gateway never sends temperature while reasoning is on and disables thinking only where
   allowed; if a provider still rejects, set `options.reasoning = NONE` or lower `maxOutputTokens` on the
   profile. For OPENAI_COMPATIBLE endpoints that reject `reasoning_effort`, use `reasoning: NONE`.
4. 400 about context length: shrink the task context or pick a model with a larger window.

Fix: correct the profile (`PUT /api/v1/model-profiles/{id}`) and `test` it.

## Structured output failures

Symptoms: attempts fail with `structured output could not be parsed: …`; judge calls and `LLM` steps
with `outputSchema`, code reviews (`consolidate findings`) are affected; small models and Ollama models
most often.

How it works: the gateway appends the JSON schema to the system prompt, enables provider-side
enforcement where available (OpenAI `response_format: json_schema`, Anthropic output schema, Gemini
JSON mime type, Mistral `json_object`, Ollama `format: json`), strips Markdown fences and, if the
answer still is not a JSON object, sends one corrective message before failing.

Checks:

1. Attempt detail / step logs: is the model answering prose, truncating (finish reason `LENGTH`), or
   returning an array instead of an object?
2. Truncation: raise `maxOutputTokens` on the judge profile; a judge with many criteria needs 2-4k output tokens.
3. Provider enforcement rejected the schema (400 mentioning `schema`, `additionalProperties`,
   `response_format`): set `backoffice.llm.native-structured-output=false` to rely on prompting only, or
   simplify the schema in the step definition.
4. Model capability: Ollama models below ~30B and some compatible endpoints ignore JSON instructions.
   Use a stronger judge; the judge is a per-benchmark choice and does not have to be a candidate.

## Ollama unreachable

Symptoms: `ollama: connection failed: … Connection refused` or `… timed out`, retried twice;
`providers` shows `configured: true` for OLLAMA because only a base URL is required.

Checks:

1. `OLLAMA_BASE_URL` (`spring.ai.ollama.base-url`) is reachable from the api pod, not from your laptop:
   `kubectl exec <api pod> -- wget -qO- $OLLAMA_BASE_URL/api/tags`. In Docker Compose use
   `http://ollama:11434` or `http://host.docker.internal:11434`, not `localhost`.
2. The model is present (`/api/tags`) and fits in memory; a cold load can exceed
   `backoffice.llm.default-timeout` on the first call.
3. Network policy or service name after a Helm upgrade.

Fix: correct the URL, pull the model, raise `LLM_TIMEOUT` for the first warm-up call.

## Timeouts and concurrency slots

Symptoms: `call timed out after PT10M` or `timed out waiting for a free slot (concurrency 4)`.

The wall-clock limit per call is `backoffice.llm.default-timeout` (`LLM_TIMEOUT`, default 10 minutes);
callers wait up to the same time for one of `concurrency-per-provider` slots. Long generations with
`MAX` reasoning on large outputs can hit it; lower reasoning or output size, or raise the timeout. Slot
waits mean too many parallel pipelines and benchmarks share one provider; lower benchmark concurrency
or run benchmarks off-hours.

## After the fix

- Pipeline: retry the run from the failed step (`POST /api/v1/runs/{id}/retry`).
- Code review: re-trigger the CI job; the same `(project, MR, sha)` returns the existing review, so
  push a new commit or delete the failed review first.
- Benchmark: attempts that failed with a retryable error (rate limiter, 429, 5xx) are re-queued
  automatically with backoff; attempts already `FAILED` are not re-run; start a new benchmark,
  optionally limited to the affected `taskIds`.
- Costs: failed calls that reached the provider may still have been billed by the vendor but are not in
  the ledger (only successful calls are booked).

## Where to look when it fails

| Where | What |
|---|---|
| api logs | `provider call failed system=… message=…` with the classified error |
| `GET /api/v1/usage/entries` | whether calls are getting through and what they cost |
| `POST /api/v1/model-profiles/{id}/test` | credentials and model id with the smallest possible call |
| Provider dashboard | rate-limit tier, spend cap, model availability |
| [running-a-benchmark.md](../guides/running-a-benchmark.md) | rubric, judge and concurrency guidance for benchmarks |
| [runner-job-failed.md](runner-job-failed.md) | coding attempts that fail inside the agent runner rather than at the provider |
