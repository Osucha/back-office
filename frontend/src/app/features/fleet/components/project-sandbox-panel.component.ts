import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzMessageService } from 'ng-zorro-antd/message';
import { FleetApi } from '../../../core/api/fleet.service';
import type { SandboxPolicy, SandboxPolicyView } from '../../../core/api/types';
import { problemOf } from '../../../core/forms/server-errors';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import {
  SOURCE_LABELS,
  compactPolicy,
  formatSeconds,
  isolationName,
  policyCaps,
  type PolicyIssue,
} from '../data/policy';
import { SandboxPolicyEditorComponent } from './sandbox-policy-editor.component';

/**
 * Project settings tab "Sandbox": the project's own policy (`GET/PUT
 * /projects/{id}/sandbox-policy`) edited under the pool/platform ceiling, next to the effective
 * policy with the level that set each field. Read-only without `project.manage`.
 */
@Component({
  selector: 'app-project-sandbox-panel',
  imports: [
    NgTemplateOutlet,
    NzButtonModule,
    ActionBarComponent,
    EmptyComponent,
    SandboxPolicyEditorComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loading() && !view()) {
      <bo-skeleton
        variant="detail"
        label="Loading the sandbox policy"
        i18n-label="@@fleet.projectPolicy.loading"
      />
    } @else if (!view()) {
      <bo-empty
        title="The sandbox policy is not available."
        i18n-title="@@fleet.projectPolicy.unavailable"
        detail="This backend does not expose /projects/{id}/sandbox-policy yet."
        i18n-detail="@@fleet.projectPolicy.unavailableDetail"
        actionLabel="Try again"
        i18n-actionLabel="@@fleet.projectPolicy.tryAgain"
        (action)="load()"
      />
    } @else if (view(); as v) {
      <div class="split">
        <section class="card" aria-labelledby="h-proj-policy">
          @if (canManage()) {
            <h2 id="h-proj-policy" i18n="@@fleet.projectPolicy.title">Project policy</h2>
          } @else {
            <h2 id="h-proj-policy" i18n="@@fleet.projectPolicy.titleReadOnly">
              Project policy (read-only)
            </h2>
          }
          <p class="lede" i18n="@@fleet.projectPolicy.lede">
            Applies to every agent job of this project. Task templates and workflow nodes can narrow
            it further; nothing here can go past the ceiling of the pool and the platform.
          </p>
          <app-sandbox-policy-editor
            [policy]="draft()"
            [ceiling]="v.ceiling"
            ceilingLabel="The pool and platform"
            i18n-ceilingLabel="@@fleet.projectPolicy.ceilingLabel"
            [sources]="v.sources ?? null"
            [readonly]="!canManage()"
            [serverIssues]="serverIssues()"
            (policyChange)="onChange($event)"
            (issuesChange)="issues.set($event)"
          />
          @if (canManage()) {
            <bo-action-bar
              [dirty]="dirty()"
              [saving]="saving()"
              [canSave]="!localIssues().length"
              saveLabel="Save sandbox policy"
              i18n-saveLabel="@@fleet.projectPolicy.save"
              (save)="save()"
            >
              <button
                nz-button
                type="button"
                [disabled]="!dirty()"
                (click)="reset()"
                i18n="@@fleet.projectPolicy.discard"
              >
                Discard changes
              </button>
            </bo-action-bar>
          }
        </section>
        <aside class="card effective" aria-labelledby="h-eff">
          <h2 id="h-eff" i18n="@@fleet.projectPolicy.effective">Effective for this project</h2>
          <p class="lede" i18n="@@fleet.projectPolicy.effectiveLede">
            What a job gets today, after platform, pool and project.
          </p>
          @if (v.effective; as e) {
            <dl>
              <div>
                <dt i18n="@@fleet.projectPolicy.isolation">Isolation</dt>
                <dd>
                  {{ iso(e.isolation) }}
                  <ng-container *ngTemplateOutlet="badge; context: { $implicit: 'isolation' }" />
                </dd>
              </div>
              <div>
                <dt i18n="@@fleet.projectPolicy.resources">Resources</dt>
                <dd class="mono">
                  {{ resources(e) }}
                  <ng-container *ngTemplateOutlet="badge; context: { $implicit: 'resources' }" />
                </dd>
              </div>
              <div>
                <dt i18n="@@fleet.projectPolicy.timeout">Timeout</dt>
                <dd class="mono">
                  {{ secs(e.timeoutSeconds) }}
                  <ng-container
                    *ngTemplateOutlet="badge; context: { $implicit: 'timeoutSeconds' }"
                  />
                </dd>
              </div>
              <div>
                <dt i18n="@@fleet.projectPolicy.hosts">Hosts</dt>
                <dd>
                  @for (h of e.network?.allow ?? []; track h) {
                    <span class="chip mono">{{ h }}</span>
                  } @empty {
                    <span class="muted" i18n="@@fleet.projectPolicy.gatewayOnly"
                      >gateway and git host only</span
                    >
                  }
                  <ng-container
                    *ngTemplateOutlet="badge; context: { $implicit: 'network.allow' }"
                  />
                </dd>
              </div>
              <div>
                <dt i18n="@@fleet.projectPolicy.deniedTools">Denied tools</dt>
                <dd>
                  @for (t of e.tools?.deny ?? []; track t) {
                    <span class="chip mono">{{ t }}</span>
                  } @empty {
                    <span class="muted" i18n="@@fleet.projectPolicy.none">none</span>
                  }
                  <ng-container *ngTemplateOutlet="badge; context: { $implicit: 'tools.deny' }" />
                </dd>
              </div>
              <div>
                <dt i18n="@@fleet.projectPolicy.image">Image</dt>
                <dd class="mono small">
                  {{ e.environment?.image ?? '-' }}
                  <ng-container
                    *ngTemplateOutlet="badge; context: { $implicit: 'environment.image' }"
                  />
                </dd>
              </div>
              <div>
                <dt i18n="@@fleet.projectPolicy.caps">Caps</dt>
                <dd class="mono">{{ caps(e) }}</dd>
              </div>
            </dl>
          }
        </aside>
      </div>
    }
    <ng-template #badge let-path>
      @if (view()?.sources?.[path]; as level) {
        <span class="src" [attr.title]="setBy(level)">{{
          labels[level] || level.toLowerCase()
        }}</span>
      }
    </ng-template>
  `,
  styles: [
    `
      .split {
        display: grid;
        grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
        gap: 16px;
        align-items: start;
      }
      @media (max-width: 1100px) {
        .split {
          grid-template-columns: 1fr;
        }
      }
      .card {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 16px 20px;
        min-width: 0;
      }
      .effective {
        position: sticky;
        top: calc(var(--bo-topbar-h) + 16px);
      }
      h2 {
        margin: 0 0 4px;
        font-size: 16px;
        font-weight: 600;
      }
      .lede {
        margin: 0 0 16px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      dl {
        margin: 0;
        display: grid;
        gap: 10px;
        font-size: 13px;
      }
      dl > div {
        display: grid;
        grid-template-columns: 96px minmax(0, 1fr);
        gap: 8px;
      }
      dt {
        color: var(--bo-ink-3);
        font-size: 12px;
      }
      dd {
        margin: 0;
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        align-items: center;
        overflow-wrap: anywhere;
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
      .chip {
        padding: 0 7px;
        line-height: 20px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        color: var(--bo-ink-2);
        font-size: 11.5px;
      }
      .src {
        font-family: var(--bo-font-ui);
        font-size: 10.5px;
        padding: 0 6px;
        line-height: 16px;
        border-radius: 999px;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
        color: var(--bo-ink-2);
      }
    `,
  ],
})
export class ProjectSandboxPanelComponent {
  private readonly api = inject(FleetApi);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly pendingSaves = inject(PendingSavesService);

  readonly projectId = input.required<string>();
  readonly canManage = input(false);

  readonly view = signal<SandboxPolicyView | null>(null);
  readonly draft = signal<SandboxPolicy>({});
  private readonly initial = signal('{}');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly issues = signal<PolicyIssue[]>([]);
  readonly serverIssues = signal<PolicyIssue[]>([]);
  readonly localIssues = computed(() =>
    this.issues().filter((i) => !this.serverIssues().includes(i)),
  );
  readonly dirty = computed(() => JSON.stringify(compactPolicy(this.draft())) !== this.initial());
  readonly labels = SOURCE_LABELS;
  iso = isolationName;
  secs = formatSeconds;
  caps = policyCaps;

  resources(e: SandboxPolicy): string {
    const cpu = e.resources?.cpu ?? '-';
    const memory = e.resources?.memory ?? '-';
    const disk = e.resources?.ephemeralStorage ?? '-';
    return $localize`:@@fleet.projectPolicy.resourcesValue:${cpu}:cpu: CPU · ${memory}:memory: · ${disk}:disk: disk`;
  }

  setBy(level: string): string {
    const name = this.labels[level] || level.toLowerCase();
    return $localize`:@@fleet.projectPolicy.setBy:Set by: ${name}:level:`;
  }

  constructor() {
    effect(() => {
      this.projectId();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.loading.set(true);
    this.api.projectPolicy(this.projectId()).subscribe({
      next: (v) => this.apply(v),
      error: () => this.loading.set(false),
    });
  }

  onChange(p: SandboxPolicy): void {
    this.draft.set(p);
    this.serverIssues.set([]);
  }

  reset(): void {
    this.draft.set(JSON.parse(this.initial()) as SandboxPolicy);
    this.serverIssues.set([]);
  }

  save(): void {
    const body = compactPolicy(this.draft());
    this.saving.set(true);
    this.pendingSaves.track(this.api.updateProjectPolicy(this.projectId(), body)).subscribe({
      next: (v) => {
        this.saving.set(false);
        this.apply(v);
        this.message.success($localize`:@@fleet.projectPolicy.saved:Sandbox policy saved`);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        const problem = problemOf(err);
        const issues = (problem?.errors ?? []).map((e) => ({
          path: (e.field ?? '').replace(/\[(\d+)\]/g, '.$1'),
          message: e.message ?? $localize`:@@fleet.projectPolicy.invalidValue:Invalid value`,
        }));
        this.serverIssues.set(issues);
        if (
          !issues.length &&
          !(err instanceof HttpErrorResponse && [0, 401, 403].includes(err.status))
        )
          this.notify.error(
            $localize`:@@fleet.projectPolicy.saveFailed:Could not save the sandbox policy`,

            problem?.detail || problem?.title || '',
          );
      },
    });
  }

  private apply(v: SandboxPolicyView): void {
    this.view.set(v);
    const p = compactPolicy(v.project ?? {});
    this.initial.set(JSON.stringify(p));
    this.draft.set(p);
    this.serverIssues.set([]);
    this.loading.set(false);
  }
}
