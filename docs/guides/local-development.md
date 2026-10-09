# Local development

## When to use this guide

You want to run Back Office on your laptop: PostgreSQL, Keycloak and MinIO in Docker, the Kotlin API and
the Angular UI from source, and optionally the runner fleet (runner-controller with the DOCKER backend and
the Smokescreen egress proxy) so `IMPLEMENT` / `SPEC_KIT` steps and coding benchmarks work end to end.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 (Temurin recommended) | `JAVA_HOME` must point at it; `java -version` prints 21.x |
| Node.js + npm | 22.x | `nvm use 22` / `nvm-windows` |
| Docker | Docker Desktop or Engine with Compose v2 | Needed for PostgreSQL, Testcontainers and the runner |
| Python | 3.11+ (only for runner tests) | `python3 -m unittest` |
| Helm / kubectl | 3.19 / 1.34 (only for the chart) | see [deploy-helm.md](deploy-helm.md) |
| Git | any recent | |

Repository layout is described in the [README](../../README.md). All commands below are run from the
repository root unless stated otherwise.

## 1. Database and identity provider

```bash
docker compose -f deploy/compose/docker-compose.yml up -d postgres keycloak minio minio-init
docker compose -f deploy/compose/docker-compose.yml ps        # wait until they are "healthy" (Keycloak ~40-60 s)
```

| Service | URL | Notes |
|---|---|---|
| PostgreSQL 16 | `localhost:5432` (or `POSTGRES_PORT`) | user/password/database `backoffice`; volume `back-office_postgres-data`; also holds the session table `SPRING_SESSION` |
| Keycloak 26.7 | `http://localhost:8180` | realm `back-office` imported from `deploy/keycloak/realm-back-office.json`; admin console `http://localhost:8180/admin` (`admin` / `admin`) |
| MinIO (S3) | `http://localhost:9000`, console `:9001` | object storage for artefacts, diffs and logs; bucket `back-office` created by `minio-init`; credentials `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` (`backoffice` / `backoffice-dev-secret`). Image `cgr.dev/chainguard/minio` (MinIO stopped publishing community images in 2025) |

Keycloak runs in dev mode with an in-memory database: every fresh container re-imports the realm, and
users you add in the admin console disappear with `docker compose down` / `rm`. Change the realm file
for anything that should stick (see [runbooks/keycloak-realm.md](../runbooks/keycloak-realm.md)).

Copy `deploy/compose/.env.example` to `deploy/compose/.env` and fill in at least one provider key.
The file is git-ignored. **On this kind of Windows machine a local PostgreSQL service already owns 5432:
put `POSTGRES_PORT=5433` in `.env`** and point the API at it (below).

### Test users (realm `back-office`)

Log in with the e-mail (or the user name) and the password. All e-mails are verified, passwords are
permanent.

| E-mail | Password | Keycloak group (`groups` claim) | Role in Back Office |
|---|---|---|---|
| `admin@example.com` | `admin` | `architects` | `platform-admin` on first login (bootstrap admin) |
| `dev@example.com` | `dev` | `developers` | invite and add to projects as `developer` |
| `approver@example.com` | `approver` | `approvers` | invite and add to projects as `approver` |
| `viewer@example.com` | `viewer` | - | invite and add to projects as `viewer` |

OIDC client: `back-office`, confidential, secret `back-office-dev-secret`, authorization code + PKCE (S256)
only, redirect URIs `http://localhost:4200/login/oauth2/code/oidc` and `http://localhost:8080/login/oauth2/code/oidc`.
Keycloak groups arrive in the `groups` claim; Back Office maps them to its own groups, permissions are
granted in Back Office (ADR 0017), never in Keycloak.

## 2. Backend

```bash
cd backend
./gradlew :app:bootRun --args="--spring.profiles.active=local"
```

The `local` profile (see `application.yml`):

- points OIDC at the compose Keycloak: issuer `http://localhost:8180/realms/back-office`, client
  `back-office` / `back-office-dev-secret`, bootstrap admin `admin@example.com`, non-Secure cookie,
- sets `RUNNER_MODE=DOCKER`,
- starts the compose services without a profile (`postgres`, `keycloak`) automatically
  (spring-boot-docker-compose, `start_only`),
- logs `pl.mos.backoffice` at DEBUG.

There is no API key any more. If your checkout's `local` profile does not set the identity values yet,
or you use another provider, export them (full list in [configuration.md](configuration.md#authentication-and-sessions)):

```bash
export BACKOFFICE_AUTH_OIDC_ISSUER_URI=http://localhost:8180/realms/back-office
export BACKOFFICE_AUTH_OIDC_CLIENT_ID=back-office
export BACKOFFICE_AUTH_OIDC_CLIENT_SECRET=back-office-dev-secret
export BACKOFFICE_AUTH_BOOTSTRAP_ADMINS=admin@example.com
export BACKOFFICE_AUTH_COOKIE_SECURE=false                    # http://localhost
export BACKOFFICE_PUBLIC_URL=http://localhost:4200             # post-logout redirect goes back to the dev UI
export RUNNER_TOKEN_SECRET=dev-runner-token-secret              # no API key to derive it from any more
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/backoffice   # only with POSTGRES_PORT=5433
# object storage and signing key (iteration 5)
export BACKOFFICE_STORAGE_S3_ENDPOINT=http://localhost:9000 BACKOFFICE_STORAGE_S3_BUCKET=back-office
export BACKOFFICE_STORAGE_S3_PATH_STYLE=true BACKOFFICE_STORAGE_S3_ACCESS_KEY=backoffice BACKOFFICE_STORAGE_S3_SECRET_KEY=backoffice-dev-secret
eval "$(deploy/scripts/gen-signing-key.sh | sed "s/^/export /")"    # a throw-away Ed25519 key per shell
```

On the host the API runs with `BACKOFFICE_ROLE` unset, i.e. `api,worker` in one process, and Flyway
migrates on start (the `migrate` role and `SPRING_FLYWAY_ENABLED=false` are for Kubernetes). To try the
split locally start a second instance with `BACKOFFICE_ROLE=worker SERVER_PORT=8082`.

Export provider keys in the same shell before starting (or use your IDE run configuration):

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

The API needs no git token: clone/push credentials belong to the runner-controller (`GIT_TOKEN` in
`deploy/compose/.env`: `GIT_TOKEN_READ` (Reporter, `read_repository`) and `GIT_TOKEN_PUSH` (Developer,
`write_repository`) become its `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH`, ADR 0031; the older `GIT_TOKEN`
is a push-level fallback).

PowerShell: `$env:ANTHROPIC_API_KEY = "sk-ant-..."`.

Useful URLs once it is up:

| URL | What |
|---|---|
| `http://localhost:8080/actuator/health/readiness` | readiness (DB + Flyway) |
| `http://localhost:8080/swagger-ui.html` | Swagger UI; authorise with a personal access token (`bo_pat_...`, created in the UI under your profile) |
| `http://localhost:8080/v3/api-docs` | live OpenAPI, should match `docs/api/openapi.yaml` |

## 3. Frontend

```bash
cd frontend
npm install
npm start          # http://localhost:4200, proxies /api, /oauth2, /login/oauth2, /logout -> http://localhost:8080
```

Always open the UI on `http://localhost:4200` (not `127.0.0.1`): the session cookie, the Keycloak
redirect URI and the post-logout redirect are registered for `localhost`.

### First login and inviting the others

1. Open `http://localhost:4200`. The UI calls `/api/v1/me`, gets `401` and sends you to Keycloak.
2. Log in as `admin@example.com` / `admin`. Keycloak redirects to `/login/oauth2/code/oidc`, the API
   creates your user (`ACTIVE`) and, because the e-mail is in `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS`,
   grants `platform-admin`. You land on the page you asked for.
3. **Administration > Users > Invite**: invite `dev@example.com`, `approver@example.com` and
   `viewer@example.com` (optionally with groups). With the default `INVITE_ONLY` registration nobody
   else can log in. Back Office sends no e-mail: copy the sign-in link the UI shows.
4. Open a project **> Members** and add the invited users with the project roles `developer`,
   `approver`, `viewer`.
5. Log out (header menu). The API ends the session and the browser is sent through Keycloak's logout
   back to the UI. Log in as `dev@example.com` / `dev` in a private window to see the developer's view.

Shortcut for a single-person setup: `BACKOFFICE_AUTH_REGISTRATION=OPEN` (everyone who can log in to the
realm gets an account with the `member` defaults) or `BACKOFFICE_AUTH_ALLOWED_EMAIL_DOMAINS=example.com`.

## 4. Running tests

```bash
# backend: unit (JUnit 5 + MockK), adapters (@SpringBootTest + Testcontainers), ArchUnit
cd backend && ./gradlew test

# frontend (vitest + jsdom, no browser needed)
cd frontend && npm run test:ci

# runner
python3 -m unittest discover -s runner/tests -v

# CI client script (fake API + temporary git repository; uses Git Bash sh.exe on Windows)
python3 -m unittest discover -s ci/tests -v
```

Testcontainers needs a reachable Docker daemon; on Windows/macOS Docker Desktop is enough. Set
`TESTCONTAINERS_RYUK_DISABLED=true` if the reaper container is blocked by a corporate proxy.

## 5. Building the images

```bash
docker build -t back-office/api:dev    backend/
docker build -t back-office/web:dev    frontend/
docker build -t back-office/runner:dev runner/
```

```bash
docker build -t back-office/egress-proxy:dev backend/runner-controller/egress-proxy/     # Smokescreen
docker build -t back-office/runner-controller:dev -f backend/runner-controller/Dockerfile backend/
```

Or all at once together with a full local stack that mirrors the Kubernetes topology - `api` (role api,
migrates on start), `worker` (role worker), `web`, MinIO, `egress-proxy` and `runner-controller` with the
DOCKER backend (web on `http://localhost:8080`, API on `:8081`, Keycloak on `:8180`):

```bash
docker compose -f deploy/compose/docker-compose.yml --profile full up -d --build
```

Open the UI on `http://localhost:8080` (the realm allows that redirect URI too). Inside the API
container `localhost` is the container itself, so the API runs in the network namespace of the small
`api-netns` socat container that forwards `localhost:8180` to Keycloak: browser and API see the same
issuer `http://localhost:8180/realms/back-office` (details at the top of `docker-compose.yml`).

The worker runs in the network namespace of its own socat container (`worker-netns`) for the same
reason. The runner-controller registers with `RUNNER_REGISTRATION_TOKEN` from `.env`: log in, open
**Administration > Runner fleet**, create a registration token for pool `default`, put it into `.env`
and `docker compose ... up -d runner-controller`. It stores the runner token in the volume
`runner-controller-data`, so the registration token is needed only once.

Security: the `full` profile mounts `/var/run/docker.sock` into the **runner-controller** (not into api
or worker) so it can start sandbox containers. Anyone who can reach that container controls your Docker
daemon - keep it on a laptop. The DOCKER backend has no gVisor/Kata boundary (isolation `DEV` = runc);
sandbox containers sit on the internal network `back-office_sandbox` without a route out and reach
anything only through the egress proxy, which enforces each job's allowlist (pulled from the controller).

### Optional: the Helm charts on kind

```bash
kind create cluster --name bo
kind load docker-image back-office/api:dev back-office/web:dev --name bo
helm dependency build deploy/helm/back-office
helm install back-office deploy/helm/back-office -n back-office --create-namespace -f deploy/helm/back-office/values-dev.yaml
helm install runners deploy/helm/back-office-runner -n back-office-runners --create-namespace \
  -f deploy/helm/back-office-runner/values-dev.yaml --set registration.token=bo_rrt_...
```

`values-dev.yaml` uses `mode: combined`, the bundled PostgreSQL, MinIO and Keycloak; the runner values
use the `K8S_JOB` backend with runc (kind has no gVisor).

## 6. Running the runner locally

Agents always run through the runner fleet (same model as production): the compose
`runner-controller` + `egress-proxy` services (section 5). With the API on the host set
`RUNNER_BACKOFFICE_URL=http://host.docker.internal:8080` for the controller. Git credentials for
clone/push: `GIT_TOKEN_READ` and `GIT_TOKEN_PUSH` (and optionally `GIT_USER_NAME` / `GIT_USER_EMAIL`) in
`deploy/compose/.env`, passed to the controller as `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH` / `RUNNER_GIT_USER_*`.

To exercise the runner by hand without the API, follow [runner/README.md](../../runner/README.md):
build a `RunnerJobSpec`, base64 it into `RUNNER_SPEC`, omit `RUNNER_CALLBACK_URL` and use
`RUNNER_RESULT_FILE` to capture the result. Handy for debugging agent prompts and Spec Kit
behaviour on a throw-away repository.

## 7. Testing the CI client against the dev server

The project's CI talks to Back Office through `ci/backoffice-ci.sh` (served by the API at
`http://localhost:8080/ci/backoffice-ci.sh`, see [ci-integration.md](ci-integration.md)). To try it
without a pipeline:

1. In the UI open a project, go to **CI**, map an entrypoint (`code-review` -> a workflow) and create
   a CI token; copy the `bo_ci_...` value (shown once).
2. In any git checkout on a feature branch (a throw-away clone is fine):

```bash
export BACKOFFICE_URL=http://localhost:8080 BACKOFFICE_CI_TOKEN=bo_ci_...
sh /path/to/back-office/ci/backoffice-ci.sh run code-review --wait     # provider OTHER, diff against origin/main
sh /path/to/back-office/ci/backoffice-ci.sh status <executionId>
sh /path/to/back-office/ci/backoffice-ci.sh signal tests --status success --run <runId>
```

Simulate a GitLab merge request by exporting `GITLAB_CI=true CI_PROJECT_PATH=g/p CI_MERGE_REQUEST_IID=1
CI_MERGE_REQUEST_TARGET_BRANCH_NAME=main CI_COMMIT_SHA=$(git rev-parse HEAD)` before the call. On
Windows run it from Git Bash (`"C:\Program Files\Git\usr\bin\sh.exe"`); the script needs `sh`, `curl`,
`git` and `awk` only. `BACKOFFICE_POLL_INTERVAL=1` speeds up polling locally.

## 8. Optional: Langfuse locally

```bash
docker compose -f deploy/compose/docker-compose.yml -f deploy/compose/docker-compose.langfuse.yml --profile langfuse up -d
```

Open `http://localhost:3000`, create a project, copy the keys and set in `.env`
`LANGFUSE_ENABLED=true`, `OTEL_TRACES_ENABLED=true`,
`OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION=Basic $(echo -n pk-lf-...:sk-lf-... | base64)`. When the
API runs on the host the endpoint is `http://localhost:3000/api/public/otel/v1/traces`.

## Where to look when it fails

- **Port already in use** - `8080` (API), `4200` (UI), `5432` (PostgreSQL), `9000`/`9001` (MinIO: `MINIO_PORT`, `MINIO_CONSOLE_PORT`), `8180` (Keycloak; change
  `KEYCLOAK_PORT` in `.env` and the issuer URL together, and add the new port to nothing else - redirect
  URIs are on 4200/8080). Change `POSTGRES_PORT` in
  `.env` and `SPRING_DATASOURCE_URL` accordingly, or stop the other process (`netstat -ano | findstr :5432` on Windows, `lsof -i :5432` elsewhere).
  A typical symptom of a locally installed PostgreSQL service (e.g. `postgresql-x64-17` on Windows) hijacking
  the port is Flyway failing with "password authentication failed for user backoffice" even though the
  compose container is healthy. Fix: `echo POSTGRES_PORT=5433 > deploy/compose/.env`, `docker compose up -d postgres`,
  then start the API with `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/backoffice`.
- **`./gradlew` says "Unsupported class file major version" or picks the wrong JDK** - `JAVA_HOME` is
  not JDK 21. `./gradlew --version` shows the JVM in use; Gradle's toolchain download can be enabled but is slow.
- **Docker on Windows** - use Docker Desktop with the WSL 2 backend; make sure the daemon is running
  (`docker version` shows a Server section). Named-pipe errors like `open //./pipe/dockerDesktopLinuxEngine`
  mean Docker Desktop is not started. Compose bind mounts of `/var/run/docker.sock` work through
  Docker Desktop's Linux VM. With a host-run API point the runner-controller at
  `RUNNER_BACKOFFICE_URL=http://host.docker.internal:8080`.
- **Testcontainers cannot connect** - `DOCKER_HOST` mismatch or Docker Desktop's "Expose daemon on
  tcp://localhost:2375" not needed: Testcontainers uses the named pipe on Windows automatically. Check
  `~/.testcontainers.properties` for a stale `docker.host`.
- **Flyway validation errors after switching branches** - the dev database has migrations that no
  longer exist. Recreate it: `docker compose -f deploy/compose/docker-compose.yml down -v postgres && ... up -d postgres`.
- **Login problems** (the UI keeps bouncing, or Keycloak shows an error page):

  | Symptom | Cause and fix |
  |---|---|
  | API fails at start with `Unable to resolve Configuration with the provided Issuer` | Keycloak not up yet or wrong issuer. `curl http://localhost:8180/realms/back-office/.well-known/openid-configuration` must answer and show exactly the configured issuer. |
  | Keycloak page "Invalid parameter: redirect_uri" | You opened the UI on another host/port than `localhost:4200` / `localhost:8080`. Use those, or add the URI to the client in the realm. |
  | Back on `/login?error=not-invited` | `INVITE_ONLY` and the e-mail was neither invited nor in `BACKOFFICE_AUTH_ALLOWED_EMAIL_DOMAINS` / bootstrap admins. Invite it as admin. |
  | Login succeeds, next request is `401` again | The `BO_SESSION` cookie was not stored: `BACKOFFICE_AUTH_COOKIE_SECURE=true` on plain http, or the UI opened on `127.0.0.1`. |
  | `403` on POST/PUT/DELETE, GET works | CSRF: the request lacks `X-XSRF-TOKEN`. Happens with hand-made curl calls; use a PAT (`Authorization: Bearer bo_pat_...`) for scripts. |
  | Keycloak "Invalid username or password" for the test users | The realm was not imported (`docker logs back-office-keycloak` must show `Realm 'back-office' imported`). Recreate: `docker compose rm -sf keycloak && docker compose up -d keycloak`. |
  | Account temporarily disabled | Brute-force protection after 10 wrong passwords; wait 1 minute or unlock in the admin console (Users > user > Brute force). |
  | Logout returns to Keycloak's page instead of the UI | `BACKOFFICE_PUBLIC_URL` does not match a post-logout redirect URI (`http://localhost:4200/*`, `http://localhost:8080/*`). |

  Clearing site data for `localhost` (cookies) resets a confused browser session.
- **Runner container starts but never reports back** - it cannot resolve the callback URL. From a
  container `host.docker.internal` works on Docker Desktop; on Linux add
  `--add-host host.docker.internal:host-gateway` (the `full` profile does this) or use the compose
  network and `http://back-office-api:8080`.
- **runner-controller exits with `registration failed`** - `RUNNER_REGISTRATION_TOKEN` empty, already
  used or for another pool; create a new one. Delete the volume `back-office_runner-controller-data` to
  force a new registration.
- **Agent cannot download dependencies** - the host is not in the job's `sandboxPolicy.network.allow`
  (or the pool ceiling); `docker logs back-office-egress-proxy` shows every decision.
- **Uploads fail with `NoSuchBucket`** - `minio-init` did not run (`docker compose ... up minio-init`).
- **Agent fails with `ProviderAuthError` / 401** - sandboxes never get provider keys; they call the LLM
  gateway with the job token. Check that the API has the provider key and that the model is the job's
  model; inspect a finished container: `docker ps -a`, `docker logs <id>` (tokens are redacted in logs).
- Logs: API console (DEBUG for `pl.mos.backoffice` in `local`), `docker compose logs -f postgres`,
  runner container logs and the run detail page in the UI (log events are streamed there).
