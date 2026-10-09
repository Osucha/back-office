import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { catchError, forkJoin, map, of } from 'rxjs';
import { AdminApi } from '../../../core/api/admin.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import { SettingsApi } from '../../../core/api/settings.service';
import { IdentityService } from '../../../core/auth/identity.service';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import type { HelpKey } from '../../../core/help/help-map';
import { ProjectsStore } from '../../../core/state/projects.store';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';

const DISMISS_KEY = 'backoffice.firstRunDismissed';
/** The built-in platform role that may create projects (ADR 0028). */
const PROJECT_MANAGER_ROLE = 'project-manager';

interface Step {
  id: string;
  label: string;
  hint: string;
  done: boolean | null;
  link: unknown[];
  query?: Record<string, string>;
  /** The manual section that explains the step. */
  help: HelpKey;
}

/**
 * Administrator first-run checklist: a provider key, a first project, someone else invited, a
 * project manager named (members cannot create projects, ADR 0028), CI connected. Hidden once complete or dismissed (per browser). Steps the admin cannot check (no
 * permission to read that setting) are left out.
 */
@Component({
  selector: 'app-first-run-checklist',
  imports: [RouterLink, NzButtonModule, NzIconModule, GlyphComponent, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <section class="box" aria-labelledby="first-run-title">
        <header>
          <h2 id="first-run-title" i18n="@@dashboard.setup.title">Set up Back Office</h2>
          <span class="progress">{{ progressText() }}</span>
          <button
            type="button"
            class="dismiss"
            (click)="dismiss()"
            aria-label="Hide the setup checklist"
            i18n-aria-label="@@dashboard.setup.hide"
          >
            <nz-icon nzType="close" />
          </button>
        </header>
        <ol>
          @for (s of steps(); track s.id) {
            <li [class.done]="s.done">
              <bo-glyph [holder]="s.done ? 'done' : 'idle'" [size]="14" />
              <div class="text">
                @if (s.done) {
                  <span class="label">{{ s.label }}</span>
                } @else {
                  <a [routerLink]="s.link" [queryParams]="s.query ?? null" class="label">{{
                    s.label
                  }}</a>
                }
                <span class="hint"
                  >{{ s.hint }}
                  @if (!s.done) {
                    <bo-help-link [topic]="s.help" variant="text"
                      ><span i18n="@@dashboard.setup.readHow">Read how</span></bo-help-link
                    >
                  }
                </span>
              </div>
              @if (s.done) {
                <span class="sr-only" i18n="@@dashboard.setup.stepDone">done</span>
              } @else {
                <span class="sr-only" i18n="@@dashboard.setup.stepToDo">to do</span>
              }
            </li>
          }
        </ol>
      </section>
    }
  `,
  styles: [
    `
      .box {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        padding: 14px 16px 10px;
      }
      header {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 6px;
      }
      h2 {
        margin: 0;
        font-size: 14px;
      }
      .progress {
        font-size: 12px;
        color: var(--bo-ink-3);
        margin-right: auto;
      }
      .dismiss {
        all: unset;
        cursor: pointer;
        padding: 4px;
        border-radius: 6px;
        color: var(--bo-ink-3);
        display: inline-flex;
      }
      .dismiss:hover {
        background: var(--bo-surface-3);
        color: var(--bo-ink);
      }
      .dismiss:focus-visible {
        outline: 2px solid var(--bo-accent);
      }
      ol {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      li {
        display: flex;
        gap: 10px;
        align-items: flex-start;
        padding: 7px 0;
        border-top: 1px solid var(--bo-line);
      }
      li bo-glyph {
        margin-top: 2px;
      }
      .text {
        display: flex;
        flex-direction: column;
        min-width: 0;
      }
      .label {
        font-size: 13px;
        font-weight: 500;
      }
      li.done .label {
        color: var(--bo-ink-3);
        text-decoration: line-through;
        font-weight: 400;
      }
      .hint {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
      }
    `,
  ],
})
export class FirstRunChecklistComponent {
  private readonly identity = inject(IdentityService);
  private readonly settings = inject(SettingsApi);
  private readonly admin = inject(AdminApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly store = inject(ProjectsStore);

  private readonly dismissed = signal(readDismissed());
  private readonly providerKey = signal<boolean | null>(null);
  private readonly users = signal<boolean | null>(null);
  private readonly ciToken = signal<boolean | null>(null);
  private readonly managers = signal<boolean | null>(null);

  readonly steps = computed<Step[]>(() => {
    const out: Step[] = [];
    if (this.identity.can('integrations.view'))
      out.push({
        id: 'provider',
        label: $localize`:@@dashboard.setup.provider:Configure a model provider key`,
        hint: $localize`:@@dashboard.setup.providerHint:Agents need at least one provider (Settings, Integrations).`,
        done: this.providerKey(),
        link: ['/settings'],
        help: 'settings.llm',
      });
    out.push({
      id: 'project',
      label: $localize`:@@dashboard.setup.project:Create the first project`,
      hint: $localize`:@@dashboard.setup.projectHint:A project binds a repository to its workflows.`,
      done: this.store.loaded() ? this.store.projects().length > 0 : null,
      link: ['/projects'],
      query: { create: '1' },
      help: 'projects',
    });
    if (this.identity.can('admin.users.view'))
      out.push({
        id: 'invite',
        label: $localize`:@@dashboard.setup.invite:Invite someone`,
        hint: $localize`:@@dashboard.setup.inviteHint:Gates need an approver; with "Forbid self-approval" it must be someone else.`,
        done: this.users(),
        link: ['/admin/users'],
        query: this.identity.can('admin.users.manage') ? { invite: '1' } : undefined,
        help: 'admin.users',
      });
    if (this.identity.can('admin.roles.manage'))
      out.push({
        id: 'manager',
        label: $localize`:@@dashboard.setup.manager:Name a project manager`,
        hint: $localize`:@@dashboard.setup.managerHint:Members cannot create projects. Project managers create them and decide who does which step.`,
        done: this.managers(),
        link: ['/admin/grants'],
        help: 'admin.projectManagers',
      });
    const first = this.store.projects()[0];
    out.push({
      id: 'ci',
      label: $localize`:@@dashboard.setup.ci:Connect CI`,
      hint: $localize`:@@dashboard.setup.ciHint:Create a CI token so pipelines can call backoffice-ci.`,
      done: this.ciToken(),
      link: first ? ['/projects', first.id] : ['/projects'],
      query: first ? { tab: 'ci' } : undefined,
      help: 'project.ci',
    });
    return out;
  });
  readonly doneCount = computed(() => this.steps().filter((s) => s.done).length);
  readonly progressText = computed(
    () =>
      $localize`:@@dashboard.setup.progress:${this.doneCount()}:done: of ${this.steps().length}:total: done`,
  );
  readonly visible = computed(
    () =>
      this.identity.isAdmin() && !this.dismissed() && this.steps().some((s) => s.done === false),
  );

  constructor() {
    if (!this.identity.isAdmin() || this.dismissed()) return;
    this.store.ensure();
    if (this.identity.can('integrations.view'))
      this.settings
        .integrations()
        .pipe(catchError(() => of(null)))
        .subscribe((s) =>
          this.providerKey.set(s ? (s.providers ?? []).some((p) => p.configured) : null),
        );
    if (this.identity.can('admin.users.view'))
      this.admin
        .users({ size: 2 })
        .pipe(catchError(() => of(null)))
        .subscribe((p) =>
          this.users.set(p ? (p.totalElements ?? p.content?.length ?? 0) > 1 : null),
        );
    if (this.identity.can('admin.roles.manage'))
      this.admin
        .grants()
        .pipe(catchError(() => of(null)))
        .subscribe((sets) =>
          this.managers.set(
            sets ? sets.some((g) => (g.roles ?? []).includes(PROJECT_MANAGER_ROLE)) : null,
          ),
        );
    this.projectsApi
      .list()
      .pipe(catchError(() => of([])))
      .subscribe((projects) => {
        const readable = projects
          .filter((p) => this.identity.canInProject('project.ci.manage', p.id))
          .slice(0, 6);
        if (!readable.length) return this.ciToken.set(projects.length ? null : false);
        forkJoin(
          readable.map((p) =>
            this.projectsApi.ciTokens(p.id).pipe(
              map((tokens) => tokens.some((t) => !t.revokedAt)),
              catchError(() => of(false)),
            ),
          ),
        ).subscribe((any) => this.ciToken.set(any.some(Boolean)));
      });
  }

  dismiss(): void {
    this.dismissed.set(true);
    try {
      localStorage.setItem(DISMISS_KEY, 'true');
    } catch {
      // storage unavailable
    }
  }
}

function readDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISS_KEY) === 'true';
  } catch {
    return false;
  }
}
