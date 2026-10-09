import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTagModule } from 'ng-zorro-antd/tag';
import {
  coerceValue,
  extraKeys,
  fieldsFromSchema,
  validateConfig,
  type FieldSpec,
  type JsonSchema,
} from '../data/schema-form';

const EMPTY_LIST: unknown[] = [];

/**
 * Form generated from a JSON schema. Emits a new config object on every change
 * (immutable updates; the parent owns the state).
 */
function invalidJson(err: unknown): string {
  return $localize`:@@workflows.schemaForm.invalidJson:Invalid JSON: ${(err as Error).message}:error:`;
}

@Component({
  selector: 'app-schema-form',
  imports: [
    FormsModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzSelectModule,
    NzSwitchModule,
    NzTagModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!fields().length && !extra().length) {
      <p class="muted" i18n="@@workflows.schemaForm.empty">No configuration keys.</p>
    }
    <form nz-form nzLayout="vertical" (submit)="$event.preventDefault()">
      @for (f of fields(); track f.key) {
        <nz-form-item>
          <nz-form-label [nzRequired]="f.required" [nzFor]="idFor(f)">{{ f.label }}</nz-form-label>
          <nz-form-control
            [nzValidateStatus]="errors()[f.key] ? 'error' : ''"
            [nzErrorTip]="errors()[f.key] || ''"
            [nzExtra]="f.description ?? ''"
          >
            @switch (f.kind) {
              @case ('textarea') {
                <textarea
                  nz-input
                  class="mono-textarea"
                  [id]="idFor(f)"
                  [name]="f.key"
                  [ngModel]="asString(config()[f.key])"
                  (ngModelChange)="set(f, $event)"
                  [nzAutosize]="{ minRows: 3, maxRows: 16 }"
                  [placeholder]="f.default !== undefined ? defaultHint(f.default) : ''"
                ></textarea>
              }
              @case ('select') {
                <nz-select
                  [id]="idFor(f)"
                  [name]="f.key"
                  [ngModel]="config()[f.key] ?? null"
                  (ngModelChange)="set(f, $event)"
                  [nzAllowClear]="!f.required"
                  nzPlaceHolder="Select"
                  i18n-nzPlaceHolder="@@workflows.schemaForm.select"
                >
                  @for (o of f.options ?? []; track o.label) {
                    <nz-option [nzValue]="o.value" [nzLabel]="o.label" />
                  }
                </nz-select>
              }
              @case ('switch') {
                <nz-switch
                  [id]="idFor(f)"
                  [name]="f.key"
                  [ngModel]="!!(config()[f.key] ?? f.default ?? false)"
                  (ngModelChange)="set(f, $event)"
                />
                @if (config()[f.key] === undefined && f.default !== undefined) {
                  <span class="muted" style="margin-left: 8px" i18n="@@workflows.schemaForm.default"
                    >default: {{ f.default }}</span
                  >
                }
              }
              @case ('number') {
                <nz-input-number
                  [id]="idFor(f)"
                  [name]="f.key"
                  [ngModel]="config()[f.key] ?? null"
                  (ngModelChange)="set(f, $event)"
                  [nzMin]="f.min ?? NEG_INF"
                  [nzMax]="f.max ?? POS_INF"
                  [nzStep]="f.integer ? 1 : 0.1"
                  [nzPrecision]="f.integer ? 0 : null"
                  style="width: 200px"
                />
              }
              @case ('tags') {
                <nz-select
                  [id]="idFor(f)"
                  [name]="f.key"
                  [nzMode]="f.options ? 'multiple' : 'tags'"
                  [ngModel]="asArray(config()[f.key])"
                  (ngModelChange)="set(f, $event)"
                  [nzTokenSeparators]="[',']"
                  nzPlaceHolder="Type and press enter"
                  i18n-nzPlaceHolder="@@workflows.schemaForm.typeEnter"
                >
                  @for (o of f.options ?? []; track o.label) {
                    <nz-option [nzValue]="o.value" [nzLabel]="o.label" />
                  }
                </nz-select>
              }
              @case ('json') {
                <textarea
                  nz-input
                  class="mono-textarea"
                  [id]="idFor(f)"
                  [name]="f.key"
                  [ngModel]="jsonDraft()[f.key] || asJson(config()[f.key])"
                  (ngModelChange)="setJson(f, $event)"
                  [nzAutosize]="{ minRows: 3, maxRows: 16 }"
                  placeholder="JSON"
                ></textarea>
                @if (jsonErrors()[f.key]) {
                  <div class="danger">{{ jsonErrors()[f.key] }}</div>
                }
              }
              @default {
                <input
                  nz-input
                  [id]="idFor(f)"
                  [name]="f.key"
                  [ngModel]="asString(config()[f.key])"
                  (ngModelChange)="set(f, $event)"
                  [placeholder]="
                    f.default !== undefined ? defaultHint(f.default) : (f.placeholder ?? '')
                  "
                />
              }
            }
          </nz-form-control>
        </nz-form-item>
      }

      @if (extra().length) {
        <nz-form-item>
          <nz-form-label
            ><span i18n="@@workflows.schemaForm.otherKeys">Other config keys</span>
            <nz-tag style="margin-left: 6px">{{ extra().join(', ') }}</nz-tag></nz-form-label
          >
          <nz-form-control
            nzExtra="Keys not described by the schema. Edited as JSON and merged into the config."
            i18n-nzExtra="@@workflows.schemaForm.otherKeys.extra"
          >
            <textarea
              nz-input
              class="mono-textarea"
              name="__extra"
              [ngModel]="extraDraft() ?? extraJson()"
              (ngModelChange)="setExtra($event)"
              [nzAutosize]="{ minRows: 3, maxRows: 12 }"
            ></textarea>
            @if (extraError()) {
              <div class="danger">{{ extraError() }}</div>
            }
          </nz-form-control>
        </nz-form-item>
      }
    </form>
  `,
})
export class SchemaFormComponent {
  readonly schema = input<JsonSchema | null | undefined>(null);
  readonly config = input<Record<string, unknown>>({});

  defaultHint(value: unknown): string {
    return $localize`:@@workflows.schemaForm.defaultHint:default: ${String(value)}:value:`;
  }
  /** Prefix for element ids so several forms can coexist on a page. */
  readonly idPrefix = input('cfg');
  readonly NEG_INF = -Infinity;
  readonly POS_INF = Infinity;
  readonly configChange = output<Record<string, unknown>>();

  readonly fields = computed<FieldSpec[]>(() => fieldsFromSchema(this.schema()));
  readonly extra = computed(() => extraKeys(this.config(), this.schema()));
  readonly errors = computed(() => validateConfig(this.config(), this.fields()));
  readonly extraJson = computed(() => {
    const out: Record<string, unknown> = {};
    for (const k of this.extra()) out[k] = this.config()[k];
    return JSON.stringify(out, null, 2);
  });

  /** Drafts keep invalid JSON text visible while the user is typing. */
  readonly jsonDraft = signal<Record<string, string>>({});
  readonly jsonErrors = signal<Record<string, string>>({});
  readonly extraDraft = signal<string | null>(null);
  readonly extraError = signal<string | null>(null);

  idFor(f: FieldSpec): string {
    return `${this.idPrefix()}-${f.key}`;
  }

  asString(v: unknown): string {
    return v === undefined || v === null ? '' : typeof v === 'string' ? v : JSON.stringify(v);
  }

  /**
   * The value as a list for multi-selects. Stable identities matter: a fresh `[]` on every change
   * detection makes nz-select write the value again, which schedules another check (NG0103 loop).
   */
  asArray(v: unknown): unknown[] {
    if (Array.isArray(v)) return v;
    if (v === undefined || v === null || v === '') return EMPTY_LIST;
    const cached = this.singletons.get(v);
    if (cached) return cached;
    const list = [v];
    this.singletons.set(v, list);
    return list;
  }
  private readonly singletons = new Map<unknown, unknown[]>();

  asJson(v: unknown): string {
    return v === undefined ? '' : JSON.stringify(v, null, 2);
  }

  set(f: FieldSpec, raw: unknown): void {
    const value = coerceValue(f, raw);
    const next = { ...this.config() };
    if (value === undefined) delete next[f.key];
    else next[f.key] = value;
    this.configChange.emit(next);
  }

  setJson(f: FieldSpec, text: string): void {
    this.jsonDraft.update((d) => ({ ...d, [f.key]: text }));
    if (!text.trim()) {
      this.jsonErrors.update((e) => ({ ...e, [f.key]: '' }));
      this.set(f, undefined);
      return;
    }
    try {
      const parsed = JSON.parse(text) as unknown;
      this.jsonErrors.update((e) => ({ ...e, [f.key]: '' }));
      const next = { ...this.config(), [f.key]: parsed };
      this.configChange.emit(next);
    } catch (err) {
      this.jsonErrors.update((e) => ({ ...e, [f.key]: invalidJson(err) }));
    }
  }

  setExtra(text: string): void {
    this.extraDraft.set(text);
    try {
      const parsed = (text.trim() ? JSON.parse(text) : {}) as Record<string, unknown>;
      if (typeof parsed !== 'object' || Array.isArray(parsed) || parsed === null)
        throw new Error($localize`:@@workflows.schemaForm.notObject:must be an object`);
      this.extraError.set(null);
      const next = { ...this.config() };
      for (const k of this.extra()) delete next[k];
      this.configChange.emit({ ...next, ...parsed });
    } catch (err) {
      this.extraError.set(invalidJson(err));
    }
  }
}
