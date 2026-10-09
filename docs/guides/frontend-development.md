# Frontend development guide

## When to use this guide

You are changing the Angular UI in `frontend/`: adding a page, wiring a new endpoint, changing the
layout, or building/deploying the web image. It assumes Node 22.12+ and npm 10.

## Stack

| Concern | Choice |
|---|---|
| Framework | Angular 21, standalone components, signals, zoneless change detection, `inject()` |
| UI kit | NG-ZORRO 21 (`ng-zorro-antd`) themed onto the Relay tokens (`src/styles/relay/ng-zorro-theme.less`), icons loaded dynamically from `assets/` |
| Design language | Relay (`docs/ui/design-language.md`): tokens in `src/styles/relay/tokens.css`, components in `core/ui/relay/` (see its README), fonts Instrument Sans + JetBrains Mono (`@fontsource-variable/*`, self-hosted) |
| Charts | ECharts 6 via `ngx-echarts` (`echarts/core` tree-shaken bundle in `core/charts/echarts.ts`, loaded lazily) |
| API types | `openapi-typescript` generates `src/app/core/api/schema.d.ts` from `docs/api/openapi.yaml` |
| Markdown | `marked` + `DOMPurify` (`core/ui/markdown-viewer.component.ts`) |
| Flow canvas | `ngx-vflow` 2.7 (peers `d3-drag`, `d3-selection`, `d3-zoom` + `@types`) for the workflow designer and the run replay |
| Tests | Vitest (Angular CLI `unit-test` builder, jsdom) |
| i18n | `@angular/localize`, runtime translations (English source, Polish), per-area files in `src/locale/`, gate `npm run i18n:check` (see "Internationalisation") |
| Auth | OIDC through the backend (BFF, `BO_SESSION` cookie); `IdentityService`, guards, `*boCan` directives |
| Guardrails | `dependency-cruiser` 17 (`npm run lint:deps`, part of `npm run build`) |
| Runtime | nginx (unprivileged image) serving the SPA and proxying `/api`, the OIDC endpoints, `/v3/api-docs`, `/swagger-ui` |

## Repository layout

```
frontend/
  angular.json                  build/serve/test targets, budgets, icon assets, font stylesheets
  proxy.conf.json               dev proxy to http://localhost:8080 (xfwd): /api, /oauth2, /login/oauth2, /logout,
                                /ci/backoffice-ci.sh, /v3/api-docs, /swagger-ui
  .dependency-cruiser.cjs       layer rules for `npm run lint:deps`
  Dockerfile                    multi-stage: node:22-alpine build -> nginxinc/nginx-unprivileged:1.27-alpine
  Dockerfile.dockerignore       BuildKit ignore file (context is the repository root)
  nginx/default.conf.template   envsubst template: SPA fallback, gzip, security headers, API/SSE/OIDC proxy
  e2e/smoke.mjs                 Playwright smoke run (OIDC login or mocked identity, every route, axe checks)
  e2e/relay-shots.mjs           screenshots of the Relay screens in light and dark (visual review)
  e2e/screenshots/relay/        curated screenshots referenced by docs/ui/design-language.md
  scripts/check-contrast.ts     npm run check:contrast (WCAG ratios of the token pairs)
  scripts/check-bundle.mjs      npm run check:bundle (initial JS + CSS <= 300 kB gzip)
  scripts/i18n-*.mjs            i18n:merge, i18n:check (lib = pure checks, scan = source scanner), tests/ with fixtures
  e2e/i18n-check.mjs            Polish UI against the running stack (screenshots in e2e/screenshots/i18n/)
  src/
    main.ts, bootstrap.ts       language resolution + translations, then the app (ADR 0026)
    locale/<lang>/<area>.json   translations per area; pending-areas.json, untranslatable.json
    theme.less                  entry: imports styles/relay/ng-zorro-theme.less
    styles.less                 entry: imports styles/relay/tokens.css and base.less
    styles/relay/               tokens.css (light/dark/compact custom properties), ng-zorro-theme.less (the ONE
                                NG-ZORRO mapping), less-token-functions.cjs (colour functions on var()), base.less
                                (document defaults and utilities: .page, .page-header, .mono, .muted, .surface ...)
    app/
      app.ts                    root: <router-outlet> + <app-session-notices>
      app.config.ts             providers: router, HttpClient (fetch, XSRF, interceptor), identity initializer, NG-ZORRO
      app.routes.ts             /login (public), shell with guards, lazy routes with access data
      layout/                   ShellComponent (rail, top bar, activity dock, palette, shortcuts overlay), RailComponent,
                                TopBarComponent, AvatarMenuComponent, ActivityDockComponent, navigation.ts (menu model),
                                palette-providers.ts (default command palette providers)
      core/
        api/                    schema.d.ts (generated), types.ts (aliases), api-base.ts, <tag>.service.ts per OpenAPI tag
                                (auth.service = auth + tokens, admin.service = admin + audit + lookups, members.service)
        auth/                   IdentityService, AuthConfigService, SessionExpiryService + SessionNoticesComponent,
                                guards.ts, can.directive.ts (*boCan, *boCanInProject), permissions.ts (pure),
                                project-access.ts, browser-navigation.ts (full-page navigation token)
        http/                   httpErrorInterceptor (no-store, 401/0/403 rules, problem detail notifications)
        forms/                  server-errors.ts (RFC 9457 field errors -> controls), readonly-form.directive.ts,
                                patterns.ts
        events/                 EventStreamService (cookie-authenticated EventSource, backoff, typed filters)
        notifications/          desktop notifications (ADR 0032): notification-prefs.ts (pure model, delivery rules),
                                NotificationSettingsService (per-user prefs in localStorage, permission; eager),
                                DesktopNotifierLoader -> DesktopNotifierService (lazy: inbox diff + SSE -> Notification),
                                notification-content.ts (texts, tags, links), sanitize.ts, sounds.ts (Web Audio motifs)
        state/                  InboxService, TurnQueueService + turn-queue.ts, ProjectsStore (projects + current project),
                                RunDetailsStore (lazy full runs for relay tracks), ActivityFeedService, PendingSavesService,
                                ProjectRolesService
        i18n/                   locale.ts (resolve / switch / activeLocale), icu.ts (plural / select in TS), numbers.ts
        routing/                HoverPreloadingStrategy (preloads a lazy route when a rail item is hovered/focused)
        charts/                 echarts.ts (registered modules), palette.ts
        ui/                     relay/ (Relay components, see below), StatusTag, Actor, PrincipalPicker, MarkdownViewer,
                                JsonView, ArtifactViewer, EmptyState (renders bo-empty), ConfirmService, NotifyService,
                                ThemeService, DensityService, A11yPatchService, clipboard, pipes
      features/<feature>/
        public-api.ts           the only entry point other features may import (runs, ci, workflows, inbox)
        pages/                  routed components
        components/             feature-local components (drawers, editors, tables)
        data/                   pure logic + specs
```

Features: `auth` (login, forbidden), `account`, `admin` (users, groups, roles, grants, platform settings,
audit), `dashboard` (the Home page), `inbox`, `projects` (incl. CI and Members tabs), `workflows` (canvas designer +
shared canvas), `runs`, `ci` (executions), `library` (templates / interfaces / skills), `models`,
`evaluation/suites`, `evaluation/benchmarks`, `costs`, `settings`, `connections` (ADR 0030, ADR 0035:
Administration → Connections incl. personal-connection metadata, the project Connections tab with the system
connection and list, My account → Connections (personal), the designer's Jira connection section with "Act as").

Navigation (`layout/navigation.ts`): Home, Your turn (`/inbox`), Projects, Runs, CI, Library (Templates /
Interfaces / Skills), Models, Evaluation (Suites / Benchmarks), Costs, and at the bottom Administration
(Users, Groups, Roles, Grants, Platform settings, Audit log) and Settings. Each entry carries the same
access rule as its route; `visibleNavigation()` drops entries the user may not open and groups left
without children; a group links to its first visible child (`navTarget`). `/` and `/dashboard`
redirect to `/home`. The shell is described under "Relay design language" below.

## npm scripts

| Script | What it does |
|---|---|
| `npm start` | `ng serve` on http://localhost:4200 with `proxy.conf.json` (backend expected on :8080) |
| `npm run build` | `lint:deps`, `i18n:check`, the production build to `dist/back-office-web/browser` (raw budgets: initial 1.5 MB warn / 1.8 MB error), then `check:bundle` |
| `npm run check:bundle` | initial JS + CSS gzip <= 300 kB (follows static imports from `index.html`) |
| `npm run manual:link` | links `../docs/manual` to `.manual` (served at `/manual/`, translations at `/manual/pl/`; run by start, build, test) |
| `npm run i18n:merge` | merges `src/locale/<lang>/*.json` into `public/i18n/messages.<lang>.json` (run by start, build, test) |
| `npm run i18n:check` | translation gate (part of `build`): custom ids, missing / stale / duplicate translations, placeholders, Polish plurals, untagged text; `-- --area <a>` lists one area, `-- --reuse` skips the extraction |
| `npm run test:scripts` | `node --test` of the i18n tooling on fixtures (part of `test:ci`) |
| `npm run check:contrast` | WCAG contrast of every text/glyph token pair in light and dark (fails below 4.5:1 / 3:1) |
| `npm run build:dev` | development build (source maps, no optimisation, no dependency check) |
| `npm run watch` | development build in watch mode |
| `npm test` | Vitest in watch mode |
| `npm run test:ci` | Vitest single run (`ng test --watch=false`) |
| `npm run lint:deps` | dependency-cruiser layer rules (about 10 s; also part of `build`) |
| `npm run api:types` | regenerate `src/app/core/api/schema.d.ts` from `../docs/api/openapi.yaml` |
| `npm run format` / `lint:format` | Prettier write / check on `src/**/*.{ts,html,less}` |
| `npm run e2e:smoke` | browser smoke run against a running dev server (see "E2E smoke") |

`.npmrc` sets `legacy-peer-deps=true`: npm 10.9 crashes (`Cannot read properties of null (reading
'edgesOut')`) while resolving the optional `vitest` peer of Vite devtools pulled in by `@angular/build`.
`npm ci` honours `.npmrc`, so Docker builds behave the same.

## Generating API types

The OpenAPI file is the contract. Never edit `schema.d.ts` by hand.

```bash
npm run api:types
```

`core/api/types.ts` re-exports the schemas with short names (`Project`, `WorkflowRun`, ...) and a few
enum arrays used by selectors (`RUN_STATUSES`, `NODE_KINDS`, `TASK_EXECUTORS`, ...). `openapi-typescript`
treats properties with a `default:` as non-optional, so for example `WorkflowDefinitionInput.enabled`
and `WorkflowNode.maxVisits` are required; build objects accordingly (`normalizeDefinition()` fills them).

Services per tag: `auth` + `tokens` (`AuthApi`: me, preferences, config, logout, session, personal tokens), `admin` + `audit` + lookups (`AdminApi`), `members` (`MembersApi`), `projects` (+ CI entrypoints, tokens, snippet), `workflows` (+ palette, validate,
expression validate, duplicate, start run), `runs` (+ manual signals), `ci` (executions), `task-library`
(interfaces, templates, resolve, skills), `approvals`, `model-profiles`, `task-suites`, `benchmarks`,
`usage`, `settings`, `dashboard`.

When the contract changes:

1. Regenerate types. The compiler points at every usage that no longer matches.
2. Update the relevant `core/api/<tag>.service.ts` (thin wrapper around `HttpClient`, one method per
   operation, query parameters built with `params()` from `api-base.ts`).
3. Update the feature that renders the data.

## Authentication (OIDC and local accounts through the backend)

The browser never holds a token (ADR 0016, `docs/architecture/08-identity-and-access.md`). The API is
the backend-for-frontend: it runs the OIDC code flow against Keycloak (or any provider), keeps the
session server-side and sets the `HttpOnly` cookie `BO_SESSION`. Everything is same-origin (dev proxy,
nginx), so the UI sends no credentials of its own and needs no CORS.

```
app start ── provideAppInitializer ── IdentityService.load() ── GET /api/v1/me
   200 → identity set (status 'authenticated') → routes render
   401 → status 'unauthenticated' → authGuard → /login?returnTo=/the/page
   0/5xx → status 'unreachable' → /login shows "cannot reach the server" + Try again
/login ── "Continue with <provider>" ── location.assign(loginUrl + '?returnTo=' + path)
   → /oauth2/authorization/oidc (backend) → Keycloak → /login/oauth2/code/oidc (backend) → returnTo
Sign out ── POST /api/v1/auth/logout ── location.assign(logoutUrl ?? '/login')
```

- `AuthConfigService` reads `GET /api/v1/auth/config` (public, cached): `loginUrl`, `providerName`,
  `registration`, `accountUrl`, `methods {local, oidc}`, `passwordPolicy`. Without it the Spring
  default `/oauth2/authorization/oidc` is used. Loading it also sets the `XSRF-TOKEN` cookie that the
  local sign-in POSTs need, so every public auth page loads it first.
- `/login` (`features/auth/pages/login-page.component.ts`) is a split layout (`AuthShellComponent`:
  product mark, one line of copy and a static relay-track illustration drawn from the tokens; the form
  card on the right). It shows only the available methods: e-mail + password (`methods.local`), a
  divider and "Continue with <provider>" (`methods.oidc`); with OIDC only the provider button is the
  primary action. Messages for `?error=`: `not-invited`, `deactivated`, `login-failed`,
  `session-expired` (texts in `features/auth/data/login-errors.ts`; unknown codes show a generic
  message, never the raw code). `publicOnlyGuard` sends signed-in users to `returnTo`.

### Local sign-in (ADR 0021)

The password and code steps are a small state machine, `LoginFlow`
(`features/auth/data/login-flow.ts`, signals only, unit-tested with fake APIs and a fake clock);
`LocalAuthApi` (`core/api/local-auth.service.ts`, kept out of `AuthApi` so it stays in the lazy
chunks) sends the requests as `probe()`s, so their 401 is a form error and never "session expired".

```
password ──POST /auth/login──▶ AUTHENTICATED ─────────────▶ /me, then returnTo
   │                           PASSWORD_CHANGE_REQUIRED ──▶ /change-password?forced=1&returnTo=
   │                           MFA_ENROLMENT_REQUIRED ────▶ /setup-mfa?forced=1&returnTo=
   │                           MFA_REQUIRED {mfaToken} ───▶ code step ──POST /auth/login/mfa──▶ AUTHENTICATED
   │                                                          │ 401: the token is consumed → back to the
   │ 401 → "The e-mail address or password is not correct"    │ password step, e-mail kept, "That code did
   │ 429 → countdown from Retry-After, submit disabled         │ not work. Sign in again."
   │ 0   → "Back Office cannot reach the server"               │ 429 → countdown, stays on the code step
```

- Every failed step is the same 401 `invalid-credentials` (no enumeration); the UI never guesses a
  reason (lockout is not revealed either). Retry-After is read as seconds or an HTTP date.
- The code step uses `bo-code-input` (`core/ui/relay/code-input.component.ts`: 6 cells, auto-advance,
  paste / one-time-code autofill of the whole code, Backspace steps back, `aria-label` per cell) and
  submits on the sixth digit; "Use a recovery code instead" switches to a text field.
- The password field (`PasswordFieldComponent`) has show/hide (`aria-pressed`) and a caps-lock hint
  inside the field (no layout shift). The card hangs from a fixed offset, so switching steps or
  showing an error never moves it.
- **Restricted sessions**: after `PASSWORD_CHANGE_REQUIRED` / `MFA_ENROLMENT_REQUIRED` the session only
  reaches `/me`, `/me/security`, logout and the endpoints that lift the restriction. The interceptor
  answers any `403` with `code: password-change-required` / `mfa-enrolment-required` by routing to
  `/change-password` / `/setup-mfa` (`?forced=1&returnTo=<current page>`, no notification), which also
  covers a reload in a restricted session: `/me` succeeds, the shell's first API call returns the 403
  and routes. Both pages live outside the shell (`authGuard` only), so nothing else is loaded.
- `/change-password`: forced mode needs no current password; live policy hints (`PasswordHintsComponent`
  + `data/password-rules.ts`: length meter, "not a common password", "not your e-mail name"), repeat
  field. Afterwards `/me/security` decides: `mfaRequired && !mfaEnabled` → `/setup-mfa`, else `returnTo`.
- The hint rules mirror the backend policy (`PasswordPolicy.commonVariants`): lower case, leading and
  trailing digits/symbols stripped, leetspeak undone (0→o 1→i 3→e 4→a 5→s 7→t @→a $→s !→i), a
  repeated unit reduced (`dragondragon`); the bundled list (`data/common-passwords.ts`) is a short
  excerpt, the backend checks the full 10 000 and its field errors are shown under the field.
- `/setup-mfa`: `POST /me/mfa/totp` → QR (`qrSvg` rendered through an `<img>` data URL, never as
  markup), the key grouped by four with copy, confirm with a code (`PUT`), then the recovery codes
  shown once with "Copy codes", "Download .txt" (`data/recovery-codes.ts`) and an "I saved these
  codes" checkbox before continuing. The same `MfaEnrolmentComponent` / `RecoveryCodesComponent`
  (exported from `features/auth/public-api.ts`) run in a side panel on the account page.
- `/set-password/:token` (public): `GET /auth/password/reset/{token}` first (e-mail, purpose
  SET_INITIAL / RESET, expiry, policy; 404 → "This link does not work any more"), then the new
  password; the answer signs in (`AUTHENTICATED`, or the restricted states) or, with MFA enabled,
  hands the step token in memory (`PendingMfaService`, never in the URL) to `/login`, which opens on
  the code step.
- The command palette offers "Change my password" and "Set up MFA" (both open
  `/account?section=security`, the latter with `setup=mfa`) when local sign-in is enabled.
- E2E: `node e2e/local-login-ui-check.mjs` drives the whole flow through the UI (admin invites a LOCAL
  user in the admin UI, set-password link, password sign-in, MFA enrolment with TOTP computed in the
  script, a wrong code, password + code); `SHOTS=1` writes the Relay screenshots of each step.
- `returnTo` is sanitised by `safeReturnTo()` (same-origin path only, never `/login`), so a crafted link
  cannot turn the login into an open redirect. The backend must validate it too.
- Full-page navigations go through the `BROWSER_NAVIGATION` token (`assign`, `currentPath`) so tests
  can observe them.

### HTTP rules (`core/http/http-error.interceptor.ts`)

`provideHttpClient(withFetch(), withXsrfConfiguration({cookieName: 'XSRF-TOKEN', headerName:
'X-XSRF-TOKEN'}), withInterceptors([httpErrorInterceptor]))`. For requests under `/api/v1`:

| Case | Behaviour |
|---|---|
| any GET | sent with `cache: 'no-store'` (fetch backend): another account never sees cached data |
| success | `SessionExpiryService.touched()` (the server extended the session) |
| 401 | session-expired notice (below); never a redirect. Requests marked `probe()` (`/me` bootstrap, auth config, logout, pickers, background role lookup) are left to the caller |
| status 0 | error notification "Connection to the server lost"; the session is never ended because of the network |
| 403 | error notification "Access denied" with the problem `detail` and `Missing permission: <permission>` (from the problem's `permission` / `projectId` extension); no redirect. Exception: `code: password-change-required` / `mfa-enrolment-required` (restricted local session) routes to `/change-password` / `/setup-mfa` without a notification |
| other | error notification with the problem detail, unless the request is `silent()` (the caller shows field errors) |

CSRF: Spring Security's SPA mode sets the readable `XSRF-TOKEN` cookie; Angular echoes it as
`X-XSRF-TOKEN` on POST/PUT/DELETE of relative URLs. Nothing to do per request.

## Identity service, guards and directives

`IdentityService` (`core/auth/identity.service.ts`) holds `me` (the `Me` of `/me`) as a signal and
answers permission questions through pure functions in `core/auth/permissions.ts`:

| API | Meaning |
|---|---|
| `me()`, `user()`, `status()`, `isAuthenticated()` | the signed-in identity |
| `can(p)` / `can([p, q])` | platform permission (array = any of) |
| `canInProject(p, projectId)` | project permission from `me.projects[].permissions`; without an id: "in any project" |
| `allProjects()` | holder of `admin.projects.all` (every project permission except `ci.*`) |
| `projectRoles(projectId)`, `projectsWith(p)` | roles / project ids |
| `allows({ permission?, projectPermission? }, projectId?)` | route rule (either one grants access) |
| `login(returnTo?)`, `logout()`, `refresh()` | navigation to the backend, reload of `/me` |

The UI decides only visibility; the backend checks every use case. Reload `/me` with `refresh()` after
changes of the caller's own access (creating a project, own memberships, own groups, grants, roles).
For a loaded project prefer its `myPermissions` (`projectCan(identity, project, p)` in
`core/auth/project-access.ts`) because it is fresher than `/me`.

Routes (`app.routes.ts`): the shell has `canActivate: [authGuard]` and `canActivateChild: [sessionGuard,
pendingSavesGuard, permissionGuard]`. Access rules live in route `data`:

```ts
{ path: 'admin/users', data: { permission: 'admin.users.view' }, loadComponent: ... }
{ path: 'projects/:projectId', data: { projectPermission: 'project.view' }, ... }      // checked on :projectId
{ path: 'costs', data: { permission: 'usage.view', projectPermission: 'project.usage.view' } } // either
```

A refused route renders `/forbidden` with `skipLocationChange` (the address bar keeps the refused URL);
it never falls back to the dashboard. Routes whose project is known only after loading (e.g.
`/workflows/:id/edit`, `/runs/:id`) check the loaded entity in the page (read-only designer, hidden run
buttons); the API answers 403 for projects the user cannot see.

Templates:

```html
<button *boCan="'admin.users.manage'" nz-button (click)="invite()">Invite</button>
<button *boCan="'models.manage'; else readOnly">Edit</button>
<button *boCanInProject="{ permission: 'workflow.edit', projectId: p.id, projectPermissions: p.myPermissions }">
```

Forms without the permission are shown read-only with `<form [boReadonly]="!canEdit()">`
(`core/forms/readonly-form.directive.ts` disables every control of a reactive or template-driven
form; NG-ZORRO renders the disabled look) and the save button is not rendered. Rule of thumb: an
action the user cannot perform is not rendered at all; a list the user can see stays visible.

### Adding a permission-aware page

1. Add the route with `data: { permission }` or `data: { projectPermission }` (the guard does the rest)
   and, if it is navigation, an entry in `layout/navigation.ts` with the same rule.
2. In the page inject `IdentityService` and derive `canX = computed(() => identity.can('x'))` (or
   `projectCan(...)` for project data). Wrap mutation buttons in `@if (canX())` or `*boCan`; use
   `[boReadonly]` for editors.
3. Mark save requests `silent()` in the API service and report failures with
   `reportFormError(notify, form, err, 'Could not save ...')` (field errors onto the controls, the rest
   in the notification). Wrap saves in `pendingSaves.track(...)`.
4. Editors implement `HasUnsavedChanges` and get `canDeactivate: [unsavedChangesGuard]` on the route.
5. Test the pure parts (permission mapping, form models) in `data/*.spec.ts`.

## Session expiry (`core/auth/session-expiry.service.ts`)

Port of the reference `sessionExpiry.ts`. The server session has an idle timeout
(`me.idleTimeoutSeconds`, extended by every request) and an absolute limit (`me.absoluteExpiresAt`).

- The idle deadline is *last successful API response + idle timeout*. User input (`mousedown`,
  `keydown`, `wheel`, `touchstart`) counts as activity: if the last server contact is older than a
  minute (or a quarter of the timeout), one `GET /api/v1/me` extends the server session. There is no
  timer-driven polling; an idle browser lets the session lapse exactly like the server does.
  (`GET /api/v1/auth/session` does not extend the session and is not used for this.)
- 60 s before the earlier of both deadlines a warning with a countdown appears. For the idle deadline it
  offers "Stay signed in" (`GET /me`); near the absolute limit it only asks to save the work.
- At the deadline, or on any 401, the persistent notice "Your session has expired" appears with
  **Sign in again** (OIDC login with `returnTo` = current page) and **Stay on this page** (closes the
  notice; the screen stays so unsaved work can be copied). While expired, `sessionGuard` refuses every
  in-app navigation and shows the notice again; SSE stops reconnecting.
- Warning and notice share one notification key (`bo-session`); the templates with the buttons live in
  `SessionNoticesComponent`, rendered once in the root component.

## Screen notifications and forms

- `NotifyService` (`core/ui/notify.service.ts`): at most one screen notification (one fixed key,
  a new one replaces the previous), errors stay until closed, success/info close after 4.5 s, the
  notification is closed when the path changes (query-only changes such as tabs keep it). The
  session notice has its own key and is unaffected. Short confirmations still use
  `NzMessageService.success`.
- `core/forms/server-errors.ts`: `applyServerErrors(form, err)` maps `errors[{field, message}]` of an
  RFC 9457 problem to `control.setErrors({server: message})` (`a[0].b` -> `a.0.b`); unknown fields are
  returned and shown in the notification (`summarize`). Show `control.errors?.server` through an
  `nzErrorTip` template (`<ng-template #tip let-control>{{ control.errors?.server || 'Required' }}</ng-template>`).
- `PendingSavesService` counts saves in flight (`track(obs)`); `pendingSavesGuard` refuses navigation
  meanwhile (30 s ceiling for lost requests).
- `unsavedChangesGuard` asks "Discard unsaved changes?" for components implementing
  `hasUnsavedChanges()`: workflow designer, template editor (drawer on the templates page), user,
  group, role, grants and platform-settings editors.

## Administration (`features/admin`)

| Page | API | Notes |
|---|---|---|
| Users `/admin/users` | `GET/POST /admin/users` | search, status, group filters, server paging; invite drawer (groups, platform roles, project memberships) shows the `signInUrl` to copy |
| User `/admin/users/:id` | `GET/PUT /admin/users/{id}`, `activate`, `deactivate`, `effective-permissions`, `sessions` | deactivate confirms and is disabled for yourself; effective permissions table System / Groups / User / Effective / Source (`data/effective-permissions.ts`); sessions with "Terminate all sessions" |
| Groups `/admin/groups`, `/admin/groups/new`, `/admin/groups/:id` | `/admin/groups` | members via `PrincipalPickerComponent` (`/users/lookup`), platform roles, external (OIDC) group |
| Roles `/admin/roles`, `/admin/roles/new?scope=`, `/admin/roles/:key` | `/admin/roles`, `/admin/permissions` | permission matrix per module of the role's scope (`data/permission-matrix.ts`); built-in badge, delete disabled for built-ins |
| Grants `/admin/grants` | `GET/PUT /admin/grants` | level SYSTEM / group / user; roles + per-permission `(inherit)` / Allow / Deny (`data/grants-model.ts`: inherit = no grant) |
| Platform settings `/admin/settings` | `/admin/settings` | registration mode, allowed domains, IdP group sync, PAT max days |
| Audit log `/admin/audit` | `GET /admin/audit` | filters (time range, actor, operation, entity, project, result, correlation id), keyset "Load more" (`nextCursor`), detail drawer with JSON and "Show all with this correlation id" |

Viewing needs `admin.users.view` (users, groups), `admin.roles.manage` or `admin.users.view` (roles,
grants), `admin.settings.manage`, `admin.audit.view`; changing needs `admin.users.manage` /
`admin.roles.manage`.

## Project members, account, gates

- **Members tab** (`projects/components/project-members-panel.component.ts`): list for `project.view`;
  with `project.members.manage` add a user or group (lookup pickers), change the role, remove (confirm).
  409s (last owner, duplicate) show the problem detail. Project roles come from
  `ProjectRolesService` (`/admin/roles?scope=PROJECT`, falling back to the built-in keys
  owner/maintainer/developer/approver/viewer when the caller may not read roles).
- **My account** `/account`: one scrolling page with a sticky section index (`?section=` deep-links):
  profile and the current session, preferences (theme SYSTEM/LIGHT/DARK, density, rows per page,
  default project; saved with `PUT /me/preferences`, density per browser), **security**
  (`SecuritySectionComponent`, from `/me/security`: sign-in method and last sign-in, password with
  "Change password" in a side panel, two-factor on/off with "Set up two-factor authentication" (side
  panel, `?setup=mfa` opens it), "Create new recovery codes" and "Turn off" behind a password
  confirmation dialog that states the consequence (turning off is disabled with the reason when the
  platform requires MFA), recovery codes left, recent sign-ins; OIDC-only accounts see a link to the
  provider's account page instead), personal access tokens (create with name, expiry days up to the
  platform maximum, optional permission subset; the plaintext is shown once with copy; revoke) and
  my platform permissions and project roles. There is no self-service session list in the API.
- **Theme**: `ThemeService` applies `me.preferences.theme` by setting `html[data-theme=light|dark]`
  (SYSTEM follows `prefers-color-scheme`) and `html.bo-dark`; the tokens switch and NG-ZORRO follows
  (one stylesheet). `theme.set()` saves the preference; `toggle()` backs the palette action and `Alt T`.
  Density (comfortable / compact) is per browser (`DensityService`, `html[data-density]`).
- **Gates**: the designer's HUMAN_GATE panel has `GateRulesEditorComponent` (approver project roles,
  users, accountable role limited to the selected roles, forbid self-approval, checklist items id /
  text / required; rules in `workflows/data/gate-rules.ts`, also checked by the local validator). The
  gate panel (`app-approval-detail-drawer`, a Relay side panel) shows why you (accountable role, approvers,
  self-approval rule, run starter) and the checklist with required items first; Approve is enabled only
  when every required item is ticked and `canDecide` is true, otherwise `cannotDecideReason` is shown
  (`inbox/data/approval-gating.ts`, the reason is written under the buttons). Lists never decide: the
  primary action (and `a`) opens the panel on the checklist. Decisions are optimistic (the gate leaves
  the queue at once and comes back if the request fails); the API cannot undo a decision, so there is
  no undo. Decided approvals show `decidedBy`; runs show `startedBy`; projects `createdBy`;
  workflows `updatedBy`.
- **Read-only designer**: without `workflow.edit` on the workflow's project the designer hides the
  palette, templates, layout, validate, duplicate and save, and the canvas and panels are read-only.

## Guardrails (`npm run lint:deps`)

`.dependency-cruiser.cjs`:

- `core/` never imports `features/`, `layout/` or the bootstrap (`app.*.ts`);
- features never import `layout/` or the bootstrap;
- a feature imports another feature only through its `public-api.ts` (currently `runs`, `ci`,
  `workflows`, `inbox`); shared building blocks belong in `core/`;
- no deep imports of `node_modules/**/internal`; no import cycles.

It runs as the first step of `npm run build` (about 10 s) and therefore in the Docker build and CI.

## Live updates (SSE)

`EventStreamService` opens a plain `new EventSource('/api/v1/events')`: same origin, so the browser
sends the `BO_SESSION` cookie; there is no token in the URL. The service:

- connects only while signed in and the session has not expired (effect on `IdentityService` and
  `SessionExpiryService`), and stops reconnecting as soon as either changes;
- listens to every `DomainEventEnvelope.type` as a named SSE event and to unnamed `message` events;
- reconnects with exponential backoff (1 s to 30 s) and passes `lastEventId` as a query parameter so the
  server can replay (same semantics as `Last-Event-ID`);
- exposes `latest` / `state` / `received` signals and `all()`, `of(...types)`, `forRun(id)`,
  `forBenchmark(id)` observables.

`EventSource` does not expose the status code of a failed connection, so a 401 on the stream does not
raise the session notice by itself; the next REST call does (and then the stream stops). The backend is
expected to filter events by the caller's project visibility.

Consumers debounce and reload from the REST API rather than patching state from event payloads, so the
UI never depends on payload shapes beyond `runId`, `benchmarkId`, `ciExecutionId` and the `RunnerLog`
fields (`at`, `nodeId`, `level`, `message`). Event types are listed in `EVENT_TYPES`; `RUN_EVENT_TYPES`
is the subset that changes a run (`WorkflowRunStarted`, `Node*`, `RunWaitingForHuman`,
`RunWaitingForCi`, `CiSignalReceived`, `ApprovalDecided`, `WorkflowRunFinished`). Pages also poll at a
slow cadence while something is active and the stream is disconnected.

## Adding a feature

1. Add `core/api/<tag>.service.ts` if the tag is new; export it from `core/api/index.ts`.
2. Create `features/<name>/pages/<name>-page.component.ts` (standalone, `ChangeDetectionStrategy.OnPush`,
   state in `signal()`s, dependencies via `inject()`). Use inline templates for small pages.
3. Register a lazy route in `app.routes.ts` (`loadComponent`) with its access rule in `data` and, if it
   is navigation, an entry in `layout/navigation.ts` (same rule; icon name from the
   `@ant-design/icons-angular` outline set). See "Adding a permission-aware page".
4. Route parameters and query parameters arrive as component `input()`s (`withComponentInputBinding`).
   Read them inside an `effect()`; use `untracked()` around the loading code so unrelated signals do not
   retrigger it. Never read a required input in the constructor body directly.
5. Put pure logic (mappings, validation, chart options) in `features/<name>/data/*.ts` with a `*.spec.ts`
   next to it. Component tests are optional; logic tests are expected.
6. Use the shared pieces: the Relay components (`bo-baton` for every status, `bo-page-header`,
   `bo-filter-bar` + `urlFilters`, `bo-side-panel`, `bo-action-bar`, `bo-empty`, `bo-skeleton`,
   `bo-provenance`, `bo-cost`; see `core/ui/relay/README.md`), `app-actor` (who did it),
   `app-principal-picker`, `app-empty-state`, `app-markdown`, `app-json-view`, `app-artifact-viewer`, `ConfirmService.danger()`
   for destructive actions, `NotifyService`, the `money`, `tokens`, `duration`, `timeAgo` pipes.
7. Every button must call the API per contract and be permitted for the user (no dead buttons); show
   `nzLoading` while a request is in flight and a skeleton while the first load runs.
8. Need something from another feature? Import it from that feature's `public-api.ts` (add it there),
   or move it to `core/` when it is generic. `npm run lint:deps` enforces this.

## Workflow designer

Routes: `/projects/:projectId/workflows/new` and `/workflows/:workflowId/edit` both load
`features/workflows/pages/workflow-designer-page.component.ts`. Layout: palette (left), canvas
(centre), property panel (right), toolbar (name, validation state, Designer/JSON switch, templates,
auto-layout, fit view, Validate, Duplicate, Save). `/runs/:runId` reuses the canvas read-only.

### Ownership and data flow

```
WorkflowDefinitionInput (signal `def` on the page)  <-- single source of truth
   |  effect                                              ^
   v                                                      | outputs: connected / nodeMoved /
WorkflowCanvasComponent  --sync-->  ngx-vflow Node[]/Edge[] deleteRequested / nodeSelected / dropped
   |                                                      |
   +-- NodeShell + per-kind CustomNodeComponent           |
NodePropertyPanelComponent --nodeChange--> page.updateNode() -> replaceNode() -> def
```

- The page owns the definition; every mutation goes through the pure helpers in
  `features/workflows/data/graph-model.ts` (`createNode`, `replaceNode`, `removeNode`, `moveNode`,
  `duplicateNode`, `upsertEdge`, `removeEdge`) and `commit()` (which records undo history and marks the
  definition dirty). `patchDef()` is for name/description/triggers/enabled.
- `WorkflowCanvasComponent` keeps a `Map<id, ComponentNode>` and `Map<id, Edge>` so vflow keeps its
  internal models (positions, handles) across renders: existing objects get their `data`/`point`
  signals updated, only added/removed ids create a new array. **Never rebuild the arrays on every
  change** or the canvas flickers and loses selection.
- Node -> vflow: `{ id, type: NODE_COMPONENTS[kind], point: signal(position), data: signal(CanvasNodeData) }`.
  `CanvasNodeData` (built by `buildNodeData`) carries the node, its ports, template metadata from the
  palette, run status, taken ports and issue counts.
- Edge -> vflow: `{ id, source: from, target: to, sourceHandle: 'port:<fromPort>', targetHandle: 'in',
  type: 'template', edgeLabels: { center: label ?? fromPort } }`. Handle ids are the port names
  prefixed with `port:` (`portHandleId` / `portFromHandleId`); the single input handle is `in`.
- `(connect)` from vflow gives `{source, target, sourceHandle}`; the canvas maps it to
  `{from, fromPort, to}` and the page calls `upsertEdge`, which enforces **one edge per
  (from, fromPort)** (a new connection from a used port replaces the old target). START/NOTE can never be
  targets; END/NOTE never sources (validator + local validation).
- Positions: vflow emits `nodesChanges.position` (`{id, point}` per drag frame) and `nodeDragEnd`;
  both go through `moveNode`, which rounds to integers and updates `node.position` (no-op when
  unchanged, so the per-frame events are cheap). Note that vflow re-orders its DOM (a clicked node is
  raised), so tests must find nodes by `aria-label`, never by index.
  The viewport (`{x, y, zoom}`) is debounced into `def.canvas` without marking the definition dirty and
  restored on load (`initialViewport`); otherwise the canvas fits the graph.
- Palette drag: items set `application/x-backoffice-node` JSON (`{kind, templateKey}`); the canvas
  converts the drop point with `documentPointToFlowPoint`. Clicking a palette item adds the node at
  the viewport centre.
- Delete / Backspace on the focused canvas deletes the selected nodes and edges.

### Validation

`features/workflows/data/workflow-validation.ts` runs on every change (one START, at least one END, ids
are slugs, template picked, branches / options / signal present and slugs, edges reference existing
nodes and ports, one edge per port, unconnected START/branches are errors, unconnected
success/options are warnings, reachability). The debounced server result of
`POST /workflows/validate?projectId=` is merged (`mergeValidation`, de-duplicated). `mapValidation`
groups issues by `nodeId` / `edgeId` / global; `issueCounts` feeds the badges on nodes and the red
dashed edges. Clicking an issue in the right panel selects the node.

Decision expressions are validated live per branch with `POST /workflows/expressions/validate`
(`ExpressionEditorComponent`, 400 ms debounce; a helper menu inserts `palette.stateFields`).

### Auto-layout and JSON

`features/workflows/data/auto-layout.ts`: BFS levels from START (`bfsLevels`), x = level, y = order
within the level; unreachable nodes go one column after the last level, NOTE nodes one further.
Applied by the toolbar button, and automatically when a definition has no usable positions
(`needsLayout`: all at the origin or overlapping), e.g. after pasting JSON or loading a template.

The JSON view edits the whole `WorkflowDefinitionInput` two-way; invalid JSON is kept in the editor
with an error until it parses, then `normalizeDefinition` fills defaults and edge ids.

### Adding a node kind

1. Add the kind to the contract (`NodeKind`, the kind-specific section on `WorkflowNode`) and run
   `npm run api:types`.
2. `graph-model.ts`: add `NODE_KIND_META[kind]` (title, icon, colour, default ports, `hasInput`),
   extend `portsFor()` if ports derive from the node config, extend `createNode()` defaults and, if the
   node has ports that can disappear, `replaceNode()`.
3. `components/workflow-nodes.ts`: add a `CustomNodeComponent` extending `WorkflowNodeBase` that wraps
   `<app-node-shell>` (handles, header, badges, status colours come for free) and register it in
   `NODE_COMPONENTS`.
4. `components/node-property-panel.component.ts`: add a `@case` with the kind-specific form and a
   `patchX()` helper.
5. `components/node-palette.component.ts`: add the kind to `CONTROL_ORDER` (titles/ports from
   `GET /workflows/palette` override the local metadata when present).
6. `workflow-validation.ts`: add the structural rules; `run-detail-page` needs nothing (it renders any
   kind through the same canvas).

### Schema forms

`features/workflows/data/schema-form.ts` turns a JSON Schema (`PaletteTask.configSchema` for task
overrides, `ControlNodeDescriptor.configSchema`) into field specs: `string` -> input (textarea when
`format` is markdown/multiline/template/json, `x-multiline: true`, `maxLength > 200`, or the key looks
like a template/prompt/schema), `enum` -> select, `boolean` -> switch, `integer|number` -> input-number,
`array` of strings -> tags, everything else -> JSON textarea. Keys not in the schema are editable as one
JSON object. `x-order` controls field order. Without a schema the property panel falls back to a JSON
textarea for `overrides.config`.

## Task Library pages

`/library/templates`, `/library/interfaces`, `/library/skills` share `LibraryNavComponent` and follow
the same shape: table + drawer editor, built-in badge, delete disabled for built-ins (shadow them per
project instead).

- Templates (`templates-page` + `template-editor-drawer`): the draft is a `TaskTemplateInput` signal;
  the right side shows the **resolved preview** from `GET /task-templates/resolve/{key}?projectId=`
  mapped by `data/resolved-preview.ts` (`toPreview`: lineage breadcrumb, effective prompts, skills,
  interface check with problems in red; `draftSkillDelta`: unsaved skill changes; `parentCandidates`:
  excludes the template and its descendants from the "extends" select). The preview reflects the saved
  version and refreshes after each save (`savedVersion`). `?key=` deep-links a template (used by the
  designer property panel). The "extend" row action pre-fills a PROJECT-scoped child with the same key.
- Interfaces: inputs/outputs slot tables (`name`, `kind`, `required`, `description`) and ports tags.
- Skills: markdown editor with a live preview (frontmatter stripped) and scope selector.

Permissions (`data/library-access.ts`): GLOBAL items need `library.manage`, PROJECT items
`library.project.manage` on their project (the scope selector offers only scopes the user may write).
Items the user may not change open read-only (`[boReadonly]`, no save or delete); "extend" is offered
with `library.project.manage` in some project; interfaces are global only. The template drawer asks
before discarding edits (also on navigation, through the page's `unsavedChangesGuard`).

## Project CI tab

`features/projects/components/project-ci-panel.component.ts` with pure helpers in
`data/ci-tokens.ts`: entrypoints editor (`GET/PUT /projects/{id}/ci-entrypoints`, validated with
`validateEntrypoints`, saved as a whole list), CI tokens (create modal is a small state machine
`closed -> naming -> created`; the plaintext is shown once with a copy button; revoke with
confirmation), the `.gitlab-ci.yml` snippet from `GET /projects/{id}/ci-snippet` with copy, and the
project's CI executions table. `?tab=ci` opens the tab directly. Entrypoints are editable with
`project.manage`; tokens are listed, created and revoked with `project.ci.manage`. Other tabs:
Workflows, Runs, Members (`?tab=members`), Settings (read-only without `project.manage`; the delete
button needs `project.manage` and `project.members.manage`, i.e. owner level).

## Run page

`/runs/:runId`: the header carries the baton, the provenance (started by, age, duration, cost with
tokens) and the full relay track (`buildTrack`: visited nodes in order, the current one marked, the
likely path ahead in outline; clicking a segment selects the step). The `waitingOn` banner opens the
gate side panel for `HUMAN_GATE` ("Review gate" when the user can decide) and offers a "Send signal
manually" form (`POST /runs/{id}/signals`) for `WAIT_FOR_CI`. The Timeline tab lists the node
executions with provenance and costs; the selected step shows its summary, error, artefacts
(`@defer`red viewer) and logs (this step / all, follow, live via SSE `RunnerLog`). The Canvas replay tab
renders `definitionSnapshot` read-only with node colours from the last `NodeExecution` status. Cancel
needs `run.cancel`, retry `run.start`, the manual signal `run.signal` on the run's project.

## Operations and governance (iteration 5)

| Screen | Route / place | API | Access |
|---|---|---|---|
| Runner fleet: pools, pool editor with the **policy ceiling**, "Add runner" (`?addRunner=<poolId>`: registration token shown once, chart values, `helm install` for `back-office-runner`), runners (revoke with consequences), agent jobs (filter bar, `?job=` side panel with effective policy and denied egress, cancel) | `/admin/fleet/pools`, `/pools/new`, `/pools/:poolId`, `/runners`, `/jobs` (`features/fleet`) | tag `fleet` (`FleetApi`) | view `fleet.view` (or `fleet.manage`), change `fleet.manage` |
| Operations: replicas, work items per status, oldest ready age, DEAD items with retry, SSE subscribers; refresh every 10 s, paused while the tab is hidden | `/admin/operations` (`features/operations`) | tag `operations` | `admin.settings.manage` |
| Sandbox policy editor `app-sandbox-policy-editor` (isolation, resources, timeout, host allowlist chips, tools allow/deny/ask, secrets, environment, caps, pool selector; shows the ceiling and marks what cannot be widened; rules in `fleet/data/policy.ts` mirror doc 10) | project tab "Sandbox" (`GET/PUT /projects/{id}/sandbox-policy`, effective view with "set by" badges), pool editor (ceiling), template editor section "Sandbox" (`sandboxPolicy`) | | `project.manage` / `fleet.manage` / library rules |
| Budgets (`features/budgets`): editor with spend, alert marker and forecast; project tab "Budget", platform budget on `/admin/settings?section=budget`, state in the Costs header, `budget` turn cards (ALERT/EXCEEDED for budgets the user can raise, `core/state/turn-queue.ts`) | | tag `budgets` (`BudgetsApi`) | `project.manage`, `admin.settings.manage` |
| Insights: human wait headline, lead time, CI wait, success, cost; bottleneck gate (`?node=` opens it in the designer when the API returns `workflowId`); weekly chart (lazy ECharts); success by template / model; gate decisions | `/insights` (rail after Runs) | `GET /insights/delivery` | `project.view` in some project |
| Provenance tab on run detail: attestations, decoded statement with key facts (`runs/data/provenance-statement.ts`), download `.intoto.jsonl`, Verify | `runs/components/provenance-panel` | tag `provenance` | run visibility |
| Account → Connect your IDE: MCP endpoint `<origin>/mcp`, PAT, Claude Code / Cursor / VS Code snippets, tools | `/account?section=ide` | | everyone |

Palette actions: Open fleet, Add runner, Open insights, Set project budget… (scope: projects you manage), Open operations.
E2E: `e2e/governance-mocks.mjs` serves these endpoints where the backend answers 404/405/5xx and adds
`fleet.view`/`fleet.manage` to an administrator's `/me`; `smoke.mjs` visits the new routes with it,
`e2e/governance-shots.mjs` writes the screenshots.

Bundle: `ng-zorro-antd/i18n` (with `core/time` and the `date-fns` barrel) is loaded by the app
initializer (`core/ui/nz-locale.ts`) and `ng-zorro-antd/modal` (with `@angular/cdk/drag-drop`) on the
first confirmation (`ConfirmService`), so neither is in the initial bundle any more.

## In-app help (the user manual)

The manual lives in `docs/manual/` (one Markdown file per chapter; `manual.json` lists groups,
chapters, slugs and anchors; an anchor is `<a id="..."></a>` right before its heading).
`npm run manual:link` (first step of `start`, `build`, `test:ci`) links it to `frontend/.manual`
(git-ignored; a junction on Windows, a copy where links fail) because the Angular build only copies
assets from inside the workspace; `angular.json` serves it at `/manual/<slug>.md` and
`/manual/manual.json`. The dev proxy does not touch `/manual`; nginx serves it with `no-cache` and
answers 404 for missing files (never the SPA fallback). Missing chapters show "This chapter is being
written."
In Polish the help reads `docs/manual/pl/` (same slugs and anchors, own `manual.json`), falling
back to the English file per chapter; `help-map.spec.ts` checks the topics against both manifests.

| Piece | Where |
|---|---|
| Topic map: screen -> `chapter#anchor`, URL -> topic (`helpTopicForUrl`) | `core/help/help-map.ts` (the ONE map; `help-map.spec.ts` fails when a chapter or anchor is not in `manual.json`) |
| Help panel: one section (anchor heading to the next heading of the same or higher level), "Open in manual" | `core/help/help-panel.component.ts`, `@defer`red in the shell, state in `HelpService.panel` |
| `bo-help-link` (`?` icon, or `variant="text"` with your words) | `core/help/help-link.component.ts` |
| Manual page `/help`, `/help/:chapter#anchor`: contents, chapter, previous / next, search across all chapters (fetched on the first search) | `features/help/pages/help-page.component.ts` |
| Markdown helpers: `x.md#y` -> `/help/x#y`, section extraction, search index | `core/help/manual-markdown.ts` (lazy chunks only) |
| Entry points | top bar `?` button and `F1` (help of the current route, else `/help`), avatar menu "Manual", palette "Open manual", "Help for this page", one entry per chapter and anchor |

To add help to a screen:

1. Pick or add a topic in `HELP_TOPICS` (`'project.ci': t('integrations-gitlab-ci', 'ci-token', 'CI tokens')`);
   the label is the accessible name "Help: CI tokens". Add the route pattern to `ROUTES` in the same
   file when the page is new, and a case to `help-map.spec.ts`.
2. Page header: `<bo-page-header help="runs" ...>` puts the `?` after the title. Elsewhere:
   `<bo-help-link topic="settings.jira" />`; inline hints: `<bo-help-link topic="project.ciVariables"
   variant="text">Full steps</bo-help-link>` (opens the panel, so one-time tokens on screen survive).
3. Empty states: `<bo-empty ... help="skills" guideLabel="How skills work" />` (the "one link to the guide").

The help code stays out of the initial bundle except `HelpService` and `manual.ts` (the topic map
is imported on the first help request; the panel, marked and DOMPurify load with it).

## Internationalisation

The UI speaks English (the source language) and Polish (ADR 0026). Angular's own i18n
(`@angular/localize`) with **runtime** translations: one build, the language is picked at start-up.
Terms: `docs/ui/i18n-glossary.md`.

```
main.ts ── resolveLocale(localStorage 'bo.locale', navigator.language)   core/i18n/locale.ts
   'pl' ── fetch /i18n/messages.pl.json ── loadTranslations() ── registerLocaleData(pl)
        ── import('./bootstrap') ── bootstrapApplication(App, { LOCALE_ID: 'pl' })
switch ── switchLocale('pl') ── localStorage 'bo.locale' ── location.reload()
```

- **Where the user switches**: avatar menu ("Language / Język"), My account → Preferences, and the
  "English | Polski" switch on the sign-in pages (`AuthShellComponent`). Without a stored choice a
  `pl*` browser gets Polish, everything else English. A missing or broken translation file falls back
  to English (console warning), never to ids.
- **Never import app code statically from `main.ts`**: templates and module-level `$localize`
  constants (navigation labels, route titles, `const LABELS = {...}`) are evaluated when their module
  loads, which must be after `loadTranslations()`. Everywhere else module-level `$localize` is fine.
- `LOCALE_ID` drives Angular's `date`, `number`, `currency` and `i18nPlural` pipes. Code outside DI
  uses `intlLocale()` / `activeLocale()` (`core/i18n/locale.ts`) and `formatNumber()` / `fixed()`
  (`core/i18n/numbers.ts`); never hard-code `'en-US'`. `timeAgo` ("5m ago" / "5 min temu"),
  `formatDuration`, `formatCost`, `formatTokens`, `monthName` follow the language.
- NG-ZORRO's own texts (pagination, pickers, empty tables) come from `pl_PL` / `en_US`
  (`core/ui/nz-locale.ts`, lazy).
- The manual follows too: `HelpService` reads `/manual/pl/manual.json` and `/manual/pl/<slug>.md`,
  falling back to the English file per chapter (and to the English section when a translated chapter
  lacks the anchor).

### Conventions (read before translating an area)

1. **Every message has a custom id** `<area>.<screen>.<name>`, lower camel case segments:
   `@@shell.topbar.help`, `@@auth.login.title`, `@@projects.detail.delete.title`. The **area** is the
   folder: `features/<name>/**` → `<name>` (`evaluation/suites` → `evaluation`), `core/**` → `core`,
   `layout/**`, `app.*.ts`, `main.ts`, `bootstrap.ts` → `shell`. An id is used in one place; reuse it
   only for exactly the same text (whitespace counts in templates: `i18n:check` reports
   `duplicate-source-id`).
2. **Templates**: `i18n="@@id"` on the element that holds the text, `i18n-<attr>="@@id"` for
   attributes: `placeholder`, `title`, `alt`, `aria-label`, `nzTitle`, `nzOkText`, `nzPlaceHolder`,
   component inputs such as `label`, `guideLabel`, `actionLabel`:

   ```html
   <h1 i18n="@@projects.list.title">Projects</h1>
   <input placeholder="Search projects" i18n-placeholder="@@projects.list.search" />
   <bo-empty title="No projects yet" i18n-title="@@projects.list.empty" />
   <p i18n="@@projects.card.repo">Repository {{ p.url }} on <strong>{{ p.branch }}</strong></p>
   ```

   Inline markup and interpolations become placeholders (`{$INTERPOLATION}`, `{$INTERPOLATION_1}`,
   `{$START_TAG_STRONG}…{$CLOSE_TAG_STRONG}`, `{$START_LINK}`); the translation keeps all of them.
   Use `<ng-container i18n="@@id">` when there is no element of its own. Ternaries with literals
   (`{{ ok ? 'Saved' : 'Failed' }}`) become `@if` blocks with one tagged element per branch, or a
   `$localize` in the component.
3. **TypeScript**: `$localize` with the id in the meta block and **named** placeholders:

   ```ts
   title: $localize`:@@projects.detail.delete.title:Delete ${p.key}:key:?`
   this.message.success($localize`:@@projects.detail.saved:Project saved`);
   readonly text = { close: $localize`:@@runs.detail.close:Close` }; // for [attr.x]="text.close"
   ```

   Unnamed `${x}` becomes `{$PH}`, `{$PH_1}`: always name them (`${x}:name:`).
4. **ICU plurals and selects**. In templates the ICU must be the **only** content of its i18n element
   (otherwise Angular extracts it under a generated id and the gate fails):

   ```html
   <span i18n="@@projects.card.workflows">{count, plural, =0 {no workflows} one {1 workflow} other {{{ count }} workflows}}</span>
   ```

   Start the ICU right after the opening tag and close the tag right after the last brace (Prettier
   then only wraps the cases); text or a line break between tag and ICU makes it "mixed". For
   longer markup prefer TypeScript: `$localize` does not evaluate ICU, so pass
   the message through `icu()` (`core/i18n/icu.ts`), which uses `Intl.PluralRules` of the UI
   language and supports `=N`, `#`, `select` and nesting:

   ```ts
   readonly label = computed(() =>
     icu($localize`:@@projects.card.workflows:{count, plural, one {# workflow} other {# workflows}}`,
       { count: this.workflows().length }));
   ```

   English keeps `one` / `other`; **Polish needs `one`, `few`, `many` and `other`** (the gate checks):
   `{count, plural, one {# workflow} few {# workflowy} many {# workflowów} other {# workflowu}}` →
   1 workflow, 2 workflowy, 5 workflowów (0, 5–21, 25… are `many`; 2–4, 22–24… `few`; fractions
   `other`).
5. **ARIA labels with values**: `aria-label="Remove {{ x }}"` binds the `ariaLabel` *property* (not
   reflected everywhere, e.g. jsdom). Use `[attr.aria-label]="removeLabel(x)"` with a `$localize` in
   the component instead.
6. **Not translated**: data from the API (names, statuses shown raw, problem `detail` messages),
   identifiers, permission keys, code, keyboard keys. Enum values shown to users go through a
   translated map (see `humanize()` / `statusLabels()` in `core/ui/status-tag.component.ts` for the
   common statuses). The permission catalogue and the built-in roles come from the server in
   English: render them through `permissionDescription()` / `roleName()` / `roleDescription()`
   (`core/i18n/catalog-labels.ts`, pipes in `catalog-labels.pipes.ts`), never `p.description` or a
   raw role key. Known keys get `@@core.permission.<key>` / `@@core.role.<key>`; unknown keys
   (custom roles) and texts an administrator changed keep the server text. A new permission or
   built-in role in the backend: add it to `catalog-defaults.ts`, `catalog-labels.ts`, the list in
   `catalog-labels.spec.ts` and `src/locale/pl/core.json`.

### Translation files

- `frontend/src/locale/pl/<area>.json`: a flat object `{ "<id>": "<Polish target>" }`, one file per
  area, ids of that area only (`shell.json`, `core.json`, `auth.json`, `help.json`, `projects.json` …).
  Targets keep exactly the placeholders of the English source (as `ng extract-i18n` writes them).
- `npm run i18n:merge` (first step of `start`, `build`, `test`, `test:ci`) merges them into
  `public/i18n/messages.pl.json` (generated, git-ignored), the file the browser fetches. Run it again
  after editing a partial while `npm start` is running (or `node scripts/i18n-merge.mjs --watch`).
- `src/locale/pending-areas.json` lists the areas that are not translated yet. Translating an area:
  remove it from the list, tag its templates and TypeScript, fill `src/locale/pl/<area>.json`, run the
  gate until it is clean.
- `src/locale/untranslatable.json`: words and phrases that are the same in every language (product
  names, protocols); the gate does not report text made only of them. A TypeScript string that is
  not UI text but looks like it: `// i18n-ignore` on the line (or the line before); a file without UI
  text: `// i18n-ignore-file`.

### The gate: `npm run i18n:check`

Part of `npm run build` (after `lint:deps`). It runs `ng extract-i18n --format json` (about 40 s) into
`.angular/i18n-extract/`, scans the sources with Angular's template parser and the TypeScript
compiler, prints messages / translated / missing / untagged per area and fails on:

| Problem | Meaning |
|---|---|
| `no-custom-id` | an `i18n` / `$localize` without `@@id`, or an ICU sharing its element with other content |
| `missing` | a source id without a Polish target (not reported for pending areas) |
| `placeholders` | the target's placeholders (`{$X}`, ICU variables, `{INTERPOLATION}` in ICU cases) differ |
| `plural-forms` | a Polish plural without `one`, `few`, `many` and `other` |
| `braces` | unbalanced `{ }` in a target |
| `stale` | a partial file holds an id the source no longer has |
| `duplicate`, `wrong-file` | an id in two partial files, or in the file of another area |
| `malformed-id`, `id-area-mismatch` | the id is not `<area>.<…>`, or its area is not the file's area |
| `duplicate-source-id` | one id for two different texts |
| `untagged` | user-visible text without i18n: template text, text attributes, string literals in template expressions, UI-looking TypeScript strings (`label: 'Home'`, `notify.error('…')`, sentences); not reported for pending areas |

```bash
npm run i18n:check                          # full run (extraction + checks)
npm run i18n:check -- --reuse               # reuse the last extraction (after editing src/locale only)
npm run i18n:check -- --area projects       # one area: report, problems, the untagged texts (the work
                                            # list, also while pending) and the missing ids as JSON
                                            # with the English source as value, ready to translate
npm run i18n:check -- --area projects --reuse
```

`npm run test:scripts` (part of `test:ci`) tests the tooling on fixtures (`scripts/tests/`), including
that every translated manual chapter carries the anchors of `docs/manual/pl/manual.json`.

### Adding a language

1. `src/locale/<lang>/` with the partial files (copy the Polish ids, translate).
2. `core/i18n/locale.ts`: `SUPPORTED_LOCALES`, `LOCALE_NAMES`, `ANGULAR_LOCALE_ID`.
3. `main.ts`: the Angular locale data in `LOCALE_DATA` (`import('@angular/common/locales/<lang>')`).
4. `core/ui/nz-locale.ts`: the NG-ZORRO and date-fns locale.
5. `scripts/i18n-lib.mjs`: the language's plural categories in `PLURAL_CATEGORIES`; run the gate with
   `--lang <lang>` (the build checks Polish).
6. Optionally `docs/manual/<lang>/` (same slugs and anchors, own `manual.json`).

## Relay design language (UI)

The source of truth is `docs/ui/design-language.md`; ADR 0022 records the decision.

**Tokens** (`src/styles/relay/tokens.css`): `--bo-*` custom properties, light on `:root`, dark on
`[data-theme=dark]` (repeated under `prefers-color-scheme: dark` for the moment before `/me`), density
on `[data-density=compact]`. Colours: `bg`, `surface`, `surface-2/3`, `line`, `line-strong`, `ink`,
`ink-2/3`, `on-ink`, `accent`, and per holder `agent|you|ci|done|blocked|idle` with `-soft` (tint) and
`-ink` (text) variants. Also type (`--bo-font-ui|mono`, `--bo-fs-*`), space (`--bo-space-0..7`), radius
(6 / 10 / 999), motion (`--bo-dur-fast|panel`), focus ring and density (`--bo-row-h`,
`--bo-row-pad-y`). Component styles use tokens only; never hex values. `npm run check:contrast`
verifies the pairs.

**NG-ZORRO theme** (`src/styles/relay/ng-zorro-theme.less`, the only place that touches `.ant-*`):
NG-ZORRO's CSS-variable theme with its less variables pointed at the tokens; primary buttons are ink,
links/focus/selection the accent, tags become baton-tinted chips, tables have hairlines and a warm
header, overlays are the only elements with shadows. Only the components the app uses are compiled
(import list at the top). `A11yPatchService` names NG-ZORRO's inner inputs and switch buttons and
removes invalid ARIA from segmented controls (found by axe).

**Shell** (`layout/`): the rail (56 px, expands to 232 px on hover or keyboard focus, "Keep open" pins it,
`backoffice.railPinned`), project tiles (monograms; the current project from `ProjectsStore.current()`,
set by project, run, CI and designer pages; "All projects" clears it), navigation gated by permissions,
Administration and Settings at the bottom; hovering an item preloads its route
(`HoverPreloadingStrategy`). The top bar shows the breadcrumb (`BreadcrumbService`: pages set entity
names, otherwise section + route title), the palette trigger, the amber *your turn* counter (open gates
the user can decide, `InboxService.decidableCount`), the live-update dot, the activity dock toggle and
the help button (`F1`), the avatar menu (Account, Theme, Density, Keyboard shortcuts, Manual, Sign out). The activity dock
(`ActivityFeedService`, toggled with `.`) lists live SSE events for the current project or all. The
palette, the dock and the `?` overlay are `@defer`red out of the initial bundle.

**Keyboard**: `ShortcutsService` (register per page with a `DestroyRef`; later registrations win;
typing in inputs is ignored unless `allowInInputs`). Global: `Ctrl/⌘ K` and `/` palette, `?`
shortcuts, `.` dock, `Alt T` theme, `F1` help for this page. Lists: `j` / `k` / `Enter` via `listNavigation()` (Home, Your
turn, Runs, CI); `f` focuses the runs search; gate panel: `a` approve, `r` send back for revision,
`x` reject (each only when allowed; `a` on a list opens the gate on its checklist, never approves);
editors: `Ctrl/⌘ S` (`bo-action-bar`); designer: `[` / `]` toggle the panels, `Ctrl/⌘ Z` undo.

**Command palette**: `CommandPaletteService.register(provider, destroyRef)` adds a source of entries
(`{ id, group, order?, minQuery?, scope?, items(query) }`, sync / promise / observable). The shell's
providers (`layout/palette-providers.ts`): destinations (permission-filtered), actions (Start run…,
Create project, Invite user, Toggle theme, Toggle density, Show shortcuts), projects, workflows of the
current project, recent runs (id prefix or issue key), templates (`library.view`, 2+ characters) and
users (`admin.users.view`, 2+ characters). Executed entries with a `link` are remembered as recent
(`backoffice.palette.recent`). An entry with `scope` opens a second step ("Start run…" lists workflows
and opens `/projects/:id?start=<workflowId>`). `/projects?create=1` and `/admin/users?invite=1` open
the create / invite drawers.

**Pages built on it**: Home (`/home`: greeting, Your turn queue from `TurnQueueService`, In flight
grouped by holder with mini relay tracks, Landed today, cost this week sparkline for cost viewers,
admin first-run checklist), Your turn (`/inbox`, gate side panel), Projects (cards), project detail,
Runs (filter bar with holder chips, project, workflow, "Started by me"; virtual scrolling above 100
rows), run detail (full relay track, timeline + selected step with artefacts and live logs, canvas
replay tab), CI list and detail, workflow designer (tokens for node kinds, collapsible palette and
properties, validation sheet with "Show node", sticky action bar).

**The baton**: every status is shown with `<bo-baton>` using `batonOf.run|ci|attempt|node|approval`.
To map a new state, see "Mapping a new state" in `core/ui/relay/README.md`.

## Conventions

- Standalone components only; no NgModules of our own. NG-ZORRO is imported per module
  (`NzTableModule`, ...) in each component.
- Signals for state, `computed()` for derived values, RxJS only at the HTTP/SSE boundary
  (`takeUntilDestroyed(this.destroyRef)` on every long-lived subscription).
- Templates use the built-in control flow (`@if`, `@for`, `@switch`); `track` by id.
- Keyboard: rows that act as links have `tabindex="0"` and `(keydown.enter)`; icon-only buttons have
  `aria-label`; charts have `role="img"` and a label.
- Money is USD with 4 decimals below one dollar (`money` pipe); tokens abbreviated (`tokens` pipe).
- Prettier formatting (`.prettierrc` from the CLI: single quotes, print width 100).

## Build, image and runtime configuration

```bash
# from the repository root (the OpenAPI file must be in the build context)
docker build -t back-office/web:$(git rev-parse --short HEAD) -f frontend/Dockerfile .
docker run --rm -p 8080:8080 -e API_UPSTREAM=http://back-office-api:8080 back-office/web:<tag>
```

| Env var | Default | Meaning |
|---|---|---|
| `API_UPSTREAM` | `http://back-office-api:8080` | where nginx proxies `/api/`, `/oauth2/`, `/login/oauth2/`, `/logout`, `/ci/backoffice-ci.sh`, `/v3/api-docs`, `/swagger-ui` |
| `NGINX_PORT` | `8080` | listen port (the image runs as uid 101, so keep it above 1024) |

nginx specifics: history fallback to `index.html` (no-store), hashed assets cached for a year,
gzip, `Content-Security-Policy` (self + `data:` images, inline styles allowed for Angular), `X-Content-Type-Options`,
`Referrer-Policy`, `X-Frame-Options`, and for `/api/v1/events` `proxy_buffering off` with a 1 h read
timeout so SSE streams are not cut. `/healthz` returns 200 for probes.

OIDC behind the proxy: only `/login/oauth2/` goes to the backend, `/login` itself is the Angular page.
Every proxied location sends `Host $host`, `X-Forwarded-Proto` and `X-Forwarded-Host` (taken over from
an ingress when present, otherwise `$scheme` / `$http_host`) so the backend builds its redirect URI
(`{baseUrl}/login/oauth2/code/oidc`) for the public address; the backend must honour forwarded headers
(`server.forward-headers-strategy=framework`). The dev proxy does the same with `"xfwd": true` and
`changeOrigin: false`, so locally the redirect URI is `http://localhost:4200/login/oauth2/code/oidc`
(register it at the Keycloak client). `/ci/` is not proxied as a whole because `/ci/:executionId` is an
SPA route; only the script `/ci/backoffice-ci.sh` is.

## E2E smoke

```bash
npm start                      # or any running UI; BASE_URL defaults to http://localhost:4200
npm run e2e:smoke              # SMOKE_USER / SMOKE_PASSWORD default admin@example.com / admin
SMOKE_MOCK=1 npm run e2e:smoke # force the mocked identity
```

`e2e/smoke.mjs` (Playwright, Edge channel):

1. opens `/login?error=not-invited` and checks the sign-in button and the message;
2. when `GET /api/v1/auth/config` answers, signs in for real: `/login` -> "Sign in with ..." -> Keycloak
   form (`#username`, `#password`, `#kc-login`) -> back to the app; otherwise (backend down) it mocks
   `GET /api/v1/me` with an administrator holding every permission so every page renders;
3. visits every route incl. `/admin/users`, `/admin/groups`, `/admin/roles`, `/admin/grants`,
   `/admin/settings`, `/admin/audit`, `/account`, the invite drawer, the project tabs incl. Members,
   and the designer (template load, gate editor);
4. opens the run and CI execution details, the command palette (`Ctrl K`) and the `?` overlay;
5. runs axe (WCAG 2.2 A/AA) on every page: serious or critical violations on the Relay routes (home,
   your turn, projects, runs, CI, designer) fail the run, others are listed under "A11Y";
6. fails on JavaScript errors (and on being bounced to `/login`); API errors are tolerated and listed.
   Screenshots go to `shots/`, the full result to `report.json`. `SMOKE_AXE=0` skips axe.

The smoke expects an administrator (it opens the template, invite and designer editors). For the
visual review, `node e2e/relay-shots.mjs` screenshots the Relay routes in light and dark
(`ROUTES=home,runs#first`, `THEMES=dark`, `MOCK_GATE=1` for an open gate, `OUT_DIR=`).

## Where to look when it fails

| Symptom | Look at |
|---|---|
| Every page goes to `/login` right after signing in | `/api/v1/me` still 401: session cookie not set or not sent (check `BO_SESSION` in devtools; proxy must keep the origin: `changeOrigin: false`), or the backend redirect ended on another host |
| Keycloak says "Invalid parameter: redirect_uri" | the backend built the redirect URI from the wrong host/scheme: forwarded headers (`xfwd`, nginx `X-Forwarded-*`) and `server.forward-headers-strategy`; register `<public origin>/login/oauth2/code/oidc` at the client |
| `/login?error=not-invited` after Keycloak | registration is INVITE_ONLY and the e-mail is neither invited nor in an allowed domain (Administration > Users > Invite, or Platform settings) |
| POST/PUT fail with 403 "Invalid CSRF token" | the `XSRF-TOKEN` cookie is missing (backend SPA CSRF mode) or the request URL is absolute (Angular only adds the header to relative URLs) |
| "Access denied ... Missing permission: x" | the user lacks `x` (effective permissions on Administration > Users > user); after changing grants the user's next request sees it, the UI after reload or `/me` refresh |
| A menu entry or button is missing | its permission (`layout/navigation.ts`, route `data`, the page's `can...` computeds); `/account` > My access lists what `/me` returned |
| "Your session has expired" too early | `me.idleTimeoutSeconds` / `absoluteExpiresAt`; the UI extends on user input only after a minute without API calls, so a much shorter server timeout expires first |
| Header dot stays grey/orange, no live updates | SSE: `/api/v1/events` needs the session cookie; behind nginx check the `/api/v1/events` location (buffering off); devtools Network tab shows the EventSource |
| "Connection to the server lost" | dev: backend not on :8080 or proxy misconfigured (`proxy.conf.json`); prod: `API_UPSTREAM` wrong |
| `npm run build` fails in `lint:deps` | a forbidden import (core -> features, cross-feature internals): use the feature's `public-api.ts` or move the code to `core/` |
| Dark theme shows light patches | a component uses a literal colour instead of a `--bo-*` token; for NG-ZORRO parts add the variable to `styles/relay/ng-zorro-theme.less` (less colour functions on `var()` become `color-mix()` through `less-token-functions.cjs`) |
| `npm run check:bundle` fails | something heavy moved into the initial bundle: keep features lazy, `@defer` heavy panels, import NG-ZORRO modules only where used |
| `npm run check:contrast` fails | a token pair in `tokens.css` is below 4.5:1 (text) / 3:1 (glyphs); change the token and keep the dark block and the `prefers-color-scheme` block in sync |
| A new NG-ZORRO component renders unstyled | add its `style/entry.less` to the import list at the top of `ng-zorro-theme.less` |
| Icons render as empty squares | `assets/outline|fill|twotone/*.svg` missing: check the icon asset glob in `angular.json` and the CSP `img-src` |
| Task config form shows only a JSON textarea | the template's `configSchema` in `GET /workflows/palette` lacks `properties` or uses unsupported types |
| Canvas nodes render but handles are missing / edges float | a custom node must render `<handle>` inside the node element; keep `NodeShellComponent` as the wrapper. Handles are positioned from the host element size, so nodes need a fixed width |
| Palette is empty | `GET /workflows/palette?projectId=` failed or returned no concrete templates; abstract templates never appear |
| Designer says valid locally but Save fails with 400 | server rules are stricter (template resolution, interface check); the Problem `detail` is shown and `POST /workflows/validate` issues are shown on the nodes |
| Browser smoke run fails | `npm run e2e:smoke` needs a running UI and Edge; API errors are tolerated (reported as "API PROBLEMS"), JS errors fail the run; `report.json` has the details |
| `npm ci` fails with `edgesOut` | `.npmrc` missing `legacy-peer-deps=true` |
| Build budget error | `angular.json` budgets; check that ECharts stays in the lazy chunk (import only from `core/charts/echarts.ts` via `provideEchartsCore`) |
| Types do not match the backend | `npm run api:types` after `docs/api/openapi.yaml` changed; the contract, not the UI, is the source of truth |
