import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { LlmProvidersApi } from '../../../core/api/llm-providers.service';
import type { LlmProvider, LlmProviderInput, Provider, ProviderModels } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { providerLabel } from '../data/model-format';
import { PROVIDER_TYPES, PRESETS, connectionProblems, emptyConnection, type ConnectionPreset } from '../data/llm-providers';

/**
 * LLM providers (ADR 0045): connections to model vendors with their API keys, added here instead of environment
 * variables. Several connections of one type are possible (OpenRouter and Groq side by side); model profiles pick one.
 * Keys are write-only and sealed with the server's secret key. Endpoints still configured by environment variables are
 * listed read-only.
 */
@Component({
  selector: 'app-llm-providers-page',
  imports: [
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzFormModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    NzSwitchModule,
    NzTableModule,
    EmptyComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="settings.llm"
        title="LLM providers"
        i18n-title="@@models.providers.title"
        subtitle="Connections to model vendors with their API keys. Model profiles pick one; you can have several of one type, for example OpenRouter and Groq."
        i18n-subtitle="@@models.providers.subtitle"
      >
        @if (canManage()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <span i18n="@@models.providers.add">Add provider</span>
          </button>
        }
      </bo-page-header>
      <p class="muted small">
        <a routerLink="/models" i18n="@@models.providers.backToModels">Model profiles</a>
      </p>

      @if (loaded() && !items().length) {
        <bo-empty
          title="No LLM provider yet"
          i18n-title="@@models.providers.empty.title"
          detail="Add a connection with an API key (or a local Ollama), then create model profiles that use it."
          i18n-detail="@@models.providers.empty.detail"
        />
      } @else {
        <nz-table #t [nzData]="items()" [nzLoading]="!loaded()" [nzShowPagination]="false" nzSize="middle" class="relay-table">
          <thead>
            <tr>
              <th i18n="@@models.providers.col.connection">Connection</th>
              <th i18n="@@models.providers.col.type">Type</th>
              <th i18n="@@models.providers.col.endpoint">Endpoint</th>
              <th i18n="@@models.providers.col.state">State</th>
              <th i18n="@@models.providers.col.changed">Changed</th>
            </tr>
          </thead>
          <tbody>
            @for (p of t.data; track p.id ?? 'env-' + p.provider) {
              <tr class="row" tabindex="0" (click)="edit(p)" (keydown.enter)="edit(p)">
                <td>
                  <div class="name">{{ p.name }}</div>
                  @if (p.key) {
                    <div class="mono muted small">{{ p.key }}</div>
                  } @else {
                    <div class="muted small" i18n="@@models.providers.fromEnvironment">environment variables (read only)</div>
                  }
                </td>
                <td>{{ typeName(p.provider) }}</td>
                <td class="mono small">{{ p.baseUrl || defaultUrl(p.provider) }}</td>
                <td>
                  @if (p.usable) {
                    <span class="ok" i18n="@@models.providers.state.usable">ready</span>
                  } @else if (!p.enabled) {
                    <span class="muted" i18n="@@models.providers.state.disabled">disabled</span>
                  } @else {
                    <span class="bad" i18n="@@models.providers.state.incomplete">missing key or URL</span>
                  }
                  @if (p.hasApiKey) {
                    <span class="muted small" i18n="@@models.providers.state.keySet"> · key set</span>
                  }
                </td>
                <td>
                  @if (p.updatedAt) {
                    <bo-provenance [actor]="p.updatedBy" [at]="p.updatedAt" />
                  }
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }

      <bo-side-panel [open]="panelOpen()" [title]="panelTitle()" (closed)="close()">
        @if (panelOpen()) {
          @if (draft(); as d) {
            <form nz-form nzLayout="vertical" (ngSubmit)="save()">
              @if (!editing()) {
                <div class="presets">
                  <span class="muted small" i18n="@@models.providers.presets">Quick start:</span>
                  @for (preset of presets; track preset.id) {
                    <button nz-button nzSize="small" type="button" (click)="applyPreset(preset)">{{ preset.label }}</button>
                  }
                </div>
              }
              <nz-form-item>
                <nz-form-label nzRequired nzFor="lp-type" i18n="@@models.providers.form.type">Type</nz-form-label>
                <nz-form-control>
                  <nz-select nzId="lp-type" name="provider" [ngModel]="d.provider" (ngModelChange)="patch({ provider: $event })" [nzDisabled]="!!editing() || !canManage()">
                    @for (t of types; track t) {
                      <nz-option [nzValue]="t" [nzLabel]="typeName(t)" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <div class="grid">
                <nz-form-item>
                  <nz-form-label nzRequired nzFor="lp-name" i18n="@@models.providers.form.name">Name</nz-form-label>
                  <nz-form-control>
                    <input nz-input id="lp-name" name="name" [ngModel]="d.name" (ngModelChange)="patch({ name: $event })" [readOnly]="!canManage()" />
                  </nz-form-control>
                </nz-form-item>
                <nz-form-item>
                  <nz-form-label nzRequired nzFor="lp-key" i18n="@@models.providers.form.key">Key</nz-form-label>
                  <nz-form-control
                    nzExtra="Model profiles refer to it; it cannot change."
                    i18n-nzExtra="@@models.providers.form.keyHint"
                  >
                    <input nz-input id="lp-key" name="key" class="mono" [ngModel]="d.key" (ngModelChange)="patch({ key: $event })" [readOnly]="!!editing() || !canManage()" />
                  </nz-form-control>
                </nz-form-item>
              </div>
              <nz-form-item>
                <nz-form-label [nzRequired]="urlRequired()" nzFor="lp-url" i18n="@@models.providers.form.baseUrl">Base URL</nz-form-label>
                <nz-form-control [nzExtra]="urlHint()">
                  <input nz-input id="lp-url" name="baseUrl" class="mono" [placeholder]="defaultUrl(d.provider)" [ngModel]="d.baseUrl ?? ''" (ngModelChange)="patch({ baseUrl: $event || null })" [readOnly]="!canManage()" />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="lp-apikey" i18n="@@models.providers.form.apiKey">API key</nz-form-label>
                <nz-form-control [nzExtra]="keyHint()">
                  <input
                    nz-input
                    id="lp-apikey"
                    name="apiKey"
                    type="password"
                    autocomplete="new-password"
                    class="mono"
                    [placeholder]="editing()?.hasApiKey ? text.keepKey : ''"
                    [ngModel]="d.apiKey ?? ''"
                    (ngModelChange)="patch({ apiKey: $event || null })"
                    [readOnly]="!canManage()"
                  />
                </nz-form-control>
              </nz-form-item>
              @if (editing()?.hasApiKey && canManage()) {
                <label class="small"
                  ><input type="checkbox" name="clear" [ngModel]="d.clearApiKey" (ngModelChange)="patch({ clearApiKey: $event })" />
                  <span i18n="@@models.providers.form.clearKey"> Remove the stored key</span></label
                >
              }
              <nz-form-item>
                <nz-form-control>
                  <nz-switch name="enabled" [ngModel]="d.enabled !== false" (ngModelChange)="patch({ enabled: $event })" [nzDisabled]="!canManage()" />
                  <span class="muted small switch-label" i18n="@@models.providers.form.enabled">Enabled</span>
                </nz-form-control>
              </nz-form-item>
              @for (p of problems(); track p) {
                <p class="problem">{{ p }}</p>
              }
              @if (canManage()) {
                <div class="actions">
                  <button nz-button nzType="primary" type="submit" [nzLoading]="saving()" [disabled]="problems().length > 0" i18n="@@models.providers.form.save">Save</button>
                  @if (editing(); as e) {
                    <button nz-button type="button" [nzLoading]="testing()" (click)="test(e)" i18n="@@models.providers.form.test">Test and list models</button>
                    <button nz-button nzDanger type="button" (click)="remove(e)" i18n="@@models.providers.form.delete">Delete</button>
                  }
                </div>
              }
            </form>
          } @else if (viewing(); as v) {
            <p class="muted" i18n="@@models.providers.envInfo">
              This endpoint comes from the server's environment variables and is used when no connection of its type
              exists. Add a connection here to manage it in Back Office instead.
            </p>
            <p class="mono small">{{ typeName(v.provider) }} · {{ v.baseUrl || defaultUrl(v.provider) }}</p>
            @if (canManage()) {
              <button nz-button type="button" [nzLoading]="testing()" (click)="test(v)" i18n="@@models.providers.form.testEnv">Test and list models</button>
            }
          }
          @if (models(); as m) {
            <div class="models">
              @if (m.ok) {
                <div class="ok small">{{ modelsText(m.models.length) }}</div>
                <ul class="model-list mono small">
                  @for (id of m.models; track id) {
                    <li>{{ id }}</li>
                  }
                </ul>
              } @else {
                <div class="problem">{{ m.error }}</div>
              }
            </div>
          }
        }
      </bo-side-panel>
    </div>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .row {
        cursor: pointer;
      }
      .name {
        font-weight: 500;
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .ok {
        color: var(--bo-done-ink, #2f9e76);
      }
      .bad,
      .problem {
        color: var(--bo-blocked-ink);
      }
      .problem {
        font-size: 12px;
        margin: 0 0 8px;
      }
      .grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 12px;
      }
      .presets {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 6px;
        margin-bottom: 12px;
      }
      .switch-label {
        margin-left: 8px;
      }
      .actions {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
      .models {
        margin-top: 16px;
      }
      .model-list {
        max-height: 320px;
        overflow: auto;
        margin: 6px 0 0;
        padding-left: 18px;
      }
    `,
  ],
})
export class LlmProvidersPageComponent {
  private readonly api = inject(LlmProvidersApi);
  private readonly identity = inject(IdentityService);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);

  readonly items = signal<LlmProvider[]>([]);
  readonly loaded = signal(false);
  readonly draft = signal<LlmProviderInput | null>(null);
  readonly editing = signal<LlmProvider | null>(null);
  /** An environment endpoint opened read-only. */
  readonly viewing = signal<LlmProvider | null>(null);
  readonly saving = signal(false);
  readonly testing = signal(false);
  readonly models = signal<ProviderModels | null>(null);
  readonly canManage = computed(() => this.identity.can('admin.settings.manage'));
  readonly panelOpen = computed(() => !!this.draft() || !!this.viewing());
  readonly problems = computed(() => {
    const d = this.draft();
    if (!d) return [];
    return connectionProblems(d, this.items().filter((p) => p.key && p.id !== this.editing()?.id).map((p) => p.key!), !this.editing());
  });
  readonly urlRequired = computed(() => {
    const t = this.draft()?.provider;
    return t === 'OPENAI_COMPATIBLE' || t === 'OLLAMA';
  });

  readonly types = PROVIDER_TYPES;
  readonly presets = PRESETS;
  readonly typeName = providerLabel;
  readonly text = {
    newTitle: $localize`:@@models.providers.form.newTitle:New LLM provider`,
    keepKey: $localize`:@@models.providers.form.keepKey:stored; type to replace`,
  };

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@models.providers.crumb.models:Models`, link: '/models' },
      { label: $localize`:@@models.providers.crumb.providers:LLM providers` },
    ]);
    this.load();
  }

  panelTitle(): string {
    return this.editing()?.name ?? this.viewing()?.name ?? this.text.newTitle;
  }

  defaultUrl(provider: Provider | undefined): string {
    switch (provider) {
      case 'ANTHROPIC':
        return 'https://api.anthropic.com';
      case 'OPENAI':
        return 'https://api.openai.com/v1';
      case 'GOOGLE':
        return 'https://generativelanguage.googleapis.com';
      case 'MISTRAL':
        return 'https://api.mistral.ai/v1';
      case 'OLLAMA':
        return 'http://localhost:11434';
      default:
        return 'https://openrouter.ai/api/v1';
    }
  }

  urlHint(): string {
    return this.urlRequired()
      ? $localize`:@@models.providers.form.urlRequiredHint:Required: the address including the version path, e.g. https://openrouter.ai/api/v1.`
      : $localize`:@@models.providers.form.urlOptionalHint:Optional: empty = the vendor's public API.`;
  }

  keyHint(): string {
    return this.draft()?.provider === 'OLLAMA'
      ? $localize`:@@models.providers.form.keyOllama:Ollama needs no key.`
      : $localize`:@@models.providers.form.keyWriteOnly:Stored encrypted and never shown again.`;
  }

  modelsText(count: number): string {
    return $localize`:@@models.providers.modelsFound:The endpoint answered: ${count}:count: models available.`;
  }

  load(): void {
    this.api.list().subscribe({
      next: (list) => {
        this.items.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  create(): void {
    this.editing.set(null);
    this.viewing.set(null);
    this.models.set(null);
    this.draft.set(emptyConnection());
  }

  edit(p: LlmProvider): void {
    this.models.set(null);
    if (!p.id) {
      this.draft.set(null);
      this.editing.set(null);
      this.viewing.set(p);
      return;
    }
    this.viewing.set(null);
    this.editing.set(p);
    this.draft.set({ key: p.key ?? '', name: p.name, provider: p.provider, baseUrl: p.baseUrl ?? null, apiKey: null, clearApiKey: false, enabled: p.enabled });
  }

  close(): void {
    this.draft.set(null);
    this.editing.set(null);
    this.viewing.set(null);
    this.models.set(null);
  }

  patch(change: Partial<LlmProviderInput>): void {
    const d = this.draft();
    if (d) this.draft.set({ ...d, ...change });
  }

  applyPreset(p: ConnectionPreset): void {
    this.patch({ provider: p.provider, name: p.label, key: p.id, baseUrl: p.baseUrl });
  }

  save(): void {
    const d = this.draft();
    if (!d || this.problems().length) return;
    this.saving.set(true);
    const existing = this.editing();
    const req = existing?.id ? this.api.update(existing.id, d) : this.api.create(d);
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.message.success($localize`:@@models.providers.saved:Provider saved`);
        this.load();
        // stay open on the saved connection so it can be tested at once
        this.edit(saved);
      },
      error: () => this.saving.set(false),
    });
  }

  test(p: LlmProvider): void {
    this.testing.set(true);
    this.models.set(null);
    const req = p.id ? this.api.models(p.id) : this.api.environmentModels(p.provider);
    req.subscribe({
      next: (m) => {
        this.testing.set(false);
        this.models.set(m);
      },
      error: () => this.testing.set(false),
    });
  }

  async remove(p: LlmProvider): Promise<void> {
    if (!p.id) return;
    const ok = await this.confirm.danger(
      $localize`:@@models.providers.deleteTitle:Delete provider "${p.name}:name:"?`,
      $localize`:@@models.providers.deleteContent:Model profiles that use this connection stop working until they use another one.`,
      $localize`:@@models.providers.deleteConfirm:Delete provider`,
    );
    if (!ok) return;
    this.api.delete(p.id).subscribe(() => {
      this.close();
      this.load();
    });
  }
}
