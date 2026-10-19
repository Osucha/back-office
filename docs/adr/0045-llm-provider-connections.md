# 0045 — LLM provider connections managed in Back Office

- Status: accepted
- Date: 2026-10-18
- Deciders: owner
- Related: ADR 0008 (LLM gateway), 0024 (runner fleet and LLM proxy), 0030 (sealed connection secrets);
  docs/manual/integrations-llm.md

## Context

Provider keys were environment variables (`ANTHROPIC_API_KEY`, `OPENAI_COMPATIBLE_BASE_URL`, ...) read once at
startup, one endpoint per provider type. Adding or rotating a key meant editing a Secret or `.env` and restarting, and
only one OpenAI-compatible endpoint was possible, so OpenRouter, Groq and a local LM Studio could not be compared side
by side, which is exactly what evaluation and benchmarks need.

## Decision

1. **Connections in the database** (`llm_provider_connection`, llm-gateway V6): key (fixed, what model profiles name),
   name, provider type (fixed), optional base URL, API key sealed with the installation's secret key
   (`BACKOFFICE_SECRETS_KEY`, envelope AES-256-GCM, associated data `llm-provider:<id>`), enabled. Managed under
   **Models → LLM providers** (`/api/v1/llm-providers`; list with `models.view`, change with
   `admin.settings.manage`, audited without the key). Keys are write-only.
2. **Several connections per type.** `ModelRef.connection` names one (model profiles: `model_profile.connection_key`,
   evaluation V202; runner specs: `model.connection`). Without a name the first enabled connection of the type serves
   the model.
3. **Environment variables stay as the fallback** of a type without connections, listed read-only, so existing
   deployments keep working.
4. **Resolution** (`ProviderRegistry`): by connection key, else the first connection of the type, else the
   environment. Clients and concurrency limits are kept per endpoint and replaced when its URL or key changes;
   connections are cached for 30 seconds and refreshed at once after a change on the same replica.
5. **Agents** keep calling the gateway's proxy; the job credential carries the model's connection
   (`modelConnections`), so the proxy adds that connection's key and URL.
6. **Connection test**: the provider's model list endpoint (`/v1/models`, `/models`, Ollama `/api/tags`), which also
   shows the model ids to put into profiles.
7. The secret key is offered to other contexts through the shared-kernel port `SecretVault`, implemented by the
   projects adapters over the existing cipher.
8. **Model profiles come from the connected providers.** The dated starter profiles are no longer seeded (they named
   models the installation usually cannot reach). `GET /api/v1/llm-providers/catalog` (`models.view`) lists the
   models of every usable endpoint, cached ten minutes per endpoint, with prices per million tokens and context
   windows where the provider publishes them (OpenRouter `pricing`, `context_length`; `:free` models and Ollama are
   marked free). The Models page offers them under "Available from providers" with "Add as profile", and hides
   profiles whose provider is not connected until asked to show them.

## Consequences

- Keys are added, rotated and removed in the UI without restarts; several vendors of one protocol can be benchmarked
  against each other.
- Storing keys needs `BACKOFFICE_SECRETS_KEY`; rotating that key keeps connections readable while the old key is
  listed in `BACKOFFICE_SECRETS_PREVIOUS_KEYS` (LLM keys are not re-wrapped automatically yet).
- Rate limits stay per provider type (environment configuration), not per connection.
