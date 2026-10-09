import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { CI_SCRIPT_URL } from '../../../core/api/ci.service';
import { SettingsApi } from '../../../core/api/settings.service';
import type {
  IntegrationName,
  IntegrationTestResult,
  IntegrationsStatus,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { EventStreamService } from '../../../core/events/event-stream.service';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { formatDuration } from '../../../core/ui/pipes/duration.pipe';
import type { Baton } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import type { HelpKey } from '../../../core/help/help-map';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { PageHeaderComponent, type PageAction } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import {
  integrationBaton,
  integrationCards,
  type IntegrationCard,
} from '../data/integration-state';

const PROVIDERS: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  GOOGLE: 'Google',
  MISTRAL: 'Mistral',
  OLLAMA: 'Ollama',
  OPENAI_COMPATIBLE: $localize`:@@settings.providers.openAiCompatible:OpenAI-compatible`,
};

/**
 * Settings: integration status (glyph + word), connection tests with the result inline, the CI
 * client script, provider keys, the session and build facts.
 */
@Component({
  selector: 'app-settings-page',
  imports: [
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzTooltipModule,
    BatonComponent,
    EmptyComponent,
    GlyphComponent,
    HelpLinkComponent,
    PageHeaderComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="settings"
        title="Settings"
        i18n-title="@@settings.page.title"
        subtitle="Integrations are configured through server environment variables; this page shows their status and tests the connections."
        i18n-subtitle="@@settings.page.subtitle"
        [menu]="menu"
      >
        @if (testableCount()) {
          <button
            boHeaderActions
            nz-button
            nzType="primary"
            type="button"
            (click)="testAll()"
            [nzLoading]="!!testing().length"
          >
            <nz-icon nzType="thunderbolt" />
            <ng-container i18n="@@settings.page.testAll">Test all connections</ng-container>
          </button>
        }
      </bo-page-header>

      @if (loading() && !status()) {
        <bo-skeleton
          variant="cards"
          [rows]="3"
          label="Loading integration status"
          i18n-label="@@settings.page.loading"
        />
      } @else if (!status()) {
        <bo-empty
          title="The integration status could not be loaded."
          i18n-title="@@settings.page.loadFailed.title"
          detail="The server did not answer; check that the backend is running."
          i18n-detail="@@settings.page.loadFailed.detail"
          actionLabel="Try again"
          i18n-actionLabel="@@settings.page.loadFailed.retry"
          (action)="load()"
        />
      } @else if (status(); as s) {
        <h2 class="section" i18n="@@settings.integrations.title">Integrations</h2>
        <div class="grid three">
          @for (c of cards(); track c.name) {
            <section class="card" [attr.aria-labelledby]="'int-' + c.name">
              <header class="card-head">
                <nz-icon [nzType]="c.icon" class="card-icon" aria-hidden="true" />
                <h3 [id]="'int-' + c.name">{{ c.title }}</h3>
                <bo-help-link [topic]="cardHelp[c.name]" />
                <bo-baton [baton]="baton(c)" size="sm" />
              </header>
              <p class="what">{{ c.what }}</p>
              @if (c.link) {
                <a class="head-link" [routerLink]="c.link.path">{{ c.link.label }}</a>
              }
              @if (c.name === 'jira' && !c.configured) {
                <p class="hint">
                  <ng-container i18n="@@settings.jira.hintConnections"
                    >Add a Jira connection in
                    <a routerLink="/admin/connections">Administration → Connections</a>: an
                    Atlassian account and API token limited to the capabilities Back Office
                    needs.</ng-container
                  >
                  <bo-help-link
                    topic="connections.leastPrivilege"
                    variant="text"
                    i18n="@@settings.jira.hintLink"
                    >How to create the token</bo-help-link
                  >
                </p>
              }
              @if (c.name === 'jira' && s.jira?.legacy) {
                <p class="hint warn" role="note">
                  <ng-container i18n="@@settings.jira.legacyNotice"
                    >The legacy credential from <code>JIRA_API_TOKEN</code> has every capability.
                    Migrate to scoped connections: create a reader and a writer connection in
                    <a routerLink="/admin/connections">Administration → Connections</a>, then remove
                    <code>JIRA_API_TOKEN</code>.</ng-container
                  >
                </p>
              }
              <dl class="lines">
                @for (l of c.lines; track l.label) {
                  <div>
                    <dt>{{ l.label }}</dt>
                    <dd [attr.title]="l.value">{{ l.value }}</dd>
                  </div>
                }
              </dl>
              <footer class="card-foot">
                @if (c.testable) {
                  <button
                    nz-button
                    nzSize="small"
                    type="button"
                    (click)="test(c.name)"
                    [nzLoading]="isTesting(c.name)"
                    [disabled]="!c.configured"
                    [attr.aria-describedby]="'res-' + c.name"
                    i18n="@@settings.integrations.test"
                  >
                    Test connection
                  </button>
                } @else if (c.untestable) {
                  <span class="quiet">{{ c.untestable }}</span>
                }
                <div class="result" [id]="'res-' + c.name" aria-live="polite">
                  @if (results()[c.name]; as r) {
                    <span class="state-word" [class.bad]="!r.ok">
                      <bo-glyph [holder]="r.ok ? 'done' : 'blocked'" [size]="12" />
                      {{ resultText(r) }}
                    </span>
                    @if (r.latencyMs) {
                      <span class="mono-num">{{ duration(r.latencyMs) }}</span>
                    }
                  } @else if (!c.configured && c.testable) {
                    <span class="quiet" i18n="@@settings.integrations.configureFirst"
                      >Configure it on the server to test it.</span
                    >
                  }
                </div>
              </footer>
            </section>
          }
        </div>

        <div class="grid two">
          <section class="card" aria-labelledby="providers-title">
            <header class="card-head">
              <nz-icon nzType="robot" class="card-icon" aria-hidden="true" />
              <h3 id="providers-title" i18n="@@settings.providers.title">LLM providers</h3>
              <bo-help-link topic="settings.llm" />
              <a class="head-link" routerLink="/models" i18n="@@settings.providers.openModels"
                >Open models</a
              >
            </header>
            <p class="what" i18n="@@settings.providers.what">
              Keys come from the <code>back-office-provider-keys</code> Secret (or
              <code>.env</code> locally). Test a single model on the Models page.
            </p>
            @if (!anyProviderKey()) {
              <p class="hint">
                <ng-container i18n="@@settings.providers.noKeyHint"
                  >No provider has an API key yet, so agents cannot run. Set one, for example
                  <code>ANTHROPIC_API_KEY</code> or <code>OPENAI_API_KEY</code>, and restart the
                  API.</ng-container
                >
                <bo-help-link
                  topic="settings.llm"
                  variant="text"
                  i18n="@@settings.providers.connect"
                  >Connect a provider</bo-help-link
                >
              </p>
            }
            <ul class="rows">
              @for (p of s.providers ?? []; track p.provider) {
                <li>
                  <span class="row-name">{{ providerName(p.provider) }}</span>
                  @if (p.configured) {
                    <span class="state-word"
                      ><bo-glyph holder="done" [size]="11" />
                      <ng-container i18n="@@settings.providers.keySet"
                        >API key set</ng-container
                      ></span
                    >
                  } @else {
                    <span class="state-word"
                      ><bo-glyph holder="idle" [size]="11" />
                      <ng-container i18n="@@settings.providers.noKey"
                        >no API key</ng-container
                      ></span
                    >
                  }
                  <span class="mono-num row-extra" [attr.title]="p.baseUrl || null">{{
                    p.baseUrl || ''
                  }}</span>
                </li>
              }
            </ul>
          </section>

          <section class="card" aria-labelledby="ci-title">
            <header class="card-head">
              <nz-icon nzType="code" class="card-icon" aria-hidden="true" />
              <h3 id="ci-title" i18n="@@settings.ci.title">CI client script</h3>
              <bo-help-link topic="settings.ciScript" />
              <bo-baton
                [baton]="{ holder: 'done', label: text.served, detail: s.ci?.version || null }"
                size="sm"
              />
            </header>
            <p class="what" i18n="@@settings.ci.what">
              Projects call Back Office from their own CI with <code>backoffice-ci.sh</code>; the
              platform never connects to GitLab. Tokens and the snippet are per project (Project ›
              CI).
            </p>
            <div class="script">
              <span class="mono-num script-url" [attr.title]="ciScriptUrl()">{{
                ciScriptUrl()
              }}</span>
              <button nz-button nzSize="small" type="button" (click)="copyScriptUrl()">
                <nz-icon nzType="copy" />
                <ng-container i18n="@@settings.ci.copyUrl">Copy URL</ng-container>
              </button>
              <a
                nz-button
                nzSize="small"
                [href]="ciScriptUrl()"
                target="_blank"
                rel="noopener noreferrer"
                i18n="@@settings.ci.openScript"
                >Open script</a
              >
            </div>
          </section>

          <section class="card" aria-labelledby="session-title">
            <header class="card-head">
              <nz-icon nzType="user" class="card-icon" aria-hidden="true" />
              <h3 id="session-title" i18n="@@settings.session.title">Session</h3>
            </header>
            <p class="what" i18n="@@settings.session.what">
              The browser holds only the session cookie; scripts use personal access tokens from
              <a routerLink="/account">My account</a>.
            </p>
            <dl class="lines">
              <div>
                <dt i18n="@@settings.session.signedInAs">Signed in as</dt>
                <dd class="plain">
                  {{ identity.user()?.displayName || identity.user()?.email }}
                  <span class="quiet">{{ identity.user()?.email }}</span>
                </dd>
              </div>
              <div>
                <dt i18n="@@settings.session.idleTimeout">Idle timeout</dt>
                <dd>{{ duration((identity.me()?.idleTimeoutSeconds ?? 0) * 1000) }}</dd>
              </div>
              <div>
                <dt i18n="@@settings.session.liveEvents">Live events</dt>
                <dd class="plain">
                  <bo-baton [baton]="eventsBaton()" size="sm" />
                  <span class="quiet" i18n="@@settings.session.received"
                    >{{ events.received() }} received</span
                  >
                </dd>
              </div>
            </dl>
          </section>

          <section class="card" aria-labelledby="about-title">
            <header class="card-head">
              <nz-icon nzType="info-circle" class="card-icon" aria-hidden="true" />
              <h3 id="about-title" i18n="@@settings.about.title">About</h3>
            </header>
            <dl class="lines">
              <div>
                <dt i18n="@@settings.about.ui">UI</dt>
                <dd class="plain" i18n="@@settings.about.uiValue">
                  Back Office web (Angular 21, NG-ZORRO 21)
                </dd>
              </div>
              <div>
                <dt i18n="@@settings.about.apiBase">API base</dt>
                <dd>{{ about.apiBase }}</dd>
              </div>
              <div>
                <dt i18n="@@settings.about.contract">Contract</dt>
                <dd>{{ about.contract }}</dd>
              </div>
              <div>
                <dt i18n="@@settings.about.types">Types</dt>
                <dd>{{ about.types }}</dd>
              </div>
            </dl>
          </section>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .section {
        margin: 0 0 10px;
        font-size: 12px;
        font-weight: 600;
        color: var(--bo-ink-3);
        letter-spacing: 0.02em;
      }
      .grid {
        display: grid;
        gap: 16px;
        margin-bottom: 16px;
      }
      .grid.three {
        grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
        margin-bottom: 24px;
      }
      .grid.two {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
      @media (max-width: 1000px) {
        .grid.two {
          grid-template-columns: 1fr;
        }
      }
      .card {
        display: flex;
        flex-direction: column;
        gap: 10px;
        padding: 16px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        min-width: 0;
      }
      .card-head {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .card-head h3 {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
        flex: 1;
      }
      .card-icon {
        color: var(--bo-ink-3);
        font-size: 15px;
      }
      .head-link {
        font-size: 12px;
      }
      .hint {
        margin: 0;
        padding: 8px 10px;
        font-size: 12.5px;
        line-height: 1.5;
        color: var(--bo-ink-2);
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-control);
      }
      .hint.warn {
        background: var(--bo-you-soft);
        border-color: transparent;
        color: var(--bo-ink);
      }
      .what {
        margin: 0;
        font-size: 12.5px;
        color: var(--bo-ink-3);
      }
      code {
        font-size: 11.5px;
        background: var(--bo-surface-3);
        padding: 1px 4px;
        border-radius: 4px;
        color: var(--bo-ink-2);
      }
      .lines {
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .lines div {
        display: grid;
        grid-template-columns: 110px minmax(0, 1fr);
        gap: 12px;
        align-items: baseline;
      }
      .lines dt {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .lines dd {
        margin: 0;
        font-family: var(--bo-font-mono);
        font-size: 12px;
        color: var(--bo-ink);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .lines dd.plain {
        font-family: var(--bo-font-ui);
        font-size: 13px;
        display: flex;
        gap: 8px;
        align-items: center;
        white-space: normal;
      }
      .card-foot {
        margin-top: auto;
        padding-top: 10px;
        border-top: 1px solid var(--bo-line);
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px 12px;
        min-height: 44px;
      }
      .result {
        display: flex;
        align-items: center;
        gap: 10px;
        min-width: 0;
      }
      .state-word {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .state-word.bad {
        color: var(--bo-blocked-ink);
      }
      .quiet {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .mono-num {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .rows {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .rows li {
        display: grid;
        grid-template-columns: 150px 120px minmax(0, 1fr);
        align-items: center;
        gap: 12px;
        padding: 7px 0;
        border-top: 1px solid var(--bo-line);
        font-size: 13px;
      }
      .rows li:first-child {
        border-top: 0;
      }
      .row-extra {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: var(--bo-ink-3);
      }
      .script {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
        padding: 8px 10px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
      }
      .script-url {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: var(--bo-ink);
      }
    `,
  ],
})
export class SettingsPageComponent {
  private readonly api = inject(SettingsApi);
  private readonly message = inject(NzMessageService);
  readonly identity = inject(IdentityService);
  readonly events = inject(EventStreamService);

  readonly status = signal<IntegrationsStatus | null>(null);
  readonly loading = signal(false);
  readonly testing = signal<IntegrationName[]>([]);
  readonly results = signal<Partial<Record<IntegrationName, IntegrationTestResult>>>({});
  readonly cards = computed(() => {
    const s = this.status();
    return s ? integrationCards(s) : [];
  });
  readonly testableCount = computed(
    () => this.cards().filter((c) => c.testable && c.configured).length,
  );
  readonly eventsBaton = computed<Baton>(() => {
    const state = this.events.state();
    if (state === 'connected')
      return { holder: 'done', label: $localize`:@@settings.session.connected:connected` };
    if (state === 'connecting')
      return {
        holder: 'agent',
        label: $localize`:@@settings.session.connecting:connecting`,
        live: true,
      };
    return { holder: 'blocked', label: $localize`:@@settings.session.disconnected:disconnected` };
  });
  readonly anyProviderKey = computed(() =>
    (this.status()?.providers ?? []).some((p) => p.configured),
  );
  /** Manual section per integration card. */
  readonly cardHelp: Record<IntegrationName, HelpKey> = {
    jira: 'settings.jira',
    kubernetes: 'settings.runner',
    langfuse: 'settings.observability',
  } as Record<IntegrationName, HelpKey>;
  readonly duration = formatDuration;
  readonly providerName = (p: string | null | undefined) =>
    PROVIDERS[p ?? ''] ?? (p ? p.charAt(0) + p.slice(1).toLowerCase() : '-');
  readonly reload = () => this.load();
  readonly menu: PageAction[] = [
    {
      label: $localize`:@@settings.page.menu.swagger:Open Swagger UI`,
      icon: 'api',
      href: '/swagger-ui/index.html',
    },
    {
      label: $localize`:@@settings.page.menu.openApi:Open the OpenAPI JSON`,
      icon: 'file-text',
      href: '/v3/api-docs',
    },
    {
      label: $localize`:@@settings.page.menu.reload:Reload status`,
      icon: 'reload',
      run: this.reload,
    },
  ];
  readonly text = { served: $localize`:@@settings.ci.served:served` };
  /** Build facts (paths and commands, not translated). */
  readonly about = {
    apiBase: '/api/v1',
    contract: 'docs/api/openapi.yaml',
    types: 'npm run api:types', // i18n-ignore
  };

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@settings.breadcrumb:Settings` }]);
    this.load();
  }

  resultText(r: IntegrationTestResult): string {
    return (
      r.message ||
      (r.ok
        ? $localize`:@@settings.integrations.testOk:Connection works.`
        : $localize`:@@settings.integrations.testFailed:The connection failed.`)
    );
  }

  baton(c: IntegrationCard): Baton {
    return integrationBaton(c.configured, this.results()[c.name], this.isTesting(c.name));
  }

  isTesting(name: IntegrationName): boolean {
    return this.testing().includes(name);
  }

  load(): void {
    this.loading.set(true);
    this.api.integrations().subscribe({
      next: (s) => {
        this.status.set(s);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  testAll(): void {
    for (const c of this.cards()) if (c.testable && c.configured) this.test(c.name);
  }

  test(name: IntegrationName): void {
    this.testing.update((t) => [...t.filter((x) => x !== name), name]);
    const done = () => this.testing.update((t) => t.filter((x) => x !== name));
    this.api.testIntegration(name).subscribe({
      next: (r) => {
        this.results.update((m) => ({ ...m, [name]: r }));
        done();
      },
      error: (err: unknown) => {
        this.results.update((m) => ({ ...m, [name]: { ok: false, message: problemMessage(err) } }));
        done();
      },
    });
  }

  ciScriptUrl(): string {
    const s = this.status();
    const path = s?.ci?.scriptUrl || CI_SCRIPT_URL;
    if (/^https?:\/\//.test(path)) return path;
    return typeof location !== 'undefined' ? `${location.origin}${path}` : path;
  }

  async copyScriptUrl(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.ciScriptUrl());
      this.message.success($localize`:@@settings.ci.copied:Script URL copied`);
    } catch {
      this.message.warning(
        $localize`:@@settings.ci.clipboardUnavailable:The clipboard is not available; copy the URL by hand.`,
      );
    }
  }
}
