import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type { ParameterSource, TaskOverrides } from '../../../core/api/types';
import {
  type ParameterField,
  isEmptyValue,
  parameterValueText,
} from '../../../core/ui/template-parameters';
import {
  PARAMETER_SOURCES,
  bindingOf,
  bindingSource,
  withBinding,
  withBindingSource,
} from '../data/step-bindings';

/**
 * ADR 0062: where each parameter of a step's template takes its value from. Per parameter a source: "Value" (a typed
 * control; empty = the preset value or the default, shown as placeholder), "Ask at start" (a field of the run start
 * form with the parameter's label and type) or "Result of an earlier step" (the content of an artefact; the names
 * earlier steps produce are offered, any other name may be typed). Emits the step's new overrides.
 */
@Component({
  selector: 'app-step-parameter-bindings',
  imports: [
    FormsModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzRadioModule,
    NzSelectModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (f of fields(); track f.param.name) {
      @let source = sourceOf(f.param.name);
      <nz-form-item class="binding" [attr.data-binding]="f.param.name">
        <nz-form-label
          [nzTooltipTitle]="f.param.help ?? ''"
          [nzRequired]="f.param.required"
          [nzFor]="controlId(f)"
        >
          {{ f.param.label || f.param.name }}
          <span class="mono name">{{ f.param.name }}</span>
        </nz-form-label>
        <nz-form-control
          [nzValidateStatus]="source === 'VALUE' && (f.missing || f.invalid) ? 'error' : ''"
          [nzErrorTip]="errorOf(f)"
        >
          <nz-radio-group
            class="sources"
            nzSize="small"
            [name]="idPrefix() + '-src-' + f.param.name"
            [ngModel]="source"
            (ngModelChange)="setSource(f, $event)"
            [nzDisabled]="readonly()"
            [attr.aria-label]="text.sourceLabel"
          >
            @for (s of sources; track s.value) {
              <label nz-radio-button [nzValue]="s.value" [attr.data-source]="s.value">{{
                s.label
              }}</label>
            }
          </nz-radio-group>
          @switch (source) {
            @case ('START') {
              <p class="muted small" data-binding-start>
                {{ f.param.type === 'ISSUE_KEY' ? text.startIssueKey : text.start }}
              </p>
            }
            @case ('ARTIFACT') {
              <nz-select
                class="artifact"
                nzMode="tags"
                [nzId]="controlId(f)"
                [name]="controlId(f)"
                [nzPlaceHolder]="text.artifactPlaceholder"
                [ngModel]="artifactOf(f.param.name)"
                (ngModelChange)="setArtifact(f, $event)"
                [nzDisabled]="readonly()"
                [nzStatus]="artifactOf(f.param.name).length ? '' : 'error'"
              >
                @for (a of artifacts(); track a) {
                  <nz-option [nzValue]="a" [nzLabel]="a" />
                }
              </nz-select>
              @if (!artifacts().length) {
                <p class="muted small" data-no-artifacts>{{ text.noArtifacts }}</p>
              }
            }
            @default {
              @switch (f.param.type) {
                @case ('MULTILINE') {
                  <textarea
                    nz-input
                    [id]="controlId(f)"
                    [name]="controlId(f)"
                    [ngModel]="f.value ?? ''"
                    (ngModelChange)="setValue(f, $event)"
                    [placeholder]="placeholder(f)"
                    [nzAutosize]="{ minRows: 2, maxRows: 10 }"
                    [readOnly]="readonly()"
                  ></textarea>
                }
                @case ('NUMBER') {
                  <nz-input-number
                    class="number"
                    [nzId]="controlId(f)"
                    [name]="controlId(f)"
                    [ngModel]="f.value"
                    (ngModelChange)="setValue(f, $event)"
                    [nzPlaceHolder]="placeholder(f)"
                    [nzDisabled]="readonly()"
                  />
                }
                @case ('BOOLEAN') {
                  <nz-select
                    [nzId]="controlId(f)"
                    [name]="controlId(f)"
                    nzAllowClear
                    [nzPlaceHolder]="placeholder(f)"
                    [ngModel]="f.value"
                    (ngModelChange)="setValue(f, $event)"
                    [nzDisabled]="readonly()"
                  >
                    <nz-option [nzValue]="true" [nzLabel]="text.yes" />
                    <nz-option [nzValue]="false" [nzLabel]="text.no" />
                  </nz-select>
                }
                @case ('CHOICE') {
                  <nz-select
                    [nzId]="controlId(f)"
                    [name]="controlId(f)"
                    nzAllowClear
                    nzShowSearch
                    [nzPlaceHolder]="placeholder(f)"
                    [ngModel]="f.value"
                    (ngModelChange)="setValue(f, $event)"
                    [nzDisabled]="readonly()"
                  >
                    @for (o of f.param.options ?? []; track o) {
                      <nz-option [nzValue]="o" [nzLabel]="o" />
                    }
                  </nz-select>
                }
                @default {
                  <input
                    nz-input
                    [class.mono]="f.param.type === 'ISSUE_KEY'"
                    [id]="controlId(f)"
                    [name]="controlId(f)"
                    [ngModel]="f.value ?? ''"
                    (ngModelChange)="setValue(f, $event)"
                    [placeholder]="placeholder(f)"
                    [readOnly]="readonly()"
                  />
                }
              }
            }
          }
        </nz-form-control>
      </nz-form-item>
    }
  `,
  styles: [
    `
      .binding {
        margin-bottom: 14px;
      }
      .name {
        margin-left: 6px;
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .sources {
        display: flex;
        flex-wrap: wrap;
        margin-bottom: 6px;
      }
      .number,
      .artifact {
        width: 100%;
      }
      .small {
        font-size: 12px;
        margin: 0;
      }
    `,
  ],
})
export class StepParameterBindingsComponent {
  /** The step's parameter fields (declarations, preset values and defaults, the step's fixed values). */
  readonly fields = input<readonly ParameterField[]>([]);
  /** The step's overrides (bindings, legacy values). */
  readonly overrides = input<TaskOverrides | null | undefined>(null);
  /** The artefact names earlier steps produce. */
  readonly artifacts = input<readonly string[]>([]);
  readonly readonly = input(false);
  /** Prefix of the control ids (unique per form on the page). */
  readonly idPrefix = input('pp-param');

  /** The step's new overrides. */
  readonly overridesChange = output<TaskOverrides>();

  readonly text = {
    sourceLabel: $localize`:@@workflows.binding.sourceLabel:Where the value comes from`,
    start: $localize`:@@workflows.binding.start:The run start form asks for it, with this label and type.`,
    startIssueKey: $localize`:@@workflows.binding.startIssueKey:The run start form asks for it; the value is the run's issue key.`,
    artifactPlaceholder: $localize`:@@workflows.binding.artifactPlaceholder:Artefact name, e.g. spec`,
    noArtifacts: $localize`:@@workflows.binding.noArtifacts:No earlier step declares an artefact; type its name.`,
    yes: $localize`:@@workflows.binding.yes:Yes`,
    no: $localize`:@@workflows.binding.no:No`,
    required: $localize`:@@workflows.binding.required:Required: set a value, ask at start or use a result`,
    invalid: $localize`:@@workflows.binding.invalid:The value does not fit the parameter's type or options`,
  };

  readonly sources = PARAMETER_SOURCES.map((value) => ({ value, label: sourceLabel(value) }));

  sourceOf(name: string): ParameterSource {
    return bindingSource(this.overrides(), name);
  }

  /** The artefact of an ARTIFACT binding as the tags select's model (none or one). */
  artifactOf(name: string): string[] {
    const a = bindingOf(this.overrides(), name)?.artifact;
    return a ? [a] : [];
  }

  controlId(f: ParameterField): string {
    return `${this.idPrefix()}-${f.param.name}`;
  }

  /** The preset value or the default as placeholder; "no value" when there is none. */
  placeholder(f: ParameterField): string {
    if (f.inheritedSource === 'NONE') return $localize`:@@workflows.binding.noValue:no value`;
    const v = parameterValueText(f.inherited);
    return f.inheritedSource === 'DEFAULT'
      ? $localize`:@@workflows.binding.default:Default: ${v}:value:`
      : $localize`:@@workflows.binding.inherited:Inherited: ${v}:value:`;
  }

  errorOf(f: ParameterField): string {
    if (this.sourceOf(f.param.name) !== 'VALUE') return '';
    return f.missing ? this.text.required : f.invalid ? this.text.invalid : '';
  }

  setSource(f: ParameterField, source: ParameterSource): void {
    if (this.readonly()) return;
    this.overridesChange.emit(withBindingSource(this.overrides(), f.param.name, source));
  }

  setValue(f: ParameterField, value: unknown): void {
    if (this.readonly()) return;
    this.overridesChange.emit(
      withBinding(
        this.overrides(),
        f.param.name,
        isEmptyValue(value) ? null : { source: 'VALUE', value },
      ),
    );
  }

  /** The tags select holds the picked name last; one artefact per parameter. */
  setArtifact(f: ParameterField, names: readonly string[] | null): void {
    if (this.readonly()) return;
    const name = (names ?? []).at(-1)?.trim() || null;
    this.overridesChange.emit(
      withBinding(this.overrides(), f.param.name, { source: 'ARTIFACT', artifact: name }),
    );
  }
}

/** Translated name of a parameter source. */
export function sourceLabel(source: ParameterSource): string {
  switch (source) {
    case 'START':
      return $localize`:@@workflows.binding.source.start:Ask at start`;
    case 'ARTIFACT':
      return $localize`:@@workflows.binding.source.artifact:Result of an earlier step`;
    default:
      return $localize`:@@workflows.binding.source.value:Value`;
  }
}
