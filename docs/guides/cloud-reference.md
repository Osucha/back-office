# Cloud reference deployments

## When to use this guide

You are planning a production installation of Back Office on AWS, Google Cloud, Azure or your own data
centre and want a tested starting point: which managed services to use, how the pieces connect, how big
to make them, and the exact Helm values. Each section ends with the values files of both charts:
`deploy/helm/back-office/values-<cloud>.yaml` (control plane) and
`deploy/helm/back-office-runner/values-<cloud>.yaml` (runner fleet). Installing the fleet itself is
described in [runner-fleet-install.md](runner-fleet-install.md), day-2 operations in
[runbooks/scaling.md](../runbooks/scaling.md) and [runbooks/upgrade.md](../runbooks/upgrade.md).

The design behind all of them: [09-scale-and-operations.md](../architecture/09-scale-and-operations.md)
(roles, PostgreSQL backbone, SLOs, capacity), [10-agent-execution.md](../architecture/10-agent-execution.md)
(sandboxes), ADR 0023/0024/0025.

## Common building blocks

| Concern | Control plane (`back-office`) | Execution plane (`back-office-runner`) |
|---|---|---|
| Workloads | `web` (nginx), `api` (role `api`, HPA), `worker` (role `worker`, KEDA on the work queue), migrate hook Job (role `migrate`) | `runner-controller` (KEDA on the pool queue), sandbox pods, Smokescreen egress proxy |
| State | PostgreSQL 16+ (managed or CloudNativePG), S3-compatible bucket | none (caches optional) |
| Credentials in pods | DB password, provider keys, OIDC secret, signing key, S3 keys or workload identity | runner token (written by the controller), git tokens per project |
| Network | inbound HTTPS via ingress; outbound HTTPS to LLM providers, OIDC, Jira, storage | outbound HTTPS to Back Office only; sandboxes egress only through the proxy |
| Cluster add-ons | ingress controller, KEDA, External Secrets Operator (optional), Prometheus Operator (optional) | Agent Sandbox (chart can install it, pinned v1.0.4), gVisor/Kata RuntimeClass, NetworkPolicy-enforcing CNI, KEDA |

The fleet can share the control-plane cluster (separate namespaces, dedicated sandbox node pool with a
taint) or live in its own cluster/account - recommended for regulated data because a sandbox escape then
lands in a cluster that holds no platform secrets.

### Sizing (from doc 09)

| Size | Users / concurrent runs | api | worker | PostgreSQL | Runner pool |
|---|---|---|---|---|---|
| Small | 10 / 5 | 1 × `mode: combined` 1 vCPU 1.5 GiB | - | 2 vCPU 4 GiB | 1 controller, 5 sandboxes of 2 vCPU 4 GiB |
| Medium | 100 / 50 | 3 × 1 vCPU 1 GiB | 3 × 1 vCPU 1.5 GiB | 4 vCPU 16 GiB + pooler | 2 controllers, 50 sandboxes |
| Large | 1000 / 500 | 6-12 | 10-30 (KEDA) | 16 vCPU 64 GiB, read replica for insights | several pools/clusters |

The reference values files are sized **Medium**. Sandbox nodes: 50 sandboxes × 2 vCPU / 4 GiB ≈ 100 vCPU
/ 200 GiB plus the gVisor/Kata overhead (Kata adds ~ 150-250 MiB per pod VM; AKS rounds CPU up to
whole vCPUs). PostgreSQL connections: see the budget in [runbooks/scaling.md](../runbooks/scaling.md).

### What every cloud has to solve the same way

- **LISTEN/NOTIFY needs a session.** Transaction-mode poolers (PgBouncer, RDS Proxy, Azure built-in
  PgBouncer, Cloud SQL managed pooling in transaction mode) break `LISTEN`. The chart therefore has two
  URLs: `SPRING_DATASOURCE_URL` (may point at a pooler, `prepareThreshold=0` is added) and
  `BACKOFFICE_DATASOURCE_DIRECT_URL` (always the primary; one listener connection per api pod,
  Flyway, KEDA). Set `externalDatabase.host` to the **primary endpoint**, the pooler goes to
  `externalDatabase.pooler`.
- **SSE needs long idle timeouts** on every hop (load balancer, ingress): 3600 s.
- **Object storage via the S3 API.** Native on AWS; GCS through its XML API interoperability; Azure
  Blob has no S3 API, so a gateway is needed.
- **Sandbox runtime.** GKE ships gVisor, AKS ships Kata; EKS ships neither - you bring the runtime.

### Supply chain

Every image is built by CI, scanned (Trivy), signed keylessly with cosign (Sigstore, identity = the
GitLab pipeline) and carries two attestations: the CycloneDX SBOM and a SLSA v1 provenance statement
(SLSA Build L2: hosted build and signed provenance; the provenance is generated inside the build pipeline,
which is not the isolated generator L3 requires). Enforce it at admission with Kyverno `verifyImages`
or Sigstore policy-controller:

```bash
cosign verify registry.example.com/back-office/api@sha256:... \
  --certificate-identity-regexp '^https://gitlab.example.com/mos/back-office//' \
  --certificate-oidc-issuer https://gitlab.example.com
cosign verify-attestation --type cyclonedx <same flags> registry.example.com/back-office/api@sha256:...
```

## AWS

```
 Route 53 ── ALB (AWS LB Controller, idle 3600 s, ACM cert)
               │
 ┌──────────── EKS control-plane node group (3 AZ) ─────────────┐    ┌── RDS PostgreSQL 17 Multi-AZ ──┐
 │ web ×2 ─▶ api ×3-12 (HPA) ── worker ×3-30 (KEDA) ────────────┼──▶ │ instance endpoint (LISTEN,     │
 │    IRSA role ──▶ S3 bucket (SSE-KMS, versioning)             │    │ Flyway, KEDA) ; RDS Proxy for  │
 │    ESO ──▶ Secrets Manager                                   │    │ Hikari pools (optional)        │
 └──────────────────────────────▲──────────────────────────────┘    └────────────────────────────────┘
                                │ HTTPS (outbound from the fleet)
 ┌── EKS sandbox node group (AL2023 + runsc, taint) / *.metal or C8i/M8i/R8i nested-virt for Kata ──┐
 │ runner-controller ×2-5 (KEDA metrics-api) ─▶ Agent Sandbox ─▶ sandbox pods (gvisor | kata)        │
 │ egress proxy (only pod with internet egress) ; VPC CNI network policies on                        │
 └────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Component | Service | Notes |
|---|---|---|
| Kubernetes | EKS 1.33+ with managed or self-managed EC2 node groups / Karpenter | **Not** EKS Auto Mode or Fargate for sandbox nodes: they cannot run a custom container runtime (Auto Mode nodes are immutable Bottlerocket; Fargate has no RuntimeClass support). The control plane can run anywhere. |
| NetworkPolicy | VPC CNI with `enableNetworkPolicy=true` (or Calico/Cilium) | verify with `check-runner-cluster.sh` |
| PostgreSQL | RDS for PostgreSQL 17 (or Aurora PostgreSQL), Multi-AZ, PITR | RDS Proxy **pins** sessions that `LISTEN` ([pinning docs](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-proxy-pinning.html)): use the instance/cluster endpoint for `externalDatabase.host`; the proxy may serve only the Hikari pools |
| Object storage | S3, SSE-KMS, versioning, lifecycle rules for `jobs/` (30 d) and `runs/` (180 d) | credentials via **IRSA** (`api.serviceAccount.annotations.eks.amazonaws.com/role-arn`) or EKS Pod Identity; leave `storage.s3.accessKey` empty |
| Secrets | Secrets Manager + External Secrets Operator (`externalSecrets.enabled`) | one JSON secret per target, keys = env names |
| Ingress | AWS Load Balancer Controller, ALB `target-type: ip`, `idle_timeout.timeout_seconds=3600` | NetworkPolicy peer = VPC CIDR (ALB is not a pod) |
| gVisor | **no managed option.** Launch template user data (or a custom AMI / Karpenter `EC2NodeClass` userData) that installs `runsc` + `containerd-shim-runsc-v1` and adds `[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runsc] runtime_type = "io.containerd.runsc.v1"`; label/taint the nodes; the runner chart creates the RuntimeClass (`runtimeClasses.create=true`, handler `runsc`). Worked example: [gVisor on EKS with Karpenter](https://jicowan.medium.com/running-gvisor-on-eks-with-karpenter-39e8d914e1c3). Bottlerocket would need a custom variant. | |
| Kata (HIGH) | `*.metal` instances with kata-deploy ([AWS blog](https://aws.amazon.com/blogs/containers/enhancing-kubernetes-workload-isolation-and-security-using-kata-containers/)); since 2026 nested virtualization on virtual C8i/M8i/R8i (and C7i/M7i/R7i, [announcement](https://aws.amazon.com/about-aws/whats-new/2026/02/amazon-ec2-nested-virtualization-on-virtual)) should allow Kata without metal - not yet documented by AWS for EKS, test before relying on it | |
| Observability | Amazon Managed Prometheus / Grafana or kube-prometheus-stack; ADOT or the OTel collector example | `metrics.*`, `otel.*` |

Values: [`values-aws.yaml`](../../deploy/helm/back-office/values-aws.yaml),
[`back-office-runner/values-aws.yaml`](../../deploy/helm/back-office-runner/values-aws.yaml).

IAM policy for the app role (bucket `acme-back-office-artifacts`):
`s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload` on `arn:aws:s3:::acme-back-office-artifacts/*`,
`s3:ListBucket` on the bucket, `kms:GenerateDataKey`/`kms:Decrypt` on the bucket key.

## GCP

```
 Cloud DNS ── external Application LB (GKE Ingress, BackendConfig timeoutSec 3600, managed cert)
               │
 ┌──────────── GKE (Standard regional or Autopilot) ────────────┐   ┌── Cloud SQL PostgreSQL 17 HA ──┐
 │ web ×2 ─▶ api ×3-12 ── worker ×3-30 (KEDA)                  ├─▶ │ private IP, direct 5432        │
 │   GCS via XML API + HMAC key ; ESO ─▶ Secret Manager (WI)    │   │ (managed pooling optional)     │
 └──────────────────────────────▲──────────────────────────────┘   └────────────────────────────────┘
 ┌── GKE Sandbox node pool (--sandbox type=gvisor) / Autopilot ─┴───────────────────────────────────┐
 │ runner-controller ─▶ Agent Sandbox ─▶ sandbox pods (runtimeClassName: gvisor) ; egress proxy       │
 │ Dataplane V2 enforces NetworkPolicy                                                               │
 └────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Component | Service | Notes |
|---|---|---|
| Kubernetes | GKE Standard (regional) or Autopilot | Dataplane V2 enforces NetworkPolicy |
| gVisor | **GKE Sandbox**: RuntimeClass `gvisor` created by GKE. Standard: `gcloud container node-pools create sandbox --cluster C --image-type=cos_containerd --sandbox type=gvisor` (a second, non-sandbox pool is required). Autopilot: supported from 1.27.4-gke.800, no node pool setup ([how-to](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/sandbox-pods)) | limitations that matter here: no hostPath, no privileged containers, no port-forward to sandbox pods, container-level memory metrics missing, node metadata server blocked (good) ([concepts](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/sandbox-pods)) |
| Kata (HIGH) | not offered on GKE | map HIGH to `gvisor` (values file does) or route HIGH jobs to a pool on another platform |
| PostgreSQL | Cloud SQL for PostgreSQL 17, HA, private IP, PITR | direct connection for LISTEN; [Managed Connection Pooling](https://docs.cloud.google.com/sql/docs/postgres/managed-connection-pooling) in transaction mode only for the Hikari pools |
| Object storage | Cloud Storage through the **S3-compatible XML API** (`https://storage.googleapis.com`, HMAC key of a service account with `roles/storage.objectUser`) ([interoperability](https://docs.cloud.google.com/storage/docs/interoperability)) | the backend's BlobStore speaks S3; native GCS/Workload Identity would need a GCS adapter (not implemented). AWS SDK v2 ≥ 2.30 sends CRC32 checksums GCS rejects: `storage.s3.checksumWhenRequired=true` sets `AWS_REQUEST_CHECKSUM_CALCULATION=when_required` ([SDK docs](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)) |
| Secrets | Secret Manager + ESO with Workload Identity | HMAC key id/secret as `BACKOFFICE_STORAGE_S3_ACCESS_KEY/_SECRET_KEY` |
| Ingress | GKE Ingress + `BackendConfig` (`timeoutSec: 3600`), managed certificate | NetworkPolicy peers: Google front ends `35.191.0.0/16`, `130.211.0.0/22` |

BackendConfig referenced by `web.service.annotations`:

```yaml
apiVersion: cloud.google.com/v1
kind: BackendConfig
metadata: { name: back-office-web, namespace: back-office }
spec:
  timeoutSec: 3600
  connectionDraining: { drainingTimeoutSec: 30 }
  healthCheck: { requestPath: /, port: 8080 }
```

Values: [`values-gcp.yaml`](../../deploy/helm/back-office/values-gcp.yaml),
[`back-office-runner/values-gcp.yaml`](../../deploy/helm/back-office-runner/values-gcp.yaml).

## Azure

```
 Azure DNS ── app routing (managed ingress-nginx) or Application Gateway for Containers
               │
 ┌──────────── AKS (Azure CNI + Cilium) ──────────────────────────┐   ┌── PostgreSQL flexible server ──┐
 │ web ×2 ─▶ api ×3-12 ── worker ×3-30 (KEDA add-on)             ├─▶ │ 5432 direct (LISTEN, Flyway)   │
 │ s3proxy (azureblob) ─▶ Blob container ; ESO ─▶ Key Vault (WI)  │   │ 6432 built-in PgBouncer (pool) │
 └──────────────────────────────▲────────────────────────────────┘   └────────────────────────────────┘
 ┌── AKS Pod Sandboxing node pool (AzureLinux, --workload-runtime KataVmIsolation, Dsv3) ────────────┐
 │ runner-controller ─▶ Agent Sandbox ─▶ sandbox pods (runtimeClassName: kata-vm-isolation) ; proxy  │
 └────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Component | Service | Notes |
|---|---|---|
| Kubernetes | AKS 1.33+, Azure CNI powered by Cilium (or Azure NPM) | |
| Sandbox runtime | **Pod Sandboxing (Kata VM isolation), GA**: `az aks nodepool add --cluster-name C --resource-group G --name sandbox --os-sku AzureLinux --workload-runtime KataVmIsolation --node-vm-size Standard_D4s_v3`; RuntimeClass **`kata-vm-isolation`** (the older `kata-mshv-vm-isolation` name and label are deprecated) ([docs](https://learn.microsoft.com/en-us/azure/aks/use-pod-sandboxing)) | Azure Linux only, Gen2 VM sizes with nested virtualization, no host network, pod VM memory = the pod's memory limit (512 Mi if unset), CPU limits round up to whole vCPUs. No gVisor on AKS: both isolation levels use Kata |
| PostgreSQL | Azure Database for PostgreSQL flexible server 17, zone-redundant HA | built-in PgBouncer on 6432 is transaction mode ([docs](https://learn.microsoft.com/en-us/azure/postgresql/connectivity/concepts-pgbouncer)): only for `externalDatabase.pooler`; LISTEN on 5432 |
| Object storage | Blob Storage **through an S3 gateway**: Azure Blob has no S3 API and the MinIO gateway was removed in 2022 ([MinIO blog](https://blog.min.io/deprecation-of-the-minio-gateway/)). Recommended: [s3proxy](https://github.com/gaul/s3proxy) (`andrewgaul/s3proxy`, provider `azureblob`) as a small Deployment in the namespace; commercial alternative Flexify.IO; small installs: MinIO on managed disks | a native Azure BlobStore adapter would remove the gateway (not implemented) |
| Secrets | Key Vault + ESO with Workload Identity | Key Vault names allow no `/` or `_`: one JSON secret per target |
| Ingress | application routing add-on (managed ingress-nginx) with `proxy-read-timeout: 3600`, or Application Gateway for Containers | |

s3proxy example (same namespace, reachable as `http://s3proxy.back-office:80`; the values file allows the
egress):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: s3proxy, namespace: back-office, labels: { app.kubernetes.io/name: s3proxy } }
spec:
  replicas: 2
  selector: { matchLabels: { app.kubernetes.io/name: s3proxy } }
  template:
    metadata: { labels: { app.kubernetes.io/name: s3proxy } }
    spec:
      containers:
        - name: s3proxy
          image: andrewgaul/s3proxy:sha-<pinned>
          env:
            - { name: JCLOUDS_PROVIDER, value: azureblob }
            - { name: JCLOUDS_ENDPOINT, value: "https://acmebackoffice.blob.core.windows.net" }
            - { name: JCLOUDS_IDENTITY, value: acmebackoffice }                 # storage account
            - { name: JCLOUDS_CREDENTIAL, valueFrom: { secretKeyRef: { name: s3proxy, key: account-key } } }
            - { name: S3PROXY_AUTHORIZATION, value: aws-v2-or-v4 }
            - { name: S3PROXY_IDENTITY, valueFrom: { secretKeyRef: { name: s3proxy, key: access-key } } }
            - { name: S3PROXY_CREDENTIAL, valueFrom: { secretKeyRef: { name: s3proxy, key: secret-key } } }
          ports: [{ containerPort: 80 }]
---
apiVersion: v1
kind: Service
metadata: { name: s3proxy, namespace: back-office }
spec: { selector: { app.kubernetes.io/name: s3proxy }, ports: [{ port: 80, targetPort: 80 }] }
```

The `access-key`/`secret-key` pair is what Back Office uses as `BACKOFFICE_STORAGE_S3_ACCESS_KEY/_SECRET_KEY`.

Values: [`values-azure.yaml`](../../deploy/helm/back-office/values-azure.yaml),
[`back-office-runner/values-azure.yaml`](../../deploy/helm/back-office-runner/values-azure.yaml).

## On-premises

```
 corporate DNS / LB ── ingress-nginx (cert-manager, internal CA)
 ┌──────────── Kubernetes (kubeadm, RKE2, OpenShift, Talos) ───────────────────────────────────┐
 │ web ─▶ api ── worker (KEDA)                                                                  │
 │ CloudNativePG Cluster ×3 (+ Pooler rw, Barman Cloud Plugin ─▶ object store: WAL + base backups)│
 │ object store: MinIO cluster / AIStor, Ceph RGW, SeaweedFS or Garage                           │
 │ sandbox nodes (taint): runsc + containerd handler, Kata on nodes with VT-x ─▶ runner fleet    │
 │ Calico / Cilium enforce NetworkPolicy                                                        │
 └───────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Component | Choice | Notes |
|---|---|---|
| PostgreSQL | [CloudNativePG](https://cloudnative-pg.io) operator, `postgresql.mode: cnpg`: 3 instances, required anti-affinity, `Pooler` (PgBouncer transaction mode) for the Hikari pools, direct `-rw` Service for LISTEN | backups with the **Barman Cloud Plugin** (`cnpg.backup.method: plugin`, `ObjectStore` CR): the in-tree `barmanObjectStore` is deprecated and planned for removal in CNPG 1.31 ([plugin docs](https://cloudnative-pg.io/plugin-barman-cloud/docs/usage/)). Install: operator manifest/Helm chart, then the plugin (needs cert-manager) |
| Object storage | whatever S3 store the organisation runs | MinIO's community edition is archived (no more community images since Oct 2025); Ceph RGW, SeaweedFS, Garage or a commercial MinIO (AIStor) are the realistic options. The chart's bundled MinIO (`minio.enabled`) is for evaluation only |
| gVisor | install `runsc` + `containerd-shim-runsc-v1` from the gVisor apt repo on sandbox nodes, add the containerd runtime `runsc` ([gVisor containerd quick start](https://gvisor.dev/docs/user_guide/containerd/quick_start/)), label + taint the nodes, `runtimeClasses.create=true` | |
| Kata | [kata-deploy](https://github.com/kata-containers/kata-containers/tree/main/tools/packaging/kata-deploy) DaemonSet on nodes with hardware virtualization; it creates RuntimeClasses such as `kata-qemu` / `kata-clh` - set `runtimeClasses.high` accordingly | |
| Secrets | Kubernetes Secrets created by the operator team, or ESO with Vault/OpenBao | |

CloudNativePG install sketch:

```bash
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm upgrade --install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace
# Barman Cloud Plugin (after cert-manager): see the plugin docs for the manifest of the current release
kubectl -n back-office create secret generic back-office-pg-backup-s3 \
  --from-literal=ACCESS_KEY_ID=... --from-literal=ACCESS_SECRET_KEY=...
helm upgrade --install back-office deploy/helm/back-office -n back-office -f deploy/helm/back-office/values-onprem.yaml
kubectl -n back-office get cluster.postgresql.cnpg.io back-office-pg     # STATUS "Cluster in healthy state"
```

Values: [`values-onprem.yaml`](../../deploy/helm/back-office/values-onprem.yaml),
[`back-office-runner/values-onprem.yaml`](../../deploy/helm/back-office-runner/values-onprem.yaml).

## Where to look when it fails

- **Sandbox pods `Pending` with `RuntimeClass ... not found` / no node matches** - the runtime is not on
  any node or the RuntimeClass `scheduling` selector matches nothing: `check-runner-cluster.sh`.
- **SSE drops every 60 s** - an idle timeout on the load balancer or ingress (ALB default 60 s, GCLB
  30 s): see the ingress rows above.
- **Events arrive late / `BackOfficeEventListenerReconnecting`** - the direct URL points at a
  transaction pooler or a proxy that cuts idle sessions ([runbooks/scaling.md](../runbooks/scaling.md)).
- **GCS `SignatureDoesNotMatch` on uploads** - `storage.s3.checksumWhenRequired=true` is missing.
- **`AccessDenied` from S3 with IRSA** - the service account annotation or the role's trust policy
  (namespace/service-account name) does not match `<fullname>-api`.
