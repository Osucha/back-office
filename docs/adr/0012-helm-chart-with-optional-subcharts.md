# 0012 - One Helm chart with conditional PostgreSQL and Langfuse dependencies

Status: accepted - 2026-09-26

## Context
Deployment must be a single `helm install` for a small cluster, but production may bring its own
database and observability stack.

## Decision
Chart `back-office` with templates for `api` (Deployment, Service, ConfigMap, Secret references, RBAC
for Jobs), `web` (Deployment, Service, Ingress), runner RBAC and optional NetworkPolicy, and dependencies
`postgresql` (Bitnami, `postgresql.enabled`) and `langfuse` (`langfuse.enabled`, default false).

## Consequences
- `values.yaml` documents every knob; `values-dev.yaml` gives a one-command kind/minikube install.
- The chart is linted (`helm lint`) and rendered (`helm template`) in CI.
