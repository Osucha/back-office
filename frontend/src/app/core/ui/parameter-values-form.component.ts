import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { type ParameterField, isEmptyValue, parameterValueText } from './template-parameters';

/**
 * ADR 0058: a form of parameter values (a preset's or a step's). One control per parameter by its
 * type; an empty control means "no value at this level" and shows the inherited value (the nearer
 * templates' or the default) as placeholder. A required parameter without any value shows an
 * error. Emits every change as `{ name, value }` (value null = cleared).
 */
@Component({
  selector: 'app-parameter-values-form',
  imports: [FormsModule, NzFormModule, NzInputModule, NzInputNumberModule, NzSelectModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (f of fields(); track f.param.name) {
      <nz-form-item class="param" [attr.data-param]="f.param.name">
        <nz-form-label [nzRequired]="f.param.required" [nzFor]="idPrefix() + '-' + f.param.name">
          {{ f.param.label || f.param.name }}
          <span class="mono name">{{ f.param.name }}</span>
        </nz-form-label>
        <nz-form-control
          [nzValidateStatus]="f.missing || f.invalid ? 'error' : ''"
          [nzErrorTip]="f.missing ? text.required : f.invalid ? text.invalid : ''"
          [nzExtra]="extra(f)"
        >
          @switch (f.param.type) {
            @case ('MULTILINE') {
              <textarea
                nz-input
                [id]="idPrefix() + '-' + f.param.name"
                [name]="idPrefix() + '-' + f.param.name"
                [ngModel]="f.value ?? ''"
                (ngModelChange)="set(f, $event)"
                [placeholder]="placeholder(f)"
                [nzAutosize]="{ minRows: 2, maxRows: 10 }"
                [readOnly]="readonly()"
              ></textarea>
            }
            @case ('NUMBER') {
              <nz-input-number
                class="number"
                [nzId]="idPrefix() + '-' + f.param.name"
                [name]="idPrefix() + '-' + f.param.name"
                [ngModel]="f.value"
                (ngModelChange)="set(f, $event)"
                [nzPlaceHolder]="placeholder(f)"
                [nzDisabled]="readonly()"
              />
            }
            @case ('BOOLEAN') {
              <nz-select
                [nzId]="idPrefix() + '-' + f.param.name"
                [name]="idPrefix() + '-' + f.param.name"
                nzAllowClear
                [nzPlaceHolder]="placeholder(f)"
                [ngModel]="f.value"
                (ngModelChange)="set(f, $event)"
                [nzDisabled]="readonly()"
              >
                <nz-option [nzValue]="true" [nzLabel]="text.yes" />
                <nz-option [nzValue]="false" [nzLabel]="text.no" />
              </nz-select>
            }
            @case ('CHOICE') {
              <nz-select
                [nzId]="idPrefix() + '-' + f.param.name"
                [name]="idPrefix() + '-' + f.param.name"
                nzAllowClear
                nzShowSearch
                [nzPlaceHolder]="placeholder(f)"
                [ngModel]="f.value"
                (ngModelChange)="set(f, $event)"
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
                [id]="idPrefix() + '-' + f.param.name"
                [name]="idPrefix() + '-' + f.param.name"
                [ngModel]="f.value ?? ''"
                (ngModelChange)="set(f, $event)"
                [placeholder]="placeholder(f)"
                [readOnly]="readonly()"
              />
            }
          }
        </nz-form-control>
      </nz-form-item>
    }
  `,
  styles: [
    `
      .param {
        margin-bottom: 12px;
      }
      .name {
        margin-left: 6px;
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .number {
        width: 100%;
      }
    `,
  ],
})
export class ParameterValuesFormComponent {
  readonly fields = input<readonly ParameterField[]>([]);
  readonly readonly = input(false);
  /** Prefix of the control ids (unique per form on the page). */
  readonly idPrefix = input('param');

  readonly valueChange = output<{ name: string; value: unknown }>();

  readonly text = {
    yes: $localize`:@@core.parameter.value.yes:Yes`,
    no: $localize`:@@core.parameter.value.no:No`,
    required: $localize`:@@core.parameter.required:Required: no value here, in the templates or as default`,
    invalid: $localize`:@@core.parameter.invalid:The value does not fit the parameter's type or options`,
  };

  /** The inherited value as placeholder; "no value" when there is none. */
  placeholder(f: ParameterField): string {
    if (f.inheritedSource === 'NONE') return $localize`:@@core.parameter.noValue:no value`;
    const v = parameterValueText(f.inherited);
    return f.inheritedSource === 'DEFAULT'
      ? $localize`:@@core.parameter.default:Default: ${v}:value:`
      : $localize`:@@core.parameter.inherited:Inherited: ${v}:value:`;
  }

  extra(f: ParameterField): string {
    return f.param.help ?? '';
  }

  set(f: ParameterField, value: unknown): void {
    if (this.readonly()) return;
    this.valueChange.emit({ name: f.param.name, value: isEmptyValue(value) ? null : value });
  }
}
