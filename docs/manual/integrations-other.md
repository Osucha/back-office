# Storage, signing and observability

Three pieces of infrastructure that Back Office depends on besides its database: object storage for large
content, the Ed25519 signing key behind provenance attestations and runner assignments, and the
telemetry export to Langfuse or any OpenTelemetry backend.

**Who needs this:** platform administrator.

<a id="storage"></a>
## Object storage

Large content lives in a blob store; PostgreSQL keeps only metadata and small content. Stored there:
artefacts above 64 kB, diffs, runner logs, agent results above 64 kB, and uploads from sandboxes
(artefacts and logs above 2 MB, sent with presigned `PUT` URLs).

| Type | Use for | Notes |
|---|---|---|
| `s3` | every installation with more than one replica; Helm default | AWS S3, MinIO, Ceph RGW, Cloudflare R2, GCS (XML interop), S3 gateways |
| `filesystem` | one replica only: laptop, tests, single-VM install; default without Helm | presigned links are served by the API under `/blobs/...` |

| Setting | Variable | Helm value |
|---|---|---|
| Type | `BACKOFFICE_STORAGE_TYPE` | `storage.type` (`s3`) |
| Endpoint (blank = AWS) | `BACKOFFICE_STORAGE_S3_ENDPOINT` | `storage.s3.endpoint` (derived with `minio.enabled`) |
| Endpoint written into presigned URLs | `BACKOFFICE_STORAGE_S3_PRESIGN_ENDPOINT` | `api.extraEnv` |
| Region | `BACKOFFICE_STORAGE_S3_REGION` | `storage.s3.region` (`us-east-1`; R2 `auto`) |
| Bucket (must exist) | `BACKOFFICE_STORAGE_S3_BUCKET` | `storage.s3.bucket` |
| Path-style addressing | `BACKOFFICE_STORAGE_S3_PATH_STYLE` | `storage.s3.pathStyle` (`true` for MinIO, Ceph, most gateways) |
| Static credentials | `BACKOFFICE_STORAGE_S3_ACCESS_KEY` / `_SECRET_KEY` | `storage.s3.accessKey`/`secretKey` → Secret `<release>-storage`, or `storage.s3.existingSecret` |
| Server-side encryption | `BACKOFFICE_STORAGE_S3_SSE` (`NONE`, `S3`, `KMS`) | `storage.s3.sse` |
| GCS / gateway checksum fix | `AWS_REQUEST_CHECKSUM_CALCULATION=when_required` | `storage.s3.checksumWhenRequired` |
| Filesystem directory | `BACKOFFICE_STORAGE_FILESYSTEM_ROOT` | `storage.filesystem.path` (+ PVC) |
| Filesystem link signing key | `BACKOFFICE_STORAGE_FILESYSTEM_SIGNING_KEY` | `api.extraEnv` from a Secret |

**Credentials with least privilege** (AWS IAM; other stores have equivalents):

1. On AWS prefer workload identity: leave access and secret key empty and give the api/worker service
   account an IAM role (IRSA or EKS Pod Identity). GCS through its S3 interface always needs an HMAC key.
2. Allow only the bucket: `s3:ListBucket` on `arn:aws:s3:::<bucket>` (health check and prefix deletes) and
   `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload` on `arn:aws:s3:::<bucket>/*`.
   With `sse: KMS` add `kms:GenerateDataKey` and `kms:Decrypt` on the key.
3. MinIO: create a user and a policy with the same actions on the bucket (`mc admin user add`,
   `mc admin policy create`, `mc admin policy attach`). Cloudflare R2: **R2** → **Manage API tokens** →
   **Object Read & Write** for the one bucket. GCS: **Cloud Storage** → **Settings** → **Interoperability**
   → HMAC key for a service account with **Storage Object Admin** on the bucket.
4. Enable versioning on the bucket for backups.

**Sandbox reachability.** Agents upload through presigned URLs pointing at the S3 endpoint (or the
presign endpoint). Their egress proxy must allow that host: add it to the runner-controller's always-allowed
hosts (`runner-controller.egress.always-allow`, for example `extraEnv` `RUNNER_CONTROLLER_EGRESS_ALWAYSALLOW_0=minio.example.com`
in the runner chart), and to `egressProxy.allowedCidrs` when it has a private address
([agent-execution.md](agent-execution.md#network)). When the API reaches an in-cluster MinIO under an
internal name, set `BACKOFFICE_STORAGE_S3_PRESIGN_ENDPOINT` to the public name.

**Test**: `/actuator/health` has a `blobStore` component (`HeadBucket` with a 3 s timeout, or "root writable"
for filesystem); `kubectl -n back-office logs deploy/back-office-api | grep blob` shows failures.

**Rotate static keys**: create a second key for the same user/policy, update the Secret, restart api and
worker, delete the old key.

**Limits**: the Helm chart has no value for the presign endpoint or the filesystem signing key yet; set
them through `api.extraEnv`. Without `BACKOFFICE_STORAGE_FILESYSTEM_SIGNING_KEY` filesystem links stop
working after a restart. Retention of blob content is described in `docs/guides/configuration.md`.

<a id="signing"></a>
## Signing keys

One Ed25519 key signs the provenance attestations of runs and agent jobs ([runs.md](runs.md#provenance))
and the job assignments runner-controllers verify before they start a sandbox. Public keys are published
unauthenticated at `<public URL>/.well-known/back-office/keys` (JWK set; each key `active` or `retired`).

| Variable (index `n` = 0, 1, ...) | Meaning |
|---|---|
| `BACKOFFICE_SIGNING_KEYS_<n>_ID` | Key id, for example `bo-20261006` |
| `BACKOFFICE_SIGNING_KEYS_<n>_PRIVATE_KEY_PKCS8_BASE64` | Private key, PKCS#8 DER, base64 (omit for retired keys) |
| `BACKOFFICE_SIGNING_KEYS_<n>_PUBLIC_KEY_X509_BASE64` | Public key, X.509 SubjectPublicKeyInfo DER, base64 |
| `BACKOFFICE_SIGNING_KEYS_<n>_ACTIVE` | `true` for the one key that signs |
| `BACKOFFICE_SIGNING_ALLOW_EPHEMERAL` | `true` (local/compose): generate a throw-away key when none is set; the chart sets `false` |

**Install**

1. Default (Helm): `signing.generate=true` lets a pre-install hook Job create Secret `<release>-signing`
   once; it is never overwritten and kept on uninstall. Nothing to do.
2. Your own key: `deploy/scripts/gen-signing-key.sh --secret back-office-signing --namespace back-office`
   (needs `openssl` 1.1.1+ and `kubectl`), then set `signing.existingSecret=back-office-signing`.
   Without `--secret` the script prints the four lines for an `.env` file.
3. Back up the Secret: losing the private key does not break old attestations (public keys verify them),
   but a key you cannot prove you control is worthless as evidence.

**Test**: `curl https://backoffice.example.com/.well-known/back-office/keys` lists the key; after a run
finishes, the run page's **Provenance** tab lists its attestations.

**Rotate** (yearly, or at once after a leak):

1. `deploy/scripts/gen-signing-key.sh --secret back-office-signing -n back-office --index 1 --merge` adds
   key 1 to the Secret.
2. Set the old key inactive: patch `BACKOFFICE_SIGNING_KEYS_0_ACTIVE` to `false`.
3. `kubectl -n back-office rollout restart deploy/back-office-api deploy/back-office-worker`.
4. Keep the old key's public part in the Secret forever so earlier attestations still verify. Runner
   controllers pick up the new key with their configuration (every 5 minutes, immediately on an unknown key id).

Runbook: `docs/runbooks/upgrade.md` ("Signing key rotation"). Offline verification: `docs/guides/provenance.md`.

<a id="observability"></a>
## Observability: Langfuse, OpenTelemetry, Prometheus

**Langfuse (LLM traces).** Back Office exports traces over OTLP/HTTP; Langfuse ingests them.

1. In Langfuse open the project for Back Office → **Settings** → **API Keys** → **Create new API keys**;
   copy the public key (`pk-lf-...`) and secret key (`sk-lf-...`).
   Docs: https://langfuse.com/docs/opentelemetry/get-started
2. Build the header value: `Basic ` + base64 of `pk-lf-...:sk-lf-...`
   (`printf %s "pk-lf-...:sk-lf-..." | base64 -w0`).
3. Configure:

| Setting | Variable | Helm value |
|---|---|---|
| Export traces | `OTEL_TRACES_ENABLED=true` | derived from `langfuse.enabled` or `otel.enabled` |
| Endpoint | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, for example `https://cloud.langfuse.com/api/public/otel/v1/traces` | `otel.tracesEndpoint` (bundled Langfuse: derived) |
| Authorization header | `OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION` | `langfuseAuth.publicKey` + `secretKey`, or `langfuseAuth.existingSecret` (full header value), or `otel.headersAuthorization` |
| Trace links in the UI | `LANGFUSE_ENABLED`, `LANGFUSE_URL` | derived for the bundled subchart; external Langfuse: `api.extraEnv` |
| Sampling | `TRACING_SAMPLING` | `api.config.tracingSampling` (`1.0`) |

4. **Settings** → **Langfuse** → **Test connection** checks that `LANGFUSE_URL/api/public/health` answers.
   It does not check the keys: confirm that traces of a test run arrive in Langfuse.
5. Rotate: create new keys in Langfuse, replace the header Secret, restart api and worker, delete the old keys.

Prompts and completions are not logged unless `AI_LOG_PROMPTS` / `AI_LOG_COMPLETIONS` are `true`
(development only).

**Any OpenTelemetry backend**: `otel.enabled=true`, `otel.tracesEndpoint` (and optionally
`otel.metricsEnabled`, `otel.logsEnabled` with their endpoints) pointing at your collector; an example
collector is `deploy/observability/otel-collector.yaml`.

**Prometheus**: every pod serves `/actuator/prometheus` (runner-controllers on port `8090`). The chart can
render a `ServiceMonitor`, alert rules (API errors and latency, stuck work items, pools with queued jobs and
no runner, budgets) and Grafana dashboards: `metrics.serviceMonitor`, `metrics.prometheusRule`,
`metrics.dashboards`.
