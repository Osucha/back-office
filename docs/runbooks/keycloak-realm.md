# Runbook: Keycloak realm (export/import, client secret, users)

## When to use this runbook

You operate the Keycloak that Back Office logs in against - the compose service `keycloak` on a
laptop, or the bundled Keycloak of the Helm chart (`keycloak.enabled=true`) - and need to back up or
change the realm `back-office`, rotate the OIDC client secret, or add a user. For an organisation IdP
(Entra ID, Google, a central Keycloak) follow that provider's procedures; the Back Office side is the
same (section 3, last step).

Background: [08-identity-and-access.md](../architecture/08-identity-and-access.md), ADR 0016. Keycloak
authenticates; **authorisation lives in Back Office** (roles, grants, project memberships). Never grant
Back Office permissions through Keycloak roles - only groups are mapped (`groups` claim).

## Where the realm comes from

| Environment | Source | Import behaviour |
|---|---|---|
| Compose (`deploy/compose`) | `deploy/keycloak/realm-back-office.json` mounted read-only | `start-dev --import-realm`, in-memory DB: **re-imported on every new container**, admin-console changes are lost with `docker compose down`/`rm` |
| Helm, bundled Keycloak | copy in `deploy/helm/back-office/files/keycloak/`, rewritten by the chart into Secret `<release>-keycloak-realm` | `start --import-realm`, data in PostgreSQL (schema `keycloak`): **imported only on the first start**, existing realm is skipped - Keycloak is the source of truth afterwards |

Rule: in compose the JSON file is the configuration; in a cluster the running Keycloak is, and the
export is the backup. The CI job `helm:lint` fails if the two JSON copies differ - copy the file after
editing `deploy/keycloak/realm-back-office.json`.

Admin access:

- Compose: `http://localhost:8180/admin`, `admin` / `admin` (`KC_ADMIN`, `KC_ADMIN_PASSWORD`).
- Helm: `https://<bundledKeycloak host>/admin` (or `kubectl -n <ns> port-forward svc/<release>-keycloak-http 8180:80`),
  user `admin`, password:
  `kubectl -n <ns> get secret <release>-keycloak-env -o jsonpath="{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}" | base64 -d`.
  The bootstrap admin is temporary by design: create a personal admin in realm `master` and delete it.

CLI inside the container (Git Bash on Windows: prefix `MSYS_NO_PATHCONV=1`):

```bash
docker exec back-office-keycloak /opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config \
  --server http://localhost:8180 --realm master --user admin --password admin
docker exec back-office-keycloak /opt/keycloak/bin/kcadm.sh get clients --config /tmp/kcadm.config \
  -r back-office -q clientId=back-office --fields clientId,publicClient
```

(In the cluster: `kubectl exec <release>-keycloak-0 -- /opt/keycloak/bin/kcadm.sh ... --server http://localhost:8080`.)

## 1. Export the realm

**Full export (cluster, backup or migration)** - includes users, credentials hashes and the client
secret; treat the file as a secret. `kc.sh export` starts a second Keycloak process against the same
database, so run it in the Keycloak pod:

```bash
kubectl -n <ns> exec <release>-keycloak-0 -- /opt/keycloak/bin/kc.sh export \
  --dir /tmp/export --realm back-office --users realm_file
kubectl -n <ns> cp <release>-keycloak-0:/tmp/export/back-office-realm.json ./back-office-realm.json
kubectl -n <ns> exec <release>-keycloak-0 -- rm -rf /tmp/export
```

**Partial export (compose, or to update the dev realm file)** - clients, groups, roles, no users,
secrets masked as `**********`. The in-memory dev database cannot be exported with `kc.sh`, use the
admin console (*Realm settings > Action > Partial export*) or REST:

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
  http://localhost:8180/realms/master/protocol/openid-connect/token | python -c "import json,sys; print(json.load(sys.stdin)[\"access_token\"])")
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8180/admin/realms/back-office/partial-export?exportClients=true&exportGroupsAndRoles=true" > partial.json
```

When you fold a partial export back into `deploy/keycloak/realm-back-office.json`, keep the file small:
copy only the settings you changed, restore `"secret": "back-office-dev-secret"`, keep the four test
users, then `cp deploy/keycloak/realm-back-office.json deploy/helm/back-office/files/keycloak/`.

## 2. Import a realm

- **Compose**: edit `deploy/keycloak/realm-back-office.json`, then recreate the container:
  `docker compose -f deploy/compose/docker-compose.yml rm -sf keycloak && docker compose -f deploy/compose/docker-compose.yml up -d keycloak`.
  `docker logs back-office-keycloak` must show `Realm 'back-office' imported`. A JSON error aborts the
  start with the parse error in the log.
- **Cluster, new installation**: the chart imports on the first start (nothing to do).
- **Cluster, replace an existing realm** (restore from backup): an existing realm is never overwritten by
  `--import-realm`. Either delete the realm in the admin console and restart the pod
  (`kubectl delete pod <release>-keycloak-0`; it re-imports the Secret's file), or import a backup with
  `kc.sh import --file <file> --override true` in the pod. Deleting the realm logs everybody out of
  Keycloak; Back Office sessions stay valid until they expire - see step 5 of section 3.
- **Partial changes** (one client, a mapper): *Realm settings > Action > Partial import* with
  "skip/overwrite" per resource, instead of replacing the realm.

## 3. Rotate the OIDC client secret

The secret exists twice: in Keycloak (client `back-office` > *Credentials*) and in the API's
environment (`BACKOFFICE_AUTH_OIDC_CLIENT_SECRET`). Between the two steps below new logins fail
(`invalid_client` at the code exchange); existing Back Office sessions are not affected because the API
does not use the client secret after login. Do it in a quiet moment, the window is a minute.

1. Generate the new secret in Keycloak: admin console *Clients > back-office > Credentials > Regenerate*,
   or REST:
   ```bash
   CID=$(curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8180/admin/realms/back-office/clients?clientId=back-office" \
     | python -c "import json,sys; print(json.load(sys.stdin)[0][\"id\"])")
   curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8180/admin/realms/back-office/clients/$CID/client-secret
   ```
   (Keycloak 26 can also keep the old secret valid for a while: *Credentials > Client secret rotation*
   policy, "Secret expiration" + "Rotated secret expiration" - enable it via *Client policies* if you
   want a zero-downtime rotation.)
2. Put the value where the API reads it:
   - `auth.oidc.existingSecret`: `kubectl -n <ns> create secret generic back-office-oidc --from-literal=client-secret='<new>' --dry-run=client -o yaml | kubectl apply -f -`
     then `kubectl -n <ns> rollout restart deploy/<fullname>-api`.
   - Inline `auth.oidc.clientSecret`: `helm upgrade ... --set auth.oidc.clientSecret=<new>` (the checksum
     annotation rolls the API). With the bundled Keycloak also set `bundledKeycloak.realmImport.clientSecret`
     if you use it, so a future re-import matches.
   - Compose / local: the dev secret is `back-office-dev-secret` in the realm file and `.env`; change both.
3. Verify: log out and log in again; the API log must not show `invalid_client`.
4. If the secret leaked: rotate as above, then in Keycloak *Sessions > Logout all* for the realm, and in
   Back Office deactivate/reactivate affected users if needed (deactivation deletes their sessions).
5. Whenever Keycloak sessions are invalidated in bulk, Back Office sessions still run until idle/absolute
   timeout; to force re-login for everyone restart nothing - delete the rows:
   `DELETE FROM spring_session;` in the Back Office database (everyone has to log in again).

## 4. Add a user

Users live in Keycloak (credentials, MFA) **and** in Back Office (account, groups, roles). The order:

1. Keycloak: *Users > Add user* in realm `back-office`: username, e-mail, first/last name, *Email
   verified* on (Back Office matches invitations by verified e-mail), join groups (e.g. `developers`) if
   you map groups. *Credentials > Set password*, *Temporary* on so the user picks their own.
   REST equivalent:
   ```bash
   curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     http://localhost:8180/admin/realms/back-office/users -d @- <<JSON
   {"username":"jane","email":"jane@example.com","firstName":"Jane","lastName":"Doe","enabled":true,
    "emailVerified":true,"groups":["/developers"],
    "credentials":[{"type":"password","value":"change-me-now","temporary":true}]}
   JSON
   ```
2. Back Office (as an admin): *Administration > Users > Invite* with the same e-mail, groups and platform
   roles; add project memberships. Copy the sign-in link to the user. With `INVITE_ONLY` (default) a
   Keycloak user without invitation gets `/login?error=not-invited`.
3. The user logs in; the invitation binds the Keycloak subject and turns the account `ACTIVE`.

Removing someone: **deactivate in Back Office first** (immediate: sessions deleted, tokens rejected),
then disable or delete the Keycloak user. Deleting only in Keycloak leaves an existing Back Office
session valid until it times out.

Local test users (`admin`, `dev`, `approver`, `viewer` @example.com) are defined in the realm file; add
permanent test users there, not in the admin console (compose forgets console changes).

## Where to look when it fails

- `docker logs back-office-keycloak` / `kubectl logs <release>-keycloak-0` - import errors, `invalid_client`,
  `invalid_redirect_uri` with the offending URI.
- Keycloak *Events* (*Realm settings > Events > User events* on) show login failures per user.
- Brute-force lock after 10 failures: *Users > <user> > Brute force: Unlock*.
- Bundled Keycloak does not start: `kubectl logs <release>-keycloak-0 -c create-schema` (DB not reachable,
  wrong password) and the main container log (`KC_HOSTNAME`, DB schema permissions).
