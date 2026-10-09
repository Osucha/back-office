import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, NgZone, effect, inject, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { RunsApi } from '../api/runs.service';
import type { DomainEventEnvelope } from '../api/types';
import { IdentityService } from '../auth/identity.service';
import { EventStreamService } from '../events/event-stream.service';
import { activeLocale } from '../i18n/locale';
import { InboxService } from '../state/inbox.service';
import { formatCost } from '../ui/relay/format';
import {
  KIND_SOUND,
  budgetContent,
  ciContent,
  connectionContent,
  gateContent,
  runContent,
  type NotificationContent,
} from './notification-content';
import { decideDelivery } from './notification-prefs';
import { NotificationSettingsService, readPermission } from './notification-settings.service';
import { soundPlayer } from './sounds';

/** Same-origin icon of the notification (the app icon). */
export const NOTIFICATION_ICON = '/favicon.ico';
const CLAIMS_KEY = 'bo.notifications.claims';
const CLAIM_TTL_MS = 60_000;

/**
 * Desktop notifications while a Back Office tab is open (ADR 0032; Web Push for closed tabs is a
 * later option). Lazy: `NotificationSettingsService` loads it once notifications are on.
 *
 * Sources: *your turn* = a gate appearing among the gates the user can decide (`InboxService`, so
 * the server's `canDecide` is the rule; the first load is the baseline and never notifies), a gate
 * assigned to the user (`ApprovalAssigned`); `WorkflowRunFinished` for runs the user started or in
 * projects they own; `CiExecutionFinished` failures in the user's projects; `BudgetAlert` /
 * `BudgetExceeded` for budgets the user can raise; `ConnectionExpiring` for connections the user
 * manages (write confirmations of ADR 0033 are gates and arrive through the inbox). Several open tabs notify once (a claim per tag in
 * localStorage under a Web Lock); the OS also replaces notifications with the same `tag`.
 */
@Injectable({ providedIn: 'root' })
export class DesktopNotifierService {
  private readonly settings = inject(NotificationSettingsService);
  private readonly inbox = inject(InboxService);
  private readonly events = inject(EventStreamService);
  private readonly identity = inject(IdentityService);
  private readonly runs = inject(RunsApi);
  private readonly router = inject(Router);
  private readonly zone = inject(NgZone);
  private readonly doc = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  /** Gates already known (baseline + notified). */
  private readonly seenGates = new Set<string>();
  /** Gates assigned to the user since they were seen: notify again, "Assigned to you". */
  private readonly assignedToMe = new Set<string>();
  private baselined = false;
  private started = false;

  constructor() {
    effect(() => {
      const loaded = this.inbox.loaded();
      const gates = this.inbox.decidable();
      if (!this.identity.isAuthenticated()) {
        this.baselined = false;
        this.seenGates.clear();
        return;
      }
      if (!loaded) return;
      untracked(() => {
        if (!this.baselined) {
          for (const a of gates) if (a.id) this.seenGates.add(a.id);
          this.baselined = true;
          return;
        }
        for (const a of gates) {
          if (!a.id || this.seenGates.has(a.id)) continue;
          this.seenGates.add(a.id);
          const mine = this.assignedToMe.delete(a.id);
          this.deliver(gateContent(a, mine));
        }
      });
    });

    this.events
      .of(
        'ApprovalAssigned',
        'WorkflowRunFinished',
        'CiExecutionFinished',
        'BudgetAlert',
        'BudgetExceeded',
        'ConnectionExpiring',
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((e) => this.onEvent(e));

    // Unlock audio on the first gesture of the page (autoplay policy) when sounds are on.
    const unlock = () => {
      if (this.settings.prefs().sound) soundPlayer().unlock();
    };
    this.doc.addEventListener('pointerdown', unlock, { once: true, capture: true });
    this.doc.addEventListener('keydown', unlock, { once: true, capture: true });
    this.destroyRef.onDestroy(() => soundPlayer().dispose());
  }

  /** Idempotent; the work happens in the constructor (effects need the injection context). */
  start(): void {
    this.started = true;
  }

  isStarted(): boolean {
    return this.started;
  }

  private onEvent(e: DomainEventEnvelope): void {
    const p = (e.payload ?? {}) as Record<string, unknown>;
    const str = (k: string) => (typeof p[k] === 'string' ? (p[k] as string) : null);
    const me = this.identity.user()?.id;
    switch (e.type as string) {
      case 'ApprovalAssigned': {
        const id = str('approvalId');
        const assignees = Array.isArray(p['assignees']) ? (p['assignees'] as unknown[]) : [];
        if (id && me && assignees.includes(me)) {
          this.assignedToMe.add(id);
          this.seenGates.delete(id);
        }
        return;
      }
      case 'WorkflowRunFinished':
        return this.onRunFinished(str('runId'), str('projectId'), str('status'));
      case 'CiExecutionFinished': {
        const id = str('executionId') ?? str('ciExecutionId');
        const projectId = str('projectId');
        const gateFailed = str('gateResult') === 'FAIL';
        if (!id || !(gateFailed || str('status') === 'FAILED')) return;
        if (!this.memberOf(projectId)) return;
        this.deliver(ciContent(id, this.projectKey(projectId), str('entrypoint'), gateFailed));
        return;
      }
      case 'ConnectionExpiring': {
        const projectId = str('projectId');
        // ADR 0035: a personal connection concerns its owner only
        const manages =
          str('scope') === 'USER'
            ? !!str('ownerUserId') && str('ownerUserId') === this.identity.me()?.user?.id
            : projectId
              ? this.identity.canInProject('project.connections.manage', projectId)
              : this.identity.can('admin.connections.manage');
        if (!manages) return;
        this.deliver(
          connectionContent({
            connectionId: str('connectionId'),
            projectId,
            projectKey: this.projectKey(projectId),
            name: str('name'),
            scope: str('scope'),
            daysLeft: typeof p['daysLeft'] === 'number' ? (p['daysLeft'] as number) : 0,
          }),
        );
        return;
      }
      case 'BudgetAlert':
      case 'BudgetExceeded': {
        const platform = str('scope') === 'PLATFORM';
        const projectId = str('projectId');
        const canRaise = platform
          ? this.identity.can('admin.settings.manage')
          : !!projectId && this.identity.canInProject('project.manage', projectId);
        if (!canRaise) return;
        this.deliver(
          budgetContent({
            exceeded: e.type === ('BudgetExceeded' as string),
            platform,
            projectId,
            projectKey: this.projectKey(projectId),
            period: str('period'),
            spent: formatCost(Number(str('spentUsd') ?? 0)),
            limit: formatCost(Number(str('limitUsd') ?? 0)),
            hardStop: p['hardStop'] === true,
          }),
        );
        return;
      }
    }
  }

  private onRunFinished(runId: string | null, projectId: string | null, status: string | null) {
    const failed = status === 'FAILED';
    if (!runId || !(failed || status === 'SUCCEEDED')) return;
    const kind = failed ? 'runFailed' : 'runLanded';
    if (!this.settings.prefs().enabled || !this.settings.prefs().kinds[kind]) return;
    // The event has no starter: read the run (also gives the names for the text).
    this.runs.get(runId).subscribe({
      next: (run) => {
        const me = this.identity.user()?.id;
        const mine = !!me && run.startedBy?.userId === me;
        const owner = this.identity.projectRoles(run.projectId ?? projectId).includes('owner');
        if (mine || owner) this.deliver(runContent(run, failed));
      },
      error: () => undefined,
    });
  }

  private memberOf(projectId: string | null): boolean {
    return (
      !!projectId && (this.identity.me()?.projects ?? []).some((p) => p.projectId === projectId)
    );
  }

  private projectKey(projectId: string | null): string | null {
    if (!projectId) return null;
    return (
      (this.identity.me()?.projects ?? []).find((p) => p.projectId === projectId)?.projectKey ??
      null
    );
  }

  /** Shows and/or sounds one notification, according to the preferences and the tab state. */
  deliver(content: NotificationContent): void {
    const prefs = this.settings.prefs();
    const permission = readPermission();
    this.settings.permission.set(permission);
    const decision = decideDelivery(prefs, content.kind, {
      attended: this.doc.visibilityState === 'visible' && this.doc.hasFocus(),
      permission,
      now: new Date(),
    });
    if (!decision.show && !decision.sound) return;
    void claim(content.tag).then((won) => {
      if (!won) return;
      this.zone.run(() => {
        if (decision.show) this.show(content);
        if (decision.sound) soundPlayer().play(KIND_SOUND[content.kind], prefs.volume);
      });
    });
  }

  /** Shows `content` now, ignoring the preferences (the "Send a test notification" button). */
  show(content: NotificationContent): Notification | null {
    try {
      const n = new Notification(content.title, {
        body: content.body,
        tag: content.tag,
        icon: NOTIFICATION_ICON,
        lang: activeLocale(),
        // Our own sound plays instead of the system one (and can be muted in the preferences).
        silent: true,
      });
      n.onclick = (ev) => {
        ev.preventDefault();
        this.zone.run(() => {
          globalThis.focus?.();
          void this.router.navigateByUrl(content.link);
        });
        n.close();
      };
      return n;
    } catch {
      // e.g. Chrome on Android allows notifications only from a service worker
      return null;
    }
  }
}

/**
 * True for the first tab that claims `tag` within a minute (several Back Office tabs receive the same
 * event). The check-and-set runs under a Web Lock where available, so two tabs cannot both win.
 */
export async function claim(tag: string, now = Date.now()): Promise<boolean> {
  const run = () => {
    try {
      const raw = localStorage.getItem(CLAIMS_KEY);
      const claims = (raw ? JSON.parse(raw) : {}) as Record<string, number>;
      for (const [k, at] of Object.entries(claims)) if (now - at > CLAIM_TTL_MS) delete claims[k];
      if (claims[tag]) return false;
      claims[tag] = now;
      localStorage.setItem(CLAIMS_KEY, JSON.stringify(claims));
      return true;
    } catch {
      return true;
    }
  };
  const locks = (globalThis.navigator as Navigator | undefined)?.locks;
  if (!locks?.request) return run();
  try {
    return await locks.request('bo.notifications', () => run());
  } catch {
    return run();
  }
}
