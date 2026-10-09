# back-office-runner Helm chart

The Back Office runner fleet (execution plane, ADR 0024): a `runner-controller` that registers with Back
Office, pulls agent jobs over HTTPS for its pool and runs each one in a sandbox - Kubernetes Agent
Sandbox (`SandboxTemplate` / `SandboxWarmPool` / `SandboxClaim`) or a plain `Job` - with a gVisor or
Kata RuntimeClass, default-deny NetworkPolicies and a per-pool Smokescreen-based egress proxy
(`bo-egress-<pool>`, created by the controller) as the only way out.
Install one release per pool, in any cluster. Guide: [runner-fleet-install.md](../../../docs/guides/runner-fleet-install.md);
cloud specifics: [cloud-reference.md](../../../docs/guides/cloud-reference.md).

```bash
deploy/scripts/check-runner-cluster.sh --standard gvisor --high kata
helm upgrade --install runners deploy/helm/back-office-runner -n back-office-runners --create-namespace \
  -f deploy/helm/back-office-runner/values-gvisor.yaml \
  --set backOffice.url=https://back-office.example.com --set registration.token=bo_rrt_...
```

## Variants

| File | Backend | Runtime | Use |
|---|---|---|---|
| `values.yaml` | `auto` | `gvisor` / `kata` referenced | base |
| `values-gvisor.yaml` | `AGENT_SANDBOX` (installs Agent Sandbox v1.0.4) | gVisor, warm pool, KEDA | default production shape |
| `values-kata.yaml` | `K8S_JOB` | Kata for every job, creates RuntimeClass | no Agent Sandbox, microVM isolation |
| `values-dev.yaml` | `K8S_JOB` | runc (none) | kind / k3d, development only |
| `values-aws.yaml`, `values-gcp.yaml`, `values-azure.yaml`, `values-onprem.yaml` | `AGENT_SANDBOX` | per platform | [cloud-reference.md](../../../docs/guides/cloud-reference.md) |

`backend: DOCKER` is rejected: it is the laptop backend of `deploy/compose`.

## Objects rendered

| Object | Namespace | Condition |
|---|---|---|
| Deployment `<fullname>` (runner-controller, pods labelled `app.kubernetes.io/name: back-office-runner-controller`), ServiceAccount | release | always |
| Service `<fullname>-controller` (port `controller.port` 8090; `RUNNER_CONTROLLER_URL` for the egress proxies) | release | always |
| Secret `<fullname>-registration` | release | `registration.token` set |
| Role/RoleBinding `<fullname>-token` (token Secret by name, leases) | release | always |
| Namespace `<release-ns>-sandboxes` (PSA restricted, kept on uninstall) | - | `sandboxNamespace.create` |
| Role/RoleBinding `<fullname>-sandboxes` (Sandbox, SandboxTemplate, SandboxWarmPool, SandboxClaim, jobs, pods, pods/exec, pods/log, secrets, networkpolicies, deployments, services) | sandbox | always |
| ServiceAccount `<fullname>-sandbox` (no token) | sandbox | always |
| (Deployment `bo-egress-<pool>` - created and owned by the controller, not rendered by Helm) | sandbox | - |
| NetworkPolicies `default-deny`, `sandboxes`, `egress-proxy`, `<fullname>` | sandbox / release | `networkPolicy.enabled` |
| PVCs `<fullname>-cache-<name>` (kept on uninstall) | sandbox | `caches.enabled` |
| ScaledObject + TriggerAuthentication (metrics-api) / HPA | release | `autoscaling.type=keda` / `hpa` |
| PodDisruptionBudget | release | `controller.podDisruptionBudget.enabled` |
| RuntimeClasses `runtimeClasses.standard` / `.high` | cluster | `runtimeClasses.create` |
| Agent Sandbox CRDs, controller (`agent-sandbox-system`), RBAC | cluster | `agentSandbox.install` |

## Values

| Key | Default | Description (env of the controller) |
|---|---|---|
| `backOffice.url` | `https://back-office.example.com` | `RUNNER_BACKOFFICE_URL` |
| `backOffice.poolId` | `default` | KEDA queue URL, `backoffice.io/pool` label (the controller takes its pool from the registration token) |
| `registration.token` / `existingSecret` | `""` | `RUNNER_REGISTRATION_TOKEN` (first start) |
| `tokenSecret.name` / `key` | `<fullname>-token` / `runnerToken` | `RUNNER_TOKEN_SECRET_NAME` (written by the controller in the release namespace, never the sandbox namespace) |
| `backend` | `auto` | `RUNNER_BACKEND` |
| `runtimeClasses.standard` / `high` | `gvisor` / `kata` | `RUNNER_RUNTIME_CLASS_STANDARD` / `_HIGH` |
| `runtimeClasses.create` / `gvisorHandler` / `kataHandler` / `scheduling` | `false` / `runsc` / `kata` / `{}` | RuntimeClass objects |
| `sandboxNamespace.create` / `name` / `podSecurity` | `true` / `<ns>-sandboxes` / `restricted` | `RUNNER_NAMESPACE` |
| `maxConcurrent` | `5` | `RUNNER_MAX_CONCURRENT` |
| `warmPool.size` | `2` | `RUNNER_WARM_POOL_SIZE` (null = the pool's value in Back Office) |
| `caches.enabled` / `storageClass` / `accessMode` / `volumes` | `false` / `""` / `ReadWriteMany` / gradle 50Gi, npm 20Gi | PVCs; `runner-controller.kubernetes.cache-pvcs` in `SPRING_APPLICATION_JSON` |
| `gitCredentials.repoSecrets` | `[]` | least privilege (ADR 0031): list of `{repository, read, push}`; `repository` = `host/group/project`, `host/group/*`, `host`, `*.domain` or `*` (most specific wins); `read` = Secret (key `token`, sandbox namespace) for READ jobs (reviews, spec analysis, benchmarks, `push=false`), `push` = Secret for PUSH_MR jobs (push the job branch, open a merge request; never merge); `runner-controller.git.repo-secrets` in `SPRING_APPLICATION_JSON` |
| `gitCredentials.allowPushCredentialForRead` | `false` | `RUNNER_GIT_ALLOW_PUSH_CREDENTIAL_FOR_READ`: hand the push Secret to READ jobs that have no read Secret. Keep `false` |
| `gitCredentials.existingSecret` / `hostSecrets` | `""` / `{}` | compatibility, **push level**: Secrets for every host / per host (`runner-controller.git.secrets`), used only when no `repoSecrets` entry matches; the controller warns at start; READ jobs covered only by them fail unless `allowPushCredentialForRead` |
| `gitCredentials.userName` / `userEmail` | `""` | `RUNNER_GIT_USER_NAME` / `RUNNER_GIT_USER_EMAIL`: commit identity, sandbox `GIT_USER_NAME` / `GIT_USER_EMAIL` (empty = `Back Office Agent <agent@backoffice.local>`) |
| `controller.*` | image `back-office/runner-controller`, 1 replica, port 8090, 100m/256Mi, grace 120 s | Deployment, Service (`PORT`, `RUNNER_CONTROLLER_URL`) |
| `autoscaling.type` / `minReplicas` / `maxReplicas` / `keda.*` | `none` / `1` / `5` | KEDA `metrics-api` on `/api/v1/runner/v2/pools/{poolId}/queue` (`valueLocation: queued`, target = `maxConcurrent`) |
| `egressProxy.image` / `port` | `back-office/egress-proxy:0.1.0` / `4750` | `RUNNER_EGRESS_PROXY_IMAGE`, `RUNNER_EGRESS_PROXY_PORT` (proxy pods `app.kubernetes.io/name: back-office-egress-proxy`, set by the controller) |
| `egressProxy.ports` / `allowedCidrs` | `[443, 80]` / `[]` | Proxy NetworkPolicy: public ports; private destinations (also `runner-controller.egress.allow-ranges`) |
| `egressProxy.alwaysAllowHosts` | `[]` | `runner-controller.egress.always-allow`: hosts every sandbox may reach, e.g. the object-storage host of presigned upload URLs |
| `networkPolicy.enabled` / `dnsPodLabels` / `controllerEgressCidrs` / `controllerEgressPorts` | `true` / `k8s-app: kube-dns` / `0.0.0.0/0` / `443, 6443` | |
| `agentSandbox.install` / `version` | `false` / `v1.0.4` | vendored upstream manifest |

## Git credentials for multi-repository jobs

`gitCredentials.repoSecrets` is matched **per repository** of a job: a job with several repositories gets, for
each one, the Secret of that repository's own level (`READ` -> `read`, `PUSH_MR` -> `push`, `NONE` -> nothing),
mounted only in the `bundle` init container (one volume `git-repo-<n>` per repository) and copied to
`/var/run/backoffice/git/<key>/token`. Every repository host is added to the job's egress allowlist. A typical
setup: documentation repositories with a read-only token and no push Secret, source repositories with both.

```yaml
gitCredentials:
  repoSecrets:
    - repository: gitlab.example.com/shop/docs      # read only: never pushed by any job
      read: git-shop-docs-read
    - repository: gitlab.example.com/shop/*         # source repositories of the group
      read: git-shop-read
      push: git-shop-push
    - repository: gitlab.example.com/platform/libs  # a shared library some jobs change
      read: git-libs-read
      push: git-libs-push
```

A job asking for `PUSH_MR` on `shop/docs` fails before a sandbox starts with
`policy-violation: repository 'docs' (gitlab.example.com/shop/docs): git credential: ... has no push credential`;
a `READ` repository whose entry only has `push` fails the same way unless `allowPushCredentialForRead`.
See docs/manual/integrations-git.md#git-multi-repo.

## Agent Sandbox

`files/agent-sandbox/sandbox-with-extensions.yaml` is the unmodified release asset of
[kubernetes-sigs/agent-sandbox v1.0.4](https://github.com/kubernetes-sigs/agent-sandbox/releases/tag/v1.0.4)
(CRDs `agents.x-k8s.io/v1beta1` Sandbox and `extensions.agents.x-k8s.io/v1beta1` SandboxTemplate,
SandboxClaim, SandboxWarmPool; controller image `registry.k8s.io/agent-sandbox/agent-sandbox-controller:v1.0.4`).
It is cluster-scoped: enable `agentSandbox.install` in one release per cluster, or apply it once with
`kubectl apply -f https://github.com/kubernetes-sigs/agent-sandbox/releases/download/v1.0.4/sandbox-with-extensions.yaml`.
Upstream also publishes a Helm chart (`helm/` in the repository) if you prefer to manage it separately.
