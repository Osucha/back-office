import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import type { AgentJob, SandboxPolicy } from '../../../core/api/types';
import { formatDuration } from '../../../core/ui/pipes/duration.pipe';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { batonOf } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { isJobActive, jobContext, jobDurationMs } from '../data/fleet-view';
import { formatSeconds, isolationName, policyCaps } from '../data/policy';

/**
 * One agent job in a side panel: state, where it runs, what produced it (links to the run or
 * benchmark), cost, the effective sandbox policy and the hosts the egress proxy refused
 * (highlighted: they are what an agent tried to reach outside its allowlist). Cancel while active.
 */
@Component({
  selector: 'app-agent-job-panel',
  imports: [
    RouterLink,
    NzButtonModule,
    NzIconModule,
    BatonComponent,
    CostComponent,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      [open]="!!job()"
      [title]="title()"
      [subtitle]="job()?.id ?? null"
      [defaultWidth]="600"
      storageKey="backoffice.agentJobPanelWidth"
      bodyLabel="Agent job"
      i18n-bodyLabel="@@fleet.job.panel.bodyLabel"
      (closed)="closed.emit()"
    >
      @if (job(); as j) {
        <span boPanelHeader class="hdr">
          <bo-baton [baton]="baton()" />
          <span class="since" [attr.title]="abs(j.queuedAt)" i18n="@@fleet.job.panel.queuedAgo"
            >queued {{ ago(j.queuedAt) }}</span
          >
        </span>
      }
      @if (job(); as j) {
        @if (j.egressDenied?.length) {
          <section class="denied" aria-labelledby="h-denied">
            <h3 id="h-denied">
              <span class="dot" aria-hidden="true"></span
              ><span i18n="@@fleet.job.panel.egressDenied">Egress denied</span>
            </h3>
            <p i18n="@@fleet.job.panel.egressDeniedText">
              The proxy refused these hosts. They are not on the job's allowlist:
            </p>
            <ul>
              @for (h of j.egressDenied; track h) {
                <li class="mono">{{ h }}</li>
              }
            </ul>
          </section>
        }

        <dl class="facts">
          <div>
            <dt i18n="@@fleet.job.panel.work">Work</dt>
            <dd>
              @if (ctx().link) {
                <a [routerLink]="ctx().link" (click)="closed.emit()">{{ ctx().label }}</a>
              } @else {
                {{ ctx().label }}
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@fleet.job.panel.mode">Mode</dt>
            <dd class="mono">{{ j.mode || '-' }}</dd>
          </div>
          <div>
            <dt i18n="@@fleet.job.panel.model">Model</dt>
            <dd class="mono">
              {{ j.model?.modelId || '-'
              }}{{ j.model?.provider ? ' · ' + j.model?.provider?.toLowerCase() : '' }}
            </dd>
          </div>
          <div>
            <dt i18n="@@fleet.job.panel.pool">Pool</dt>
            <dd>
              @if (j.poolId) {
                <a
                  class="mono"
                  [routerLink]="['/admin/fleet/pools', j.poolId]"
                  (click)="closed.emit()"
                  >{{ j.poolId }}</a
                >
              } @else {
                <span class="muted" i18n="@@fleet.job.panel.notAssigned">not assigned</span>
              }
              @if (j.runnerId) {
                <span class="muted mono" i18n="@@fleet.job.panel.runner">
                  · runner {{ j.runnerId.slice(0, 8) }}</span
                >
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@fleet.job.panel.isolation">Isolation</dt>
            <dd>{{ iso(j.isolation ?? j.policy?.isolation) }}</dd>
          </div>
          @if (j.gitAccess) {
            <div>
              <dt i18n="@@fleet.job.panel.gitAccess">Git access</dt>
              <dd>
                <span class="mono">{{ j.gitAccess }}</span>
                <span class="muted"> · {{ gitAccessText(j.gitAccess) }}</span>
              </dd>
            </div>
          }
          <div>
            <dt i18n="@@fleet.job.panel.duration">Duration</dt>
            <dd class="mono">{{ duration() }}</dd>
          </div>
          <div>
            <dt i18n="@@fleet.job.panel.cost">Cost</dt>
            <dd><bo-cost [value]="j.cost" /></dd>
          </div>
          @if (j.reason) {
            <div>
              <dt i18n="@@fleet.job.panel.reason">Reason</dt>
              <dd class="reason">{{ j.reason }}</dd>
            </div>
          }
        </dl>

        <h3 i18n="@@fleet.job.panel.effectivePolicy">Effective policy</h3>
        @if (j.policy; as p) {
          <dl class="facts policy">
            <div>
              <dt i18n="@@fleet.job.panel.resources">Resources</dt>
              <dd class="mono">{{ resources(p) }}</dd>
            </div>
            <div>
              <dt i18n="@@fleet.job.panel.timeout">Timeout</dt>
              <dd class="mono">{{ secs(p.timeoutSeconds) }}</dd>
            </div>
            <div>
              <dt i18n="@@fleet.job.panel.allowedHosts">Allowed hosts</dt>
              <dd>
                <span class="chips">
                  @for (h of p.network?.allow ?? []; track h) {
                    <span class="chip mono">{{ h }}</span>
                  } @empty {
                    <span class="muted" i18n="@@fleet.job.panel.gatewayOnly"
                      >gateway and git host only</span
                    >
                  }
                </span>
              </dd>
            </div>
            <div>
              <dt i18n="@@fleet.job.panel.tools">Tools</dt>
              <dd>
                <span class="chips">
                  @for (t of p.tools?.allow ?? []; track t) {
                    <span class="chip mono">{{ t }}</span>
                  }
                  @for (t of p.tools?.deny ?? []; track t) {
                    <span class="chip mono deny" [attr.title]="text.denied"
                      ><ng-container i18n="@@fleet.job.panel.notTool"
                        >not {{ t }}</ng-container
                      ></span
                    >
                  }
                </span>
              </dd>
            </div>
            <div>
              <dt i18n="@@fleet.job.panel.image">Image</dt>
              <dd class="mono small">{{ p.environment?.image || '-' }}</dd>
            </div>
            <div>
              <dt i18n="@@fleet.job.panel.caps">Caps</dt>
              <dd class="mono">{{ caps(p) }}</dd>
            </div>
          </dl>
        } @else {
          <p class="muted" i18n="@@fleet.job.panel.policyLater">
            The policy is resolved when a runner leases the job.
          </p>
        }
      }
      <ng-container boPanelFooter>
        @if (job(); as j) {
          @if (canCancel() && active()) {
            <button
              nz-button
              nzDanger
              type="button"
              [nzLoading]="cancelling()"
              (click)="cancelJob.emit(j)"
              i18n="@@fleet.job.panel.cancel"
            >
              Cancel job
            </button>
          }
          @if (j.runId) {
            <a
              nz-button
              [routerLink]="['/runs', j.runId]"
              (click)="closed.emit()"
              i18n="@@fleet.job.panel.openRun"
              >Open run</a
            >
          }
          @if (j.benchmarkId) {
            <a
              nz-button
              [routerLink]="['/evaluation/benchmarks', j.benchmarkId]"
              (click)="closed.emit()"
              i18n="@@fleet.job.panel.openBenchmark"
              >Open benchmark</a
            >
          }
        }
      </ng-container>
    </bo-side-panel>
  `,
  styles: [
    `
      .hdr {
        display: inline-flex;
        align-items: center;
        gap: 8px;
      }
      .since {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .facts {
        margin: 0 0 20px;
        display: grid;
        gap: 6px;
        font-size: 13px;
      }
      .facts > div {
        display: grid;
        grid-template-columns: 120px minmax(0, 1fr);
        gap: 12px;
        align-items: baseline;
      }
      dt {
        color: var(--bo-ink-3);
      }
      dd {
        margin: 0;
        overflow-wrap: anywhere;
      }
      h3 {
        font-size: 13px;
        font-weight: 600;
        margin: 0 0 8px;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .small {
        font-size: 11.5px;
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .reason {
        color: var(--bo-blocked-ink);
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      .chip {
        padding: 0 7px;
        line-height: 20px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        color: var(--bo-ink-2);
        font-size: 11.5px;
      }
      .chip.deny {
        background: transparent;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
      }
      .denied {
        margin: 0 0 16px;
        padding: 10px 14px;
        border-radius: var(--bo-radius-card);
        background: var(--bo-blocked-soft);
        border: 1px solid var(--bo-blocked);
        color: var(--bo-blocked-ink);
      }
      .denied h3 {
        display: flex;
        align-items: center;
        gap: 6px;
        color: var(--bo-blocked-ink);
        margin-bottom: 4px;
      }
      .denied p {
        margin: 0 0 6px;
        font-size: 12.5px;
      }
      .denied ul {
        margin: 0;
        padding-left: 18px;
      }
      .denied li {
        font-weight: 600;
      }
      .dot {
        width: 8px;
        height: 8px;
        border-radius: 2px;
        background: var(--bo-blocked);
        transform: rotate(45deg);
      }
    `,
  ],
})
export class AgentJobPanelComponent {
  readonly job = input<AgentJob | null>(null);
  readonly canCancel = input(false);
  readonly cancelling = input(false);
  readonly closed = output<void>();
  /** "Cancel job" pressed (the parent cancels it). */
  readonly cancelJob = output<AgentJob>();

  readonly baton = computed(() => batonOf.agentJob(this.job()?.status));
  readonly active = computed(() => isJobActive(this.job()?.status));
  readonly ctx = computed(() => jobContext(this.job() ?? {}));
  readonly text = { denied: $localize`:@@fleet.job.panel.denied:denied` };
  readonly title = computed(() => {
    const j = this.job();
    if (!j) return $localize`:@@fleet.job.panel.title:Agent job`;
    const what = j.nodeId ? '· ' + j.nodeId : (j.id ?? '').slice(0, 8);
    return $localize`:@@fleet.job.panel.titleWith:Agent job ${what}:what:`;
  });
  readonly duration = computed(() => {
    const ms = jobDurationMs(this.job() ?? {});
    return ms === null ? $localize`:@@fleet.job.panel.notStarted:not started` : formatDuration(ms);
  });

  resources(p: SandboxPolicy): string {
    const r = p.resources;
    const cpu = r?.cpu ?? '-';
    const memory = r?.memory ?? '-';
    const disk = r?.ephemeralStorage ?? '-';
    const text = $localize`:@@fleet.job.panel.resourcesValue:${cpu}:cpu: CPU · ${memory}:memory: memory · ${disk}:disk: disk`;
    if (!r?.pids) return text;
    const pids = r.pids;
    return text + ' · ' + $localize`:@@fleet.job.panel.pids:${pids}:pids: pids`;
  }

  /** What the git access level lets the job's token do (ADR 0031). */
  gitAccessText(level: string): string {
    switch (level) {
      case 'READ':
        return $localize`:@@fleet.job.panel.gitAccessRead:read-only token, nothing is pushed`;
      case 'PUSH_MR':
        return $localize`:@@fleet.job.panel.gitAccessPush:pushes its branch and opens a merge request, never merges`;
      default:
        return $localize`:@@fleet.job.panel.gitAccessNone:no repository credential`;
    }
  }

  caps = policyCaps;

  ago = timeAgo;
  abs = absoluteTime;
  iso = isolationName;
  secs = formatSeconds;
}
