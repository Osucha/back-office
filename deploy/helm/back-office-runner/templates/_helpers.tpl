{{- define "runner.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "runner.fullname" -}}
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

{{- define "runner.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "runner.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: back-office
backoffice.io/pool: {{ .Values.backOffice.poolId | quote }}
{{- with .Values.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{/* Controller pods. The name is fixed: the controller's own proxy NetworkPolicy (EgressInfrastructure)
selects app.kubernetes.io/name=back-office-runner-controller. */}}
{{- define "runner.controllerSelectorLabels" -}}
app.kubernetes.io/name: back-office-runner-controller
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: controller
{{- end -}}

{{/* Service of the controller (egress proxies pull allowlists from it); port = server.port 8090 */}}
{{- define "runner.controllerServiceName" -}}
{{- printf "%s-controller" (include "runner.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* RUNNER_CONTROLLER_URL: FQDN, the proxies run in the sandbox namespace */}}
{{- define "runner.controllerUrl" -}}
{{- printf "http://%s.%s.svc:%d" (include "runner.controllerServiceName" .) .Release.Namespace (int .Values.controller.port) -}}
{{- end -}}

{{/* Labels the controller puts on every sandbox pod (Labels.SANDBOX in SandboxBackend.kt) */}}
{{- define "runner.sandboxPodLabels" -}}
backoffice.io/sandbox: "true"
{{- end -}}

{{- define "runner.sandboxNamespace" -}}
{{- if .Values.sandboxNamespace.create -}}
{{- default (printf "%s-sandboxes" .Release.Namespace) .Values.sandboxNamespace.name -}}
{{- else -}}
{{- default .Release.Namespace .Values.sandboxNamespace.name -}}
{{- end -}}
{{- end -}}

{{- define "runner.serviceAccountName" -}}
{{- if .Values.controller.serviceAccount.create -}}
{{- default (include "runner.fullname" .) .Values.controller.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.controller.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "runner.tokenSecretName" -}}
{{- default (printf "%s-token" (include "runner.fullname" .)) .Values.tokenSecret.name -}}
{{- end -}}

{{- define "runner.registrationSecretName" -}}
{{- default (printf "%s-registration" (include "runner.fullname" .)) .Values.registration.existingSecret -}}
{{- end -}}

{{/* Labels the controller puts on its per-pool egress proxy pods bo-egress-<pool> (EgressInfrastructure.kt) */}}
{{- define "runner.proxyPodLabels" -}}
app.kubernetes.io/name: back-office-egress-proxy
{{- end -}}

{{- define "runner.image" -}}
{{- printf "%s:%s" .Values.controller.image.repository (default .Chart.AppVersion .Values.controller.image.tag) -}}
{{- end -}}

{{- define "runner.validate" -}}
{{- if not (has .Values.backend (list "auto" "AGENT_SANDBOX" "K8S_JOB")) -}}
{{- fail (printf "backend must be auto, AGENT_SANDBOX or K8S_JOB (got %q). DOCKER is for laptops only (deploy/compose), not for Kubernetes." .Values.backend) -}}
{{- end -}}
{{- range $i, $e := .Values.gitCredentials.repoSecrets -}}
{{- if not $e.repository -}}
{{- fail (printf "gitCredentials.repoSecrets[%d].repository is required (host/group/project, host/group/*, host, *.domain or *)" $i) -}}
{{- end -}}
{{- if and (not $e.read) (not $e.push) -}}
{{- fail (printf "gitCredentials.repoSecrets[%d] (%s) needs a read and/or a push Secret" $i $e.repository) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
SPRING_APPLICATION_JSON for the runner-controller.* maps and lists that have no single environment variable:
kubernetes.cache-pvcs (cache -> PVC), git.repo-secrets (repository -> read / push Secret, ADR 0031), git.secrets
(legacy push-level fallback: repository host or * -> Secret with key `token` in the sandbox namespace) and
egress.allow-ranges (private CIDRs Smokescreen may connect to). Empty when none is set.
*/}}
{{- define "runner.applicationJson" -}}
{{- $rc := dict -}}
{{- if .Values.caches.enabled -}}
{{- $pvcs := dict -}}
{{- range $cache, $_ := .Values.caches.volumes -}}
{{- $_ := set $pvcs $cache (printf "%s-cache-%s" (include "runner.fullname" $) $cache) -}}
{{- end -}}
{{- $_ := set $rc "kubernetes" (dict "cache-pvcs" $pvcs) -}}
{{- end -}}
{{- $git := dict -}}
{{- /* bracket notation keeps dots and `*` inside one map key */ -}}
{{- range $host, $secret := .Values.gitCredentials.hostSecrets -}}
{{- $_ := set $git (printf "[%s]" $host) $secret -}}
{{- end -}}
{{- with .Values.gitCredentials.existingSecret -}}
{{- $_ := set $git "[*]" . -}}
{{- end -}}
{{- $gitConfig := dict -}}
{{- if $git -}}
{{- $_ := set $gitConfig "secrets" $git -}}
{{- end -}}
{{- $repoSecrets := list -}}
{{- range .Values.gitCredentials.repoSecrets -}}
{{- $entry := dict "repository" .repository -}}
{{- with .read }}{{- $_ := set $entry "read" . -}}{{- end -}}
{{- with .push }}{{- $_ := set $entry "push" . -}}{{- end -}}
{{- $repoSecrets = append $repoSecrets $entry -}}
{{- end -}}
{{- if $repoSecrets -}}
{{- $_ := set $gitConfig "repo-secrets" $repoSecrets -}}
{{- end -}}
{{- if $gitConfig -}}
{{- $_ := set $rc "git" $gitConfig -}}
{{- end -}}
{{- $egress := dict -}}
{{- with .Values.egressProxy.allowedCidrs -}}
{{- $_ := set $egress "allow-ranges" . -}}
{{- end -}}
{{- with .Values.egressProxy.alwaysAllowHosts -}}
{{- $_ := set $egress "always-allow" . -}}
{{- end -}}
{{- if $egress -}}
{{- $_ := set $rc "egress" $egress -}}
{{- end -}}
{{- if $rc -}}
{{- dict "runner-controller" $rc | toJson -}}
{{- end -}}
{{- end -}}
