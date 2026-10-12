{{/*
Chart name.
*/}}
{{- define "back-office.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified release name. Mirrors the Bitnami convention so the embedded PostgreSQL service is
predictable (<fullname>-postgresql).
*/}}
{{- define "back-office.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "back-office.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels (add commonLabels from values).
*/}}
{{- define "back-office.labels" -}}
helm.sh/chart: {{ include "back-office.chart" . }}
app.kubernetes.io/name: {{ include "back-office.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: back-office
{{- with .Values.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{- define "back-office.annotations" -}}
{{- with .Values.commonAnnotations }}
annotations:
{{ toYaml . | indent 2 }}
{{- end }}
{{- end -}}

{{/*
Selector labels for a component. Usage: include "back-office.selectorLabels" (dict "root" . "component" "api")
*/}}
{{- define "back-office.selectorLabels" -}}
app.kubernetes.io/name: {{ include "back-office.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* Component names */}}
{{- define "back-office.api.fullname" -}}
{{- printf "%s-api" (include "back-office.fullname" .) -}}
{{- end -}}

{{- define "back-office.web.fullname" -}}
{{- printf "%s-web" (include "back-office.fullname" .) -}}
{{- end -}}

{{- define "back-office.api.serviceAccountName" -}}
{{- if .Values.api.serviceAccount.create -}}
{{- default (include "back-office.api.fullname" .) .Values.api.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.api.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Secret names (created or existing) */}}
{{- define "back-office.appSecretName" -}}
{{- if or .Values.externalSecrets.enabled .Values.secrets.create -}}
{{- printf "%s-app" (include "back-office.fullname" .) -}}
{{- else -}}
{{- required "secrets.existingSecret is required when secrets.create=false" .Values.secrets.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "back-office.providerKeysSecretName" -}}
{{- if or .Values.externalSecrets.enabled .Values.secrets.providerKeys.create -}}
{{- printf "%s-provider-keys" (include "back-office.fullname" .) -}}
{{- else -}}
{{- required "secrets.providerKeys.existingSecret is required when secrets.providerKeys.create=false" .Values.secrets.providerKeys.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "back-office.integrationsSecretName" -}}
{{- if or .Values.externalSecrets.enabled .Values.secrets.integrations.create -}}
{{- printf "%s-integrations" (include "back-office.fullname" .) -}}
{{- else -}}
{{- required "secrets.integrations.existingSecret is required when secrets.integrations.create=false" .Values.secrets.integrations.existingSecret -}}
{{- end -}}
{{- end -}}

{{/* Images */}}
{{- define "back-office.api.image" -}}
{{- printf "%s:%s" .Values.api.image.repository (default .Chart.AppVersion .Values.api.image.tag) -}}
{{- end -}}

{{- define "back-office.web.image" -}}
{{- printf "%s:%s" .Values.web.image.repository (default .Chart.AppVersion .Values.web.image.tag) -}}
{{- end -}}

{{/* =========================================================================================
Database (ADR 0023). Three modes:
  bundled  - Bitnami PostgreSQL subchart (postgresql.enabled=true), dev and evaluation installs
  cnpg     - CloudNativePG `Cluster` rendered by this chart (operator installed separately)
  external - managed PostgreSQL (RDS, Cloud SQL, Azure flexible server, ...), externalDatabase.*
`postgresql.mode` empty = auto (bundled when postgresql.enabled, else external).
Every mode has a "direct" endpoint (primary, session semantics: Flyway, LISTEN/NOTIFY) and an optional
pooled endpoint (PgBouncer / RDS Proxy / CNPG Pooler in transaction mode) for the Hikari pool.
========================================================================================= */}}
{{- define "back-office.postgresql.fullname" -}}
{{- printf "%s-postgresql" (include "back-office.fullname" .) -}}
{{- end -}}

{{- define "back-office.database.mode" -}}
{{- $m := .Values.postgresql.mode | default "" -}}
{{- if eq $m "" -}}
{{- ternary "bundled" "external" (.Values.postgresql.enabled | default false) -}}
{{- else if has $m (list "bundled" "cnpg" "external") -}}
{{- if and (eq $m "bundled") (not .Values.postgresql.enabled) -}}
{{- fail "postgresql.mode=bundled needs postgresql.enabled=true (the Bitnami subchart)" -}}
{{- else if and (ne $m "bundled") .Values.postgresql.enabled -}}
{{- fail (printf "postgresql.mode=%s: set postgresql.enabled=false (the Bitnami subchart is only for mode bundled)" $m) -}}
{{- end -}}
{{- $m -}}
{{- else -}}
{{- fail (printf "postgresql.mode must be one of bundled, cnpg, external (got %q)" $m) -}}
{{- end -}}
{{- end -}}

{{/* true when the database lives in this release (it does not exist yet during pre-install) */}}
{{- define "back-office.database.inRelease" -}}
{{- if has (include "back-office.database.mode" .) (list "bundled" "cnpg") -}}true{{- end -}}
{{- end -}}

{{- define "back-office.cnpg.clusterName" -}}
{{- default (printf "%s-pg" (include "back-office.fullname" .)) .Values.cnpg.clusterName -}}
{{- end -}}

{{- define "back-office.database.host" -}}
{{- $mode := include "back-office.database.mode" . -}}
{{- if eq $mode "bundled" -}}
{{- include "back-office.postgresql.fullname" . -}}
{{- else if eq $mode "cnpg" -}}
{{- printf "%s-rw" (include "back-office.cnpg.clusterName" .) -}}
{{- else -}}
{{- required "externalDatabase.host is required for an external database (postgresql.enabled=false)" .Values.externalDatabase.host -}}
{{- end -}}
{{- end -}}

{{- define "back-office.database.port" -}}
{{- if eq (include "back-office.database.mode" .) "external" -}}{{ .Values.externalDatabase.port }}{{- else -}}5432{{- end -}}
{{- end -}}

{{- define "back-office.database.name" -}}
{{- $mode := include "back-office.database.mode" . -}}
{{- if eq $mode "bundled" -}}{{ .Values.postgresql.auth.database }}
{{- else if eq $mode "cnpg" -}}{{ .Values.cnpg.database }}
{{- else -}}{{ .Values.externalDatabase.database }}{{- end -}}
{{- end -}}

{{- define "back-office.database.username" -}}
{{- $mode := include "back-office.database.mode" . -}}
{{- if eq $mode "bundled" -}}{{ .Values.postgresql.auth.username }}
{{- else if eq $mode "cnpg" -}}{{ .Values.cnpg.owner }}
{{- else -}}{{ .Values.externalDatabase.username }}{{- end -}}
{{- end -}}

{{- define "back-office.database.jdbcParams" -}}
{{- if eq (include "back-office.database.mode" .) "external" -}}{{ .Values.externalDatabase.jdbcParams }}{{- end -}}
{{- end -}}

{{/* Direct (primary) JDBC URL: Flyway, LISTEN/NOTIFY, advisory locks */}}
{{- define "back-office.database.directJdbcUrl" -}}
{{- $url := printf "jdbc:postgresql://%s:%s/%s" (include "back-office.database.host" .) (include "back-office.database.port" .) (include "back-office.database.name" .) -}}
{{- $p := include "back-office.database.jdbcParams" . -}}
{{- if $p -}}{{- printf "%s?%s" $url $p -}}{{- else -}}{{- $url -}}{{- end -}}
{{- end -}}

{{/* Pooled endpoint host:port, empty when no pooler is configured */}}
{{- define "back-office.database.poolerHostPort" -}}
{{- $mode := include "back-office.database.mode" . -}}
{{- if and (eq $mode "cnpg") .Values.cnpg.pooler.enabled -}}
{{- printf "%s-pooler-rw:5432" (include "back-office.cnpg.clusterName" .) -}}
{{- else if and (eq $mode "external") .Values.pgbouncer.enabled -}}
{{- printf "%s-pgbouncer:%v" (include "back-office.fullname" .) .Values.pgbouncer.port -}}
{{- else if and (eq $mode "external") .Values.externalDatabase.pooler.host -}}
{{- printf "%s:%v" .Values.externalDatabase.pooler.host .Values.externalDatabase.pooler.port -}}
{{- end -}}
{{- end -}}

{{/* JDBC URL of the Hikari pool: the pooler when there is one, else the primary */}}
{{- define "back-office.database.jdbcUrl" -}}
{{- $pool := include "back-office.database.poolerHostPort" . -}}
{{- if $pool -}}
{{- $url := printf "jdbc:postgresql://%s/%s" $pool (include "back-office.database.name" .) -}}
{{- /* PgBouncer transaction mode: no server-side prepared statements */ -}}
{{- $p := list -}}
{{- with include "back-office.database.jdbcParams" . }}{{ $p = append $p . }}{{ end -}}
{{- $p = append $p "prepareThreshold=0" -}}
{{- printf "%s?%s" $url (join "&" $p) -}}
{{- else -}}
{{- include "back-office.database.directJdbcUrl" . -}}
{{- end -}}
{{- end -}}

{{/* Name of the Secret holding the DB password and the key inside it (application pods) */}}
{{- define "back-office.database.passwordSecretName" -}}
{{- $mode := include "back-office.database.mode" . -}}
{{- if eq $mode "bundled" -}}
{{- default (include "back-office.postgresql.fullname" .) .Values.postgresql.auth.existingSecret -}}
{{- else if eq $mode "cnpg" -}}
{{- /* CNPG creates <cluster>-app (keys username, password, uri, ...) for the owner */ -}}
{{- printf "%s-app" (include "back-office.cnpg.clusterName" .) -}}
{{- else if .Values.externalDatabase.existingSecret -}}
{{- .Values.externalDatabase.existingSecret -}}
{{- else if .Values.externalSecrets.enabled -}}
{{- printf "%s-db" (include "back-office.fullname" .) -}}
{{- else -}}
{{- include "back-office.appSecretName" . -}}
{{- end -}}
{{- end -}}

{{- define "back-office.database.passwordSecretKey" -}}
{{- $mode := include "back-office.database.mode" . -}}
{{- if eq $mode "bundled" -}}
{{- ternary .Values.postgresql.auth.secretKeys.userPasswordKey "password" (not (empty .Values.postgresql.auth.existingSecret)) -}}
{{- else if eq $mode "cnpg" -}}
password
{{- else if .Values.externalDatabase.existingSecret -}}
{{- .Values.externalDatabase.passwordKey -}}
{{- else if .Values.externalSecrets.enabled -}}
password
{{- else -}}
SPRING_DATASOURCE_PASSWORD
{{- end -}}
{{- end -}}

{{/* true when the external DB password is inline in values (then the migration hook gets its own copy) */}}
{{- define "back-office.database.inlinePassword" -}}
{{- if and (eq (include "back-office.database.mode" .) "external") (not .Values.externalDatabase.existingSecret) (not .Values.externalSecrets.enabled) -}}true{{- end -}}
{{- end -}}

{{/* =========================================================================================
Roles (ADR 0023): one image, BACKOFFICE_ROLE = api | worker | api,worker | migrate
========================================================================================= */}}
{{- define "back-office.mode" -}}
{{- $m := .Values.mode | default "split" -}}
{{- if not (has $m (list "split" "combined")) -}}
{{- fail (printf "mode must be split or combined (got %q)" $m) -}}
{{- end -}}
{{- $m -}}
{{- end -}}

{{- define "back-office.worker.fullname" -}}
{{- printf "%s-worker" (include "back-office.fullname" .) -}}
{{- end -}}

{{- define "back-office.migrate.fullname" -}}
{{- printf "%s-migrate" (include "back-office.fullname" .) -}}
{{- end -}}

{{- define "back-office.config.fullname" -}}
{{- printf "%s-config" (include "back-office.fullname" .) -}}
{{- end -}}

{{/* Run migrations in an init container of every app pod? (auto: DB or secrets not there at pre-install) */}}
{{- define "back-office.migration.initContainer" -}}
{{- $v := toString .Values.migration.initContainer -}}
{{- if eq $v "true" -}}true
{{- else if eq $v "false" -}}
{{- else if or (include "back-office.database.inRelease" .) .Values.externalSecrets.enabled -}}true
{{- end -}}
{{- end -}}

{{/* Helm hook events of the migration Job */}}
{{- define "back-office.migration.hookEvents" -}}
{{- if include "back-office.migration.initContainer" . -}}pre-upgrade{{- else -}}pre-install,pre-upgrade{{- end -}}
{{- end -}}

{{/* =========================================================================================
Object storage (BlobStore port, ADR 0023)
========================================================================================= */}}
{{- define "back-office.minio.fullname" -}}
{{- printf "%s-minio" (include "back-office.fullname" .) -}}
{{- end -}}

{{- define "back-office.storage.s3.endpoint" -}}
{{- if .Values.minio.enabled -}}
{{- printf "http://%s:9000" (include "back-office.minio.fullname" .) -}}
{{- else -}}
{{- .Values.storage.s3.endpoint -}}
{{- end -}}
{{- end -}}

{{/* Secret with BACKOFFICE_STORAGE_S3_ACCESS_KEY / _SECRET_KEY; empty = ambient credentials (IRSA, Workload Identity) */}}
{{- define "back-office.storage.secretName" -}}
{{- if .Values.storage.s3.existingSecret -}}
{{- .Values.storage.s3.existingSecret -}}
{{- else if or .Values.minio.enabled .Values.storage.s3.accessKey (and .Values.externalSecrets.enabled .Values.externalSecrets.storage.enabled) -}}
{{- printf "%s-storage" (include "back-office.fullname" .) -}}
{{- end -}}
{{- end -}}

{{/* =========================================================================================
Signing keys (ADR 0025): Ed25519, BACKOFFICE_SIGNING_KEYS_<n>_* in one Secret
========================================================================================= */}}
{{- define "back-office.signing.secretName" -}}
{{- default (printf "%s-signing" (include "back-office.fullname" .)) .Values.signing.existingSecret -}}
{{- end -}}

{{/* Langfuse */}}
{{- define "back-office.langfuse.webService" -}}
{{- printf "%s-langfuse-web" .Release.Name -}}
{{- end -}}

{{- define "back-office.langfuse.url" -}}
{{- default (printf "http://%s:3000" (include "back-office.langfuse.webService" .)) .Values.langfuseAuth.url -}}
{{- end -}}

{{- define "back-office.langfuse.otlpEndpoint" -}}
{{- printf "%s/api/public/otel/v1/traces" (include "back-office.langfuse.url" .) -}}
{{- end -}}

{{/* Whether OTLP trace export is on at all */}}
{{- define "back-office.tracingEnabled" -}}
{{- if or .Values.langfuse.enabled .Values.otel.enabled -}}true{{- else -}}false{{- end -}}
{{- end -}}

{{/* Public URL default */}}
{{- define "back-office.publicUrl" -}}
{{- if .Values.api.config.publicUrl -}}
{{- .Values.api.config.publicUrl -}}
{{- else if .Values.ingress.enabled -}}
{{- $scheme := ternary "https" "http" (gt (len .Values.ingress.tls) 0) -}}
{{- printf "%s://%s" $scheme .Values.ingress.host -}}
{{- else -}}
{{- printf "http://%s:%v" (include "back-office.web.fullname" .) .Values.web.service.port -}}
{{- end -}}
{{- end -}}

{{- define "back-office.api.callbackUrl" -}}
{{- printf "http://%s:%v" (include "back-office.api.fullname" .) .Values.api.service.port -}}
{{- end -}}

{{- define "back-office.web.apiUpstream" -}}
{{- default (include "back-office.api.callbackUrl" .) .Values.web.apiUpstream -}}
{{- end -}}

{{/* =========================================================================================
Identity (ADR 0016, ADR 0021, ADR 0039): local accounts and the optional external OIDC provider of the API.
========================================================================================= */}}

{{/* Comma separated string from a list or a string value */}}
{{- define "back-office.csv" -}}
{{- if kindIs "slice" . -}}{{- join "," . -}}{{- else -}}{{- . | default "" -}}{{- end -}}
{{- end -}}

{{/* OIDC issuer of an external provider; empty = local accounts only (ADR 0021, ADR 0039) */}}
{{- define "back-office.oidc.issuerUri" -}}
{{- if .Values.auth.oidc.issuerUri -}}
{{- .Values.auth.oidc.issuerUri -}}
{{- else if .Values.auth.local.enabled -}}
{{- /* no identity provider: local accounts only (ADR 0021) */ -}}
{{- else -}}
{{- fail "auth.oidc.issuerUri is required when auth.local.enabled=false (keep auth.local.enabled=true for local accounts only). See docs/guides/deploy-helm.md" -}}
{{- end -}}
{{- end -}}

{{/* s3Credentials block of a CNPG ObjectStore / barmanObjectStore */}}
{{- define "back-office.cnpg.s3Credentials" -}}
{{- $cr := .Values.cnpg.backup.s3Credentials -}}
s3Credentials:
{{- if $cr.inheritFromIAMRole }}
  inheritFromIAMRole: true
{{- else }}
  accessKeyId:
    name: {{ required "cnpg.backup.s3Credentials.secretName is required (or inheritFromIAMRole=true)" $cr.secretName }}
    key: {{ $cr.accessKeyIdKey }}
  secretAccessKey:
    name: {{ $cr.secretName }}
    key: {{ $cr.secretAccessKeyKey }}
{{- end }}
{{- end -}}
