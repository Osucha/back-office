import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { LlmProvidersApi } from '../../../core/api/llm-providers.service';
import type { ModelProfile, ProviderCatalogSection } from '../../../core/api/types';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { icu } from '../../../core/i18n/icu';
import { catalogRows, filterCatalog, profileFromCatalog, type CatalogRow } from '../data/model-catalog';
import { contextLabel, perMTok, providerLabel } from '../data/model-format';

/**
 * "Available from providers" (ADR 0045): the models the connected providers offer, read live from them, with prices
 * and context windows where the provider publishes them. "Add as profile" opens the profile drawer prefilled.
 */
@Component({
  selector: 'app-provider-catalog',
  imports: [
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    NzSwitchModule,
    NzTableModule,
    NzTooltipModule,
    GlyphComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="catalog" aria-labelledby="catalog-title">
      <div class="head">
        <div>
          <h2 id="catalog-title" i18n="@@models.catalog.title">Available from providers</h2>
          <p class="sub" i18n="@@models.catalog.subtitle">
            Models your connected providers offer right now. Add the ones you want as profiles.
          </p>
        </div>
        <button
          nz-button
          type="button"
          (click)="load(true)"
          [nzLoading]="loading()"
          i18n-aria-label="@@models.catalog.refresh"
          aria-label="Ask the providers again"
          nz-tooltip
          nzTooltipTitle="Ask the providers again"
          i18n-nzTooltipTitle="@@models.catalog.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
      </div>

      @for (s of failed(); track s.name) {
        <div class="notice" role="note">
          <bo-glyph holder="blocked" [size]="12" />
          <span><strong>{{ s.name }}</strong>: {{ s.error }}</span>
        </div>
      }

      @if (loaded() && !sections().length) {
        <div class="notice" role="note">
          <bo-glyph holder="idle" [size]="12" />
          <span i18n="@@models.catalog.none">No provider is connected yet.</span>
          <a routerLink="/models/providers" i18n="@@models.catalog.connect">Connect a provider</a>
        </div>
      } @else if (rows().length) {
        <div class="filters">
          <input
            nz-input
            type="search"
            [ngModel]="query()"
            (ngModelChange)="query.set($event)"
            placeholder="Search model id or name"
            i18n-placeholder="@@models.catalog.search"
            aria-label="Search model id or name"
            i18n-aria-label="@@models.catalog.search"
            class="search"
          />
          @if (sections().length > 1) {
            <nz-select
              [ngModel]="connection()"
              (ngModelChange)="connection.set($event)"
              nzAllowClear
              nzPlaceHolder="All connections"
              i18n-nzPlaceHolder="@@models.catalog.allConnections"
              aria-label="Filter by connection"
              i18n-aria-label="@@models.catalog.filterConnection"
              style="width: 200px"
            >
              @for (s of sections(); track s.name) {
                <nz-option [nzValue]="s.connection || 'env:' + s.provider" [nzLabel]="s.name" />
              }
            </nz-select>
          }
          <label class="switch">
            <nz-switch nzSize="small" [ngModel]="freeOnly()" (ngModelChange)="freeOnly.set($event)" />
            <span i18n="@@models.catalog.freeOnly">Free only</span>
          </label>
          <span class="count">{{ countLabel() }}</span>
        </div>

        <nz-table
          #t
          [nzData]="sortedRows()"
          [nzPageSize]="20"
          [nzShowPagination]="sortedRows().length > 20"
          nzSize="small"
        >
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="model" i18n="@@models.catalog.col.model">Model</th>
              <th nzShowSort [boSort]="sort" boSortKey="connection" nzWidth="186px" i18n="@@models.catalog.col.connection">Connection</th>
              <th nzShowSort [boSort]="sort" boSortKey="price" nzWidth="166px" nzAlign="right" i18n="@@models.col.price">In / out per MTok</th>
              <th nzShowSort [boSort]="sort" boSortKey="context" nzWidth="96px" nzAlign="right" i18n="@@models.col.context">Context</th>
              <th nzWidth="150px"><span class="sr-only" i18n="@@models.col.actions">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (r of t.data; track r.key) {
              <tr>
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      <span class="cell-title">{{ r.model.name || r.model.id }}</span>
                      @if (r.model.free) {
                        <span class="chip free" i18n="@@models.catalog.free">free</span>
                      }
                    </div>
                    @if (r.model.name) {
                      <span class="mono-cell sub">{{ r.model.id }}</span>
                    }
                  </div>
                </td>
                <td>
                  <div class="cell-main">
                    <span>{{ r.connectionName }}</span>
                    <span class="sub">{{ providerName(r.provider) }}</span>
                  </div>
                </td>
                <td nzAlign="right">
                  <span class="mono-cell"
                    >{{ price(r.model.inputPerMTok) }} <span class="quiet">/</span>
                    {{ price(r.model.outputPerMTok) }}</span
                  >
                </td>
                <td nzAlign="right">
                  <span class="mono-cell">{{ context(r.model.contextWindow) }}</span>
                </td>
                <td>
                  @if (existing().has(r.key)) {
                    <span class="state-word"
                      ><bo-glyph holder="done" [size]="11" />
                      <ng-container i18n="@@models.catalog.added">profile exists</ng-container></span
                    >
                  } @else if (canManage()) {
                    <button nz-button nzSize="small" type="button" (click)="add.emit(prefill(r))">
                      <nz-icon nzType="plus" />
                      <ng-container i18n="@@models.catalog.add">Add as profile</ng-container>
                    </button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      } @else if (loading()) {
        <p class="sub" i18n="@@models.catalog.loading">Asking the providers for their models...</p>
      }
    </section>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .catalog {
        margin-top: 32px;
      }
      .head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 12px;
        margin-bottom: 12px;
      }
      h2 {
        margin: 0;
        font-size: 16px;
        font-weight: 600;
        color: var(--bo-ink);
      }
      .sub {
        margin: 2px 0 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .notice {
        display: flex;
        align-items: baseline;
        gap: 10px;
        padding: 8px 12px;
        margin-bottom: 12px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      .notice a {
        margin-left: auto;
        white-space: nowrap;
      }
      .filters {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 12px;
        margin-bottom: 12px;
      }
      .search {
        width: 280px;
        max-width: 100%;
      }
      .switch {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        cursor: pointer;
      }
      .count {
        margin-left: auto;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .chip.free {
        color: var(--bo-done-ink);
      }
    `,
  ],
})
export class ProviderCatalogComponent {
  private readonly api = inject(LlmProvidersApi);

  /** Catalogue keys (`catalogKey`) of the existing profiles. */
  readonly existing = input<ReadonlySet<string>>(new Set());
  readonly canManage = input(false);
  readonly add = output<ModelProfile>();

  readonly sections = signal<ProviderCatalogSection[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly query = signal('');
  readonly connection = signal<string | null>(null);
  readonly freeOnly = signal(false);

  readonly providerName = providerLabel;
  readonly price = perMTok;
  readonly context = contextLabel;
  readonly prefill = profileFromCatalog;

  readonly rows = computed<CatalogRow[]>(() => catalogRows(this.sections()));
  readonly failed = computed(() => this.sections().filter((s) => !s.ok));
  readonly filtered = computed(() =>
    filterCatalog(this.rows(), { query: this.query(), connection: this.connection(), freeOnly: this.freeOnly() }),
  );
  /** Column sorting (URL `?catalogSort=`, apart from the profiles table above). */
  readonly sort = tableSort<CatalogRow>(
    {
      model: (r) => r.model.name || r.model.id,
      connection: (r) => r.connectionName,
      price: (r) => r.model.inputPerMTok,
      context: (r) => r.model.contextWindow,
    },
    { param: 'catalogSort' },
  );
  readonly sortedRows = computed(() => this.sort.apply(this.filtered()));
  readonly countLabel = computed(() =>
    icu($localize`:@@models.catalog.count:{count, plural, one {# model} other {# models}}`, {
      count: this.filtered().length,
    }),
  );

  constructor() {
    this.load(false);
  }

  load(refresh: boolean): void {
    this.loading.set(true);
    this.api.catalog(refresh).subscribe({
      next: (s) => {
        this.sections.set(s);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }
}
