# runner-controller

Execution plane of the Back Office runner fleet (ADR 0024,
[docs/architecture/10-agent-execution.md](../../docs/architecture/10-agent-execution.md)). A separate Spring
Boot application that runs in any Kubernetes cluster (or next to Docker on a laptop), pulls agent jobs from
Back Office over HTTPS and runs each one in an isolated sandbox. Back Office never gets cluster credentials.

```
Back Office  ◀── register / config / lease (long poll) / heartbeat / complete ──  runner-controller
                                                                                   │ verify Ed25519 + digest
                                                                                   │ clamp policy to pool ceiling
                                                                                   ▼
          ◀── events / result / uploads (job token, via proxy) ──  sandbox pod ── egress proxy (Smokescreen)
          ◀── LLM gateway calls (job token, via proxy) ───────────┘                ▲ ACL + decisions
                                                                     runner-controller /egress/v1/*
```

## Layout

| Path | What |
|---|---|
| `protocol/` | runner v2 DTOs, `BackOfficeClient` (JDK HttpClient), runner-token stores (Kubernetes Secret / file) |
| `security/` | `CanonicalJson` (spec digest, RFC 8785 + null removal) and `AssignmentVerifier` (Ed25519 JWK) |
| `policy/` | `SandboxPolicy` parsing, `PolicyResolver` (clamp to ceiling, image allowlist, egress hosts) |
| `egress/` | `EgressRegistry` (per-job hosts, proxy credentials, decisions, digest), Smokescreen ACL rendering, `/egress/v1/*` endpoints |
| `sandbox/` | `SandboxBackend`, completion mapping, backend selection and capability detection |
| `sandbox/k8s/` | `AgentSandboxBackend` (v1beta1 CRDs), `KubernetesJobBackend`, `PodSpecRenderer`, `EgressInfrastructure` (proxy Deployment, NetworkPolicies) |
| `sandbox/docker/` | `DockerBackend` (internal network + proxy container + runner container) |
| `engine/` | `JobSupervisor` (lease loop, monitor, heartbeats, deadlines, completion, orphans, graceful shutdown), `RunnerRegistrar` |
| `egress-proxy/` | Go wrapper around Smokescreen (per-job roles from `Proxy-Authorization`, ACL pulled from the controller) and its Dockerfile |

## Build and test

```bash
cd backend
export JAVA_HOME=...jdk21
./gradlew :runner-controller:build --no-daemon          # unit + KubernetesMockServer tests + Docker e2e (skipped without images)
./gradlew :runner-controller:test --no-daemon -Pe2e     # fail instead of skipping the Docker e2e

# images
docker build -t back-office/runner:dev ../runner
docker build -t back-office/egress-proxy:dev runner-controller/egress-proxy
docker build -f runner-controller/Dockerfile -t back-office/runner-controller:dev .
```

Tests:

- `CanonicalJsonTest`, `AssignmentVerifierTest`: canonical form, tampered spec/policy/job id/lease, unknown and foreign keys, expiry;
- `PolicyResolverTest`: clamping, isolation, image allowlist, egress hosts vs ceiling;
- `EgressAclTest`: Smokescreen ACL YAML, credential hashing, decision digest;
- `PodSpecRendererTest`, `CompletionMappingTest`: pod hardening and status → completion;
- `KubernetesJobBackendTest`, `AgentSandboxBackendTest`: fabric8 `KubernetesMockServer` in CRUD mode (CRDs registered) — Job, Secret, proxy Deployment, NetworkPolicies, SandboxTemplate/WarmPool/Claim, cold Sandbox, warm bundle delivery, capability detection;
- `JobSupervisorTest`: against `FakeBackOffice` (JDK HttpServer, real Ed25519 signatures) — registration, happy path, verification failures, policy violations, cancel, lost lease, deadline, orphans, free slots, graceful shutdown;
- `DockerEndToEndTest`: DOCKER backend with the real runner image (STUB agent) and the real egress proxy against `FakeBackOffice` — LLM call through the gateway with the job token, non-allowlisted host refused by the proxy, no direct route, read-only rootfs, callbacks and upload with the job token, completion with `egressDenied` and `egressLogDigest`, cleanup.

## Run

Locally with Docker (development only, runc):

```bash
RUNNER_BACKOFFICE_URL=http://localhost:8081 RUNNER_REGISTRATION_TOKEN=bo_rrt_... RUNNER_BACKEND=DOCKER \
RUNNER_DEFAULT_IMAGE=back-office/runner:dev \
java -jar runner-controller/build/libs/runner-controller.jar \
  --runner-controller.egress.allow-ranges[0]=192.168.65.254/32     # host.docker.internal on Docker Desktop
```

In Kubernetes use the Helm chart `deploy/helm/back-office-runner`, or deploy the image with a ServiceAccount
bound to the Role described in doc 10 ("Backends"), `RUNNER_BACKOFFICE_URL`, `RUNNER_REGISTRATION_TOKEN`
(first start only) and a Service on port 8090 that the egress proxy pods can reach
(`runner-controller.kubernetes.controller-url`, `RUNNER_CONTROLLER_URL`). The pod needs the label
`app.kubernetes.io/name=back-office-runner-controller` (the proxy NetworkPolicy allows egress to it).

## Configuration (`runner-controller.*`)

Environment variables in parentheses; the older `BO_URL`, `BO_REGISTRATION_TOKEN`, `BO_RUNNER_BACKEND`,
`BO_RUNNER_MAX_CONCURRENT` and `BO_RUNNER_DEFAULT_IMAGE` are still accepted as fallbacks. Properties without
a variable bind from `SPRING_APPLICATION_JSON` or `--runner-controller.…` arguments.

| Property | Default | Meaning |
|---|---|---|
| `back-office.url` (`RUNNER_BACKOFFICE_URL`) | – | Back Office origin (`/api/v1` appended) |
| `back-office.registration-token` (`RUNNER_REGISTRATION_TOKEN`) | – | single-use `bo_rrt_` token, needed only until a runner token is stored |
| `back-office.credential-store` | `AUTO` | `KUBERNETES_SECRET` (`credential-secret`, `RUNNER_TOKEN_SECRET_NAME`, default `back-office-runner-credentials`, in `kubernetes.namespace`, key `runnerToken`) or `FILE` (`credential-file`, `RUNNER_TOKEN_FILE`, default `./runner-credentials.json`); `AUTO` = file with the DOCKER backend |
| `backend` (`RUNNER_BACKEND`) | `AUTO` | `AGENT_SANDBOX`, `K8S_JOB`, `DOCKER` |
| `max-concurrent` (`RUNNER_MAX_CONCURRENT`) | 4 | concurrent sandboxes (lease asks only for free slots) |
| `lease.wait-seconds`, `lease.max-jobs-per-lease` | 25, 4 | long poll |
| `heartbeat-seconds`, `monitor-interval-millis` | 15, 2000 | |
| `shutdown-grace-seconds` | 300 | running jobs may finish; the rest is handed back as `LOST` |
| `image-pull-grace-seconds`, `start-timeout-seconds`, `deadline-grace-seconds` | 90, 900, 120 | completion mapping |
| `verification.require-signature` | true | never disable outside development |
| `defaults.image` (`RUNNER_DEFAULT_IMAGE`), `.cpu`, `.memory`, `.ephemeral-storage`, `.pids`, `.timeout-seconds` | –, 2, 4Gi, 10Gi, 2048, 3600 | when neither policy nor ceiling set them |
| `egress.always-allow` | [] | hosts every job may reach (e.g. object storage of upload URLs) |
| `egress.proxy-image` (`RUNNER_EGRESS_PROXY_IMAGE`), `egress.proxy-port` (`RUNNER_EGRESS_PROXY_PORT`) | `back-office/egress-proxy:dev`, 4750 | |
| `egress.allow-ranges`, `egress.allow-addresses` | [] | private CIDRs / IP:port the proxy may connect to (Smokescreen `--allow-range`/`--allow-address`) |
| `egress.token` | generated | proxy ↔ controller token (stored in Secret `bo-egress-<pool>`) |
| `git.repo-secrets` | [] | least privilege (ADR 0031): list of `{repository, read, push}`; `repository` = `*`, `host`, `*.domain`, `host/group/project` or `host/group/*` (most specific wins); `read` = Secret (key `token`) for READ jobs, `push` = Secret for PUSH_MR jobs; the selected one is mounted at `/var/run/backoffice/git/token` |
| `git.secrets` | {} | compatibility fallback, **push level**: repository host (or `*`) → Secret; used only when no `repo-secrets` entry matches; logged as a warning at start |
| `git.allow-push-credential-for-read` (`RUNNER_GIT_ALLOW_PUSH_CREDENTIAL_FOR_READ`) | false | false = a READ job whose only credential is a push credential fails (`PolicyViolation: git credential: ...`) |
| `git.token-read-env`, `git.token-push-env` | `BO_GIT_TOKEN_READ`, `BO_GIT_TOKEN_PUSH` | Docker backend: controller env vars with the read-only and the push token (every repository) |
| `git.token-env` | `BO_GIT_TOKEN` | Docker backend: legacy single token, push-level fallback |
| `git.user-name` / `git.user-email` | "" (`RUNNER_GIT_USER_NAME` / `RUNNER_GIT_USER_EMAIL`) | commit identity, passed to every sandbox as `GIT_USER_NAME` / `GIT_USER_EMAIL`; empty = runner default |
| `kubernetes.namespace` (`RUNNER_NAMESPACE`) | own namespace | where sandboxes, pool resources and the runner-token Secret live (empty = own namespace) |
| `kubernetes.runtime-classes` (`RUNNER_RUNTIME_CLASS_STANDARD`, `RUNNER_RUNTIME_CLASS_HIGH`) | `STANDARD: gvisor`, `HIGH: kata` | empty value = no `runtimeClassName` |
| `kubernetes.require-runtime-class` | true | refuse jobs whose RuntimeClass is missing |
| `kubernetes.warm-pool-size` (`RUNNER_WARM_POOL_SIZE`) | from pool config | Agent Sandbox warm pool replicas (0 = cold `Sandbox` per job) |
| `kubernetes.cache-pvcs` | {} | cache name → PVC |
| `kubernetes.controller-url` (`RUNNER_CONTROLLER_URL`) | `http://back-office-runner-controller:8090` | as seen from the proxy pods (other namespace: use the FQDN) |
| `kubernetes.proxy-replicas`, `.dns-namespace`, `.dns-pod-labels`, `.private-cidrs`, `.job-ttl-seconds-after-finished` | 2, `kube-system`, `k8s-app=kube-dns`, RFC 1918 + link-local + CGNAT, 600 | |
| `docker.executable`, `.work-dir`, `.proxy-extra-hosts`, `.extra-env` | `docker`, tmp, `host.docker.internal:host-gateway`, {} | |
| `keep-finished-sandboxes` | false | debugging only |

Endpoints: `/actuator/health` (liveness/readiness), `/actuator/prometheus`, `/egress/v1/acl` and
`/egress/v1/decisions` (egress proxy only, bearer = pool egress token).
