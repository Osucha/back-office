# 0039 — The Helm chart bundles no identity provider; local accounts are the default

- Status: accepted
- Date: 2026-10-12
- Deciders: owner
- Related: ADR 0012 (Helm chart with optional subcharts; amended here), 0016 (OIDC BFF authentication),
  0021 (local accounts); docs/guides/deploy-helm.md, deploy/helm/back-office/README.md

## Context

ADR 0012 made Keycloak an optional subchart (codecentric/keycloakx) for evaluation installs, with a realm import,
its own Secrets and Ingress, a schema in the Back Office database and, for dev clusters, a socat sidecar so the API
could reach the issuer through `localhost`. Since ADR 0021, Back Office has local accounts (e-mail + password,
optional TOTP, bootstrap administrator), which cover a single owner or a small team without any identity provider.

The owner will move the installation to Kubernetes in the cloud and does not want to run Keycloak there: it is a
second stateful service to patch, back up and secure, and nothing else in their setup uses it.

## Decision

1. The chart no longer depends on `keycloakx`. Removed: `keycloak.*` and `bundledKeycloak.*` values,
   `templates/keycloak.yaml`, `files/keycloak/` (the chart copy of the realm), `api.oidcLoopback` and the
   NetworkPolicy rule towards Keycloak.
2. **Local accounts are the default** (`auth.local.enabled=true`, `auth.oidc.issuerUri` empty). The first
   administrator comes from `auth.local.bootstrapAdminEmail` + `bootstrapAdminPassword` (forced password change);
   `values-dev.yaml` uses them. The chart refuses a configuration with neither local accounts nor an issuer.
3. An **external OIDC provider** (Entra ID, Google, Okta, a Keycloak run separately, ...) stays fully supported
   through `auth.oidc.*`, as in ADR 0016.
4. Local development keeps Keycloak in `deploy/compose` (and `deploy/keycloak/realm-back-office.json`) to test the
   OIDC path; CI no longer renders a bundled-Keycloak variant but renders a local-accounts-only one and rejects a
   chart without any way to sign in.

## Alternatives considered

- **Keep the subchart, disabled by default**: costs nothing at runtime, but the chart, its CI and its docs carry
  an option nobody uses, and the dev values pulled the owner towards it.
- **Replace it with another bundled IdP** (Dex, Authentik): the same operational burden for no gain over local
  accounts.

## Consequences

- An existing release that set `keycloak.enabled=true` must switch to local accounts (or point `auth.oidc.*` at
  an external provider) before upgrading; users who signed in through Keycloak keep their Back Office accounts
  (same e-mail) and an administrator can send them set-password links (ADR 0021).
- Moving to the cloud needs only PostgreSQL (data, including users and sessions) and object storage (artefact
  blobs) to be migrated.
