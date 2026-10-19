# LLM providers

Back Office calls language models for workflow LLM steps, code reviews and benchmarks, and it relays the
calls of coding agents in sandboxes through its LLM gateway. Provider keys are server configuration held
by the API and worker pods only. This chapter shows where to create each key, the narrowest permissions,
where to put it, how to test it and how to rotate it.

**Who needs this:** platform administrator.

<a id="llm-overview"></a>
## How provider keys are used

**Add providers in Back Office** (ADR 0045): **Models** → **LLM providers** (`/models/providers`, you need
`admin.settings.manage`) → **Add provider**. Pick the type, a name and a key (for example `openrouter`), the base
URL where the type needs one, and paste the API key. **Quick start** fills in OpenRouter, Groq, GitHub Models,
Mistral, Google Gemini, Ollama or LM Studio. **Test and list models** calls the provider and lists the model ids it
offers: copy one into a model profile. You can add several connections of one type, for example OpenRouter and Groq;
a model profile picks one in its field **Connection** (empty = the first connection of its type).

- The key is stored encrypted with the server's secret key (`BACKOFFICE_SECRETS_KEY`, the one Jira connections use)
  and never shown again; leave the field empty to keep it, or tick **Remove the stored key**. Without that secret key
  configured, keys cannot be stored.
- Changes apply without a restart (other replicas within 30 seconds).
- **Requests per minute** and **Requests per day** (optional, ADR 0046) are limits Back Office keeps for the
  connection: it counts every request through it (workflow steps, benchmarks, agents in sandboxes) and stops at the
  limit instead of letting the provider refuse. A reached daily limit fails at once until 00:00 UTC, so benchmarks
  skip the remaining attempts of the models on that connection. The OpenRouter quick start fills in 20 and 50, the
  free tier without purchased credits (1000 a day after buying 10 credits); the list shows today's count.
- **Environment variables** below still work as a fallback: a provider type without any connection uses them, and
  the page lists them read-only. Prefer connections in the UI; keep the variables only where deployment tooling
  already sets them.

| Provider | `Provider` value | Active when | Variable(s) | Helm value (`secrets.providerKeys.*`) |
|---|---|---|---|---|
| Anthropic (Claude) | `ANTHROPIC` | key set | `ANTHROPIC_API_KEY` | `anthropicApiKey` |
| OpenAI | `OPENAI` | key set | `OPENAI_API_KEY` | `openaiApiKey` |
| Google Gemini | `GOOGLE` | key set | `GOOGLE_GENAI_API_KEY` | `googleGenaiApiKey` |
| Mistral | `MISTRAL` | key set | `MISTRAL_API_KEY` | `mistralApiKey` |
| Ollama | `OLLAMA` | base URL set (default `http://localhost:11434`) | `OLLAMA_BASE_URL` | `ollamaBaseUrl` |
| OpenAI-compatible endpoint | `OPENAI_COMPATIBLE` | base URL **and** key set | `OPENAI_COMPATIBLE_BASE_URL`, `OPENAI_COMPATIBLE_API_KEY` | `openaiCompatibleBaseUrl`, `openaiCompatibleApiKey` |

- The chart writes these into the Secret `<release>-provider-keys`, read by the api and worker pods only
  (`envFrom`). For production create the Secret yourself with exactly these key names and set
  `secrets.providerKeys.create=false` and `secrets.providerKeys.existingSecret=<name>` (or External Secrets,
  `externalSecrets.providerKeys.remoteKey`). Docker Compose: `deploy/compose/.env`.
- **Settings** → **LLM providers** shows which provider types are configured (by a connection or by
  environment variables); **Models** tests a model profile.
- **Sandboxes never receive a provider key.** A coding agent gets a per-job token (`bo_job_...`) and calls
  the gateway at `<public URL>/api/v1/llm/anthropic` or `/api/v1/llm/openai/v1`. The gateway checks the
  job's model allowlist and budget, adds the real key and books the usage
  ([agent-execution.md](agent-execution.md#credentials)). Claude Code therefore runs on your Anthropic key,
  OpenCode on whichever provider the step's model uses.
- Keys from environment variables are read when the pods start; changing them needs a restart of api and worker.

**Good practice for every vendor**

1. One key per environment (dev, staging, prod) and per installation, named so you recognise it, for
   example `back-office-prod`.
2. Set a spend limit at the vendor **in addition to** Back Office budgets
   ([costs-insights.md](costs-insights.md#budgets)). Back Office budgets stop agent calls, the vendor limit
   is your backstop if something bypasses Back Office.
3. Create keys under an organisation or service account, not a person's account.

**Test any provider**

1. **Settings** → **LLM providers**: the provider shows **API key set**.
2. **Models** (needs `models.manage`): on a profile of that provider press the test button
   (**Send a tiny prompt and measure latency and cost**). It answers with latency and cost, or the
   provider's error.
3. For agents: run a workflow step that uses the profile; the run's cost appears under **Costs**.

**Rotate any key** (no downtime):

1. Create the new key at the vendor; keep the old one.
2. Replace the value in the Secret (`helm upgrade --set secrets.providerKeys.<key>=...`, or
   `kubectl apply` on your own Secret).
3. `kubectl -n back-office rollout restart deploy/back-office-api deploy/back-office-worker` (the chart
   restarts automatically only when it renders the Secret itself).
4. Test a profile on **Models**, then delete the old key at the vendor.

<a id="anthropic"></a>
## Anthropic

1. Sign in to the Claude Console (platform.claude.com, formerly console.anthropic.com) with an
   organisation account that has the admin or developer role.
2. Create a dedicated **workspace** for Back Office (**Settings** → **Workspaces** → **Create workspace**),
   so its keys, rate limits and spend limit are separate from other use.
3. **Settings** → **API keys** → **Create key**, choose the workspace, name `back-office-prod`, copy the
   key (`sk-ant-...`).
4. In the workspace settings set a **spend limit** per month.
5. Put it in `ANTHROPIC_API_KEY` / `secrets.providerKeys.anthropicApiKey`.

Anthropic keys have no finer permission model than the workspace; the workspace is your scope.
Claude Code also makes background calls with a small model: allow it for agent jobs with
`RUNNER_EXTRA_ALLOWED_MODELS=anthropic/claude-haiku-*` (`backoffice.runner.extra-allowed-models`), else the
gateway answers `model-not-allowed`.
Docs: https://docs.claude.com/en/api/overview

<a id="openai"></a>
## OpenAI

1. Sign in to platform.openai.com → choose or create a **project** for Back Office (**Settings** →
   **Projects** → **Create project**).
2. **Settings** → **Project** → **API keys** → **Create new secret key**. Owned by: **Service account**
   (survives people leaving), project: the Back Office project.
3. **Permissions**: **Restricted**, then grant **Write** for **Model capabilities** (`/v1/chat/completions`,
   used by both the in-app client and the gateway) and **Read** for **Models**; leave the rest **None**.
4. Copy the key (`sk-proj-...` / `sk-svcacct-...`).
5. In **Settings** → **Project** → **Limits** set a monthly budget and, if you wish, an allowlist of models.
6. Put it in `OPENAI_API_KEY` / `secrets.providerKeys.openaiApiKey`.

Docs: https://platform.openai.com/docs/api-reference/authentication

<a id="google"></a>
## Google Gemini

1. Open Google AI Studio (aistudio.google.com) → **Get API key** → **Create API key**, and select a Google
   Cloud project dedicated to Back Office (create one if needed).
2. In the Google Cloud console of that project → **APIs & Services** → **Credentials** → the key →
   **API restrictions** → **Restrict key** → only **Generative Language API**. (An application restriction
   by IP is possible when your egress IPs are fixed.)
3. Enable billing on the project and set a **budget alert** (**Billing** → **Budgets & alerts**).
4. Put it in `GOOGLE_GENAI_API_KEY` / `secrets.providerKeys.googleGenaiApiKey`.

Agents reach Gemini through the gateway's OpenAI-compatible endpoint
(`generativelanguage.googleapis.com/v1beta/openai`) with the same key.
Docs: https://ai.google.dev/gemini-api/docs/api-key

<a id="mistral"></a>
## Mistral

1. Sign in to console.mistral.ai, select the workspace for Back Office (or create one).
2. **API keys** → **Create new key**, name `back-office-prod`, optionally an expiration date; copy the key.
3. Under the workspace's billing settings set a monthly usage limit.
4. Put it in `MISTRAL_API_KEY` / `secrets.providerKeys.mistralApiKey`.

Docs: https://docs.mistral.ai/getting-started/quickstart/

<a id="ollama"></a>
## Ollama

Ollama has no API key. Back Office needs only the server URL.

1. Run Ollama where the api and worker pods can reach it, for example a Service `http://ollama.ai-models.svc.cluster.local:11434` (Service `ollama` in namespace `ai-models`),
   and pull the models (`ollama pull qwen3-coder`).
2. Set `OLLAMA_BASE_URL` / `secrets.providerKeys.ollamaBaseUrl` to that URL **without** `/v1`. The gateway
   appends `/v1` for agents.
3. If the Back Office chart's NetworkPolicies are on, allow egress from the api and worker pods to the
   Ollama port.

**Limits**: Ollama always counts as configured because of the default URL, so **Settings** shows it even
when no server runs; the profile test on **Models** tells the truth. Ollama has no authentication: never
expose it beyond the cluster network.

<a id="openai-compatible"></a>
## OpenAI-compatible endpoints

One extra endpoint that speaks the OpenAI chat-completions protocol: OpenRouter, Groq, Together, vLLM,
LM Studio, an internal gateway.

1. Create a key at that vendor (OpenRouter: openrouter.ai → **Settings** → **API keys**, set a credit
   limit on the key; Groq: console.groq.com → **API keys**; vLLM: the value passed to `--api-key`).
2. Set `OPENAI_COMPATIBLE_BASE_URL` to the base **including** the version path, for example
   `https://openrouter.ai/api/v1` or `https://api.groq.com/openai/v1`. Back Office appends
   `/chat/completions`.
3. Set `OPENAI_COMPATIBLE_API_KEY`. The provider counts as configured only with both values; for a vLLM
   server without authentication put any non-empty placeholder.
4. Create model profiles with provider **OPENAI_COMPATIBLE** and the vendor's model id.

**Limits**: through environment variables only one OpenAI-compatible endpoint is possible; add more as connections
under **LLM providers**. Base URL and key must come from the same vendor (an OpenRouter key against a Groq URL answers
`401`).

Errors and rate limits: [troubleshooting.md](troubleshooting.md#providers).
