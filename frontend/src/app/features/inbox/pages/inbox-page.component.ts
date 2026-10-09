import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzSegmentedModule } from 'ng-zorro-antd/segmented';
import { ApprovalsApi } from '../../../core/api/approvals.service';
import { icu } from '../../../core/i18n/icu';
import type { ApprovalRequest, ApprovalStatus } from '../../../core/api/types';
import { InboxService } from '../../../core/state/inbox.service';
import { TurnQueueService } from '../../../core/state/turn-queue.service';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { batonOf } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import type { TurnItem } from '../../../core/ui/relay/turn';
import { TurnCardComponent } from '../../../core/ui/relay/turn-card.component';
import { ApprovalDetailDrawerComponent } from '../components/approval-detail-drawer.component';
import { assignedToText } from '../data/approval-assignment';

type View = 'turn' | 'others' | 'DECIDED' | 'EXPIRED';

/**
 * Your turn: the queue of things that need you (gates you can decide, failed runs, failed CI),
 * keyboard navigable (`j` / `k`, `Enter`, `a` opens a gate on its checklist). Gates open in the
 * gate side panel; `/inbox/:approvalId` deep-links one.
 */
@Component({
  selector: 'app-inbox-page',
  imports: [
    FormsModule,
    NzButtonModule,
    NzIconModule,
    NzSegmentedModule,
    BatonComponent,
    EmptyComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
    TurnCardComponent,
    ApprovalDetailDrawerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="inbox"
        title="Your turn"
        i18n-title="@@inbox.page.title"
        subtitle="Gates to decide and failures to look at. Runs wait for you before they go on."
        i18n-subtitle="@@inbox.page.subtitle"
      >
        <nz-segmented
          boHeaderActions
          [nzOptions]="viewOptions()"
          [ngModel]="view()"
          (ngModelChange)="setView($event)"
          aria-label="Show"
          i18n-aria-label="@@inbox.page.show"
        />
        <button
          boHeaderActions
          nz-button
          type="button"
          (click)="reload()"
          aria-label="Refresh"
          i18n-aria-label="@@inbox.page.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
      </bo-page-header>

      @switch (view()) {
        @case ('turn') {
          @if (!queue.loaded() && !items().length) {
            <bo-skeleton variant="cards" [rows]="4" />
          } @else if (!items().length) {
            <bo-empty
              tone="calm"
              holder="done"
              holderLabel="all clear"
              i18n-holderLabel="@@inbox.page.allClear"
              title="Nothing needs you right now."
              i18n-title="@@inbox.page.empty"
              [detail]="calmDetail()"
              actionLabel="See runs in flight"
              i18n-actionLabel="@@inbox.page.seeInFlight"
              [primary]="false"
              (action)="router.navigate(['/runs'], { queryParams: { holder: 'agent,ci' } })"
              help="gates"
              guideLabel="How gates work"
              i18n-guideLabel="@@inbox.page.guide"
            />
          } @else {
            <ol class="queue" aria-label="Your turn" i18n-aria-label="@@inbox.page.queue">
              @for (t of items(); track t.id; let i = $index) {
                <li [attr.data-nav-index]="i" tabindex="-1">
                  <bo-turn-card
                    [item]="t"
                    [active]="nav.cursor() === i"
                    (open)="open(t, i)"
                    (primary)="act(t, i)"
                  />
                </li>
              }
            </ol>
            <p class="hint muted" i18n="@@inbox.page.keysHint">
              <kbd>j</kbd> <kbd>k</kbd> to move, <kbd>Enter</kbd> to open, <kbd>a</kbd> to review a
              gate.
            </p>
          }
        }
        @case ('others') {
          @if (!others().length) {
            <bo-empty
              tone="quiet"
              title="No gates are waiting for other people."
              i18n-title="@@inbox.page.othersEmpty"
            />
          } @else {
            <ul class="rows">
              @for (a of others(); track a.id) {
                <li>
                  <button type="button" class="row with-assignee" (click)="openApproval(a)">
                    <bo-baton [baton]="batonFor(a)" size="sm" />
                    <span class="row-title">{{ a.question }}</span>
                    <span class="row-where mono">{{ a.projectKey }}</span>
                    <span class="row-where">{{ a.issueKey || a.workflowName }}</span>
                    <span class="row-where" [attr.title]="assignedLabel(a)">{{
                      assignedLabel(a)
                    }}</span>
                    <span class="row-age" [attr.title]="abs(a.createdAt)">{{
                      ago(a.createdAt)
                    }}</span>
                  </button>
                </li>
              }
            </ul>
          }
        }
        @default {
          @if (historyLoading() && !history().length) {
            <bo-skeleton variant="list" [rows]="6" />
          } @else if (!history().length) {
            <bo-empty
              tone="quiet"
              [title]="view() === 'DECIDED' ? text.noDecided : text.noExpired"
            />
          } @else {
            <ul class="rows">
              @for (a of history(); track a.id) {
                <li>
                  <button type="button" class="row" (click)="openApproval(a)">
                    <bo-baton [baton]="batonFor(a)" size="sm" />
                    <span class="row-title">{{ a.question }}</span>
                    <span class="row-where mono">{{ a.projectKey }}</span>
                    <span class="row-where">{{ a.issueKey || a.workflowName }}</span>
                    @if (a.decision?.decidedBy) {
                      <bo-provenance
                        class="row-prov"
                        [actor]="a.decision?.decidedBy"
                        [at]="a.decision?.decidedAt"
                      />
                    } @else {
                      <span class="row-age">{{ ago(a.createdAt) }}</span>
                    }
                  </button>
                </li>
              }
            </ul>
          }
        }
      }
    </div>

    <app-approval-detail-drawer
      [approval]="selected()"
      [focus]="focusChecklist() ? 'checklist' : null"
      (closed)="closePanel()"
      (decided)="onDecided()"
      (changed)="onChanged($event)"
    />
  `,
  styles: [
    `
      .queue {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 8px;
        max-width: 920px;
      }
      .queue li {
        outline: none;
      }
      .hint {
        margin-top: 12px;
        font-size: 12px;
      }
      kbd {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        padding: 0 4px;
        border: 1px solid var(--bo-line-strong);
        border-radius: 4px;
        background: var(--bo-surface);
      }
      .rows {
        list-style: none;
        margin: 0;
        padding: 0;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        max-width: 1100px;
      }
      .rows li + li {
        border-top: 1px solid var(--bo-line);
      }
      .row {
        all: unset;
        box-sizing: border-box;
        display: grid;
        grid-template-columns: 150px minmax(0, 1fr) 80px 180px 150px;
        align-items: center;
        gap: 12px;
        width: 100%;
        min-height: calc(var(--bo-row-h) + 12px);
        padding: 6px 14px;
        cursor: pointer;
        font-size: 13px;
      }
      .row.with-assignee {
        grid-template-columns: 150px minmax(0, 1fr) 80px 150px 180px 110px;
      }
      .row:hover {
        background: var(--bo-surface-2);
      }
      .row:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .row-title {
        font-weight: 500;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .row-where,
      .row-age {
        color: var(--bo-ink-3);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 12px;
      }
      .row-prov {
        min-width: 0;
      }
      @media (max-width: 900px) {
        .row,
        .row.with-assignee {
          grid-template-columns: 130px minmax(0, 1fr);
        }
        .row-where,
        .row-age,
        .row-prov {
          display: none;
        }
      }
    `,
  ],
})
export class InboxPageComponent {
  private readonly api = inject(ApprovalsApi);
  readonly inbox = inject(InboxService);
  readonly queue = inject(TurnQueueService);
  readonly router = inject(Router);

  /** Bound from the route `/inbox/:approvalId` to deep-link a specific approval. */
  readonly approvalId = input<string | undefined>();

  readonly view = signal<View>('turn');
  readonly history = signal<ApprovalRequest[]>([]);
  readonly historyLoading = signal(false);
  readonly selected = signal<ApprovalRequest | null>(null);
  readonly focusChecklist = signal(false);

  readonly items = this.queue.items;
  readonly others = computed(() => this.inbox.open().filter((a) => a.canDecide === false));
  readonly viewOptions = computed(() => [
    {
      label:
        $localize`:@@inbox.view.needsYou:Needs you` +
        (this.items().length ? ' ' + this.items().length : ''),
      value: 'turn',
    },
    {
      label:
        $localize`:@@inbox.view.withOthers:With others` +
        (this.others().length ? ' ' + this.others().length : ''),
      value: 'others',
    },
    { label: $localize`:@@inbox.view.decided:Decided`, value: 'DECIDED' },
    { label: $localize`:@@inbox.view.expired:Expired`, value: 'EXPIRED' },
  ]);
  readonly text = {
    noDecided: $localize`:@@inbox.page.noDecided:No decided gates yet.`,
    noExpired: $localize`:@@inbox.page.noExpired:No expired gates yet.`,
  };
  readonly calmDetail = computed(() => {
    const waiting = this.others().length;
    return waiting
      ? icu(
          $localize`:@@inbox.page.othersWaiting:{count, plural, one {# gate waits for other approvers.} other {# gates wait for other approvers.}}`,
          { count: waiting },
        )
      : $localize`:@@inbox.page.calm:Agents and CI hold the baton; you will see it here when they hand it back.`;
  });

  readonly nav = listNavigation({
    items: this.items,
    open: (t, i) => this.open(t, i),
    group: $localize`:@@inbox.page.shortcutGroup:Your turn`,
    when: () => this.view() === 'turn' && !this.selected(),
  });

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@inbox.page.crumb:Your turn` }]);
    this.queue.ensure();
    inject(ShortcutsService).register(
      {
        keys: 'a',
        description: $localize`:@@inbox.page.shortcut.review:Review the gate under the cursor`,
        group: $localize`:@@inbox.page.shortcut.group:Your turn`,
        when: () => !this.selected() && this.items()[this.nav.cursor()]?.kind === 'gate',
        handler: () => this.act(this.items()[this.nav.cursor()], this.nav.cursor()),
      },
      inject(DestroyRef),
    );
    effect(() => {
      const id = this.approvalId();
      untracked(() => {
        if (!id) return;
        const known =
          this.inbox.open().find((a) => a.id === id) ?? this.history().find((a) => a.id === id);
        if (known) this.selected.set(known);
        else this.api.get(id).subscribe((a) => this.selected.set(a));
      });
    });
  }

  batonFor = (a: ApprovalRequest) => batonOf.approval(a);
  assignedLabel = (a: ApprovalRequest) =>
    $localize`:@@inbox.page.assignedTo:Assigned to: ${assignedToText(a)}:who:`;
  ago = timeAgo;
  abs = absoluteTime;

  setView(value: View): void {
    this.view.set(value);
    if (value === 'DECIDED' || value === 'EXPIRED') this.loadHistory(value);
  }

  reload(): void {
    this.queue.refresh();
    const v = this.view();
    if (v === 'DECIDED' || v === 'EXPIRED') this.loadHistory(v);
  }

  /** Card click / Enter: open the item. */
  open(t: TurnItem, index: number): void {
    this.nav.set(index);
    if (t.approval) this.openApproval(t.approval);
    else void this.router.navigate(t.link as unknown[], { queryParams: t.queryParams });
  }

  /** Primary action / `a`: gates open on the checklist, never approve from the list. */
  act(t: TurnItem | undefined, index: number): void {
    if (!t) return;
    this.nav.set(index);
    if (t.approval) this.openApproval(t.approval, true);
    else void this.router.navigate(t.link as unknown[], { queryParams: t.queryParams });
  }

  openApproval(a: ApprovalRequest, checklist = false): void {
    this.focusChecklist.set(checklist);
    this.selected.set(a);
    void this.router.navigate(['/inbox', a.id], { replaceUrl: true });
  }

  closePanel(): void {
    this.selected.set(null);
    if (this.approvalId()) void this.router.navigate(['/inbox'], { replaceUrl: true });
  }

  /** The gate was assigned to other users: show the new state, then reconcile the queue. */
  onChanged(updated: ApprovalRequest): void {
    this.selected.set(updated);
    this.queue.refresh();
  }

  onDecided(): void {
    this.closePanel();
    const v = this.view();
    if (v === 'DECIDED' || v === 'EXPIRED') this.loadHistory(v);
  }

  private loadHistory(status: ApprovalStatus): void {
    this.historyLoading.set(true);
    this.api.list(status).subscribe({
      next: (list) => {
        this.history.set(list);
        this.historyLoading.set(false);
      },
      error: () => this.historyLoading.set(false),
    });
  }
}
