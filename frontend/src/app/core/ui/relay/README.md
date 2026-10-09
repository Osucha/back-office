# Relay components (`core/ui/relay`)

Standalone, OnPush, signal-input components and pure helpers that implement the Relay design
language (`docs/ui/design-language.md`). Import from the file or from the barrel
`core/ui/relay` (`index.ts`). Styling reads the tokens of `src/styles/relay/tokens.css` only, so
every component works in light, dark and compact density without extra code.

## The baton (status)

| Export | Use |
|---|---|
| `batonOf.run(run, { canDecide?, nodeName? })` | `WorkflowRun(Summary)`: RUNNING → agent (ci while it waits for CI), WAITING on a gate → you (idle "with approver" when `canDecide === false`), SUCCEEDED → done (blocked when the outcome is FAILURE), FAILED → blocked, PENDING/CANCELLED → idle |
| `batonOf.ci(execution)` | QUEUED → idle, RUNNING → agent, gate PASS → done, gate FAIL / FAILED → blocked |
| `batonOf.attempt(status)` | benchmark attempts: RUNNING/JUDGING → agent, SUCCEEDED → done, FAILED/TIMED_OUT → blocked, PENDING → idle |
| `batonOf.node(execution)` | node executions: active nodes by kind (task → agent, gate → you, CI wait → ci), SUCCEEDED → done, FAILED → blocked, SKIPPED → idle |
| `batonOf.approval(approval)` | OPEN → you (idle when you cannot decide), approved → done, revise → agent, rejected → blocked, expired → idle |
| `batonOf.agentJob(status)` | runner-fleet jobs: QUEUED/LEASED → idle "queued", STARTING/RUNNING → agent, SUCCEEDED → done, FAILED/TIMED_OUT/LOST → blocked, CANCELLED → idle |
| `holderOfKind(kind)`, `batonText(b)`, `HOLDER_MEANING`, `HOLDER_NAME` | helpers |

```html
<bo-baton [baton]="batonOf.run(run)" />                       <!-- chip: glyph + word (+ detail) -->
<bo-baton holder="you" label="your turn" detail="spec gate" />
<bo-baton [baton]="b" size="sm" appearance="plain" />          <!-- dense tables -->
<bo-glyph holder="ci" [live]="true" [size]="14" />             <!-- glyph only (decorative) -->
```

### Mapping a new state

1. Add a function to `baton.ts` that maps the entity's status to a `Baton` (`holder`, a short
   sentence-case `label`, optional `detail`, `live` for work in progress) and add it to `batonOf`.
2. Pick the holder by *who has to act next*: an agent (agent), a person (you), the project CI (ci),
   nobody because it succeeded (done) or failed (blocked), or nothing has started / it was stopped
   (idle). Only use `you` when the signed-in user can act; otherwise idle "with …".
3. Cover every enum value in `baton.spec.ts`. Never show a state by colour alone: the chip always
   carries the word.

## Components

| Selector | Inputs / outputs | Notes |
|---|---|---|
| `bo-baton` | `baton` or `holder`/`label`/`detail`/`live`; `size` `sm`/`md`; `appearance` `chip`/`plain` | the single status component |
| `bo-glyph` | `holder`, `live`, `size` | hexagon / hand / loop / check / octagon / circle; motion off with reduced motion |
| `bo-relay-track` | `segments: TrackSegment[]`, `variant` `mini`/`full`, `selectedNodeId`, `emptyLabel`; `(segmentSelected)` | build segments with `buildTrack(run)`; widths from `segmentWidths` (clamped, never stretched), pointer on `pointerIndex`; ARIA list with a label per step |
| `bo-run-track` | `run` (`WorkflowRun` or summary), `variant`, `selectedNodeId`, `ahead`; `(segmentSelected)` | loads summaries through `RunDetailsStore` (lazy, cached, 4 at a time) |
| `bo-turn-card` | `item: TurnItem`, `active`; `(open)`, `(primary)` | build items with `buildTurnQueue` (`core/state/turn-queue.ts`) |
| `bo-cost` | `value: Money \| number`, `usage: TokenUsage` | `$0.042`, tokens on hover |
| `bo-provenance` | `verb`, `who` or `actor`, `detail`, `at`, `durationMs`, `cost`, `usage`; projected extra parts | "by Implement (OpenCode) · 4m 12s · $0.31" |
| `bo-page-header` | `title`, `eyebrow`, `subtitle`, `monoTitle`, `menu: PageAction[]`, `help` (topic of `core/help/help-map.ts`: a `?` after the title); slots `[boHeaderLead]`, `[boHeaderStatus]`, `[boHeaderMeta]`, `[boHeaderActions]`, `[boHeaderBelow]` | breadcrumb lives in the top bar (`BreadcrumbService`) |
| `bo-filter-bar` | `query`, `placeholder`, `holders`, `selectedHolders`, `chips`, `presets`, `debounce`; `(queryChange)`, `(selectedHoldersChange)`, `(chipRemoved)`, `(presetSelected)`, `(cleared)`; projected controls; `focus()` | pair with `urlFilters()` |
| `bo-side-panel` | `open`, `title`, `subtitle`, `defaultWidth`, `minWidth` 480, `maxWidth` 720, `storageKey`, `initialFocus`, `closeOnEscape`, `bodyLabel` (focusable scrolling body), `zIndex`; `(closed)`; slots `[boPanelHeader]`, body, `[boPanelFooter]` | resizable (drag or ←/→ on the handle), Esc closes, focus in/out; deep-link with `panelParam()` |
| `bo-action-bar` | `dirty`, `saving`, `canSave`, `showSave`, `requireDirty`, `saveLabel`, `dirtyLabel`, `cleanLabel`, `sticky`; `(save)`; slots default (secondary) and `[boActionStatus]` | registers `Ctrl/⌘ S` (works in inputs) |
| `bo-empty` | `title`, `detail`, `actionLabel`, `primary`, `guideLink` (`/path#fragment` works), `help` (manual section in the help panel, preferred), `guideLabel`, `holder`, `holderLabel`, `tone` `default`/`calm`/`quiet`; `(action)` | one sentence, one action, one link |
| `bo-skeleton` | `variant` `table`/`cards`/`list`/`detail`, `rows`, `columns`, `label` | shape-true; never spinners for page loads |
| `bo-kbd` | `keys` (`mod+k`, `a`, `Escape`), `quiet` | platform-aware (`⌘` on macOS) |
| `bo-command-palette`, `bo-shortcuts-overlay` | rendered once by the shell | |
| `bo-segmented-nav` | `items: SegmentedNavItem[]` (`id`, `label`, `link`, `count?`), `active`, `label` | section switcher made of links (Library, Evaluation); `aria-current="page"` |

`app-empty-state` (older pages) renders `bo-empty`.

- `RELAY_TABLE_STYLES` (`table-styles.ts`): shared component styles for list tables (13 px cells, the
  keyboard-cursor row that keeps meta text AA, quiet `.row-actions` with `.del`, `.chip`, `.key`,
  `.cell-title` / `.cell-sub`); add to a component's `styles`.
- `chart-tokens.ts`: ECharts colours from the tokens. `chartTokensSignal()` (injection context)
  re-reads the custom properties when the theme changes; `chartBase`, `chartAxis`, `seriesColor`,
  `providerColor` are pure. Keep charts lazy (`@defer (on viewport)`).

### One-time code input (`code-input.component.ts`, not in the barrel)

| Selector | Inputs / outputs | Notes |
|---|---|---|
| `bo-code-input` | `length` (6), `label`, `name`, `disabled`, `invalid`, `describedBy`, `autofocus`; `[(value)]`; `(completed)`; `focus()`, `clear()` | one cell per digit: typing advances, Backspace clears the cell or steps back, ←/→/Home/End move, paste or one-time-code autofill of a full code fills every cell; the group has `label`, each cell "Digit 2 of 6"; a gap in the middle of even lengths |

```html
<bo-code-input label="Authentication code" describedBy="code-hint" [autofocus]="true"
               [(value)]="code" (completed)="verify($event)" />
```

Import it from the file (`core/ui/relay/code-input.component`) so only the pages that use it load
it. The editing rules are pure functions in `code-input.ts` (`applyInput`, `applyPaste`,
`applyBackspace`, `completeCode`), covered by `code-input.spec.ts`.

## Services and helpers

- `ShortcutsService.register(shortcuts, destroyRef)`: `{ keys, description, group, handler,
  allowInInputs?, when?(event?), hidden? }`. Later registrations win for the same keys; typing in
  inputs, selects and contenteditable is ignored unless `allowInInputs`. `?` lists the visible ones.
  Global keys (shell): `Ctrl/⌘ K` and `/` palette, `?` shortcuts, `.` activity dock, `Alt T` theme.
- `listNavigation({ items, open, group, when })`: `j` / `k` / `Enter` for a list; rows set
  `[attr.data-nav-index]` and `[class.active]="nav.cursor() === i"`.
- `CommandPaletteService.register(provider, destroyRef)`: a `PaletteProvider` is
  `{ id, group, order?, minQuery?, scope?, items(query) }` returning `PaletteItem[]` (sync, promise
  or observable). Items have a stable `id` (for "recent"), `title`, `subtitle`, `keywords`, `icon`
  or `holder`, and `link` or `run()`; an item with `scope` opens a sub-list (e.g. "Start run…").
  The shell's defaults are in `layout/palette-providers.ts`.
- `fuzzyScore(query, fields)`, `fuzzyFilter(items, query, fields)`, `highlightParts` — the scorer.
- `urlFilters({ q: 'string', holder: 'list', ... })` — filter state in query parameters
  (`values()`, `active()`, `set(patch)`, `clear()`); `parseFilters` / `serializeFilters` are pure.
- `panelParam('run')` — side panel open state in a query parameter.
- `BreadcrumbService.set(crumbs)` — entity names for the top-bar breadcrumb.
- `formatCost`, `formatUsage`, `absoluteTime`, `sumMoney`, `firstName` (`format.ts`).
- `contrast.ts` — WCAG math behind `npm run check:contrast`.
