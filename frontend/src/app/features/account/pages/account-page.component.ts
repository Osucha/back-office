import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { AdminApi } from '../../../core/api/admin.service';
import { AuthApi } from '../../../core/api/auth.service';
import type { Theme } from '../../../core/api/types';
import { AuthConfigService } from '../../../core/auth/auth-config.service';
import { IdentityService } from '../../../core/auth/identity.service';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ProjectsStore } from '../../../core/state/projects.store';
import { DensityService, type Density } from '../../../core/ui/density.service';
import { PermissionDescriptionPipe, RoleNamePipe } from '../../../core/i18n/catalog-labels.pipes';
import { compareKeys } from '../../../core/i18n/compare';
import { icu } from '../../../core/i18n/icu';
import {
  LOCALE_NAMES,
  SUPPORTED_LOCALES,
  activeLocale,
  intlLocale,
  switchLocale,
  type AppLocale,
} from '../../../core/i18n/locale';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { absoluteTime } from '../../../core/ui/relay/format';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ConnectIdeSectionComponent } from '../components/connect-ide-section.component';
import { PersonalTokensPanelComponent } from '../components/personal-tokens-panel.component';
import { NotificationsSectionComponent } from '../components/notifications-section.component';
import { SecuritySectionComponent } from '../components/security-section.component';
import { PersonalConnectionsPanelComponent } from '../../connections/public-api';

export const ACCOUNT_SECTIONS = [
  { id: 'profile', label: $localize`:@@account.index.profile:Profile` },
  { id: 'preferences', label: $localize`:@@account.index.preferences:Preferences` },
  { id: 'notifications', label: $localize`:@@account.index.notifications:Notifications` },
  { id: 'security', label: $localize`:@@account.index.security:Security` },
  { id: 'tokens', label: $localize`:@@account.index.tokens:Personal access tokens` },
  { id: 'connections', label: $localize`:@@account.index.connections:Connections` },
  { id: 'ide', label: $localize`:@@account.index.ide:Connect your IDE` },
  { id: 'access', label: $localize`:@@account.index.access:My access` },
] as const;
export type AccountSection = (typeof ACCOUNT_SECTIONS)[number]['id'];

/**
 * My account: one scrolling page with a section index (profile, preferences, notifications, security, personal
 * access tokens, personal Jira connections (ADR 0035), connect your IDE, my access). `?section=security` scrolls to a section; `?setup=mfa` opens the MFA
 * set-up in the security section.
 */
@Component({
  selector: 'app-account-page',
  imports: [
    PermissionDescriptionPipe,
    RoleNamePipe,
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzInputNumberModule,
    NzRadioModule,
    NzSelectModule,
    PageHeaderComponent,
    HelpLinkComponent,
    ConnectIdeSectionComponent,
    PersonalConnectionsPanelComponent,
    PersonalTokensPanelComponent,
    NotificationsSectionComponent,
    SecuritySectionComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="account"
        title="My account"
        i18n-title="@@account.page.title"
        [subtitle]="identity.user()?.email ?? null"
      >
        <div boHeaderLead class="avatar" aria-hidden="true">{{ initials() }}</div>
        <button boHeaderActions nz-button type="button" (click)="identity.logout()">
          <nz-icon nzType="logout" /> <span i18n="@@account.page.signOut">Sign out</span>
        </button>
      </bo-page-header>

      <div class="layout">
        <nav class="index" aria-label="Account sections" i18n-aria-label="@@account.page.sections">
          @for (s of sections; track s.id) {
            <a
              [routerLink]="[]"
              [queryParams]="{ section: s.id }"
              queryParamsHandling="merge"
              [class.active]="active() === s.id"
              [attr.aria-current]="active() === s.id ? 'true' : null"
              (click)="scrollTo(s.id)"
              >{{ s.label }}</a
            >
          }
        </nav>

        <div class="sections">
          <section id="profile" class="card" aria-labelledby="h-profile">
            <h2 id="h-profile" class="card-title" i18n="@@account.profile.title">Profile</h2>
            <dl class="facts">
              <div>
                <dt i18n="@@account.profile.name">Name</dt>
                <dd>{{ identity.user()?.displayName || '-' }}</dd>
              </div>
              <div>
                <dt i18n="@@account.profile.email">E-mail</dt>
                <dd>{{ identity.user()?.email }}</dd>
              </div>
              <div>
                <dt i18n="@@account.profile.groups">Groups</dt>
                <dd>
                  @for (g of identity.me()?.groups ?? []; track g.id) {
                    <span class="chip">{{ g.name }}</span>
                  } @empty {
                    <span class="muted" i18n="@@account.profile.noGroups">None</span>
                  }
                </dd>
              </div>
              <div>
                <dt i18n="@@account.profile.session">This session</dt>
                <dd>
                  <span
                    [title]="abs(identity.me()?.authenticatedAt)"
                    i18n="@@account.profile.signedIn"
                    >Signed in {{ ago(identity.me()?.authenticatedAt) }}</span
                  >
                  <span class="muted" i18n="@@account.profile.endsAt">
                    · ends {{ endsAt() }} at the latest</span
                  >
                </dd>
              </div>
            </dl>
            <p class="note" i18n="@@account.profile.managedByAdmin">
              An administrator manages your name, e-mail and groups.
            </p>
          </section>

          <section id="preferences" class="card" aria-labelledby="h-preferences">
            <h2 id="h-preferences" class="card-title" i18n="@@account.prefs.title">Preferences</h2>
            <form class="prefs" (submit)="$event.preventDefault(); savePreferences()">
              <div class="pref">
                <span class="pref-label" id="pref-theme" i18n="@@account.prefs.theme">Theme</span>
                <nz-radio-group name="theme" aria-labelledby="pref-theme" [(ngModel)]="theme">
                  <label nz-radio-button nzValue="SYSTEM" i18n="@@account.prefs.themeSystem"
                    >System</label
                  >
                  <label nz-radio-button nzValue="LIGHT" i18n="@@account.prefs.themeLight"
                    >Light</label
                  >
                  <label nz-radio-button nzValue="DARK" i18n="@@account.prefs.themeDark"
                    >Dark</label
                  >
                </nz-radio-group>
                <span class="pref-help" i18n="@@account.prefs.themeHelp"
                  >System follows your operating system.</span
                >
              </div>
              <div class="pref">
                <span class="pref-label" id="pref-density" i18n="@@account.prefs.density"
                  >Density</span
                >
                <nz-radio-group
                  name="density"
                  aria-labelledby="pref-density"
                  [ngModel]="density.density()"
                  (ngModelChange)="setDensity($event)"
                >
                  <label nz-radio-button nzValue="comfortable" i18n="@@account.prefs.comfortable"
                    >Comfortable</label
                  >
                  <label nz-radio-button nzValue="compact" i18n="@@account.prefs.compact"
                    >Compact</label
                  >
                </nz-radio-group>
                <span class="pref-help" i18n="@@account.prefs.densityHelp"
                  >Applies right away, in this browser.</span
                >
              </div>
              <div class="pref">
                <span class="pref-label" id="pref-language" i18n="@@account.prefs.language"
                  >Language / Język</span
                >
                <nz-radio-group
                  name="language"
                  aria-labelledby="pref-language"
                  [ngModel]="locale"
                  (ngModelChange)="setLocale($event)"
                >
                  @for (l of locales; track l) {
                    <label nz-radio-button [nzValue]="l" [attr.lang]="l">{{
                      localeNames[l]
                    }}</label>
                  }
                </nz-radio-group>
                <span class="pref-help" i18n="@@account.prefs.languageHelp"
                  >Reloads the page; saved in this browser.</span
                >
              </div>
              <div class="pref">
                <label class="pref-label" for="pref-size" i18n="@@account.prefs.pageSize"
                  >Rows per page</label
                >
                <nz-input-number
                  id="pref-size"
                  name="pageSize"
                  [(ngModel)]="pageSize"
                  [nzMin]="10"
                  [nzMax]="200"
                  [nzStep]="10"
                />
                <span class="pref-help" i18n="@@account.prefs.pageSizeHelp"
                  >10 to 200; longer lists scroll virtually.</span
                >
              </div>
              <div class="pref">
                <label class="pref-label" for="pref-project" i18n="@@account.prefs.defaultProject"
                  >Default project</label
                >
                <nz-select
                  id="pref-project"
                  name="defaultProject"
                  nzAllowClear
                  nzShowSearch
                  nzPlaceHolder="None"
                  i18n-nzPlaceHolder="@@account.prefs.noDefaultProject"
                  [(ngModel)]="defaultProjectId"
                  style="width: 260px"
                >
                  @for (p of projects.projects(); track p.id) {
                    <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' · ' + p.name" />
                  }
                </nz-select>
                <span class="pref-help" i18n="@@account.prefs.defaultProjectHelp"
                  >Selected on the rail when you sign in.</span
                >
              </div>
              <div class="pref-actions">
                <button
                  nz-button
                  nzType="primary"
                  type="submit"
                  [nzLoading]="saving()"
                  i18n="@@account.prefs.save"
                >
                  Save preferences
                </button>
              </div>
            </form>
          </section>

          <section id="notifications" class="card" aria-labelledby="h-notifications">
            <h2 id="h-notifications" class="card-title">
              <span i18n="@@account.notify.title">Notifications</span>
              <bo-help-link topic="account.notifications" />
            </h2>
            @defer (on viewport) {
              <app-notifications-section />
            } @placeholder {
              <div class="ph" aria-hidden="true"></div>
            }
          </section>

          <section id="security" class="card" aria-labelledby="h-security">
            <h2 id="h-security" class="card-title">
              <span i18n="@@account.security.title">Security</span>
              <bo-help-link topic="account.mfa" />
            </h2>
            <app-security-section [startSetup]="setup() === 'mfa'" />
          </section>

          <section id="tokens" class="card" aria-labelledby="h-tokens">
            <h2 id="h-tokens" class="card-title">
              <span i18n="@@account.tokens.title">Personal access tokens</span>
              <bo-help-link topic="account.tokens" />
            </h2>
            @defer (on viewport) {
              <app-personal-tokens-panel [platformMaxDays]="tokenMaxDays()" />
            } @placeholder {
              <div class="ph" aria-hidden="true"></div>
            }
          </section>

          <section id="connections" class="card" aria-labelledby="h-connections">
            <h2 id="h-connections" class="card-title">
              <span i18n="@@account.connections.title">Connections</span>
              <bo-help-link topic="connections.personal" />
            </h2>
            @defer (on viewport) {
              <app-personal-connections-panel />
            } @placeholder {
              <div class="ph" aria-hidden="true"></div>
            }
          </section>

          <section id="ide" class="card" aria-labelledby="h-ide">
            <h2 id="h-ide" class="card-title">
              <span i18n="@@account.ide.title">Connect your IDE</span>
              <bo-help-link topic="account.ide" />
            </h2>
            @defer (on viewport) {
              <app-connect-ide-section />
            } @placeholder {
              <div class="ph" aria-hidden="true"></div>
            }
          </section>

          <section id="access" class="card" aria-labelledby="h-access">
            <h2 id="h-access" class="card-title" i18n="@@account.access.title">My access</h2>
            <h3 class="sub" i18n="@@account.access.platformPermissions">Platform permissions</h3>
            <div class="perms">
              @for (p of platformPermissions(); track p) {
                <span class="chip mono" [title]="p | permissionDescription">{{ p }}</span>
              } @empty {
                <span class="muted" i18n="@@account.access.noPlatformPermissions">None</span>
              }
            </div>
            @if (identity.allProjects()) {
              <p class="note" i18n="@@account.access.allProjects">
                You hold {{ allProjectsKey }}: every project permission in every project.
              </p>
            }
            <h3 class="sub" i18n="@@account.access.projects">Projects</h3>
            @for (p of identity.me()?.projects ?? []; track p.projectId) {
              <div class="project-row">
                <a [routerLink]="['/projects', p.projectId]" class="mono key">{{ p.projectKey }}</a>
                <span class="roles">
                  @for (r of p.roles ?? []; track r) {
                    <span class="chip" [title]="r">{{ r | roleName }}</span>
                  }
                </span>
                <span class="muted small">{{ permissionCount((p.permissions ?? []).length) }}</span>
              </div>
            } @empty {
              <p class="note" i18n="@@account.access.noProjects">
                You are not a member of any project.
              </p>
            }
          </section>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .avatar {
        width: 44px;
        height: 44px;
        border-radius: 50%;
        background: var(--bo-ink);
        color: var(--bo-on-ink);
        display: flex;
        align-items: center;
        justify-content: center;
        font-weight: 600;
        font-size: 15px;
        margin-right: var(--bo-space-2);
      }
      .layout {
        display: grid;
        grid-template-columns: 200px minmax(0, 1fr);
        gap: var(--bo-space-6);
        align-items: start;
      }
      .index {
        position: sticky;
        top: calc(var(--bo-topbar-h) + var(--bo-space-4));
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .index a {
        padding: 6px 10px;
        border-radius: var(--bo-radius-control);
        color: var(--bo-ink-2);
        text-decoration: none;
        font-size: var(--bo-fs-dense);
        border-left: 2px solid transparent;
      }
      .index a:hover {
        background: var(--bo-surface-2);
        color: var(--bo-ink);
      }
      .index a.active {
        color: var(--bo-ink);
        font-weight: 500;
        background: var(--bo-surface);
        border-left-color: var(--bo-ink);
        border-radius: 0 var(--bo-radius-control) var(--bo-radius-control) 0;
      }
      .sections {
        display: flex;
        flex-direction: column;
        gap: var(--bo-space-4);
        max-width: 920px;
      }
      .card {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: var(--bo-space-5);
        scroll-margin-top: calc(var(--bo-topbar-h) + var(--bo-space-4));
      }
      .card-title {
        margin: 0 0 var(--bo-space-4);
        font-size: var(--bo-fs-section);
        font-weight: 600;
      }
      .facts {
        margin: 0;
        display: grid;
        gap: var(--bo-space-2);
      }
      .facts > div {
        display: grid;
        grid-template-columns: 200px minmax(0, 1fr);
        gap: var(--bo-space-4);
      }
      dt {
        font-size: var(--bo-fs-dense);
        color: var(--bo-ink-3);
      }
      dd {
        margin: 0;
      }
      .chip {
        display: inline-block;
        margin: 1px 6px 1px 0;
        padding: 0 8px;
        border-radius: var(--bo-radius-chip);
        border: 1px solid var(--bo-line);
        background: var(--bo-surface-2);
        font-size: 12px;
        line-height: 20px;
        color: var(--bo-ink-2);
      }
      .chip.mono {
        font-size: 11px;
      }
      .note {
        margin: var(--bo-space-3) 0 0;
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      .prefs {
        display: grid;
        gap: var(--bo-space-4);
      }
      .pref {
        display: grid;
        grid-template-columns: 200px auto minmax(0, 1fr);
        gap: var(--bo-space-4);
        align-items: center;
      }
      .pref-label {
        font-size: var(--bo-fs-dense);
        font-weight: 500;
      }
      .pref-help {
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      .pref-actions {
        padding-top: var(--bo-space-3);
        border-top: 1px solid var(--bo-line);
        display: flex;
        justify-content: flex-end;
      }
      .sub {
        margin: 0 0 var(--bo-space-2);
        font-size: var(--bo-fs-dense);
        font-weight: 600;
      }
      .perms {
        margin-bottom: var(--bo-space-4);
      }
      .project-row {
        display: flex;
        gap: var(--bo-space-3);
        align-items: center;
        padding: 6px 0;
        border-top: 1px solid var(--bo-line);
      }
      .project-row .key {
        width: 72px;
      }
      .roles {
        flex: 1;
      }
      .small {
        font-size: var(--bo-fs-meta);
      }
      .ph {
        height: 96px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
      }
      @media (max-width: 900px) {
        .layout {
          grid-template-columns: 1fr;
        }
        .index {
          position: static;
          flex-direction: row;
          flex-wrap: wrap;
        }
        .facts > div,
        .pref {
          grid-template-columns: 1fr;
          gap: var(--bo-space-1);
        }
      }
    `,
  ],
})
export class AccountPageComponent {
  readonly identity = inject(IdentityService);
  readonly authConfig = inject(AuthConfigService);
  readonly density = inject(DensityService);
  readonly projects = inject(ProjectsStore);
  private readonly api = inject(AuthApi);
  private readonly admin = inject(AdminApi);
  private readonly message = inject(NzMessageService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly destroyRef = inject(DestroyRef);

  /** `?section=` scrolls to that section. */
  readonly section = input<string | undefined>();
  /** `?setup=mfa` opens the MFA set-up. */
  readonly setup = input<string | undefined>();

  readonly sections = ACCOUNT_SECTIONS;
  readonly active = signal<AccountSection>('profile');
  readonly saving = signal(false);
  readonly tokenMaxDays = signal<number | null>(null);
  readonly platformPermissions = computed(() =>
    [...(this.identity.me()?.platformPermissions ?? [])].sort(compareKeys),
  );
  readonly initials = computed(() => {
    const name = this.identity.user()?.displayName || this.identity.user()?.email || '?';
    return name
      .split(/[\s.@]+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((p) => p[0]!.toUpperCase())
      .join('');
  });
  readonly endsAt = computed(() => {
    const iso = this.identity.me()?.absoluteExpiresAt;
    return iso
      ? new Date(iso).toLocaleTimeString(intlLocale(), { hour: '2-digit', minute: '2-digit' })
      : '-';
  });
  readonly allProjectsKey = 'admin.projects.all'; // i18n-ignore: permission key
  readonly permissionCount = (count: number) =>
    icu(
      $localize`:@@account.access.permissionCount:{count, plural, one {# permission} other {# permissions}}`,
      { count },
    );
  theme: Theme = 'SYSTEM';
  pageSize = 20;
  defaultProjectId: string | null = null;

  readonly ago = (iso: string | null | undefined) => (iso ? timeAgo(iso) : '-');
  readonly abs = absoluteTime;

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@account.page.breadcrumb:My account` }]);
    this.authConfig.load().subscribe();
    this.projects.ensure();
    // The platform limit is readable only for settings admins; otherwise the contract max applies.
    if (this.identity.can('admin.settings.manage')) {
      this.admin.settings().subscribe({
        next: (s) => this.tokenMaxDays.set(s.personalTokenMaxDays ?? null),
        error: () => undefined,
      });
    }
    effect(() => {
      const prefs = this.identity.preferences();
      untracked(() => {
        this.theme = prefs.theme ?? 'SYSTEM';
        this.pageSize = prefs.pageSize ?? 20;
        this.defaultProjectId = prefs.defaultProjectId ?? null;
      });
    });

    // Deep link to a section, and the index follows scrolling.
    afterNextRender(() => {
      const target = this.section();
      // After the router restored the scroll position (top) of the navigation.
      if (target && this.sections.some((s) => s.id === target))
        setTimeout(() => this.scrollTo(target as AccountSection, false), 60);
      this.observeSections();
    });
  }

  scrollTo(id: AccountSection, smooth = true): void {
    this.active.set(id);
    const el = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>('#' + id);
    el?.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' });
  }

  setDensity(d: Density): void {
    this.density.set(d);
  }

  /** UI language (per browser, `bo.locale`); switching reloads the page (ADR 0026). */
  readonly locales = SUPPORTED_LOCALES;
  readonly localeNames = LOCALE_NAMES;
  readonly locale = activeLocale();

  setLocale(locale: AppLocale): void {
    switchLocale(locale);
  }

  savePreferences(): void {
    this.saving.set(true);
    this.pendingSaves
      .track(
        this.api.updatePreferences({
          theme: this.theme,
          pageSize: this.pageSize,
          defaultProjectId: this.defaultProjectId,
        }),
      )
      .subscribe({
        next: (prefs) => {
          this.saving.set(false);
          this.identity.setPreferences(prefs);
          this.message.success($localize`:@@account.prefs.saved:Preferences saved`);
        },
        error: () => this.saving.set(false),
      });
  }

  /** The index follows scrolling: the last section whose top passed the top bar. */
  private observeSections(): void {
    const root = this.host.nativeElement as HTMLElement;
    let frame = 0;
    const update = () => {
      frame = 0;
      let current: AccountSection = this.sections[0].id;
      for (const s of this.sections) {
        const el = root.querySelector<HTMLElement>('#' + s.id);
        if (el && el.getBoundingClientRect().top <= 140) current = s.id;
      }
      this.active.set(current);
    };
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };
    // Capture: the scrolling element is the shell's content area, not the window.
    document.addEventListener('scroll', onScroll, { capture: true, passive: true });
    this.destroyRef.onDestroy(() => {
      document.removeEventListener('scroll', onScroll, { capture: true });
      if (frame) cancelAnimationFrame(frame);
    });
  }
}
