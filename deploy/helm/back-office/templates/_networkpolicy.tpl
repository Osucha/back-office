{{/* NetworkPolicy building blocks (networkpolicy.yaml) */}}
{{- define "back-office.np.dns" -}}
- to:
    - namespaceSelector: {}
      podSelector:
        matchLabels:
          {{- toYaml .Values.networkPolicy.dnsPodLabels | nindent 10 }}
  ports:
    - { port: 53, protocol: UDP }
    - { port: 53, protocol: TCP }
{{- end -}}
{{- define "back-office.np.fromIngress" -}}
- from:
    {{- with .Values.networkPolicy.ingressController.peers }}
    {{- toYaml . | nindent 4 }}
    {{- else }}
    - namespaceSelector:
        matchLabels:
          {{- toYaml .Values.networkPolicy.ingressController.namespaceLabels | nindent 10 }}
      {{- with .Values.networkPolicy.ingressController.podLabels }}
      podSelector:
        matchLabels:
          {{- toYaml . | nindent 10 }}
      {{- end }}
    {{- end }}
  ports:
    - { port: 8080, protocol: TCP }
{{- end -}}
{{- define "back-office.np.fromMonitoring" -}}
{{- if .Values.networkPolicy.monitoring.namespaceLabels }}
- from:
    - namespaceSelector:
        matchLabels:
          {{- toYaml .Values.networkPolicy.monitoring.namespaceLabels | nindent 10 }}
  ports:
    - { port: 8080, protocol: TCP }
{{- end }}
{{- end -}}
{{- define "back-office.np.toDatabase" -}}
{{- $mode := include "back-office.database.mode" . }}
{{- if eq $mode "bundled" }}
- to:
    - podSelector:
        matchLabels:
          app.kubernetes.io/instance: {{ .Release.Name }}
          app.kubernetes.io/name: postgresql
  ports:
    - { port: 5432, protocol: TCP }
{{- else if eq $mode "cnpg" }}
- to:
    - podSelector:
        matchLabels:
          cnpg.io/cluster: {{ include "back-office.cnpg.clusterName" . }}
    {{- if .Values.cnpg.pooler.enabled }}
    - podSelector:
        matchLabels:
          cnpg.io/poolerName: {{ printf "%s-pooler-rw" (include "back-office.cnpg.clusterName" .) }}
    {{- end }}
  ports:
    - { port: 5432, protocol: TCP }
{{- else }}
- to:
    {{- if .Values.networkPolicy.database.cidrs }}
    {{- range .Values.networkPolicy.database.cidrs }}
    - ipBlock:
        cidr: {{ . }}
    {{- end }}
    {{- else }}
    - ipBlock:
        cidr: 0.0.0.0/0
    {{- end }}
    {{- if .Values.pgbouncer.enabled }}
    - podSelector:
        matchLabels:
          {{- include "back-office.selectorLabels" (dict "root" . "component" "pgbouncer") | nindent 10 }}
    {{- end }}
  ports:
    - { port: {{ .Values.externalDatabase.port }}, protocol: TCP }
    {{- if .Values.externalDatabase.pooler.host }}
    - { port: {{ .Values.externalDatabase.pooler.port }}, protocol: TCP }
    {{- end }}
    {{- if .Values.pgbouncer.enabled }}
    - { port: {{ .Values.pgbouncer.port }}, protocol: TCP }
    {{- end }}
{{- end }}
{{- end -}}
{{- define "back-office.np.egressCommon" -}}
{{- $np := .Values.networkPolicy }}
{{- if .Values.minio.enabled }}
- to:
    - podSelector:
        matchLabels:
          {{- include "back-office.selectorLabels" (dict "root" . "component" "minio") | nindent 10 }}
  ports:
    - { port: 9000, protocol: TCP }
{{- end }}
{{- if .Values.langfuse.enabled }}
- to:
    - podSelector:
        matchLabels:
          app.kubernetes.io/instance: {{ .Release.Name }}
  ports:
    - { port: 3000, protocol: TCP }
{{- end }}
{{- with $np.egress.otelCollector }}
- to:
    {{- toYaml . | nindent 4 }}
  ports:
    - { port: 4317, protocol: TCP }
    - { port: 4318, protocol: TCP }
{{- end }}
{{- if $np.egress.internet.enabled }}
# LLM providers, OIDC issuer, Jira, S3/GCS/Blob endpoints, git hosts are not contacted by the control plane
- to:
    - ipBlock:
        cidr: 0.0.0.0/0
        {{- with $np.egress.internet.except }}
        except:
          {{- toYaml . | nindent 10 }}
        {{- end }}
  ports:
    {{- range $np.egress.internet.ports }}
    - { port: {{ . }}, protocol: TCP }
    {{- end }}
{{- end }}
{{- with $np.egress.extra }}
{{ toYaml . }}
{{- end }}
{{- end -}}
