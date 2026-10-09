# Installing the runner fleet

## When to use this guide

You want coding agents (`SPEC_KIT`, `IMPLEMENT` steps, coding benchmark attempts) to run: you install a
**runner pool** - the `back-office-runner` chart - in a Kubernetes cluster (the control-plane cluster, a
dedicated agents cluster, or a customer's on-prem cluster). The pool pulls jobs from Back Office over
HTTPS; Back Office never gets credentials for that cluster (ADR 0024,
[10-agent-execution.md](../architecture/10-agent-execution.md)). Cloud specifics are in
[cloud-reference.md](cloud-reference.md); a laptop uses the compose `full` profile instead
([local-development.md](local-development.md#5-building-the-images)).

## What gets installed

| Object | Namespace | Purpose |
|---|---|---|
| Deployment `<fullname>` (runner-controller) | release namespace | registers, leases jobs, drives sandboxes, heartbeats, reports |
| ServiceAccount + Roles | release + sandbox namespace | namespaced only - sandbox namespace: `agents.x-k8s.io` Sandbox, `extensions.agents.x-k8s.io` SandboxTemplate/SandboxWarmPool/SandboxClaim, Jobs, pods, `pods/exec`, `pods/log`, Secrets, NetworkPolicies, Deployments and Services (the per-pool proxy); release namespace: its own token Secret and leases |
| Namespace `<release-ns>-sandboxes` | - | sandbox pods, PSA `restricted` |
| NetworkPolicies `default-deny`, `sandboxes`, `egress-proxy`, controller | both | sandboxes reach only cluster DNS and the pool proxy on 4750; the proxy is the only pod with internet egress (never private ranges or the metadata endpoint unless `egressProxy.allowedCidrs`) |
| Deployment `bo-egress-<pool>` (Smokescreen-based, **created by the controller**, not by Helm) | sandbox namespace | per-job allowlists and credential hashes pulled from the controller |
| ScaledObject (KEDA metrics-api) or HPA | release namespace | `autoscaling.type` |
| PVCs `<fullname>-cache-gradle/npm` | sandbox namespace | `caches.enabled` |
| RuntimeClasses | cluster | only with `runtimeClasses.create=true` |
| Agent Sandbox v1.0.4 CRDs + controller (`agent-sandbox-system`) | cluster | only with `agentSandbox.install=true` (pinned manifest vendored in `files/agent-sandbox/`) |

Backends (`backend`): `AGENT_SANDBOX` (SandboxTemplate per environment, SandboxWarmPool, SandboxClaim per
job), `K8S_JOB` (plain `batch/v1 Job` with the same pod spec and RuntimeClass, no warm pool), `auto`
(AGENT_SANDBOX when the CRDs exist). `DOCKER` is the laptop backend and is rejected by the chart.

## Prerequisites

1. Kubernetes 1.29+, a CNI that **enforces** NetworkPolicy (Calico, Cilium, GKE Dataplane V2, AKS Cilium/NPM, EKS VPC CNI with network policies on).
2. A sandbox runtime: RuntimeClass `gvisor` (GKE Sandbox, or `runsc` on the nodes) for `STANDARD`,
   optionally Kata for `HIGH` (AKS `kata-vm-isolation`, kata-deploy elsewhere). Without any, only
   development use is acceptable (runc).
3. Agent Sandbox (recommended) - install once per cluster:
   `kubectl apply -f https://github.com/kubernetes-sigs/agent-sandbox/releases/download/v1.0.4/sandbox-with-extensions.yaml`
   or `agentSandbox.install=true` in exactly one release.
4. KEDA 2.x for queue-based autoscaling (optional).
5. Outbound HTTPS from the cluster to `BACKOFFICE_PUBLIC_URL` - nothing inbound.
6. Images in a registry the cluster can pull: `runner-controller`, `egress-proxy`
   (`backend/runner-controller/egress-proxy/Dockerfile`, Smokescreen-based) and the agent image(s)
   (`runner`), all signed by CI (cosign).

Check them with the script (creates and deletes a temporary namespace `bo-runner-check`):

```bash
deploy/scripts/check-runner-cluster.sh --standard gvisor --high kata --url https://back-office.example.com
```

```
== Agent Sandbox (AGENT_SANDBOX backend)
  [ OK ] CRD sandboxes.agents.x-k8s.io
  ...
== RuntimeClasses
  [ OK ] STANDARD: RuntimeClass gvisor (handler runsc)
  [WARN] HIGH: RuntimeClass kata missing (isolation HIGH jobs cannot run)
== Sandbox pods per RuntimeClass
  [ OK ] gvisor: pod ran inside gVisor ([    0.000000] Starting gVisor... Linux 4.4.0 ...)
== NetworkPolicy enforcement
  [ OK ] NetworkPolicy is enforced (reached before, blocked after default-deny)
RESULT: prerequisites met
```

`--skip-pods` checks only the API objects (no test pods); `--keep` leaves the namespace for inspection.

## Install

1. **Create the pool** in Back Office: *Administration → Runner fleet → New pool* (id, labels such as
   `eu`, `gpu`, allowed images, policy ceiling), then *Add runner* on the pool: a registration token
   (single use, `bo_rrt_...`) and chart values with `backOffice.url`, `backOffice.poolId`,
   `registration.token` and `backend` (usable with `-f` instead of the `--set` flags below).
2. **Registration Secret** (or pass `registration.token` for a quick test):

   ```bash
   kubectl create namespace back-office-runners
   kubectl -n back-office-runners create secret generic back-office-runner-registration \
     --from-literal=RUNNER_REGISTRATION_TOKEN=bo_rrt_...
   ```

3. **Git credentials** stay in this cluster, least privilege (ADR 0031): per repository (or group) a READ token
   (GitLab: role Reporter, scope `read_repository`) for jobs that only read and a PUSH token (role Developer,
   scope `write_repository` only, no `api`) for jobs that push a branch and open a merge request; protect the
   default branch so the push token cannot merge (step-by-step: docs/manual/integrations-git.md#git-least-privilege):

   ```bash
   read -rs TOKEN   # READ token
   kubectl -n back-office-runners-sandboxes create secret generic git-shop-api-read --from-literal=token="$TOKEN"
   read -rs TOKEN   # PUSH token
   kubectl -n back-office-runners-sandboxes create secret generic git-shop-api-push --from-literal=token="$TOKEN"
   unset TOKEN
   ```

   and map them in the values (`repository`: `host/group/project`, `host/group/*`, `host`, `*.domain` or `*`):

   ```yaml
   gitCredentials:
     repoSecrets:
       - repository: gitlab.example.com/shop/api
         read: git-shop-api-read
         push: git-shop-api-push
   ```

   The controller mounts the read Secret for READ jobs, the push Secret only for PUSH_MR jobs. The older
   `gitCredentials.existingSecret` / `hostSecrets` still work as **push-level** fallbacks (warning in the
   controller log); READ jobs covered only by them fail unless `gitCredentials.allowPushCredentialForRead=true`.
   The namespace is created by the chart; create the Secrets after the first install or pre-create the
   namespace with `sandboxNamespace.create=false`.
4. **Install**:

   ```bash
   helm upgrade --install runners deploy/helm/back-office-runner -n back-office-runners \
     -f deploy/helm/back-office-runner/values-gvisor.yaml \
     --set backOffice.url=https://back-office.example.com --set backOffice.poolId=eu-1 \
     --set registration.existingSecret=back-office-runner-registration \
     --set controller.image.repository=registry.example.com/back-office/runner-controller \
     --set controller.image.tag=$SHA \
     --set egressProxy.image=registry.example.com/back-office/egress-proxy:$SHA
   ```

   The images sandboxes may use (`allowedImages`) are part of the pool in Administration → Runner fleet,
   not of the chart; the controller receives them with the pool configuration.

   Variants shipped with the chart: `values-gvisor.yaml` (AGENT_SANDBOX + gVisor, warm pool, KEDA),
   `values-kata.yaml` (K8S_JOB + Kata for every job), `values-dev.yaml` (kind, runc, development only),
   `values-aws|gcp|azure|onprem.yaml` ([cloud-reference.md](cloud-reference.md)).
5. **Verify**: the controller logs `registered as runner ...`, the Secret `<fullname>-token` exists in the
   release (controller) namespace, not the sandbox namespace (key `runnerToken`; another namespace only
   when `RUNNER_TOKEN_SECRET_NAMESPACE` is set), the
   pool shows the runner *online* in Administration → Runner fleet, and a test workflow with an
   `IMPLEMENT` step creates a `SandboxClaim` (or Job) in `back-office-runners-sandboxes`:

   ```bash
   kubectl -n back-office-runners logs deploy/runners-back-office-runner | head
   kubectl -n back-office-runners get secret runners-back-office-runner-token
   kubectl -n back-office-runners-sandboxes get sandboxclaims,sandboxes,pods -w
   ```

After a successful registration remove the registration token (`kubectl delete secret ...`); it is
single use anyway.

## Egress

Each pool has one proxy Deployment `bo-egress-<pool>` that the controller creates and updates in the
sandbox namespace (image `egressProxy.image`). Per job it receives the allowlist resolved from the
sandbox policy (`sandboxPolicy.network.allow` plus the LLM gateway and git host) and the job's credential
hash, so one job cannot use another job's allowlist. The chart's NetworkPolicies enforce the rest:
sandboxes (pods labelled `backoffice.io/sandbox=true` by the controller) may reach only cluster DNS and
the proxy pods (`app.kubernetes.io/name=back-office-egress-proxy`) on `egressProxy.port` (4750); the
proxy may reach public addresses on `egressProxy.ports` but never private ranges,
`169.254.0.0/16` (cloud metadata) or `100.64.0.0/10` - add private destinations such as a self-hosted
GitLab, Nexus or Back Office inside the VPC to `egressProxy.allowedCidrs` (NetworkPolicy and Smokescreen
`--allow-range`). The proxy pulls allowlists from the controller Service `<fullname>-controller:8090`
(controller pods labelled `app.kubernetes.io/name=back-office-runner-controller`). Every decision is
logged by the proxy and reported to the job's event stream.

## Autoscaling

`autoscaling.type=keda` scales the controller on `GET /api/v1/runner/v2/pools/{poolId}/queue`
(`{"queued": n, "running": m}`, bearer = the runner token from `<fullname>-token`), one replica per
`maxConcurrent` queued jobs. The ScaledObject is inactive until the controller has registered (the token
Secret does not exist before). The controller keeps that Secret in its own (release) namespace, where KEDA
reads it; sandboxes never share a namespace with the runner token. Sandbox capacity itself comes from the node autoscaler (Karpenter,
cluster autoscaler, Autopilot) - size the sandbox node pool for `maxReplicas × maxConcurrent` pods.

## Upgrade and removal

`helm upgrade` rolls the controller with `maxUnavailable: 0`; on SIGTERM it stops leasing and lets
running sandboxes finish or hands their leases back within `controller.terminationGracePeriodSeconds`.
`helm uninstall` keeps the sandbox namespace and cache PVCs (`helm.sh/resource-policy: keep`) so running
jobs are not killed by accident; delete them explicitly. Revoke the runner in Administration → Runner
fleet afterwards.

## Where to look when it fails

- **`registration failed: 401`** - the registration token was already used or belongs to another pool;
  issue a new one. After a successful registration the controller ignores the token.
- **Controller runs, pool shows no runner online** - outbound HTTPS to `backOffice.url` blocked (proxy,
  firewall, `networkPolicy.controllerEgressCidrs`), or TLS trust (corporate CA).
- **Jobs stay `QUEUED`** - pool id/labels do not match the job's pool, the controller is at
  `maxConcurrent`, or the job's image is not in `allowedImages` (the controller rejects the assignment
  and logs why). Alert `BackOfficeRunnerPoolWithoutRunners` fires after 10 minutes.
- **Sandbox pod `Pending`** - no node offers the RuntimeClass (taints/labels of `runtimeClasses.scheduling`), or resources exhausted.
- **Pod rejected by Pod Security** - the job's pod spec violates `restricted`; this is a bug of the
  sandbox template - report it with the event text (`kubectl -n <ns>-sandboxes get events`).
- **Agent cannot reach npm/Maven/git** - host not in the job's allowlist (`sandboxPolicy.network.allow`,
  proxy log of `bo-egress-<pool>` shows `"allow":false`), or a private address not in `egressProxy.allowedCidrs`.
- **Proxy Deployment `bo-egress-<pool>` missing** - the controller could not create it (RBAC on
  `deployments`, image pull); `kubectl -n <ns>-sandboxes get deploy,events`.
- **KEDA ScaledObject `False`/fallback** - the token Secret is missing (controller never registered) or
  Back Office is unreachable from the KEDA operator's namespace.
- Runbooks: [scaling](../runbooks/scaling.md), [runner-job-failed](../runbooks/runner-job-failed.md).
