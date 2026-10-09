# 0024 - Agent execution: pull-based runner fleet, Agent Sandbox with gVisor/Kata, brokered credentials

Status: accepted - 2026-09-28. Supersedes the execution model of [0006](0006-coding-agents-in-kubernetes-jobs.md).

## Context
Coding agents run model-written code, read prompt-injectable inputs (issues, diffs, repositories) and
use network, credentials and compute. In the first iterations the API created Kubernetes Jobs itself
(RBAC to create pods in its namespace) and injected provider API keys and a git token into the pod as
environment variables. Three problems follow: a compromised or buggy API can run arbitrary pods; an agent
can exfiltrate every key it is given; and ordinary containers share the node kernel, which is not a
sufficient boundary for untrusted code. The owner also asked whether "the system starting pods itself"
is the right model at all.

## Decision
**Separate the control plane (Back Office) from the execution plane (runner fleet), and give the agent
nothing it could leak.**

1. **Pull, not push.** A `runner-controller` runs in any Kubernetes cluster (the platform's own, a
   dedicated agents cluster, a customer's on-prem cluster, a developer laptop with Docker). It registers
   with a registration token, leases jobs over HTTPS (long polling), heartbeats and reports. Back Office
   holds **no cluster credentials at all**; outbound-only connectivity from the fleet. Pools with labels
   (e.g. `gpu`, `eu`, `customer-a`) route jobs; a project or template selects a pool.
2. **Sandbox per job.** Default backend: **Kubernetes Agent Sandbox** (`kubernetes-sigs/agent-sandbox`,
   SIG Apps): `SandboxTemplate` per environment, `SandboxClaim` per job, `SandboxWarmPool` for sub-second
   starts. Runtime class **gVisor** by default; **Kata Containers** (microVM) for `isolation: HIGH`
   policies. Fallback backend where the CRDs are not installed: a plain `Job` with the same RuntimeClass,
   security context and policies. Docker backend for local development only.
3. **Credential brokering.** Agents never receive provider keys: they call Back Office's **LLM gateway**,
   which exposes Anthropic-compatible (`/llm/anthropic/v1/messages`) and OpenAI-compatible
   (`/llm/openai/v1/chat/completions`) endpoints authenticated with a **per-job token** (`bo_job_`, bound
   to one job, one model allowlist, expiring with the lease). Claude Code (`ANTHROPIC_BASE_URL`) and
   OpenCode (`provider.*.options.baseURL`) support this natively. The gateway meters every token into the
   usage ledger and enforces budgets in real time. Git credentials live in the runner cluster (per-project
   Secret, short-lived where the host supports it) and are mounted only into the git credential helper.
4. **Default-deny egress.** A NetworkPolicy allows the sandbox to reach only its egress proxy; the
   proxy (Stripe **Smokescreen**, an allowlisting CONNECT proxy) permits only the LLM gateway, the git host
   and the package registries in the job's policy, and logs every connection to the job's event stream.
   *Refined during implementation:* the proxy is a per-pool Deployment with per-job roles (proxy credentials),
   not a sidecar, because a pod-level NetworkPolicy cannot restrict one container of a pod
   (docs/architecture/10-agent-execution.md, "Egress").
5. **Least-privilege runtime.** Non-root, read-only root filesystem, dropped capabilities, seccomp
   `RuntimeDefault`, no service-account token, ephemeral workspace, CPU/memory/ephemeral-storage limits,
   `activeDeadlineSeconds`, process and output caps.
6. **Tool policy.** Each task template carries a `SandboxPolicy.tools` (allow / deny / ask command
   patterns); the runner renders it into OpenCode's `permission` config and Claude Code's
   `settings.json` permissions. In unattended jobs "ask" means "deny and report", so a human can widen the
   policy and retry.
7. **Evidence.** Every job produces a signed provenance attestation (ADR 0025): inputs, template and model,
   policy, network log digest, diff digest, verification result.

## Alternatives
- **API creates pods directly (previous model)**: simplest, but concentrates cluster privileges in the most
  exposed component and cannot reach other clusters.
- **Hosted sandboxes** (E2B, Daytona, Modal, cloud agent runtimes): excellent isolation and zero ops, but
  data leaves the organisation and the evaluation capability needs identical conditions for every model;
  kept as future pluggable backends behind the same runner protocol.
- **Firecracker managed by us**: strongest boundary, but reimplements what Kata and Agent Sandbox provide.

## Consequences
- The fleet is a separately deployable chart (`back-office-runner`) that can run in several clusters.
- Agent Sandbox and a gVisor/Kata RuntimeClass are cluster prerequisites for the default backend (the
  chart can install the controller; GKE, AKS and EKS have documented runtime options).
- The runner protocol v1 (push, API-created Jobs) is replaced by v2; the embedded Docker mode remains for
  development.
