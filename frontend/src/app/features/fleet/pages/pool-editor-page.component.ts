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
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { FleetApi } from '../../../core/api/fleet.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import type { Project, RunnerPool, RunnerPoolInput, SandboxPolicy } from '../../../core/api/types';
import type { HasUnsavedChanges } from '../../../core/auth/guards';
import { IdentityService } from '../../../core/auth/identity.service';
import { problemOf } from '../../../core/forms/server-errors';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { icu } from '../../../core/i18n/icu';
import { AdminNavComponent } from '../../admin/public-api';
import { AddRunnerPanelComponent } from '../components/add-runner-panel.component';
import { ChipListComponent } from '../components/chip-list.component';
import { SandboxPolicyEditorComponent } from '../components/sandbox-policy-editor.component';
import { emptyPool, poolBaton, poolInputErrors, toPoolInput } from '../data/fleet-view';
import { compactPolicy, formatLabels, parseLabels, type PolicyIssue } from '../data/policy';

const IMAGE_PATTERN =
  /^[a-z0-9.-]+(:\d+)?\/[a-z0-9._/*-]+(:[A-Za-z0-9._*-]+)?(@sha256:[a-f0-9*]+)?$/;

/**
 * Pool editor (`/admin/fleet/pools/new`, `/admin/fleet/pools/:poolId`): identity, labels, allowed
 * images, warm pool and concurrency, project restriction, and the **policy ceiling** every job in
 * the pool is narrowed under. Read-only without `fleet.manage`.
 */
@Component({
  selector: 'app-pool-editor-page',
  imports: [
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzInputNumberModule,
    NzSelectModule,
    ActionBarComponent,
    AddRunnerPanelComponent,
    AdminNavComponent,
    BatonComponent,
    ChipListComponent,
    PageHeaderComponent,
    SandboxPolicyEditorComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      @if (!ready()) {
        <bo-skeleton
          variant="detail"
          label="Loading the pool"
          i18n-label="@@fleet.poolEditor.loading"
        />
      } @else {
        @let d = draft();
        <bo-page-header
          help="fleet.poolEditor"
          [title]="isNew() ? text.newTitle : d.name || d.id"
          [eyebrow]="isNew() ? text.fleet : eyebrow(d.id)"
          [subtitle]="isNew() ? text.newSubtitle : null"
          [menu]="menu()"
        >
          @if (pool(); as p) {
            <bo-baton boHeaderStatus [baton]="baton(p)" />
          }
          @if (pool(); as p) {
            <div boHeaderMeta class="meta">
              <ng-container i18n="@@fleet.poolEditor.meta"
                >{{ p.onlineRunners ?? 0 }} of {{ p.runners ?? 0 }} runners online ·
                {{ p.running ?? 0 }} running · {{ p.queued ?? 0 }} queued</ng-container
              >
              ·
              <a
                [routerLink]="['/admin/fleet/jobs']"
                [queryParams]="{ poolId: p.id }"
                i18n="@@fleet.poolEditor.jobsLink"
                >Agent jobs</a
              >
              ·
              <a
                [routerLink]="['/admin/fleet/runners']"
                [queryParams]="{ poolId: p.id }"
                i18n="@@fleet.poolEditor.runnersLink"
                >Runners</a
              >
            </div>
          }
          @if (pool() && canManage()) {
            <button boHeaderActions nz-button type="button" (click)="addRunner.open(pool()!.id)">
              <nz-icon nzType="plus" />
              <ng-container i18n="@@fleet.pools.rowAddRunner">Add runner</ng-container>
            </button>
          }
        </bo-page-header>

        <form class="layout" (submit)="$event.preventDefault(); save()">
          <section class="card" aria-labelledby="h-pool">
            <h2 id="h-pool" i18n="@@fleet.poolEditor.pool">Pool</h2>
            <div class="grid">
              <div class="field">
                <label for="pool-id" i18n="@@fleet.poolEditor.id">Pool id</label>
                <input
                  nz-input
                  id="pool-id"
                  name="id"
                  class="mono"
                  [placeholder]="examples.id"
                  [ngModel]="d.id"
                  (ngModelChange)="patch({ id: $event.trim() })"
                  [disabled]="!isNew() || readonly()"
                  [attr.aria-invalid]="touched() && errors().id ? 'true' : null"
                />
                @if (touched() && errors().id) {
                  <div class="err">{{ errors().id }}</div>
                } @else {
                  <span class="hint" i18n="@@fleet.poolEditor.idHint"
                    >Used in the chart values and job labels; cannot be changed.</span
                  >
                }
              </div>
              <div class="field">
                <label for="pool-name" i18n="@@fleet.poolEditor.name">Name</label>
                <input
                  nz-input
                  id="pool-name"
                  name="name"
                  placeholder="EU sandbox (gVisor)"
                  i18n-placeholder="@@fleet.poolEditor.namePlaceholder"
                  [ngModel]="d.name"
                  (ngModelChange)="patch({ name: $event })"
                  [disabled]="readonly()"
                />
                @if (touched() && errors().name) {
                  <div class="err">{{ errors().name }}</div>
                }
              </div>
              <div class="field wide">
                <label for="pool-desc" i18n="@@fleet.poolEditor.description">Description</label>
                <input
                  nz-input
                  id="pool-desc"
                  name="description"
                  placeholder="Which cluster, who uses it"
                  i18n-placeholder="@@fleet.poolEditor.descriptionPlaceholder"
                  [ngModel]="d.description ?? ''"
                  (ngModelChange)="patch({ description: $event || null })"
                  [disabled]="readonly()"
                />
              </div>
              <div class="field wide">
                <label for="pool-labels" i18n="@@fleet.poolEditor.labels">Labels</label>
                <input
                  nz-input
                  id="pool-labels"
                  name="labels"
                  class="mono"
                  [placeholder]="examples.labels"
                  [ngModel]="labelsText()"
                  (ngModelChange)="onLabels($event)"
                  [disabled]="readonly()"
                />
                @if (labelsError()) {
                  <div class="err">{{ labelsError() }}</div>
                } @else {
                  <span class="hint" i18n="@@fleet.poolEditor.labelsHint"
                    >Jobs pick a pool by these labels (a template's pool selector).</span
                  >
                }
              </div>
            </div>
          </section>

          <section class="card" aria-labelledby="h-cap">
            <h2 id="h-cap" i18n="@@fleet.poolEditor.capacity">Capacity and images</h2>
            <div class="grid">
              <div class="field">
                <label for="pool-warm" i18n="@@fleet.poolEditor.warm">Warm pool size</label>
                <nz-input-number
                  nzId="pool-warm"
                  name="warm"
                  [nzMin]="0"
                  [nzMax]="100"
                  [ngModel]="d.warmPoolSize"
                  (ngModelChange)="patch({ warmPoolSize: $event ?? 0 })"
                  [nzDisabled]="readonly()"
                />
                @if (touched() && errors().warmPoolSize) {
                  <div class="err">{{ errors().warmPoolSize }}</div>
                } @else {
                  <span class="hint" i18n="@@fleet.poolEditor.warmHint"
                    >Sandboxes kept ready (Agent Sandbox backend only).</span
                  >
                }
              </div>
              <div class="field">
                <label for="pool-max" i18n="@@fleet.poolEditor.max">Max concurrent jobs</label>
                <nz-input-number
                  nzId="pool-max"
                  name="max"
                  [nzMin]="1"
                  [nzMax]="1000"
                  [ngModel]="d.maxConcurrentJobs"
                  (ngModelChange)="patch({ maxConcurrentJobs: $event ?? 1 })"
                  [nzDisabled]="readonly()"
                />
                @if (touched() && errors().maxConcurrentJobs) {
                  <div class="err">{{ errors().maxConcurrentJobs }}</div>
                } @else {
                  <span class="hint" i18n="@@fleet.poolEditor.maxHint"
                    >Across all runners of the pool.</span
                  >
                }
              </div>
              <div class="field wide">
                <span class="label" i18n="@@fleet.poolEditor.images">Allowed images</span>
                <app-chip-list
                  label="Allowed image patterns"
                  i18n-label="@@fleet.poolEditor.imagesLabel"
                  [placeholder]="examples.image"
                  [values]="d.allowedImages ?? []"
                  [validate]="validateImage"
                  [readonly]="readonly()"
                  emptyText="No image allowed yet"
                  i18n-emptyText="@@fleet.poolEditor.imagesEmpty"
                  (valuesChange)="patch({ allowedImages: $event })"
                />
                <span class="hint" i18n="@@fleet.poolEditor.imagesHint"
                  >Image references with * wildcards. Templates can only pick images that
                  match.</span
                >
              </div>
              <div class="field wide">
                <label for="pool-projects" i18n="@@fleet.poolEditor.projects">Projects</label>
                <nz-select
                  nzId="pool-projects"
                  name="projects"
                  nzMode="multiple"
                  nzAllowClear
                  nzShowSearch
                  nzPlaceHolder="Any project"
                  i18n-nzPlaceHolder="@@fleet.pools.anyProject"
                  [ngModel]="d.projectIds ?? []"
                  (ngModelChange)="patch({ projectIds: $event })"
                  [nzDisabled]="readonly()"
                  aria-label="Projects that may use the pool"
                  i18n-aria-label="@@fleet.poolEditor.projectsLabel"
                >
                  @for (p of projects(); track p.id) {
                    <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' · ' + p.name" />
                  }
                </nz-select>
                <span class="hint" i18n="@@fleet.poolEditor.projectsHint"
                  >Empty: any project may use the pool.</span
                >
              </div>
            </div>
          </section>

          <section class="card" aria-labelledby="h-ceiling">
            <h2 id="h-ceiling" i18n="@@fleet.poolEditor.ceiling">Policy ceiling</h2>
            <p class="lede" i18n="@@fleet.poolEditor.ceilingLede">
              The most any job in this pool may get. Projects, templates and workflow nodes can only
              narrow it: stronger isolation, smaller limits, fewer hosts and tools.
            </p>
            <app-sandbox-policy-editor
              [policy]="d.ceiling"
              [allowedImages]="d.allowedImages ?? []"
              [readonly]="readonly()"
              [sections]="[
                'isolation',
                'resources',
                'network',
                'tools',
                'secrets',
                'environment',
                'caps',
              ]"
              [serverIssues]="serverIssues()"
              ceilingLabel="The platform"
              i18n-ceilingLabel="@@fleet.poolEditor.platform"
              (policyChange)="patch({ ceiling: $event })"
              (issuesChange)="policyIssues.set($event)"
            />
          </section>

          @if (!readonly()) {
            <bo-action-bar
              [dirty]="dirty()"
              [saving]="saving()"
              [canSave]="valid()"
              [saveLabel]="isNew() ? text.create : text.save"
              (save)="save()"
            >
              <button
                nz-button
                type="button"
                [disabled]="!dirty()"
                (click)="reset()"
                i18n="@@fleet.poolEditor.discard"
              >
                Discard changes
              </button>
              <span boActionStatus class="status">{{ statusText() }}</span>
            </bo-action-bar>
          }
        </form>
      }
    </div>

    <app-add-runner-panel
      [pool]="pool()"
      [open]="!!addRunner.value() && canManage()"
      (created)="load()"
      (closed)="addRunner.close()"
    />
  `,
  styles: [
    `
      .meta {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .meta a {
        text-decoration: underline;
        text-underline-offset: 2px;
      }
      .layout {
        display: flex;
        flex-direction: column;
        gap: 16px;
        max-width: 1080px;
      }
      .card {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 16px 20px 20px;
      }
      h2 {
        margin: 0 0 12px;
        font-size: 16px;
        font-weight: 600;
      }
      .lede {
        margin: -4px 0 16px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        max-width: 80ch;
      }
      .grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 14px 20px;
      }
      .wide {
        grid-column: 1 / -1;
      }
      @media (max-width: 900px) {
        .grid {
          grid-template-columns: 1fr;
        }
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: 4px;
        min-width: 0;
      }
      .field label,
      .field .label {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      .field nz-input-number {
        width: 100%;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .hint {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .err {
        font-size: 12px;
        color: var(--bo-blocked-ink);
      }
      .status {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class PoolEditorPageComponent implements HasUnsavedChanges {
  private readonly api = inject(FleetApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly identity = inject(IdentityService);
  private readonly router = inject(Router);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly confirm = inject(ConfirmService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly crumbs = inject(BreadcrumbService);

  /** Route parameter; absent on `/admin/fleet/pools/new`. */
  readonly poolId = input<string | undefined>();

  readonly addRunner = panelParam('addRunner');
  readonly pool = signal<RunnerPool | null>(null);
  readonly draft = signal<RunnerPoolInput>(emptyPool());
  private readonly initial = signal(JSON.stringify(emptyPool()));
  readonly ready = signal(false);
  readonly saving = signal(false);
  readonly touched = signal(false);
  readonly projects = signal<Project[]>([]);
  readonly labelsText = signal('');
  readonly labelsError = signal<string | null>(null);
  readonly policyIssues = signal<PolicyIssue[]>([]);
  readonly serverIssues = signal<PolicyIssue[]>([]);

  readonly text = {
    newTitle: $localize`:@@fleet.poolEditor.newTitle:New runner pool`,
    fleet: $localize`:@@fleet.title:Runner fleet`,
    newSubtitle: $localize`:@@fleet.poolEditor.newSubtitle:A pool is a set of runner-controllers with the same capabilities and policy ceiling.`,
    create: $localize`:@@fleet.poolEditor.create:Create pool`,
    save: $localize`:@@fleet.poolEditor.save:Save pool`,
  };
  /** Sample values (identifiers, the same in every language). */
  readonly examples = {
    id: 'eu-gvisor', // i18n-ignore: sample id
    labels: 'region=eu, gpu=no', // i18n-ignore: labels
    image: 'ghcr.io/org/back-office-runner:*', // i18n-ignore: image pattern
  };
  readonly isNew = computed(() => !this.poolId());
  readonly canManage = computed(() => this.identity.can('fleet.manage'));
  readonly readonly = computed(() => !this.canManage());
  readonly errors = computed(() => poolInputErrors(this.draft()));
  readonly dirty = computed(() => JSON.stringify(this.normalized()) !== this.initial());
  readonly valid = computed(
    () =>
      !Object.keys(this.errors()).length &&
      !this.labelsError() &&
      !this.policyIssues().some((i) => !this.serverIssues().includes(i)),
  );
  readonly statusText = computed(() => {
    const n = Object.keys(this.errors()).length + this.policyIssues().length;
    return n
      ? icu(
          $localize`:@@fleet.poolEditor.attention:{count, plural, one {# field needs attention} other {# fields need attention}}`,
          { count: n },
        )
      : '';
  });
  readonly menu = computed(() =>
    this.canManage() && !this.isNew()
      ? [
          {
            label: $localize`:@@fleet.poolEditor.delete:Delete pool`,
            icon: 'delete',
            danger: true,
            run: () => void this.remove(),
          },
        ]
      : [],
  );

  baton = poolBaton;

  eyebrow(id: string): string {
    return $localize`:@@fleet.poolEditor.eyebrow:Runner pool · ${id}:pool:`;
  }

  constructor() {
    this.projectsApi
      .list()
      .subscribe({ next: (p) => this.projects.set(p), error: () => undefined });
    effect(() => {
      const id = this.poolId();
      untracked(() => (id ? this.load(id) : this.startNew()));
    });
  }

  hasUnsavedChanges(): boolean {
    return !this.readonly() && this.dirty() && !this.saving();
  }

  readonly validateImage = (v: string): string | null =>
    IMAGE_PATTERN.test(v)
      ? null
      : $localize`:@@fleet.poolEditor.imageError:Use an image reference such as ghcr.io/org/back-office-runner:*`;

  patch(changes: Partial<RunnerPoolInput>): void {
    this.draft.update((d) => ({ ...d, ...changes }));
    if ('ceiling' in changes) this.serverIssues.set([]);
  }

  onLabels(text: string): void {
    this.labelsText.set(text);
    const { labels, error } = parseLabels(text);
    this.labelsError.set(error);
    if (!error) this.patch({ labels });
  }

  reset(): void {
    this.apply(JSON.parse(this.initial()) as RunnerPoolInput);
  }

  load(id = this.poolId()!): void {
    this.api.pool(id).subscribe({
      next: (p) => {
        this.pool.set(p);
        this.crumbs.set([
          { label: $localize`:@@fleet.breadcrumb.admin:Administration`, link: '/admin' },
          { label: $localize`:@@fleet.title:Runner fleet`, link: '/admin/fleet' },
          { label: p.name || p.id },
        ]);
        // Keep unsaved edits when only the counters were refreshed (after "Add runner").
        if (!this.ready() || !this.dirty()) {
          const input = toPoolInput(p);
          this.initial.set(JSON.stringify(this.normalize(input)));
          this.apply(input);
        }
        this.ready.set(true);
      },
      error: () => this.ready.set(true),
    });
  }

  save(): void {
    this.touched.set(true);
    if (!this.valid() || this.readonly()) return;
    const body = this.normalized();
    this.saving.set(true);
    const req = this.isNew() ? this.api.createPool(body) : this.api.updatePool(body.id, body);
    this.pendingSaves.track(req).subscribe({
      next: (p) => {
        this.saving.set(false);
        this.pool.set(p);
        const input = toPoolInput(p);
        this.initial.set(JSON.stringify(this.normalize(input)));
        this.apply(input);
        const id = p.id;
        this.message.success(
          this.isNew()
            ? $localize`:@@fleet.poolEditor.created:Pool ${id}:pool: created`
            : $localize`:@@fleet.poolEditor.saved:Pool saved`,
        );
        if (this.isNew()) void this.router.navigate(['/admin/fleet/pools', p.id]);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.reportError(err);
      },
    });
  }

  async remove(): Promise<void> {
    const p = this.pool();
    if (!p) return;
    const id = p.id;
    const ok = await this.confirm.danger(
      $localize`:@@fleet.poolEditor.delete.title:Delete pool ${id}:pool:?`,
      $localize`:@@fleet.poolEditor.delete.text:Its runners are revoked and queued jobs of the pool fail. Deleting is refused while jobs are running.`,
      $localize`:@@fleet.poolEditor.delete:Delete pool`,
    );
    if (!ok) return;
    this.api.deletePool(p.id).subscribe(() => {
      this.message.success($localize`:@@fleet.poolEditor.deleted:Pool ${id}:pool: deleted`);
      this.initial.set(JSON.stringify(this.normalized()));
      void this.router.navigate(['/admin/fleet/pools']);
    });
  }

  private startNew(): void {
    const input = emptyPool();
    this.pool.set(null);
    this.initial.set(JSON.stringify(this.normalize(input)));
    this.apply(input);
    this.crumbs.set([
      { label: $localize`:@@fleet.breadcrumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@fleet.title:Runner fleet`, link: '/admin/fleet' },
      { label: $localize`:@@fleet.pools.newPool:New pool` },
    ]);
    this.ready.set(true);
  }

  private apply(input: RunnerPoolInput): void {
    this.draft.set(structuredClone(input));
    this.labelsText.set(formatLabels(input.labels));
    this.labelsError.set(null);
    this.serverIssues.set([]);
    this.touched.set(false);
  }

  normalized(): RunnerPoolInput {
    return this.normalize(this.draft());
  }

  private normalize(d: RunnerPoolInput): RunnerPoolInput {
    return {
      ...d,
      name: d.name.trim(),
      description: d.description?.trim() || null,
      ceiling: compactPolicy(d.ceiling ?? ({} as SandboxPolicy)),
      allowedImages: [...new Set(d.allowedImages ?? [])],
      projectIds: d.projectIds ?? [],
    };
  }

  /** Field errors under `ceiling.*` go to the policy editor, the rest into one notification. */
  private reportError(err: unknown): void {
    const problem = problemOf(err);
    const issues: PolicyIssue[] = [];
    const other: string[] = [];
    for (const e of problem?.errors ?? []) {
      const field = (e.field ?? '').replace(/\[(\d+)\]/g, '.$1');
      if (field.startsWith('ceiling.'))
        issues.push({
          path: field.slice(8),
          message: e.message ?? $localize`:@@fleet.projectPolicy.invalidValue:Invalid value`,
        });
      else other.push(e.field ? `${e.field}: ${e.message}` : (e.message ?? ''));
    }
    this.serverIssues.set(issues);
    if (!issues.length || other.length)
      this.notify.error(
        $localize`:@@fleet.poolEditor.saveFailed:Could not save the pool`,
        [
          problem?.detail ||
            problem?.title ||
            $localize`:@@fleet.poolEditor.requestFailed:Request failed`,
          ...other,
        ].join(' · '),
      );
  }
}
