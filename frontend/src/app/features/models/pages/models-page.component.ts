import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import type {
  ModelProfile,
  ModelProfileInput,
  ModelTestResult,
  ProviderStatus,
  LlmProvider,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { formatDuration } from '../../../core/ui/pipes/duration.pipe';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import {
  FilterBarComponent,
  type FilterChip,
  type FilterPreset,
} from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { ModelProfileDrawerComponent } from '../components/model-profile-drawer.component';
import {
  contextLabel,
  filterProfiles,
  perMTok,
  providerLabel,
  reasoningLabel,
} from '../data/model-format';
import { compareText } from '../../../core/i18n/compare';
import { icu } from '../../../core/i18n/icu';
import { LlmProvidersApi } from '../../../core/api/llm-providers.service';
import { ProviderCatalogComponent } from '../components/provider-catalog.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { isLoginProfile, profileKey } from '../data/model-catalog';

/**
 * Models: the catalogue of model profiles. Filters live in the URL; rows open the profile side
 * panel (`?profile=<id>`, `?profile=new`), where the profile can be tested and edited. "Test
 * profile" results also show inline in the list.
 */
@Component({
  selector: 'app-models-page',
  imports: [
    FormsModule,
    RouterLink,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    NzSwitchModule,
    NzTooltipModule,
    CostComponent,
    EmptyComponent,
    FilterBarComponent,
    GlyphComponent,
    PageHeaderComponent,
    SkeletonComponent,
    ModelProfileDrawerComponent,
    ProviderCatalogComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="models"
        title="Models"
        i18n-title="@@models.title"
        subtitle="The model catalogue: provider, model id, default options and pricing. Workflow steps and benchmarks pick from here."
        i18n-subtitle="@@models.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        <a boHeaderActions nz-button routerLink="/models/providers">
          <nz-icon nzType="api" />
          <ng-container i18n="@@models.providersLink">LLM providers</ng-container>
        </a>
        @if (canManage()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <ng-container i18n="@@models.create">Create profile</ng-container>
          </button>
        }
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search name, model id or notes"
        i18n-placeholder="@@models.search"
        label="Filter model profiles"
        i18n-label="@@models.filter"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [chips]="chips()"
        (chipRemoved)="removeChip($event)"
        (cleared)="f.clear()"
        [presets]="presets()"
        (presetSelected)="applyPreset($any($event))"
      >
        <nz-select
          [ngModel]="f.values().provider"
          (ngModelChange)="f.set({ provider: $event })"
          nzAllowClear
          nzPlaceHolder="All providers"
          i18n-nzPlaceHolder="@@models.allProviders"
          style="width: 180px"
          aria-label="Filter by provider"
          i18n-aria-label="@@models.filterProvider"
        >
          @for (p of providerOptions(); track p) {
            <nz-option [nzValue]="p" [nzLabel]="providerName(p)" />
          }
        </nz-select>
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="7"
          [columns]="7"
          label="Loading model profiles"
          i18n-label="@@models.loading"
        />
      } @else if (!visible().length) {
        @if (hiddenCount()) {
          <!-- only profiles of unconnected providers: the line below offers them -->
        } @else if (f.active()) {
          <bo-empty
            title="No profile matches these filters."
            i18n-title="@@models.noMatch"
            actionLabel="Clear filters"
            i18n-actionLabel="@@models.clearFilters"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No model profiles yet."
            i18n-title="@@models.empty"
            detail="Add the models you want workflow steps and benchmarks to run with."
            i18n-detail="@@models.emptyDetail"
            [actionLabel]="canManage() ? text.create : null"
            (action)="create()"
            help="settings.llm"
            guideLabel="Connect a provider"
            i18n-guideLabel="@@models.guide"
          />
        }
      } @else {
        <nz-table
          #t
          [nzData]="rows()"
          [nzShowPagination]="rows().length > 50"
          [nzPageSize]="50"
          nzSize="middle"
        >
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@models.col.profile">
                Profile
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="provider"
                nzWidth="150px"
                i18n="@@models.col.provider"
              >
                Provider
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="model"
                nzWidth="170px"
                i18n="@@models.col.modelId"
              >
                Model id
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="price"
                nzWidth="150px"
                nzAlign="right"
                i18n="@@models.col.price"
              >
                In / out per MTok
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="context"
                nzWidth="96px"
                nzAlign="right"
                i18n="@@models.col.context"
              >
                Context
              </th>
              <th nzWidth="190px" i18n="@@models.col.lastTest">Last test</th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="enabled"
                nzWidth="96px"
                i18n="@@models.col.enabled"
              >
                Enabled
              </th>
              <th nzWidth="130px">
                <span class="sr-only" i18n="@@models.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (p of t.data; track p.id; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i || panel.value() === p.id"
                [class.off]="p.enabled === false"
                [attr.data-nav-index]="i"
                tabindex="0"
                (click)="edit(p)"
                (keydown.enter)="edit(p)"
                [attr.aria-label]="p.displayName + ', ' + p.ref.modelId"
              >
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      <span class="cell-title">{{ p.displayName }}</span>
                      @if (isLogin(p)) {
                        <span
                          class="chip login"
                          nz-tooltip
                          [nzTooltipTitle]="text.loginHint"
                          i18n="@@models.login.tag"
                          >Claude Code login</span
                        >
                      } @else if (p.ref.options?.reasoning; as r) {
                        <span class="chip" i18n="@@models.reasoningChip"
                          >{{ reasoning(r) }} reasoning</span
                        >
                      }
                    </div>
                    @if (p.notes) {
                      <span class="cell-sub" [attr.title]="p.notes">{{ p.notes }}</span>
                    }
                  </div>
                </td>
                <td>
                  <div class="cell-main">
                    <span class="ink">{{ providerName(p.ref.provider) }}</span>
                    @if (isLogin(p)) {
                      <span class="state-word"
                        ><bo-glyph holder="done" [size]="11" />
                        <ng-container i18n="@@models.login.yourLogin"
                          >your Claude login</ng-container
                        ></span
                      >
                    } @else if (p.providerConfigured === false) {
                      <span class="state-word"
                        ><bo-glyph holder="idle" [size]="11" />
                        <ng-container i18n="@@models.notConnected"
                          >not connected</ng-container
                        ></span
                      >
                    } @else {
                      <span class="state-word"
                        ><bo-glyph holder="done" [size]="11" />
                        <ng-container i18n="@@models.configured">configured</ng-container></span
                      >
                    }
                  </div>
                </td>
                <td>
                  <span class="mono-cell">{{ p.ref.modelId }}</span>
                </td>
                <td nzAlign="right">
                  @if (isLogin(p)) {
                    <span class="quiet" i18n="@@models.login.notBilled">not billed</span>
                  } @else {
                    <div class="cell-main right">
                      <span class="mono-cell"
                        >{{ price(p.pricing.inputPerMTok) }}
                        <span class="quiet">/</span>
                        {{ price(p.pricing.outputPerMTok) }}</span
                      >
                      @if (
                        p.pricing.cacheReadPerMTok !== null &&
                        p.pricing.cacheReadPerMTok !== undefined
                      ) {
                        <span class="sub-mono" i18n="@@models.cachePrice"
                          >cache {{ price(p.pricing.cacheReadPerMTok) }}</span
                        >
                      }
                    </div>
                  }
                </td>
                <td nzAlign="right">
                  <span class="mono-cell">{{ context(p.contextWindow) }}</span>
                </td>
                <td>
                  @if (testing() === p.id) {
                    <span class="state-word"
                      ><bo-glyph holder="agent" [live]="true" [size]="11" />
                      <ng-container i18n="@@models.testing">testing</ng-container></span
                    >
                  } @else if (results()[p.id]; as r) {
                    <span class="state-word" [attr.title]="r.error || null">
                      <bo-glyph [holder]="r.ok ? 'done' : 'blocked'" [size]="11" />
                      @if (r.ok && isLogin(p)) {
                        <ng-container i18n="@@models.login.ready">runner ready</ng-container>
                      } @else if (r.ok) {
                        <ng-container i18n="@@models.answered">answered</ng-container>
                      } @else {
                        <ng-container i18n="@@models.failed">failed</ng-container>
                      }
                      @if (r.ok && r.latencyMs !== undefined) {
                        <span class="sub-mono">{{ duration(r.latencyMs) }}</span>
                      }
                      @if (r.cost) {
                        <bo-cost [value]="r.cost" [usage]="r.usage" />
                      }
                    </span>
                  } @else {
                    <span class="quiet" i18n="@@models.notTested">not tested</span>
                  }
                </td>
                <td (click)="$event.stopPropagation()">
                  <nz-switch
                    [ngModel]="p.enabled !== false"
                    (ngModelChange)="toggle(p, $event)"
                    [nzLoading]="busy() === p.id"
                    [nzDisabled]="!canManage()"
                    nzSize="small"
                    [attr.aria-label]="enableLabel(p)"
                  />
                </td>
                <td (click)="$event.stopPropagation()">
                  <div class="row-actions">
                    @if (canManage()) {
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        type="button"
                        (click)="test(p)"
                        [disabled]="p.providerConfigured === false || testing() === p.id"
                        nz-tooltip
                        [nzTooltipTitle]="
                          p.providerConfigured === false
                            ? text.setKey
                            : isLogin(p)
                              ? text.loginTestHint
                              : text.testHint
                        "
                        [attr.aria-label]="testLabel(p)"
                      >
                        <nz-icon nzType="thunderbolt" />
                      </button>
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        type="button"
                        (click)="duplicate(p)"
                        nz-tooltip
                        nzTooltipTitle="Duplicate profile"
                        i18n-nzTooltipTitle="@@models.duplicate"
                        [attr.aria-label]="duplicateLabel(p)"
                      >
                        <nz-icon nzType="copy" />
                      </button>
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        class="del"
                        type="button"
                        (click)="remove(p)"
                        nz-tooltip
                        nzTooltipTitle="Delete profile"
                        i18n-nzTooltipTitle="@@models.delete"
                        [attr.aria-label]="deleteLabel(p)"
                      >
                        <nz-icon nzType="delete" />
                      </button>
                    } @else {
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        type="button"
                        (click)="edit(p)"
                        [attr.aria-label]="viewLabel(p)"
                      >
                        <nz-icon nzType="eye" />
                      </button>
                    }
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }

      @if (hiddenCount() || showUnavailable()) {
        <div class="notice" role="note">
          <bo-glyph holder="idle" [size]="13" />
          <span>{{ hiddenLabel() }}</span>
          <button
            nz-button
            nzType="link"
            nzSize="small"
            type="button"
            (click)="showUnavailable.set(!showUnavailable())"
          >
            @if (showUnavailable()) {
              <ng-container i18n="@@models.unavailable.hide">Hide them</ng-container>
            } @else {
              <ng-container i18n="@@models.unavailable.show">Show them</ng-container>
            }
          </button>
        </div>
      }

      <app-provider-catalog
        [existing]="existingKeys()"
        [canManage]="canManage()"
        (add)="addFromCatalog($event)"
      />
    </div>

    <app-model-profile-drawer
      [open]="panelOpen()"
      [profile]="editing()"
      [providers]="providers()"
      [connections]="connections()"
      [saving]="saving()"
      [testing]="!!editing()?.id && testing() === editing()?.id"
      [testResult]="editing()?.id ? (results()[editing()!.id] ?? null) : null"
      (saved)="save($event)"
      (test)="editing() && test(editing()!)"
      (closed)="closePanel()"
    />
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .notice {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 6px 14px;
        margin: 12px 0 0;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      .notice button {
        margin-left: auto;
      }
      .ink {
        color: var(--bo-ink);
      }
      .right {
        align-items: flex-end;
      }
      .sub-mono {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .chip.login {
        color: var(--bo-you-ink);
      }
      tr.off .cell-title,
      tr.off .mono-cell {
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class ModelsPageComponent {
  private readonly api = inject(ModelProfilesApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  readonly identity = inject(IdentityService);

  readonly f = urlFilters({ q: 'string', provider: 'string', state: 'string' });
  readonly panel = panelParam('profile');
  readonly profiles = signal<ModelProfile[]>([]);
  readonly providers = signal<ProviderStatus[]>([]);
  /** ADR 0045: provider connections a profile may name. */
  readonly connections = signal<LlmProvider[]>([]);
  private readonly llmProvidersApi = inject(LlmProvidersApi);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly saving = signal(false);
  readonly busy = signal<string | null>(null);
  readonly testing = signal<string | null>(null);
  readonly results = signal<Partial<Record<string, ModelTestResult>>>({});
  /** Source of a duplicate (opened as `?profile=new`). */
  private readonly copyOf = signal<ModelProfile | null>(null);

  readonly canManage = computed(() => this.identity.can('models.manage'));
  readonly providerName = providerLabel;
  readonly price = perMTok;
  readonly context = contextLabel;
  readonly duration = (ms: number) => formatDuration(ms);
  readonly reasoning = (r: string) => reasoningLabel(r).toLowerCase();
  readonly text = {
    reload: $localize`:@@models.reload:Reload profiles`,
    create: $localize`:@@models.createAction:Create profile`,
    setKey: $localize`:@@models.setKeyFirst:Set the provider API key first`,
    testHint: $localize`:@@models.testHint:Send a tiny prompt and measure latency and cost`,
    loginTestHint: $localize`:@@models.login.testHint:Check that your personal runner with Claude Code login is online`,
    loginHint: $localize`:@@models.login.hint:Claude Code on your personal runner with your own Claude subscription: coding tasks only, never the judge, nothing billed.`,
  };
  /** ADR 0048: Claude Code with the user's own login (personal runner, no API key, no price). */
  readonly isLogin = isLoginProfile;
  /** ADR 0045: profiles whose provider is not connected are hidden unless asked for. */
  readonly showUnavailable = signal(false);

  readonly filtered = computed(() =>
    filterProfiles(this.profiles(), {
      query: this.f.values().q,
      provider: this.f.values().provider,
      state: this.f.values().state,
    }),
  );
  readonly visible = computed(() =>
    this.showUnavailable()
      ? this.filtered()
      : this.filtered().filter((p) => p.providerConfigured !== false),
  );
  /** Column sorting (URL `?sort=`); the same order for the table and keyboard navigation. */
  readonly sort = tableSort<ModelProfile>({
    name: (p) => p.displayName,
    provider: (p) => providerLabel(p.ref.provider),
    model: (p) => p.ref.modelId,
    price: (p) => p.pricing.inputPerMTok,
    context: (p) => p.contextWindow,
    enabled: (p) => p.enabled !== false,
  });
  readonly rows = computed(() => this.sort.apply(this.visible()));
  readonly hiddenCount = computed(
    () => this.filtered().filter((p) => p.providerConfigured === false).length,
  );
  readonly hiddenLabel = computed(() =>
    this.showUnavailable()
      ? icu(
          $localize`:@@models.unavailable.shown:{count, plural, =0 {Every profile has a connected provider.} one {# profile is shown although its provider is not connected; tests and runs with it fail.} other {# profiles are shown although their provider is not connected; tests and runs with them fail.}}`,
          { count: this.hiddenCount() },
        )
      : icu(
          $localize`:@@models.unavailable.hidden:{count, plural, one {# profile is hidden: its provider is not connected.} other {# profiles are hidden: their provider is not connected.}}`,
          { count: this.hiddenCount() },
        ),
  );
  /** Catalogue keys of the existing profiles, so the catalogue marks models already added. */
  readonly existingKeys = computed(() => new Set(this.profiles().map((p) => profileKey(p))));
  readonly providerOptions = computed(() =>
    [...new Set(this.profiles().map((p) => p.ref.provider))].sort(compareText),
  );
  readonly editing = computed<ModelProfile | null>(() => {
    const id = this.panel.value();
    if (!id) return null;
    if (id === 'new') {
      const src = this.copyOf();
      return src
        ? src.id
          ? {
              ...src,
              id: undefined as unknown as string,
              displayName: $localize`:@@models.copyName:${src.displayName}:name: (copy)`,
            }
          : src
        : null;
    }
    return this.profiles().find((p) => p.id === id) ?? null;
  });
  readonly panelOpen = computed(() => {
    const id = this.panel.value();
    return id === 'new' ? this.canManage() : !!this.editing();
  });
  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    if (v.provider) {
      const provider = providerLabel(v.provider);
      out.push({
        id: 'provider',
        label: $localize`:@@models.chip.provider:Provider: ${provider}:provider:`,
      });
    }
    return out;
  });
  readonly presets = computed<FilterPreset[]>(() => {
    const s = this.f.values().state;
    return [
      {
        label: $localize`:@@models.preset.enabled:Enabled`,
        value: 'enabled',
        active: s === 'enabled',
      },
      {
        label: $localize`:@@models.preset.disabled:Disabled`,
        value: 'disabled',
        active: s === 'disabled',
      },
    ];
  });

  readonly nav = listNavigation({
    items: this.rows,
    open: (p) => this.edit(p),
    group: $localize`:@@models.group:Models`,
    when: () => !this.panelOpen(),
  });
  readonly reload = () => this.load();

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@models.group:Models` }]);
    this.load();
    this.api.providers().subscribe((p) => this.providers.set(p));
    this.llmProvidersApi
      .list()
      .subscribe({ next: (c) => this.connections.set(c), error: () => undefined });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@models.shortcut.search:Search profiles`,
          group: $localize`:@@models.group:Models`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@models.createAction:Create profile`,
          group: $localize`:@@models.group:Models`,
          when: () => this.canManage() && !this.panelOpen(),
          handler: () => this.create(),
        },
      ],
      inject(DestroyRef),
    );
  }

  enableLabel(p: ModelProfile): string {
    const name = p.displayName;
    return $localize`:@@models.enableLabel:Enable ${name}:name:`;
  }

  testLabel(p: ModelProfile): string {
    const name = p.displayName;
    return $localize`:@@models.testLabel:Test profile ${name}:name:`;
  }

  duplicateLabel(p: ModelProfile): string {
    const name = p.displayName;
    return $localize`:@@models.duplicateLabel:Duplicate ${name}:name:`;
  }

  deleteLabel(p: ModelProfile): string {
    const name = p.displayName;
    return $localize`:@@models.deleteLabel:Delete ${name}:name:`;
  }

  viewLabel(p: ModelProfile): string {
    const name = p.displayName;
    return $localize`:@@models.viewLabel:View ${name}:name:`;
  }

  applyPreset(value: string): void {
    this.f.set({ state: this.f.values().state === value ? null : value });
  }

  removeChip(id: string): void {
    if (id === 'provider') this.f.set({ provider: null });
  }

  load(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (list) => {
        this.profiles.set(list);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  create(): void {
    this.copyOf.set(null);
    this.panel.open('new');
  }

  edit(p: ModelProfile): void {
    this.panel.open(p.id);
  }

  duplicate(p: ModelProfile): void {
    this.copyOf.set(p);
    this.panel.open('new');
  }

  /** "Add as profile" in the catalogue: the drawer opens prefilled (a profile without id). */
  addFromCatalog(prefill: ModelProfile): void {
    this.copyOf.set(prefill);
    this.panel.open('new');
  }

  closePanel(): void {
    this.panel.close();
    this.copyOf.set(null);
  }

  save(input: ModelProfileInput): void {
    this.saving.set(true);
    const existing = this.editing();
    const req = existing?.id ? this.api.update(existing.id, input) : this.api.create(input);
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        const name = saved.displayName;
        this.message.success(
          existing?.id
            ? $localize`:@@models.saved:Profile saved`
            : $localize`:@@models.created:Profile "${name}:name:" created`,
        );
        this.copyOf.set(null);
        this.panel.close();
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  toggle(p: ModelProfile, enabled: boolean): void {
    this.busy.set(p.id);
    const {
      id: _id,
      providerConfigured: _pc,
      createdAt: _c,
      updatedAt: _u,
      updatedBy: _ub,
      ...input
    } = p;
    this.api.update(p.id, { ...input, enabled }).subscribe({
      next: (updated) => {
        this.profiles.update((list) => list.map((x) => (x.id === updated.id ? updated : x)));
        this.busy.set(null);
      },
      error: () => this.busy.set(null),
    });
  }

  test(p: ModelProfile): void {
    this.testing.set(p.id);
    this.api.test(p.id).subscribe({
      next: (r) => {
        this.testing.set(null);
        this.results.update((m) => ({ ...m, [p.id]: r }));
      },
      error: (err: unknown) => {
        this.testing.set(null);
        this.results.update((m) => ({ ...m, [p.id]: { ok: false, error: problemMessage(err) } }));
      },
    });
  }

  async remove(p: ModelProfile): Promise<void> {
    const name = p.displayName;
    const ok = await this.confirm.danger(
      $localize`:@@models.delete.title:Delete "${name}:name:"?`,
      $localize`:@@models.delete.text:Workflow steps, project defaults and benchmarks that still use this profile keep the server from deleting it; disable it instead to hide it from pickers.`,
      $localize`:@@models.delete.ok:Delete profile`,
    );
    if (!ok) return;
    this.api.delete(p.id).subscribe(() => {
      this.message.success($localize`:@@models.deleted:Profile deleted`);
      if (this.panel.value() === p.id) this.panel.close();
      this.load();
    });
  }
}
