# 0032 — Desktop notifications in the open tab, with synthesised sounds

- Status: accepted
- Date: 2026-10-08
- Deciders: owner
- Related: ADR 0022 (Relay design language), 0026 (UI internationalisation), 0029 (prompt-injection
  defences); `frontend/src/app/core/notifications/`, manual `account.md#notifications`

## Context

The most valuable moment in Back Office is the hand-off: a gate waits for a person (*your turn*). Today
it is visible only inside the app (the amber counter, Home, the activity dock). People keep Back Office
in a background tab while they work elsewhere, so gates wait longer than they need to (the insights
"human wait" headline). Failures of runs the user started, budget alerts and CI failures have the same
problem to a lesser degree.

The owner asked for desktop notifications with a pleasant, original sound, without new infrastructure,
respecting the browser's permission and autoplay rules, i18n, and the rule that untrusted text (agent
output) never reaches the user unfiltered.

## Decision

1. **Notification API in the open tab.** The SPA already receives every relevant event over the
   authenticated SSE stream (`/api/v1/events`) and keeps the open gates in `InboxService`. A lazily
   loaded `DesktopNotifierService` turns them into `new Notification(...)` while at least one Back
   Office tab is open (foreground or background):
   - *your turn*: a gate appearing among the gates the user may decide (the server's `canDecide`; the
     first load is the baseline and never notifies), or `ApprovalAssigned` naming the user;
   - run failed / landed: `WorkflowRunFinished` for runs the user started (`startedBy`, read with one
     `GET /runs/{id}`) or in projects they own;
   - CI failed: `CiExecutionFinished` failures in the user's projects;
   - budget: the `BudgetAlert` / `BudgetExceeded` events of the llm-gateway for budgets the user can
     raise (the contract's `DomainEventType` does not list them yet; the stream sends them as named
     events and the UI listens for them explicitly).
2. **Permission only from a gesture**: the master switch in My account → Notifications or the avatar
   menu's "Desktop notifications" calls `Notification.requestPermission()` in the click handler. Denied
   and unsupported states are explained on the page with the steps to re-enable per browser (manual).
3. **Preferences per user in the browser** (`localStorage`, `bo.notifications.<userId>`, synchronised
   across tabs): master switch, per type (*your turn*, run failed, run landed (off), budget, CI failed
   (off)), "only when the tab is not in view" (on), sound + volume, quiet hours. The contract's
   `UserPreferences` has no field for them; a server-side copy is a later contract change.
4. **Content**: title + one line, `tag` per gate / run / execution / budget (no stacking), the app
   icon, click focuses the tab and opens the item; texts in the UI language; server text is sanitised
   (controls, bidi and zero-width characters removed, length capped) and limited to names and keys,
   never agent output, failure messages or secrets. Several tabs notify once (claim per tag under a
   Web Lock).
5. **Synthesised sounds** (`core/notifications/sounds.ts`): one soft FM-mallet timbre (sine carrier,
   4x modulator with a ~90 ms decaying index, octave partial, 12 ms attack, exponential decay,
   low-pass), a motif family under 0.9 s: *your turn* A4→D5 with an echo, *landed* D5 F#5 A5,
   *failed* E4→C#4, *budget* G4 double tap. Parameters are data and unit-tested on a mock context. The
   `AudioContext` is created after a user gesture; one sound at a time, at most one per 3 s (bursts
   coalesce, most important wins). The OS sound is suppressed (`silent: true`) so the user hears one
   sound they can mute.
6. Notifications and sounds are **additional** to the in-app indicators, never the only signal.

## Alternatives considered

- **Web Push (service worker + Push API + VAPID push service)**: notifies even when every tab is
  closed. Needs a service worker (none today), push subscriptions stored per user and device, a sender
  in the backend (new outbound dependency on browser push services, which an air-gapped installation
  may not reach), and payload encryption. Kept as a **later option**: the in-tab design above keeps the
  same preferences and content builders, so adding a push channel means a service worker that renders
  the same `NotificationContent` and a backend subscriber on the event outbox.
- **Audio files (`.mp3`/`.ogg`) or system sounds**: licensing and provenance of samples, extra assets
  in the bundle, no single timbre across the motif family, and system sounds differ per OS. Synthesis
  costs a few hundred bytes, is original and licence-free, and is testable.
- **In-app toasts only**: invisible when the tab is in the background, which is the problem.
- **Polling for notifications**: the SSE stream already delivers the events; polling would add load.

## Consequences

- No backend change; the notifier and the sounds are a lazy chunk (the initial bundle only carries the
  preferences and the avatar toggle).
- Notifications stop when every Back Office tab is closed (documented in the manual).
- The preferences do not follow the user to another browser until `UserPreferences` gains them.
- `BudgetAlert` / `BudgetExceeded` should be added to `DomainEventEnvelope.type` in the OpenAPI
  contract; until then the UI's `EXTRA_EVENT_TYPES` lists them.
  **Update (2026-10-08):** resolved. `DomainEventEnvelope.type` now lists `BudgetAlert`,
  `BudgetExceeded` and `ConnectionExpiring` (ADR 0030 amendment) and the `EXTRA_EVENT_TYPES` workaround
  was removed. Two notification additions came with ADR 0033 and the ADR 0030 amendment: a write
  confirmation notifies under *your turn* with the title "Confirm a Jira write", and the new type
  "Connection expiring" (on by default for administrators) announces `ConnectionExpiring`.
- OS focus modes (Windows Focus Assist / Do not disturb, macOS Focus) can hide notifications; the
  manual says so.
