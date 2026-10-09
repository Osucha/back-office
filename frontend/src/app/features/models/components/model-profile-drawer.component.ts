import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import {
  CAPABILITIES,
  REASONING_LEVELS,
  type Capability,
  type ModelProfile,
  type ModelProfileInput,
  type ModelTestResult,
  type Provider,
  type ProviderStatus,
  type Reasoning,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { formatDuration } from '../../../core/ui/pipes/duration.pipe';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { formatUsage } from '../../../core/ui/relay/format';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { humanize } from '../../../core/ui/status-tag.component';
import { capabilityLabel, providerLabel, reasoningLabel } from '../data/model-format';

/**
 * Model profile side panel: provenance, a "Test profile" check with its result inline, and the
 * create / edit form with a sticky action bar (`Ctrl/⌘ S`). The scrolling body is a focusable
 * region, so it can be scrolled with the keyboard also when the form is read-only.
 */
@Component({
  selector: 'app-model-profile-drawer',
  imports: [
    ReadonlyFormDirective,
    ReactiveFormsModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzSelectModule,
    NzSwitchModule,
    NzButtonModule,
    NzIconModule,
    ActionBarComponent,
    BatonComponent,
    CostComponent,
    ProvenanceComponent,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      [open]="open()"
      [title]="profile()?.id ? profile()!.displayName : text.newTitle"
      [subtitle]="
        profile()?.id
          ? providerName(profile()!.ref.provider) + ' · ' + profile()!.ref.modelId
          : text.newSubtitle
      "
      [defaultWidth]="640"
      storageKey="backoffice.panelWidth.model"
      bodyLabel="Model profile"
      i18n-bodyLabel="@@models.drawer.bodyLabel"
      initialFocus="#mp-name"
      (closed)="closed.emit()"
    >
      @if (profile()?.id) {
        <bo-provenance
          boPanelHeader
          verb="updated by"
          i18n-verb="@@models.drawer.updatedBy"
          [actor]="profile()!.updatedBy"
          [at]="profile()!.updatedAt"
        />
      }

      @if (profile()?.id && canManage()) {
        <section class="test" aria-label="Test profile" i18n-aria-label="@@models.drawer.test">
          <div class="test-head">
            <div>
              <div class="test-title" i18n="@@models.drawer.test">Test profile</div>
              <div class="test-sub" i18n="@@models.drawer.testSub">
                Sends a tiny prompt and measures latency and cost.
              </div>
            </div>
            <button
              nz-button
              type="button"
              (click)="test.emit()"
              [nzLoading]="testing()"
              [disabled]="profile()!.providerConfigured === false"
            >
              <nz-icon nzType="thunderbolt" />
              <ng-container i18n="@@models.drawer.test">Test profile</ng-container>
            </button>
          </div>
          @if (profile()!.providerConfigured === false) {
            <p class="test-note" i18n="@@models.drawer.testNoKey">
              The provider has no API key on the server, so the test would fail. Set the key first
              (see Settings).
            </p>
          }
          @if (testResult(); as r) {
            <div class="result" role="status">
              <bo-baton
                [holder]="r.ok ? 'done' : 'blocked'"
                [label]="r.ok ? text.answered : text.callFailed"
                size="sm"
              />
              @if (r.latencyMs !== null && r.latencyMs !== undefined) {
                <span class="mono-num">{{ duration(r.latencyMs) }}</span>
              }
              @if (r.cost) {
                <bo-cost [value]="r.cost" [usage]="r.usage" />
              }
              @if (usage(r)) {
                <span class="quiet">{{ usage(r) }}</span>
              }
            </div>
            @if (r.error) {
              <p class="error">{{ r.error }}</p>
            }
            @if (r.sample) {
              <pre class="code-block sample">{{ r.sample }}</pre>
            }
          }
        </section>
      }

      <form
        nz-form
        nzLayout="vertical"
        [formGroup]="form"
        [boReadonly]="!canManage()"
        id="model-profile-form"
        (ngSubmit)="submit()"
      >
        <nz-form-item>
          <nz-form-label nzRequired nzFor="mp-name" i18n="@@models.drawer.displayName"
            >Display name</nz-form-label
          >
          <nz-form-control nzErrorTip="Required" i18n-nzErrorTip="@@models.drawer.required">
            <input
              nz-input
              id="mp-name"
              formControlName="displayName"
              placeholder="Claude Opus (high reasoning)"
              i18n-placeholder="@@models.drawer.displayNamePlaceholder"
            />
          </nz-form-control>
        </nz-form-item>
        <div class="grid">
          <nz-form-item>
            <nz-form-label nzRequired nzFor="mp-provider" i18n="@@models.drawer.provider"
              >Provider</nz-form-label
            >
            <nz-form-control [nzExtra]="providerHint()">
              <nz-select id="mp-provider" formControlName="provider">
                @for (p of providers(); track p.provider) {
                  <nz-option [nzValue]="p.provider" [nzLabel]="providerOption(p)" />
                }
              </nz-select>
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzRequired nzFor="mp-model" i18n="@@models.drawer.modelId"
              >Model id</nz-form-label
            >
            <nz-form-control
              nzErrorTip="Required"
              i18n-nzErrorTip="@@models.drawer.required"
              nzExtra="Exactly as the provider API expects it."
              i18n-nzExtra="@@models.drawer.modelIdHint"
            >
              <input
                nz-input
                id="mp-model"
                class="mono"
                formControlName="modelId"
                [placeholder]="modelIdExample"
              />
            </nz-form-control>
          </nz-form-item>
        </div>

        <h3 class="sec" i18n="@@models.drawer.defaults">Default options</h3>
        <div class="grid three" formGroupName="options">
          <nz-form-item>
            <nz-form-label nzFor="mp-temp" i18n="@@models.drawer.temperature"
              >Temperature</nz-form-label
            >
            <nz-form-control>
              <nz-input-number
                id="mp-temp"
                formControlName="temperature"
                [nzMin]="0"
                [nzMax]="2"
                [nzStep]="0.1"
                nzPlaceHolder="provider default"
                i18n-nzPlaceHolder="@@models.drawer.providerDefault"
                style="width: 100%"
              />
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="mp-max" i18n="@@models.drawer.maxOutput"
              >Max output tokens</nz-form-label
            >
            <nz-form-control>
              <nz-input-number
                id="mp-max"
                formControlName="maxOutputTokens"
                [nzMin]="1"
                [nzStep]="1024"
                [nzPrecision]="0"
                nzPlaceHolder="provider default"
                i18n-nzPlaceHolder="@@models.drawer.providerDefault"
                style="width: 100%"
              />
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="mp-reasoning" i18n="@@models.drawer.reasoning"
              >Reasoning effort</nz-form-label
            >
            <nz-form-control>
              <nz-select id="mp-reasoning" formControlName="reasoning">
                @for (r of reasoningLevels; track r) {
                  <nz-option [nzValue]="r" [nzLabel]="reasoningName(r)" />
                }
              </nz-select>
            </nz-form-control>
          </nz-form-item>
        </div>
        <nz-form-item formGroupName="options">
          <nz-form-label nzFor="mp-extra" i18n="@@models.drawer.extra"
            >Extra options (JSON)</nz-form-label
          >
          <nz-form-control
            nzExtra="Provider-specific parameters passed through as-is (e.g. top_p, thinking budgets)."
            i18n-nzExtra="@@models.drawer.extraHint"
            [nzValidateStatus]="extraError() ? 'error' : ''"
            [nzErrorTip]="extraError() ?? ''"
          >
            <textarea
              nz-input
              id="mp-extra"
              class="mono-textarea"
              formControlName="extra"
              [nzAutosize]="{ minRows: 2, maxRows: 8 }"
              placeholder="{ }"
            ></textarea>
          </nz-form-control>
        </nz-form-item>

        <h3 class="sec">
          <ng-container i18n="@@models.drawer.pricing">Pricing</ng-container>
          <span class="sec-note" i18n="@@models.drawer.pricingNote">USD per million tokens</span>
        </h3>
        <div class="grid four" formGroupName="pricing">
          <nz-form-item>
            <nz-form-label nzRequired nzFor="mp-in" i18n="@@models.drawer.input"
              >Input</nz-form-label
            >
            <nz-form-control nzErrorTip="Required" i18n-nzErrorTip="@@models.drawer.required">
              <nz-input-number
                id="mp-in"
                formControlName="inputPerMTok"
                [nzMin]="0"
                [nzStep]="0.25"
                style="width: 100%"
              />
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzRequired nzFor="mp-out" i18n="@@models.drawer.output"
              >Output</nz-form-label
            >
            <nz-form-control nzErrorTip="Required" i18n-nzErrorTip="@@models.drawer.required">
              <nz-input-number
                id="mp-out"
                formControlName="outputPerMTok"
                [nzMin]="0"
                [nzStep]="0.25"
                style="width: 100%"
              />
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="mp-cr" i18n="@@models.drawer.cacheRead">Cache read</nz-form-label>
            <nz-form-control>
              <nz-input-number
                id="mp-cr"
                formControlName="cacheReadPerMTok"
                [nzMin]="0"
                [nzStep]="0.05"
                style="width: 100%"
              />
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="mp-cw" i18n="@@models.drawer.cacheWrite"
              >Cache write</nz-form-label
            >
            <nz-form-control>
              <nz-input-number
                id="mp-cw"
                formControlName="cacheWritePerMTok"
                [nzMin]="0"
                [nzStep]="0.05"
                style="width: 100%"
              />
            </nz-form-control>
          </nz-form-item>
        </div>

        <h3 class="sec" i18n="@@models.drawer.catalogue">Catalogue</h3>
        <div class="grid">
          <nz-form-item>
            <nz-form-label nzFor="mp-caps" i18n="@@models.drawer.capabilities"
              >Capabilities</nz-form-label
            >
            <nz-form-control>
              <nz-select
                id="mp-caps"
                formControlName="capabilities"
                nzMode="multiple"
                nzPlaceHolder="Informational tags"
                i18n-nzPlaceHolder="@@models.drawer.capabilitiesPlaceholder"
              >
                @for (c of capabilities; track c) {
                  <nz-option [nzValue]="c" [nzLabel]="capabilityName(c)" />
                }
              </nz-select>
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="mp-ctx" i18n="@@models.drawer.contextWindow"
              >Context window (tokens)</nz-form-label
            >
            <nz-form-control>
              <nz-input-number
                id="mp-ctx"
                formControlName="contextWindow"
                [nzMin]="1000"
                [nzStep]="1000"
                [nzPrecision]="0"
                style="width: 100%"
              />
            </nz-form-control>
          </nz-form-item>
        </div>
        <nz-form-item>
          <nz-form-label nzFor="mp-notes" i18n="@@models.drawer.notes">Notes</nz-form-label>
          <nz-form-control>
            <textarea
              nz-input
              id="mp-notes"
              formControlName="notes"
              [nzAutosize]="{ minRows: 2, maxRows: 6 }"
            ></textarea>
          </nz-form-control>
        </nz-form-item>
        <label class="enabled">
          <nz-switch
            formControlName="enabled"
            aria-label="Enabled"
            i18n-aria-label="@@models.drawer.enabled"
          />
          <span>
            <strong i18n="@@models.drawer.enabled">Enabled</strong>
            <span class="quiet" i18n="@@models.drawer.enabledHint"
              >Disabled profiles are hidden from step and benchmark pickers.</span
            >
          </span>
        </label>
      </form>

      <div boPanelFooter>
        <bo-action-bar
          [sticky]="false"
          [dirty]="dirty()"
          [saving]="saving()"
          [canSave]="valid()"
          [requireDirty]="!!profile()?.id"
          [showSave]="open() && canManage()"
          [saveLabel]="profile()?.id ? text.save : text.create"
          [cleanLabel]="
            canManage() ? (profile()?.id ? text.noChanges : text.notSaved) : text.readOnly
          "
          (save)="submit()"
        >
          <button nz-button type="button" (click)="closed.emit()">
            {{ dirty() ? text.discard : text.close }}
          </button>
        </bo-action-bar>
      </div>
    </bo-side-panel>
  `,
  styles: [
    `
      .grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0 16px;
      }
      .grid.three {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
      .grid.four {
        grid-template-columns: repeat(4, minmax(0, 1fr));
      }
      .sec {
        margin: 8px 0 10px;
        font-size: 12px;
        font-weight: 600;
        color: var(--bo-ink-2);
        display: flex;
        align-items: baseline;
        gap: 8px;
      }
      .sec::after {
        content: '';
        flex: 1;
        height: 1px;
        background: var(--bo-line);
        align-self: center;
      }
      .sec-note {
        font-weight: 400;
        color: var(--bo-ink-3);
      }
      .test {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface-2);
        padding: 12px 14px;
        margin-bottom: 20px;
      }
      .test-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
      }
      .test-title {
        font-weight: 600;
        font-size: 13px;
      }
      .test-sub,
      .test-note,
      .quiet {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .test-note {
        margin: 8px 0 0;
      }
      .result {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 10px;
        margin-top: 10px;
        padding-top: 10px;
        border-top: 1px solid var(--bo-line);
      }
      .mono-num {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .error {
        margin: 8px 0 0;
        font-size: 12px;
        color: var(--bo-blocked-ink);
      }
      .sample {
        margin-top: 8px;
        max-height: 180px;
      }
      .enabled {
        display: flex;
        gap: 10px;
        align-items: flex-start;
        font-size: 13px;
      }
      .enabled span {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      [boPanelFooter] {
        display: block;
        margin: -13px -20px -12px;
      }
    `,
  ],
})
export class ModelProfileDrawerComponent {
  private readonly fb = inject(FormBuilder);

  readonly identity = inject(IdentityService);
  readonly open = input(false);
  /** Existing profile (edit), a copy without id (duplicate) or null (create). */
  readonly profile = input<ModelProfile | null>(null);
  readonly providers = input<ProviderStatus[]>([]);
  readonly saving = input(false);
  readonly testing = input(false);
  readonly testResult = input<ModelTestResult | null>(null);
  readonly saved = output<ModelProfileInput>();
  readonly closed = output<void>();
  readonly test = output<void>();

  readonly reasoningLevels = REASONING_LEVELS;
  readonly capabilities = CAPABILITIES;
  readonly humanize = humanize;
  readonly reasoningName = reasoningLabel;
  readonly capabilityName = capabilityLabel;
  readonly modelIdExample = 'claude-opus-4-1'; // i18n-ignore: model id
  readonly text = {
    newTitle: $localize`:@@models.drawer.newTitle:New model profile`,
    newSubtitle: $localize`:@@models.drawer.newSubtitle:A provider, a model id, default options and pricing`,
    answered: $localize`:@@models.drawer.answered:the model answered`,
    callFailed: $localize`:@@models.drawer.callFailed:the call failed`,
    save: $localize`:@@models.drawer.save:Save profile`,
    create: $localize`:@@models.createAction:Create profile`,
    noChanges: $localize`:@@models.drawer.noChanges:No changes`,
    notSaved: $localize`:@@models.drawer.notSaved:Not saved yet`,
    readOnly: $localize`:@@models.drawer.readOnly:Read-only: needs models.manage`,
    discard: $localize`:@@models.drawer.discard:Discard changes`,
    close: $localize`:@@models.drawer.close:Close`,
  };

  providerOption(p: ProviderStatus): string {
    const name = providerLabel(p.provider);
    return p.configured
      ? name
      : $localize`:@@models.drawer.providerNoKey:${name}:provider: (no API key)`;
  }
  readonly providerName = providerLabel;
  readonly duration = (ms: number) => formatDuration(ms);
  readonly usage = (r: ModelTestResult) => formatUsage(r.usage);
  readonly extraError = signal<string | null>(null);
  readonly canManage = computed(() => this.identity.can('models.manage'));
  readonly dirty = signal(false);
  private readonly status = signal(0);

  readonly form = this.fb.group({
    displayName: this.fb.nonNullable.control('', Validators.required),
    provider: this.fb.nonNullable.control<Provider>('ANTHROPIC', Validators.required),
    modelId: this.fb.nonNullable.control('', Validators.required),
    options: this.fb.group({
      temperature: this.fb.control<number | null>(null),
      maxOutputTokens: this.fb.control<number | null>(null),
      reasoning: this.fb.nonNullable.control<Reasoning>('MEDIUM'),
      extra: this.fb.nonNullable.control(''),
    }),
    pricing: this.fb.group({
      inputPerMTok: this.fb.control<number | null>(null, Validators.required),
      outputPerMTok: this.fb.control<number | null>(null, Validators.required),
      cacheReadPerMTok: this.fb.control<number | null>(null),
      cacheWritePerMTok: this.fb.control<number | null>(null),
    }),
    capabilities: this.fb.nonNullable.control<Capability[]>([]),
    contextWindow: this.fb.control<number | null>(null),
    notes: this.fb.nonNullable.control(''),
    enabled: this.fb.nonNullable.control(true),
  });

  readonly valid = computed(() => {
    this.status();
    return this.form.valid && !this.extraError();
  });

  readonly providerHint = computed(() => {
    this.status();
    const p = this.providers().find((x) => x.provider === this.form.controls.provider.value);
    if (!p) return '';
    if (!p.configured)
      return $localize`:@@models.drawer.noKeyHint:No API key on the server: tests and runs fail until it is set.`;
    if (!p.baseUrl) return $localize`:@@models.drawer.keyConfigured:API key configured.`;
    const url = p.baseUrl;
    return $localize`:@@models.drawer.keyConfiguredUrl:API key configured (${url}:url:).`;
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.form.valueChanges.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => {
      this.dirty.set(this.form.dirty);
      this.status.update((v) => v + 1);
    });
    effect(() => {
      if (!this.open()) return;
      const p = this.profile();
      const providers = this.providers();
      untracked(() => this.reset(p, providers));
    });
    this.form.controls.options.controls.extra.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((v) => {
        if (!v.trim()) {
          this.extraError.set(null);
          return;
        }
        try {
          const parsed = JSON.parse(v) as unknown;
          this.extraError.set(
            typeof parsed === 'object' && parsed && !Array.isArray(parsed)
              ? null
              : $localize`:@@models.drawer.jsonObject:Must be a JSON object`,
          );
        } catch (e) {
          const detail = (e as Error).message;
          this.extraError.set(
            $localize`:@@models.drawer.invalidJson:Invalid JSON: ${detail}:detail:`,
          );
        }
      });
  }

  private reset(p: ModelProfile | null, providers: ProviderStatus[]): void {
    this.extraError.set(null);
    if (!p) {
      this.form.reset({
        displayName: '',
        provider: providers.find((x) => x.configured)?.provider ?? 'ANTHROPIC',
        modelId: '',
        options: { temperature: null, maxOutputTokens: null, reasoning: 'MEDIUM', extra: '' },
        pricing: {
          inputPerMTok: null,
          outputPerMTok: null,
          cacheReadPerMTok: null,
          cacheWritePerMTok: null,
        },
        capabilities: [],
        contextWindow: null,
        notes: '',
        enabled: true,
      });
    } else {
      this.form.reset({
        displayName: p.displayName,
        provider: p.ref.provider,
        modelId: p.ref.modelId,
        options: {
          temperature: p.ref.options?.temperature ?? null,
          maxOutputTokens: p.ref.options?.maxOutputTokens ?? null,
          reasoning: p.ref.options?.reasoning ?? 'MEDIUM',
          extra:
            p.ref.options?.extra && Object.keys(p.ref.options.extra).length
              ? JSON.stringify(p.ref.options.extra, null, 2)
              : '',
        },
        pricing: {
          inputPerMTok: p.pricing.inputPerMTok,
          outputPerMTok: p.pricing.outputPerMTok,
          cacheReadPerMTok: p.pricing.cacheReadPerMTok ?? null,
          cacheWritePerMTok: p.pricing.cacheWritePerMTok ?? null,
        },
        capabilities: p.capabilities ?? [],
        contextWindow: p.contextWindow ?? null,
        notes: p.notes ?? '',
        enabled: p.enabled ?? true,
      });
    }
    this.form.markAsPristine();
    this.dirty.set(false);
    this.status.update((v) => v + 1);
  }

  submit(): void {
    if (this.form.invalid || this.extraError()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const extra = v.options.extra.trim()
      ? (JSON.parse(v.options.extra) as Record<string, unknown>)
      : undefined;
    const input: ModelProfileInput = {
      displayName: v.displayName.trim(),
      ref: {
        provider: v.provider,
        modelId: v.modelId.trim(),
        options: {
          temperature: v.options.temperature,
          maxOutputTokens: v.options.maxOutputTokens,
          reasoning: v.options.reasoning,
          ...(extra ? { extra } : {}),
        },
      },
      pricing: {
        inputPerMTok: v.pricing.inputPerMTok ?? 0,
        outputPerMTok: v.pricing.outputPerMTok ?? 0,
        cacheReadPerMTok: v.pricing.cacheReadPerMTok,
        cacheWritePerMTok: v.pricing.cacheWritePerMTok,
        currency: 'USD',
      },
      capabilities: v.capabilities,
      contextWindow: v.contextWindow,
      notes: v.notes.trim() || null,
      enabled: v.enabled,
    };
    this.saved.emit(input);
  }
}
