# 0003 - Spring AI as the multi-vendor LLM abstraction

Status: accepted - 2026-09-26

## Context
Models from any vendor must be usable both in pipelines and in benchmarks, with token usage captured
for cost accounting, without writing and maintaining a client per vendor.

## Decision
Spring AI 2.0 `ChatModel`/`ChatClient` behind the `LlmGateway` port. Providers: Anthropic (official Java
SDK underneath), OpenAI, Google GenAI, Mistral, Ollama, OpenAI-compatible endpoints. Structured output
through `ChatClient.entity(...)` with provider schema enforcement where available. Micrometer
observations (`gen_ai.*`) are exported over OTLP.

## Alternatives
- LangChain4j: comparable coverage, but Spring AI is first-party Spring, has the Boot 4 baseline, MCP
  client auto-configuration and observability integrated.
- Vendor SDKs directly: best control per vendor, but N clients, N usage formats, N retry policies.

## Consequences
- Vendor-specific options (Anthropic effort, OpenAI reasoning effort, Gemini thinking level) are mapped
  from one `reasoning` enum in `ModelOptions`; unmapped extras pass through `options.extra`.
- Spring AI default `ChatModel` auto-configuration is disabled (`spring.ai.model.chat=none`); the
  gateway instantiates models per provider explicitly so several providers coexist.
