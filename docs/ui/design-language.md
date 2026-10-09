# Relay — the Back Office design language

Back Office is where one person hands work to agents and CI and takes it back only when needed. The
interface is built around that single idea: **every piece of work has a baton, and the UI always shows
who holds it.** Everything else follows from it. This document is the source of truth for tokens,
components and page patterns; `frontend/src/styles/relay/*` implements it.

## 1. Principles

| # | Principle | In practice |
|---|---|---|
| 1 | **Your turn first** | The home page is a queue of things that need you, ordered by urgency and age; agent and CI work is ambient. "Nothing needs you" is a valid, calm state. |
| 2 | **Calm by default, loud when needed** | Neutral paper-and-graphite UI. Saturated colour is reserved for state; only *your turn* (amber) is loud. |
| 3 | **Never lose your place** | Lists open details in a side panel; editors keep a sticky action bar; back always returns to the same scroll and filters. |
| 4 | **Keyboard first, mouse friendly** | `Ctrl/⌘ K` command palette for every destination and action; list navigation with `j`/`k`, `Enter`; gate shortcuts `a` approve, `r` revise, `x` reject; `?` lists shortcuts. |
| 5 | **Provenance on everything** | Every result says who produced it (agent + model, person, CI) and what it cost. |
| 6 | **Dense, not cramped** | 4 px grid, 14 px body, 13 px tables, tabular numerals, generous line height; information scent over decoration. |
| 7 | **State never by colour alone** | Every state has a glyph and a word. |

## 2. The baton

| Holder | Meaning | Token | Glyph | Motion |
|---|---|---|---|---|
| `agent` | an agent is working (LLM step, runner job) | `--bo-agent` cobalt | filled hexagon | slow breathing dot while running |
| `you` | a person must act (gate, failed run to retry, invite to finish) | `--bo-you` signal amber | raised hand | none (static, loud colour) |
| `ci` | waiting for the project's CI | `--bo-ci` teal | loop arrow | slow rotation while waiting |
| `done` | finished successfully | `--bo-done` moss | check | none |
| `blocked` | failed or rejected | `--bo-blocked` brick | stop octagon | none |
| `idle` | draft, pending, cancelled | `--bo-idle` grey | hollow circle | none |

Mapping: run `RUNNING` → agent (or ci when `waitingOn.kind = WAIT_FOR_CI`), `WAITING` + human gate → you,
`SUCCEEDED` → done, `FAILED` → blocked, `CANCELLED`/`PENDING` → idle. CI executions and benchmark
attempts use the same mapping. `prefers-reduced-motion` disables all motion.

## 3. Tokens

### Colour (light / dark)

| Token | Light | Dark | Use |
|---|---|---|---|
| `--bo-bg` | `#F6F5F2` | `#111214` | page background (warm paper) |
| `--bo-surface` | `#FFFFFF` | `#1A1C1F` | cards, tables, panels |
| `--bo-surface-2` | `#FAF9F6` | `#202328` | table headers, hover, sub-panels |
| `--bo-line` | `#E3E0D8` | `#2D3137` | hairlines (1 px) |
| `--bo-line-strong` | `#CFCBC1` | `#3B4047` | inputs, focusable outlines |
| `--bo-ink` | `#1C1E21` | `#ECEAE5` | primary text, primary buttons |
| `--bo-ink-2` | `#4A4E55` | `#B9BDC3` | secondary text |
| `--bo-ink-3` | `#6B7078` | `#8A9098` | meta, placeholders (AA 4.5:1 on bg, surface and surface-2; the first draft value `#7A7F87` measured 4.0:1 on white) |
| `--bo-accent` | `#3552D6` | `#8FA2FF` | links, focus ring, selection |
| `--bo-agent` | `#3552D6` | `#8FA2FF` | baton: agent |
| `--bo-you` | `#C77800` | `#FFB23E` | baton: you (text on light uses `#8F5600`) |
| `--bo-ci` | `#0E7C71` | `#3CC9B8` | baton: ci |
| `--bo-done` | `#2F7D40` | `#6FC77F` | baton: done |
| `--bo-blocked` | `#B8392A` | `#FF7A63` | baton: blocked, destructive |
| `--bo-idle` | `#8A8F96` | `#7C828A` | baton: idle |

Each baton colour has a `-soft` variant (12 % tint on light, 18 % on dark) for backgrounds and an
`-ink` variant for text on that tint and on surfaces (light: agent `#3552D6`, you `#8F5600`, ci `#0D776C`,
done `#2D783D`, blocked `#B8392A`, idle `#686C70`; dark: the baton colours, idle `#94999F`). Every text
pair is checked by `npm run check:contrast` (4.5:1 for text, 3:1 for glyphs and focus).
Primary buttons are **ink** (graphite on light, off-white on dark), not blue: the accent is for
navigation and focus, the ink is for committing actions. Destructive buttons are brick outline until
confirmed.

### Type

| Token | Value |
|---|---|
| UI family | **Instrument Sans** (variable, self-hosted via `@fontsource-variable/instrument-sans`) |
| Mono family | **JetBrains Mono** (self-hosted via `@fontsource-variable/jetbrains-mono`) for ids, keys, costs, code, diffs |
| Sizes | 12 (meta) · 13 (tables, dense) · 14 (body) · 16 (section titles) · 20 (page titles) · 28 (home greeting) |
| Weights | 400 body · 500 labels · 600 titles |
| Numerals | `font-variant-numeric: tabular-nums` in tables, costs, counters |
| Tracking | −0.01 em on titles ≥ 20 px |

No CDN fonts (privacy and offline installs).

### Space, shape, depth, motion

- Spacing scale (4 px grid): 2, 4, 8, 12, 16, 24, 32, 48.
- Radius: 6 px controls, 10 px cards and panels, 999 px chips.
- Depth: hairlines, not shadows. Shadows only on overlays (`0 8px 24px rgba(0,0,0,.12)` light, `.4` dark).
- Motion: 140 ms ease-out for hover/press, 200 ms for panels; no bounce; respect reduced motion.
- Focus: 2 px `--bo-accent` ring with 2 px offset, always visible on keyboard focus.
- Density: *comfortable* (default) and *compact* (row height 32 → 28 px), a preference.

## 4. Signature components

| Component | Description |
|---|---|
| **Baton chip** `<bo-baton [holder]>` | pill with glyph + word (+ optional detail, e.g. "you · spec gate"). The single status component across the app; replaces ad-hoc status tags. |
| **Relay track** `<bo-relay-track [nodes] [current]>` | a horizontal strip of segments, one per visited workflow node, coloured by the holder at that step, with gaps between them and a small pointer under the current (finished run: last) segment. Widths follow the square root of the step duration between a minimum and a maximum, so a short run stays short and left-aligned. Mini variant (6 px high) in run lists; full variant with labels, durations and costs in the run header. |
| **Turn card** | an item in the *Your turn* queue: what, where (project · workflow · node), why you (accountable role / checklist pending), age, and the primary action inline (Approve / Review / Retry) with keyboard hint. |
| **Cost tick** | mono figure `$0.042` with tokens on hover; appears next to every agent result. |
| **Provenance line** | "by *Implement (OpenCode · claude-opus-5)* · 4 min · $0.31" / "approved by *Anna* · 2 h ago". |
| **Command palette** | `Ctrl/⌘ K`: fuzzy search across destinations, projects, workflows, runs (by id or issue key), templates, users (admins), plus actions ("Start run…", "Create project", "Invite user", "Toggle theme"). Recent items first. |
| **Page header** | breadcrumb, title, provenance line, primary action on the right, secondary actions in an overflow menu. |
| **Filter bar** | search field + removable filter chips + saved views; state reflected in the URL. |
| **Side panel** | 480–720 px resizable drawer for details; `Esc` closes; deep-linkable. |
| **Sticky action bar** | editors: unsaved-changes dot, secondary left, primary right, `Ctrl/⌘ S` saves. |
| **Empty state** | one sentence, one primary action, one link to the guide; no illustrations of people. |
| **Skeletons** | shape-true skeletons for tables and cards; never spinners for page loads. |

### Help link and help panel

- **Help link** `<bo-help-link topic>`: a quiet 24 px round `?` (ink-3, accent on hover, focus ring)
  after the page title (`bo-page-header help="..."`), next to section and card titles, never inside
  a sentence. Its accessible name is "Help: <topic>". Inline hints (two to four lines: what to do,
  where, and one link) use the text variant, an underlined accent link such as "Full steps".
- **Help panel**: a side panel (520 px, resizable, `Esc` closes) with ONE manual section: the
  section heading as the title, "Manual · <chapter>" as the subtitle, the rendered Markdown and
  "Open in manual" in the footer. It opens over the page (and over dialogs) without navigating, so
  forms and one-time secrets stay where they are. Links inside go to the full manual (`/help`).
- **Empty states** link their "one link to the guide" to the manual section through the help panel.
- The top bar's `?` button and `F1` open the help of the current screen; screens without a topic open
  the manual. Missing manual text says "This chapter is being written.", never an error.

## 5. Layout

```
┌──┬───────────────────────────────────────────────────────────────┬──────────┐
│▣ │ Projects / SHOP / Runs / #1f32          [⌘K search]  ✋ 3  (A) │          │
│  ├───────────────────────────────────────────────────────────────┤ activity │
│  │ page header · title · provenance · [primary]                   │ dock     │
│R │                                                               │ (toggle  │
│A │ content                                                       │  with .) │
│I │                                                               │          │
│L │                                                               │          │
└──┴───────────────────────────────────────────────────────────────┴──────────┘
```

- **Rail** (56 px, expands to 232 px on hover or when pinned): project switcher on top (monogram tiles of
  project keys, the current one highlighted), then Home, Your turn, Projects, Runs, Insights, CI, Library,
  Evaluation, Costs, then Administration (permission-gated) and Settings at the bottom.
- **Top bar**: breadcrumb, command palette trigger, the amber *your turn* counter (hidden when zero),
  help (`?`, `F1`), avatar menu (account, theme, density, manual, sign out).
- **Activity dock** (right, optional): live SSE events filtered to the current project or all.

## 6. Page patterns

| Page | Pattern |
|---|---|
| Home | greeting + *Your turn* queue (turn cards) + *In flight* (runs with mini relay tracks, grouped by holder agent/ci) + *Landed today* + cost this week sparkline; admins see a first-run checklist until done (provider key, first project, invite someone, connect CI). |
| Lists | filter bar → table (13 px, sticky header, row hover actions, keyboard navigation) → side panel. |
| Run detail | header with full relay track; left: node timeline with provenance and costs; right: selected node panel (artefacts, logs, output); canvas replay on a tab. |
| Workflow designer | canvas full height; palette and properties as collapsible panels; validation issues as a bottom sheet grouped by node with jump-to-node. Layered layout (main path on the top row, branches below, loops routed in lanes around the cards) when positions are missing or cards overlap, and on *Tidy layout*; opens at a readable zoom (>= 60 %) from START; overview map from 8 nodes (togglable); arrow keys walk the edges, `Delete`, `Ctrl/⌘ D` duplicate, `Ctrl/⌘ Z` / `Shift Z` undo / redo. |
| Gate decision | side panel: question, why you, checklist (required items first), artefacts in tabs, decision buttons with shortcuts; *Approve* disabled until required items are ticked, with the reason shown. |
| Editors | full page, sticky action bar, unsaved guard. |
| Admin | same list pattern; effective-permissions table uses baton-like chips `allow`/`deny`/`inherit`. |
| Login | split layout: left the product mark and a static relay-track illustration with one line of copy; right the form card (e-mail, password, "Sign in"), divider, "Continue with <provider>"; MFA step with a 6-cell code input (auto-advance, paste). |

## 7. Writing

- Sentence case everywhere. Buttons are verbs with objects: "Approve spec", "Send back for revision",
  "Start run", never "Submit" or "OK".
- Say who: "Waiting for you (approver)" rather than "Pending".
- Errors say what happened and what to do next; field errors sit under the field.
- Numbers: costs `$0.042` (4 significant decimals under $1), durations `4m 12s`, relative times with the
  absolute time on hover.

## 8. Performance and quality budgets

| Budget | Target |
|---|---|
| Initial JS (gzip) | ≤ 300 kB (257 kB after iteration 5: NG-ZORRO's i18n/date-fns and the modal service load lazily; 259 kB with in-app help, whose panel, map and Markdown renderer load on first use) |
| Route change to content (warm) | ≤ 150 ms with skeleton |
| INP | ≤ 200 ms |
| Tables > 100 rows | CDK virtual scroll |
| Accessibility | no serious/critical axe violations on the smoke routes; WCAG 2.2 AA contrast |

Techniques: lazy routes, `@defer` for heavy panels (charts, canvas, diff viewer), prefetch on rail
hover, signal-based stores with stale-while-revalidate, optimistic updates for approvals and toggles.

## 9. Screens

Reference screenshots of the implementation (`frontend/e2e/screenshots/relay/`, regenerated with
`node e2e/relay-shots.mjs` from `frontend/` against a running stack; `MOCK_GATE=1` adds an open gate,
`ROUTES=` and `THEMES=` narrow the set, `SHOTS_PUBLIC=1` shoots signed-out pages; the local-account screens come from `SHOTS=1 node e2e/local-login-ui-check.mjs`; the fleet, operations and governance screens from `node e2e/governance-shots.mjs`, which serves the iteration-5 endpoints from `e2e/governance-mocks.mjs` where the backend does not answer them yet). Themes follow the OS colour scheme for a SYSTEM preference.

| Screen | Light | Dark |
|---|---|---|
| Home: greeting, your turn, in flight, landed today, cost | ![](../../frontend/e2e/screenshots/relay/home-light.png) | ![](../../frontend/e2e/screenshots/relay/home-dark.png) |
| Your turn with the gate panel open | ![](../../frontend/e2e/screenshots/relay/your-turn-gate-light.png) | ![](../../frontend/e2e/screenshots/relay/your-turn-gate-dark.png) |
| Projects | ![](../../frontend/e2e/screenshots/relay/projects-light.png) | ![](../../frontend/e2e/screenshots/relay/projects-dark.png) |
| Project detail | ![](../../frontend/e2e/screenshots/relay/project-detail-light.png) | ![](../../frontend/e2e/screenshots/relay/project-detail-dark.png) |
| Runs with filter bar and mini relay tracks | ![](../../frontend/e2e/screenshots/relay/runs-light.png) | ![](../../frontend/e2e/screenshots/relay/runs-dark.png) |
| Run detail: full relay track, timeline, selected step | ![](../../frontend/e2e/screenshots/relay/run-detail-light.png) | ![](../../frontend/e2e/screenshots/relay/run-detail-dark.png) |
| Run detail, many steps waiting at a gate (mocked run) | ![](../../frontend/e2e/screenshots/relay/run-detail-steps-light.png) | ![](../../frontend/e2e/screenshots/relay/run-detail-steps-dark.png) |
| Canvas replay centred on the current node | ![](../../frontend/e2e/screenshots/relay/run-replay-light.png) | ![](../../frontend/e2e/screenshots/relay/run-replay-dark.png) |
| CI execution | ![](../../frontend/e2e/screenshots/relay/ci-detail-light.png) | ![](../../frontend/e2e/screenshots/relay/ci-detail-dark.png) |
| Workflow designer | ![](../../frontend/e2e/screenshots/relay/designer-light.png) | ![](../../frontend/e2e/screenshots/relay/designer-dark.png) |
| Command palette (`Ctrl/⌘ K`) | ![](../../frontend/e2e/screenshots/relay/command-palette-light.png) | ![](../../frontend/e2e/screenshots/relay/command-palette-dark.png) |
| Sign in: e-mail and password, divider, Continue with Keycloak | ![](../../frontend/e2e/screenshots/relay/login-light.png) | ![](../../frontend/e2e/screenshots/relay/login-dark.png) |
| Sign in: code step (6-cell code input) | ![](../../frontend/e2e/screenshots/relay/login-mfa-light.png) | ![](../../frontend/e2e/screenshots/relay/login-mfa-dark.png) |
| Set password from a one-time link, live policy hints | ![](../../frontend/e2e/screenshots/relay/set-password-light.png) | ![](../../frontend/e2e/screenshots/relay/set-password-dark.png) |
| Set-password link that no longer works | ![](../../frontend/e2e/screenshots/relay/set-password-invalid-light.png) | ![](../../frontend/e2e/screenshots/relay/set-password-invalid-dark.png) |
| Forced MFA set-up after a local sign-in (restricted session) | ![](../../frontend/e2e/screenshots/relay/setup-mfa-forced-light.png) | ![](../../frontend/e2e/screenshots/relay/setup-mfa-forced-dark.png) |
| Account: security (sign-in method, password, two-factor, recent sign-ins) | ![](../../frontend/e2e/screenshots/relay/account-security-light.png) | ![](../../frontend/e2e/screenshots/relay/account-security-dark.png) |
| Account: MFA set-up in a side panel | ![](../../frontend/e2e/screenshots/relay/account-security-enrol-light.png) | ![](../../frontend/e2e/screenshots/relay/account-security-enrol-dark.png) |
| Account: recovery codes shown once | ![](../../frontend/e2e/screenshots/relay/account-security-codes-light.png) | ![](../../frontend/e2e/screenshots/relay/account-security-codes-dark.png) |
| Admin: users | ![](../../frontend/e2e/screenshots/relay/admin_users-light.png) | ![](../../frontend/e2e/screenshots/relay/admin_users-dark.png) |
| Admin: invite a local user, one-time link | ![](../../frontend/e2e/screenshots/relay/admin-invite-local-light.png) | ![](../../frontend/e2e/screenshots/relay/admin-invite-local-dark.png) |
| Admin: user detail with local-account controls | ![](../../frontend/e2e/screenshots/relay/admin_users-first-light.png) | ![](../../frontend/e2e/screenshots/relay/admin_users-first-dark.png) |
| Admin: user detail after issuing a set-password link | ![](../../frontend/e2e/screenshots/relay/admin_user-local-issued-light.png) | ![](../../frontend/e2e/screenshots/relay/admin_user-local-issued-dark.png) |
| Admin: platform settings (sign-in, password policy, lockout) | ![](../../frontend/e2e/screenshots/relay/admin_settings-light.png) | ![](../../frontend/e2e/screenshots/relay/admin_settings-dark.png) |
| Admin: audit log with sign-in and security filters | ![](../../frontend/e2e/screenshots/relay/admin_audit-light.png) | ![](../../frontend/e2e/screenshots/relay/admin_audit-dark.png) |
| Library: task templates | ![](../../frontend/e2e/screenshots/relay/library_templates-light.png) | ![](../../frontend/e2e/screenshots/relay/library_templates-dark.png) |
| Library: template editor | ![](../../frontend/e2e/screenshots/relay/library_templates-editor-light.png) | ![](../../frontend/e2e/screenshots/relay/library_templates-editor-dark.png) |
| Models | ![](../../frontend/e2e/screenshots/relay/models-light.png) | ![](../../frontend/e2e/screenshots/relay/models-dark.png) |
| Models: profile side panel | ![](../../frontend/e2e/screenshots/relay/models-profile-panel-light.png) | ![](../../frontend/e2e/screenshots/relay/models-profile-panel-dark.png) |
| Evaluation: task suites | ![](../../frontend/e2e/screenshots/relay/evaluation_suites-light.png) | ![](../../frontend/e2e/screenshots/relay/evaluation_suites-dark.png) |
| Evaluation: benchmarks | ![](../../frontend/e2e/screenshots/relay/evaluation_benchmarks-light.png) | ![](../../frontend/e2e/screenshots/relay/evaluation_benchmarks-dark.png) |
| Benchmark detail with leaderboard | ![](../../frontend/e2e/screenshots/relay/benchmark-detail-light.png) | ![](../../frontend/e2e/screenshots/relay/benchmark-detail-dark.png) |
| Costs: total, breakdown, ledger | ![](../../frontend/e2e/screenshots/relay/costs-light.png) | ![](../../frontend/e2e/screenshots/relay/costs-dark.png) |
| Settings: integrations | ![](../../frontend/e2e/screenshots/relay/settings-light.png) | ![](../../frontend/e2e/screenshots/relay/settings-dark.png) |
| Runner fleet: pools with capacity, isolation ceiling (mocked API) | ![](../../frontend/e2e/screenshots/relay/fleet-pools-light.png) | ![](../../frontend/e2e/screenshots/relay/fleet-pools-dark.png) |
| Add runner: registration token shown once, chart values, helm install | ![](../../frontend/e2e/screenshots/relay/fleet-add-runner-light.png) | ![](../../frontend/e2e/screenshots/relay/fleet-add-runner-dark.png) |
| Pool editor: policy ceiling | ![](../../frontend/e2e/screenshots/relay/fleet-pool-editor-light.png) | ![](../../frontend/e2e/screenshots/relay/fleet-pool-editor-dark.png) |
| Runners: version, backends, runtime classes, last seen | ![](../../frontend/e2e/screenshots/relay/fleet-runners-light.png) | ![](../../frontend/e2e/screenshots/relay/fleet-runners-dark.png) |
| Agent job side panel: effective policy, denied egress highlighted | ![](../../frontend/e2e/screenshots/relay/fleet-job-panel-light.png) | ![](../../frontend/e2e/screenshots/relay/fleet-job-panel-dark.png) |
| Operations: replicas, work queue, dead items (10 s refresh) | ![](../../frontend/e2e/screenshots/relay/operations-light.png) | ![](../../frontend/e2e/screenshots/relay/operations-dark.png) |
| Project sandbox policy under the ceiling, effective view with sources | ![](../../frontend/e2e/screenshots/relay/project-sandbox-light.png) | ![](../../frontend/e2e/screenshots/relay/project-sandbox-dark.png) |
| Project budget: spend, alert threshold, forecast, hard stop | ![](../../frontend/e2e/screenshots/relay/project-budget-light.png) | ![](../../frontend/e2e/screenshots/relay/project-budget-dark.png) |
| Home: budget alert as a turn card | ![](../../frontend/e2e/screenshots/relay/home-budget-turn-light.png) | ![](../../frontend/e2e/screenshots/relay/home-budget-turn-dark.png) |
| Costs: budget state in the header | ![](../../frontend/e2e/screenshots/relay/costs-budget-light.png) | ![](../../frontend/e2e/screenshots/relay/costs-budget-dark.png) |
| Insights: human wait headline, bottleneck, weekly chart, success tables | ![](../../frontend/e2e/screenshots/relay/insights-light.png) | ![](../../frontend/e2e/screenshots/relay/insights-dark.png) |
| Run detail: provenance tab with a verified attestation | ![](../../frontend/e2e/screenshots/relay/run-provenance-light.png) | ![](../../frontend/e2e/screenshots/relay/run-provenance-dark.png) |
| Account: connect your IDE (MCP) | ![](../../frontend/e2e/screenshots/relay/account-ide-light.png) | ![](../../frontend/e2e/screenshots/relay/account-ide-dark.png) |
