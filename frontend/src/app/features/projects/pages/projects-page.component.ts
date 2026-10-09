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
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDrawerModule } from 'ng-zorro-antd/drawer';
import { NzMessageService } from 'ng-zorro-antd/message';
import { catchError, forkJoin, map, of } from 'rxjs';
import { MembersApi } from '../../../core/api/members.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import { RunsApi } from '../../../core/api/runs.service';
import type { Project, ProjectInput, WorkflowRunSummary } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ProjectsStore, monogram } from '../../../core/state/projects.store';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { projectRepositories, shortRepoUrl } from '../../../core/ui/repositories';
import { batonOf, type BatonHolder } from '../../../core/ui/relay/baton';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { icu } from '../../../core/i18n/icu';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { ProjectFormComponent } from '../components/project-form.component';

interface Holding {
  holder: BatonHolder;
  count: number;
  label: string;
}

/**
 * Projects as cards: key monogram, name, repository, members, runs in flight by holder, open gates,
 * cost over 30 days and last activity. `?create=1` opens the create drawer (command palette).
 */
@Component({
  selector: 'app-projects-page',
  imports: [
    RouterLink,
    NzButtonModule,
    NzDrawerModule,
    CostComponent,
    EmptyComponent,
    GlyphComponent,
    PageHeaderComponent,
    SkeletonComponent,
    ProjectFormComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="projects"
        title="Projects"
        i18n-title="@@projects.list.title"
        subtitle="A project binds a repository, an optional Jira project and a constitution to its workflows."
        i18n-subtitle="@@projects.list.subtitle"
      >
        @if (canCreatePersonal()) {
          <button
            boHeaderActions
            nz-button
            [nzType]="canCreate() ? 'default' : 'primary'"
            type="button"
            (click)="openCreate(true)"
            i18n="@@projects.list.createPersonal"
          >
            Create private project
          </button>
        }
        @if (canCreate()) {
          <button
            boHeaderActions
            nz-button
            nzType="primary"
            type="button"
            (click)="openCreate(false)"
            i18n="@@projects.list.create"
          >
            Create project
          </button>
        } @else if (store.loaded()) {
          <span boHeaderActions class="who-creates muted" i18n="@@projects.list.managersCreate"
            >Project managers create projects.</span
          >
        }
      </bo-page-header>

      @if (!store.loaded() && !store.projects().length) {
        <bo-skeleton variant="cards" [rows]="6" />
      } @else if (!store.projects().length) {
        <bo-empty
          [title]="canCreate() ? text.empty : text.emptyNotMember"
          [detail]="canCreate() ? text.emptyDetail : text.emptyNotMemberDetail"
          [actionLabel]="
            canCreate() ? text.create : canCreatePersonal() ? text.createPersonal : null
          "
          (action)="openCreate(!canCreate())"
          help="projects"
          guideLabel="How projects work"
          i18n-guideLabel="@@projects.list.guide"
        />
      } @else {
        <ul class="cards" aria-label="Projects" i18n-aria-label="@@projects.list.cards">
          @for (p of sorted(); track p.id) {
            <li>
              <a class="card" [routerLink]="['/projects', p.id]" (click)="store.setCurrent(p.id)">
                <div class="top">
                  <span class="mono-tile" aria-hidden="true">{{ mono(p.key) }}</span>
                  <div class="names">
                    <span class="key"
                      >{{ p.key }}
                      @if (p.personal) {
                        <span class="private" i18n="@@projects.list.private">private</span>
                      }
                    </span>
                    <span class="name">{{ p.name }}</span>
                  </div>
                  @if (p.stats?.openApprovals) {
                    <span class="gates" [attr.aria-label]="gatesLabel(p.stats?.openApprovals ?? 0)">
                      <bo-glyph holder="you" [size]="12" />
                      {{ p.stats?.openApprovals }}
                    </span>
                  }
                </div>
                @if (p.description) {
                  <p class="desc">{{ p.description }}</p>
                }
                <div class="repo mono" [attr.title]="repoTitle(p) || null">{{ repoLabel(p) }}</div>
                <div
                  class="holding"
                  aria-label="Runs in flight"
                  i18n-aria-label="@@projects.list.inFlight"
                >
                  @for (h of holdings(p); track h.holder) {
                    <span class="hold" [attr.data-holder]="h.holder">
                      <bo-glyph
                        [holder]="h.holder"
                        [live]="h.holder === 'agent' || h.holder === 'ci'"
                        [size]="11"
                      />
                      {{ h.label }}
                    </span>
                  } @empty {
                    <span class="quiet" i18n="@@projects.list.nothingInFlight"
                      >Nothing in flight</span
                    >
                  }
                </div>
                <div class="foot">
                  <span>{{ workflowsText(p) }}</span>
                  <span>{{ memberText(p) }}</span>
                  <span class="cost"
                    ><bo-cost [value]="p.stats?.costLast30Days" />
                    <span i18n="@@projects.list.per30d">/ 30d</span></span
                  >
                  <span class="when" [attr.title]="abs(lastActivity(p))">{{
                    ago(lastActivity(p))
                  }}</span>
                </div>
              </a>
            </li>
          }
        </ul>
      }
    </div>

    <nz-drawer
      [nzVisible]="creating()"
      [nzTitle]="creatingPersonal() ? text.drawerPersonal : text.drawerTeam"
      [nzWidth]="drawerWidth"
      (nzOnClose)="closeCreate()"
    >
      <ng-container *nzDrawerContent>
        <app-project-form
          [saving]="saving()"
          [serverError]="saveError()"
          (saved)="createProject($event)"
          (cancelled)="closeCreate()"
        />
      </ng-container>
    </nz-drawer>
  `,
  styles: [
    `
      .private {
        margin-left: 6px;
        padding: 0 6px;
        border-radius: 999px;
        font-size: 10.5px;
        font-weight: 500;
        color: var(--bo-ink-2);
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
        vertical-align: middle;
      }
      .who-creates {
        font-size: 12px;
        align-self: center;
      }
      .cards {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
        gap: 12px;
      }
      .card {
        display: flex;
        flex-direction: column;
        gap: 8px;
        height: 100%;
        box-sizing: border-box;
        padding: 16px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        color: inherit;
        text-decoration: none;
        transition: border-color var(--bo-dur-fast) var(--bo-ease);
      }
      .card:hover {
        border-color: var(--bo-ink-3);
      }
      .card:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .top {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      .mono-tile {
        flex: none;
        width: 40px;
        height: 40px;
        border-radius: 10px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--bo-ink);
        color: var(--bo-on-ink);
        font-family: var(--bo-font-mono);
        font-weight: 600;
        font-size: 14px;
      }
      .names {
        display: flex;
        flex-direction: column;
        min-width: 0;
        flex: 1;
      }
      .key {
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .name {
        font-weight: 600;
        font-size: 15px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .gates {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        height: 22px;
        padding: 0 8px;
        border-radius: 999px;
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
        font-weight: 600;
        font-size: 12px;
        box-shadow: inset 0 0 0 1px var(--bo-you);
      }
      .desc {
        margin: 0;
        font-size: 13px;
        color: var(--bo-ink-2);
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .repo {
        font-size: 11.5px;
        color: var(--bo-ink-3);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .holding {
        display: flex;
        flex-wrap: wrap;
        gap: 10px;
        font-size: 12px;
        color: var(--bo-ink-2);
        margin-top: auto;
        padding-top: 4px;
      }
      .hold {
        display: inline-flex;
        align-items: center;
        gap: 5px;
      }
      .quiet {
        color: var(--bo-ink-3);
      }
      .foot {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 14px;
        padding-top: 10px;
        border-top: 1px solid var(--bo-line);
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .cost {
        display: inline-flex;
        gap: 4px;
        align-items: baseline;
      }
      .when {
        margin-left: auto;
      }
    `,
  ],
})
export class ProjectsPageComponent {
  private readonly api = inject(ProjectsApi);
  private readonly runsApi = inject(RunsApi);
  private readonly membersApi = inject(MembersApi);
  private readonly router = inject(Router);
  private readonly message = inject(NzMessageService);
  private readonly identity = inject(IdentityService);
  private readonly pendingSaves = inject(PendingSavesService);
  readonly store = inject(ProjectsStore);

  /** `?create=1` opens the drawer. */
  readonly create = input<string | undefined>();

  readonly creating = signal(false);
  readonly saving = signal(false);
  readonly saveError = signal<unknown>(null);
  readonly inFlight = signal<WorkflowRunSummary[]>([]);
  readonly recent = signal<WorkflowRunSummary[]>([]);
  readonly members = signal<Map<string, number>>(new Map());
  readonly canCreate = computed(() => this.identity.can('projects.create'));
  /** ADR 0038: every member may create private projects of their own. */
  readonly canCreatePersonal = computed(() => this.identity.can('projects.create.personal'));
  readonly creatingPersonal = signal(false);

  readonly sorted = computed(() =>
    [...this.store.projects()].sort((a, b) => a.key.localeCompare(b.key)),
  );
  private readonly lastRun = computed(() => {
    const m = new Map<string, string>();
    for (const r of this.recent()) {
      const at = r.createdAt ?? '';
      if (r.projectId && at > (m.get(r.projectId) ?? '')) m.set(r.projectId, at);
    }
    return m;
  });

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@projects.list.crumb:Projects` }]);
    this.store.refresh();
    this.runsApi
      .list({ status: ['RUNNING', 'WAITING', 'PENDING'], size: 100 })
      .pipe(catchError(() => of({ content: [] })))
      .subscribe((p) => this.inFlight.set(p.content ?? []));
    this.runsApi
      .list({ size: 100 })
      .pipe(catchError(() => of({ content: [] })))
      .subscribe((p) => this.recent.set(p.content ?? []));
    effect(() => {
      if (this.create() && (this.canCreate() || this.canCreatePersonal()))
        untracked(() => this.openCreate(!this.canCreate()));
    });
    effect(() => {
      const projects = this.store.projects().slice(0, 30);
      untracked(() => {
        const missing = projects.filter((p) => !this.members().has(p.id));
        if (!missing.length) return;
        forkJoin(
          missing.map((p) =>
            this.membersApi.list(p.id).pipe(
              map((list) => [p.id, list.length] as const),
              catchError(() => of([p.id, -1] as const)),
            ),
          ),
        ).subscribe((pairs) => this.members.update((m) => new Map([...m, ...pairs])));
      });
    });
  }

  mono = monogram;
  readonly drawerWidth = 'min(760px, 92vw)';
  ago = timeAgo;
  abs = absoluteTime;

  /** The primary repository (`host/path @ branch`, `+N` for the others), a count, or "LLM work only". */
  repoLabel(p: Project): string {
    const repos = projectRepositories(p);
    if (!repos.length)
      return $localize`:@@projects.list.noRepository:No repository - LLM work only`;
    const primary = repos.find((r) => r.primary);
    if (!primary)
      return icu(
        $localize`:@@projects.list.repositoryCount:{count, plural, one {# repository} other {# repositories}}`,
        { count: repos.length },
      );
    const main = `${shortRepoUrl(primary.url)} @ ${primary.defaultBranch || 'main'}`;
    return repos.length > 1 ? `${main} +${repos.length - 1}` : main;
  }

  repoTitle(p: Project): string {
    return projectRepositories(p)
      .map((r) => `${r.key}: ${r.url}`)
      .join('\n');
  }

  readonly text = {
    empty: $localize`:@@projects.list.empty:No projects yet.`,
    emptyNotMember: $localize`:@@projects.list.emptyNotMember:You are not a member of any project yet.`,
    emptyDetail: $localize`:@@projects.list.emptyDetail:Create one to design the first workflow.`,
    emptyNotMemberDetail: $localize`:@@projects.list.emptyNotMemberDetail:Projects are created by project managers. Ask a project manager or a project owner to add you.`,
    create: $localize`:@@projects.list.emptyAction:Create project`,
    createPersonal: $localize`:@@projects.list.emptyActionPersonal:Create private project`,
    drawerTeam: $localize`:@@projects.list.drawerTitle:Create project`,
    drawerPersonal: $localize`:@@projects.list.drawerPersonal:Create private project (only you are a member)`,
  };

  memberText(p: Project): string {
    const n = this.members().get(p.id);
    if (n === undefined || n < 0) return '';
    return icu(
      $localize`:@@projects.list.members:{count, plural, one {# member} other {# members}}`,
      { count: n },
    );
  }

  workflowsText(p: Project): string {
    return icu(
      $localize`:@@projects.list.workflows:{count, plural, one {# workflow} other {# workflows}}`,
      { count: p.stats?.workflows ?? 0 },
    );
  }

  gatesLabel(n: number): string {
    return icu(
      $localize`:@@projects.list.openGates:{count, plural, one {# open gate} other {# open gates}}`,
      { count: n },
    );
  }

  lastActivity(p: Project): string {
    const run = this.lastRun().get(p.id) ?? '';
    return run > (p.updatedAt ?? '') ? run : p.updatedAt;
  }

  holdings(p: Project): Holding[] {
    const counts = new Map<BatonHolder, number>();
    for (const r of this.inFlight()) {
      if (r.projectId !== p.id) continue;
      const h = batonOf.run(r).holder;
      counts.set(h, (counts.get(h) ?? 0) + 1);
    }
    const label: Partial<Record<BatonHolder, string>> = {
      agent: $localize`:@@projects.list.hold.agent:{count, plural, other {# with agents}}`,
      you: $localize`:@@projects.list.hold.you:{count, plural, other {# at a gate}}`,
      ci: $localize`:@@projects.list.hold.ci:{count, plural, other {# waiting for CI}}`,
      idle: $localize`:@@projects.list.hold.idle:{count, plural, other {# queued}}`,
    };
    return (['agent', 'ci', 'you', 'idle'] as BatonHolder[])
      .filter((h) => counts.get(h))
      .map((h) => ({
        holder: h,
        count: counts.get(h)!,
        label: icu(label[h]!, { count: counts.get(h)! }),
      }));
  }

  openCreate(personal: boolean): void {
    this.creatingPersonal.set(personal);
    this.creating.set(true);
  }

  closeCreate(): void {
    this.creating.set(false);
    if (this.create())
      void this.router.navigate([], {
        queryParams: { create: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
  }

  createProject(input: ProjectInput): void {
    this.saving.set(true);
    this.saveError.set(null);
    const body: ProjectInput = { ...input, personal: this.creatingPersonal() };
    this.pendingSaves.track(this.api.create(body)).subscribe({
      next: async (p) => {
        this.saving.set(false);
        this.creating.set(false);
        this.message.success($localize`:@@projects.list.created:Project ${p.key}:key: created`);
        this.store.upsert(p);
        this.store.setCurrent(p.id);
        // The creator became owner: reload /me so guards and buttons know it.
        await this.identity.refresh();
        void this.router.navigate(['/projects', p.id]);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.saveError.set(err);
      },
    });
  }
}
