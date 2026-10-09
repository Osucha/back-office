# 0009 - Single-owner API-key authentication, OIDC deferred

Status: superseded by [0016](0016-oidc-bff-authentication.md) on 2026-09-27

## Context
Exactly one human uses the tool; machine callers are CI jobs and runner containers.

## Decision
`X-Api-Key` header checked by a Spring Security filter; separate optional CI key; per-job HMAC bearer
tokens for runner callbacks; webhook secret for GitLab. No user table.

## Alternatives
- OIDC (Keycloak, Entra): correct for teams; unnecessary operational weight for one user. The filter is
  isolated so OIDC can replace it later.
- Basic auth: equivalent security, worse ergonomics for CI.

## Consequences
- The key is a Kubernetes Secret; rotation means changing the secret and restarting.
- All endpoints require the key except `/actuator/health`, `/api/v1/internal/runner/**` (job token) and
  `/api/v1/webhooks/**` (webhook secret).
