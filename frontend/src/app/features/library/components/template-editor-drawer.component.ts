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
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCollapseModule } from 'ng-zorro-antd/collapse';
import { NzDrawerModule } from 'ng-zorro-antd/drawer';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type {
  Project,
  ResolvedParameter,
  ResolvedTask,
  Skill,
  TaskConfigSchemas,
  TaskTemplate,
  TaskTemplateInput,
  TemplateParameter,
} from '../../../core/api/types';
import { ParameterValuesFormComponent } from '../../../core/ui/parameter-values-form.component';
import { HelpTipComponent } from '../../../core/ui/help-tip.component';
import {
  PARAMETER_TYPES,
  addParameter,
  cleanParameters,
  fromResolved,
  moveParameter,
  parameterFields,
  parameterProblem,
  parameterTypeLabel,
  parameterValueText,
  patchParameter,
  removeParameter,
  withParameterValue,
} from '../../../core/ui/template-parameters';
import { TEMPLATE_CATEGORIES, categoryLabel } from '../../../core/ui/template-categories';
import { effectiveCategory, nameOfKey } from '../data/library-filters';
import { JsonViewComponent } from '../../../core/ui/json-view.component';
import { neededAgentLabel } from '../../../core/ui/agents';
import { deprecatedHint, deprecatedLabel, isDeprecated } from '../../../core/ui/template-tags';
import { SchemaFormComponent } from '../../workflows/public-api';
import {
  cleanGlobs,
  templateConfigSchema,
  templateNeedsCodingAgent,
} from '../data/template-config';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import {
  emptyTemplate,
  parentCandidates,
  presetOf,
  toPreview,
  toSavePayload,
  toTemplateInput,
  type ResolvedPreview,
} from '../data/resolved-preview';

function invalidJson(e: unknown): string {
  return $localize`:@@library.templateEditor.invalidJson:Invalid JSON: ${(e as Error).message}:error:`;
}

/** Create / edit drawer for a task template with a live resolved preview on the right. */
@Component({
  selector: 'app-template-editor-drawer',
  imports: [
    FormsModule,
    NzDrawerModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzSwitchModule,
    NzRadioModule,
    ReadonlyFormDirective,
    NzButtonModule,
    NzIconModule,
    ActionBarComponent,
    NzTagModule,
    NzAlertModule,
    NzTabsModule,
    NzTooltipModule,
    NzCollapseModule,
    JsonViewComponent,
    NzInputNumberModule,
    ParameterValuesFormComponent,
    SchemaFormComponent,
    HelpTipComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-drawer
      [nzVisible]="open()"
      [nzTitle]="titleTpl"
      [nzWidth]="drawerWidth"
      (nzOnClose)="requestClose()"
      [nzFooter]="footer"
      [nzBodyStyle]="{ padding: '0', overflow: 'hidden', display: 'flex' }"
      nzWrapClassName="bo-editor-drawer"
    >
      <ng-template #titleTpl>
        <span class="drawer-title">
          @if (readonly()) {
            <span i18n="@@library.templateEditor.titleView">View template</span>
          } @else if (template()?.id) {
            <span i18n="@@library.templateEditor.titleEdit">Edit template</span>
          } @else {
            <span i18n="@@library.templateEditor.titleNew">New template</span>
          }
          @if (template()?.version) {
            <span class="title-key">v{{ template()?.version }}</span>
          }
          @if (template()?.builtIn) {
            <span class="title-chip"
              ><nz-icon nzType="lock" />
              <span i18n="@@library.templateEditor.builtIn">built-in</span></span
            >
          }
          @if (draft().abstract) {
            <span class="title-chip" i18n="@@library.templateEditor.abstractChip">abstract</span>
          }
          @if (needsCodingAgent()) {
            <span
              class="title-chip coding"
              data-needs-coding-agent
              nz-tooltip
              [nzTooltipTitle]="text.needsCodingHint"
              >{{ text.needsCoding }}</span
            >
          }
          @if (deprecated()) {
            <span class="title-chip deprecated" [attr.title]="text.deprecatedHint">{{
              text.deprecated
            }}</span>
          }
          @if (readonly()) {
            <span class="title-chip" i18n="@@library.templateEditor.readOnlyChip">read-only</span>
          }
        </span>
      </ng-template>
      <ng-container *nzDrawerContent>
        <div class="split">
          <form
            nz-form
            nzLayout="vertical"
            class="editor"
            tabindex="0"
            role="region"
            aria-label="Template fields"
            i18n-aria-label="@@library.templateEditor.fields"
            [boReadonly]="readonly()"
            (submit)="$event.preventDefault()"
          >
            @if (customizing()) {
              <nz-alert
                nzType="info"
                nzShowIcon
                nzMessage="This project gets its own version of the global template. Change only what differs; everything left empty is inherited from the global one."
                i18n-nzMessage="@@library.templateEditor.customizing"
                style="margin-bottom: 12px"
              />
            }
            <div class="grid">
              <nz-form-item>
                <nz-form-label nzRequired nzFor="tt-name" i18n="@@library.templateEditor.name"
                  >Name</nz-form-label
                >
                <nz-form-control>
                  <input
                    nz-input
                    id="tt-name"
                    name="name"
                    [ngModel]="draft().name"
                    (ngModelChange)="patch({ name: $event })"
                    placeholder="Strict code review"
                    i18n-placeholder="@@library.templateEditor.name.placeholder"
                  />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="Groups the template in the library and in the workflow designer. Empty: inherited."
                  i18n-nzTooltipTitle="@@library.templateEditor.category.extra"
                  nzFor="tt-category"
                  i18n="@@library.templateEditor.category"
                  >Category</nz-form-label
                >
                <nz-form-control>
                  <nz-select
                    id="tt-category"
                    name="category"
                    nzAllowClear
                    [nzPlaceHolder]="categoryPlaceholder()"
                    [ngModel]="draft().category ?? null"
                    (ngModelChange)="patch({ category: $event ?? null })"
                  >
                    @for (c of categories; track c.value) {
                      <nz-option [nzValue]="c.value" [nzLabel]="c.label" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
            </div>
            <nz-form-item>
              <nz-form-label nzFor="tt-desc" i18n="@@library.templateEditor.description"
                >Description</nz-form-label
              >
              <nz-form-control>
                <textarea
                  nz-input
                  id="tt-desc"
                  name="description"
                  [ngModel]="draft().description ?? ''"
                  (ngModelChange)="patch({ description: $event })"
                  [nzAutosize]="{ minRows: 2, maxRows: 4 }"
                ></textarea>
              </nz-form-control>
            </nz-form-item>

            <h3 class="sec" i18n="@@library.templateEditor.type">Type</h3>
            <div class="grid">
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="The template this one starts from: it inherits its instructions, parameters and configuration and changes only what you set here."
                  i18n-nzTooltipTitle="@@library.templateEditor.extends.extra"
                  nzFor="tt-extends"
                  i18n="@@library.templateEditor.extends"
                  >Based on</nz-form-label
                >
                <nz-form-control>
                  <nz-select
                    id="tt-extends"
                    name="extendsKey"
                    nzAllowClear
                    nzShowSearch
                    nzPlaceHolder="no parent"
                    i18n-nzPlaceHolder="@@library.templateEditor.noParent"
                    [ngModel]="draft().extendsKey ?? null"
                    (ngModelChange)="patch({ extendsKey: $event ?? null })"
                  >
                    @for (t of parents(); track t.key) {
                      <nz-option [nzValue]="t.key" [nzLabel]="parentLabel(t)" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label i18n="@@library.templateEditor.scope">Scope</nz-form-label>
                <nz-form-control>
                  <nz-radio-group
                    name="scopeType"
                    [ngModel]="draft().scope.type"
                    (ngModelChange)="setScope($event)"
                    [disabled]="!!template()?.builtIn"
                  >
                    <label
                      nz-radio-button
                      nzValue="GLOBAL"
                      [nzDisabled]="!canGlobal()"
                      i18n="@@library.templateEditor.scope.global"
                      >Global</label
                    >
                    <label
                      nz-radio-button
                      nzValue="PROJECT"
                      i18n="@@library.templateEditor.scope.project"
                      >Project</label
                    >
                  </nz-radio-group>
                </nz-form-control>
              </nz-form-item>
              @if (draft().scope.type === 'PROJECT') {
                <nz-form-item>
                  <nz-form-label
                    nzRequired
                    nzFor="tt-project"
                    i18n="@@library.templateEditor.project"
                    >Project</nz-form-label
                  >
                  <nz-form-control>
                    <nz-select
                      id="tt-project"
                      name="scopeProject"
                      nzShowSearch
                      nzPlaceHolder="Project"
                      i18n-nzPlaceHolder="@@library.templateEditor.project"
                      [ngModel]="draft().scope.projectId ?? null"
                      (ngModelChange)="patch({ scope: { type: 'PROJECT', projectId: $event } })"
                    >
                      @for (p of scopeProjects() ?? projects(); track p.id) {
                        <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' - ' + p.name" />
                      }
                    </nz-select>
                  </nz-form-control>
                </nz-form-item>
              }
            </div>

            <nz-form-item data-requires-skills>
              <nz-form-label
                nzFor="tt-requires-skills"
                nzTooltipTitle="Skills the work needs. A step whose agent lacks one gets a warning (it still runs)."
                i18n-nzTooltipTitle="@@library.templateEditor.requiresSkills.help"
                i18n="@@library.templateEditor.requiresSkills"
                >Required skills</nz-form-label
              >
              <nz-form-control>
                <nz-select
                  id="tt-requires-skills"
                  name="requiresSkills"
                  nzMode="multiple"
                  nzShowSearch
                  nzPlaceHolder="none"
                  i18n-nzPlaceHolder="@@library.templateEditor.requiresSkills.none"
                  [ngModel]="draft().requiresSkills ?? []"
                  (ngModelChange)="patch({ requiresSkills: $event })"
                >
                  @for (s of requiredSkillOptions(); track s.key) {
                    <nz-option [nzValue]="s.key" [nzLabel]="s.label" />
                  }
                </nz-select>
              </nz-form-control>
            </nz-form-item>

            <h3 class="sec" i18n="@@library.templateEditor.instructionsSection">Instructions</h3>
            @if (draft().extendsKey) {
              <nz-form-item data-instructions-mode>
                <nz-form-control>
                  <nz-radio-group
                    name="instructionsMode"
                    [ngModel]="draft().instructionsMode"
                    (ngModelChange)="patch({ instructionsMode: $event })"
                    nzSize="small"
                  >
                    <label
                      nz-radio-button
                      nzValue="APPEND"
                      i18n="@@library.templateEditor.appendParent"
                      >Append to parent</label
                    >
                    <label
                      nz-radio-button
                      nzValue="REPLACE"
                      i18n="@@library.templateEditor.replaceParent"
                      >Replace parent</label
                    >
                  </nz-radio-group>
                </nz-form-control>
              </nz-form-item>
            }
            <nz-form-item data-instructions>
              <nz-form-label
                [nzTooltipTitle]="instructionsHelp"
                nzFor="tt-instructions"
                i18n="@@library.templateEditor.instructions"
                >Instructions</nz-form-label
              >
              <nz-form-control>
                <textarea
                  nz-input
                  id="tt-instructions"
                  name="instructions"
                  class="mono-textarea"
                  [ngModel]="draft().instructions ?? ''"
                  (ngModelChange)="patch({ instructions: $event })"
                  [nzAutosize]="{ minRows: 10, maxRows: 30 }"
                  placeholder="What to do and when the result is good enough"
                  i18n-placeholder="@@library.templateEditor.instructions.placeholder"
                ></textarea>
              </nz-form-control>
            </nz-form-item>
            <ng-template #instructionsHelp
              ><span i18n="@@library.templateEditor.instructions.help">
                What to do and the acceptance criteria. The platform adds the issue, the results of
                earlier steps, the start inputs, the reviewer's feedback and the owner's answers
                itself, unless you place them with their placeholders: {{ placeholders.issue }},
                {{ placeholders.artifacts }}, {{ placeholders.params }},
                {{ placeholders.feedback }}, {{ placeholders.answers }}.
              </span></ng-template
            >

            <h3 class="sec">
              <span i18n="@@library.templateEditor.parameters">Parameters</span>
              <bo-help-tip [text]="parametersHelp" />
            </h3>
            <ng-template #parametersHelp
              ><span i18n="@@library.templateEditor.parameters.sourcesHelp">
                Inputs of the work. A workflow step gives each a value, asks for it when the run
                starts or takes the result of an earlier step. The instructions and text settings
                read them as {{ paramsExample }}. A parameter with the name of an inherited one
                replaces it.
              </span></ng-template
            >
            @for (p of draft().parameters ?? []; track $index; let i = $index) {
              <div class="param-row" [attr.data-parameter]="i">
                <input
                  nz-input
                  class="mono"
                  [name]="'pa-name-' + i"
                  [ngModel]="p.name"
                  (ngModelChange)="patchParameter(i, { name: $event })"
                  placeholder="name"
                  i18n-placeholder="@@library.templateEditor.parameters.name"
                  aria-label="Name"
                  i18n-aria-label="@@library.templateEditor.parameters.nameLabel"
                />
                <input
                  nz-input
                  [name]="'pa-label-' + i"
                  [ngModel]="p.label"
                  (ngModelChange)="patchParameter(i, { label: $event })"
                  placeholder="Label shown in the step"
                  i18n-placeholder="@@library.templateEditor.parameters.label"
                  aria-label="Label"
                  i18n-aria-label="@@library.templateEditor.parameters.labelLabel"
                />
                <nz-select
                  [name]="'pa-type-' + i"
                  [ngModel]="p.type"
                  (ngModelChange)="patchParameter(i, { type: $event })"
                  aria-label="Type"
                  i18n-aria-label="@@library.templateEditor.parameters.typeLabel"
                >
                  @for (t of parameterTypes; track t.value) {
                    <nz-option [nzValue]="t.value" [nzLabel]="t.label" />
                  }
                </nz-select>
                <label class="ri-required">
                  <nz-switch
                    nzSize="small"
                    [name]="'pa-required-' + i"
                    [ngModel]="p.required"
                    (ngModelChange)="patchParameter(i, { required: $event })"
                  />
                  <span i18n="@@library.templateEditor.parameters.required">Required</span>
                </label>
                @if (p.type === 'CHOICE') {
                  <nz-select
                    class="pa-wide"
                    nzMode="tags"
                    [name]="'pa-options-' + i"
                    [ngModel]="p.options ?? []"
                    (ngModelChange)="patchParameter(i, { options: $event })"
                    nzPlaceHolder="Options (type and press Enter)"
                    i18n-nzPlaceHolder="@@library.templateEditor.parameters.options"
                    aria-label="Options"
                    i18n-aria-label="@@library.templateEditor.parameters.optionsLabel"
                  ></nz-select>
                }
                <span class="pa-default">
                  @switch (p.type) {
                    @case ('NUMBER') {
                      <nz-input-number
                        class="pa-number"
                        [name]="'pa-default-' + i"
                        [ngModel]="p.default ?? null"
                        (ngModelChange)="patchParameter(i, { default: $event })"
                        nzPlaceHolder="Default (optional)"
                        i18n-nzPlaceHolder="@@library.templateEditor.parameters.default"
                        [nzDisabled]="readonly()"
                      />
                    }
                    @case ('BOOLEAN') {
                      <nz-select
                        [name]="'pa-default-' + i"
                        nzAllowClear
                        [ngModel]="p.default ?? null"
                        (ngModelChange)="patchParameter(i, { default: $event ?? null })"
                        nzPlaceHolder="Default (optional)"
                        i18n-nzPlaceHolder="@@library.templateEditor.parameters.default"
                      >
                        <nz-option [nzValue]="true" [nzLabel]="text.yes" />
                        <nz-option [nzValue]="false" [nzLabel]="text.no" />
                      </nz-select>
                    }
                    @case ('CHOICE') {
                      <nz-select
                        [name]="'pa-default-' + i"
                        nzAllowClear
                        [ngModel]="p.default ?? null"
                        (ngModelChange)="patchParameter(i, { default: $event ?? null })"
                        nzPlaceHolder="Default (optional)"
                        i18n-nzPlaceHolder="@@library.templateEditor.parameters.default"
                      >
                        @for (o of p.options ?? []; track o) {
                          <nz-option [nzValue]="o" [nzLabel]="o" />
                        }
                      </nz-select>
                    }
                    @default {
                      <input
                        nz-input
                        [name]="'pa-default-' + i"
                        [ngModel]="p.default ?? ''"
                        (ngModelChange)="patchParameter(i, { default: $event || null })"
                        placeholder="Default (optional)"
                        i18n-placeholder="@@library.templateEditor.parameters.default"
                      />
                    }
                  }
                </span>
                <input
                  nz-input
                  class="pa-help"
                  [name]="'pa-help-' + i"
                  [ngModel]="p.help ?? ''"
                  (ngModelChange)="patchParameter(i, { help: $event || null })"
                  placeholder="Help text (optional)"
                  i18n-placeholder="@@library.templateEditor.parameters.helpText"
                  aria-label="Help text"
                  i18n-aria-label="@@library.templateEditor.parameters.helpLabel"
                />
                @if (!readonly()) {
                  <span class="ri-actions">
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      [disabled]="i === 0"
                      (click)="moveParameter(i, -1)"
                      aria-label="Move up"
                      i18n-aria-label="@@library.templateEditor.parameters.up"
                    >
                      <nz-icon nzType="arrow-up" />
                    </button>
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      [disabled]="i === (draft().parameters ?? []).length - 1"
                      (click)="moveParameter(i, 1)"
                      aria-label="Move down"
                      i18n-aria-label="@@library.templateEditor.parameters.down"
                    >
                      <nz-icon nzType="arrow-down" />
                    </button>
                    <button
                      nz-button
                      nzSize="small"
                      nzDanger
                      type="button"
                      (click)="removeParameter(i)"
                      aria-label="Remove"
                      i18n-aria-label="@@library.templateEditor.parameters.remove"
                    >
                      <nz-icon nzType="delete" />
                    </button>
                  </span>
                }
              </div>
            }
            @if (!readonly()) {
              <button
                nz-button
                nzType="dashed"
                type="button"
                class="ri-add pa-add"
                (click)="addParameter()"
                i18n="@@library.templateEditor.parameters.add"
              >
                Add parameter
              </button>
            }

            @if (inheritedParameterFields().length) {
              <h3 class="sec">
                <span i18n="@@library.templateEditor.parameterValues">Parameter values</span>
                <bo-help-tip [text]="parameterValuesHelp" />
              </h3>
              <ng-template #parameterValuesHelp
                ><span i18n="@@library.templateEditor.parameterValues.help">
                  Values for the parameters of the parent templates. Empty: the parent's value or
                  the default; a workflow step may still set its own.
                </span></ng-template
              >
              <div class="param-values" data-parameter-values>
                <app-parameter-values-form
                  [fields]="inheritedParameterFields()"
                  [readonly]="readonly()"
                  idPrefix="tt-pv"
                  (valueChange)="setParameterValue($event.name, $event.value)"
                />
              </div>
            }

            <h3 class="sec">
              <span i18n="@@library.templateEditor.configuration">Configuration</span>
              <bo-help-tip [text]="configHelp" />
              @if (configSchema() && !readonly()) {
                <label class="raw-toggle">
                  <nz-switch
                    nzSize="small"
                    name="configRaw"
                    [ngModel]="configRaw()"
                    (ngModelChange)="setConfigRaw($event)"
                    [nzDisabled]="!!configError()"
                  />
                  <span i18n="@@library.templateEditor.config.raw">Raw JSON</span>
                </label>
              }
            </h3>
            <ng-template #configHelp
              ><span i18n="@@library.templateEditor.config.help"
                >Set only what differs from the parent template; empty fields are inherited.</span
              ></ng-template
            >
            @if (configSchema() && !configRaw() && !readonly()) {
              @for (k of [configFormKey()]; track k) {
                <div class="config-form" data-config-form>
                  <app-schema-form
                    [schema]="configSchema()"
                    [config]="draft().config ?? {}"
                    (configChange)="onConfigForm($event)"
                    idPrefix="tt-cfg"
                  />
                </div>
              }
            } @else {
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="Keys of model answer or coding agent steps (codingAgent, verifyCommand, allowQuestions, ...). Deep-merged over the parent; null deletes."
                  i18n-nzTooltipTitle="@@library.templateEditor.config.extra"
                  nzFor="tt-config"
                  i18n="@@library.templateEditor.config"
                  >Config (JSON)</nz-form-label
                >
                <nz-form-control>
                  <textarea
                    nz-input
                    id="tt-config"
                    name="config"
                    class="mono-textarea"
                    [ngModel]="configText()"
                    (ngModelChange)="onConfig($event)"
                    [nzAutosize]="{ minRows: 4, maxRows: 16 }"
                  ></textarea>
                  @if (configError()) {
                    <div class="danger" style="font-size: 12px">{{ configError() }}</div>
                  }
                </nz-form-control>
              </nz-form-item>
            }

            <nz-collapse nzGhost class="advanced">
              <nz-collapse-panel
                nzHeader="Advanced: artefacts, output files, output schema"
                i18n-nzHeader="@@library.templateEditor.advancedNoSandbox"
                [nzExtra]="advancedTip"
              >
                <ng-template #advancedTip>
                  <bo-help-tip [text]="advancedHelp" (click)="$event.stopPropagation()" />
                </ng-template>
                <ng-template #advancedHelp
                  ><span i18n="@@library.templateEditor.advancedNoSandbox.help">
                    Most templates need nothing here. These settings matter for base templates and
                    decisions on the model answer. The sandbox is set on the project.
                  </span></ng-template
                >
                @if (template()?.id && template()?.key) {
                  <p class="sec-help" data-template-key>
                    <span i18n="@@library.templateEditor.keyNote"
                      >Technical key (workflow steps, MCP, support):</span
                    >
                    <span class="mono">{{ template()?.key }}</span>
                  </p>
                }
                <div class="grid">
                  <nz-form-item>
                    <nz-form-label
                      nzTooltipTitle="Only a base for other templates, never a step itself."
                      i18n-nzTooltipTitle="@@library.templateEditor.abstract.extra"
                      i18n="@@library.templateEditor.abstract"
                      >Abstract</nz-form-label
                    >
                    <nz-form-control>
                      <nz-switch
                        name="abstract"
                        [ngModel]="draft().abstract"
                        (ngModelChange)="patch({ abstract: $event })"
                      />
                    </nz-form-control>
                  </nz-form-item>
                  <nz-form-item>
                    <nz-form-label
                      nzTooltipTitle="Artefact names this step writes."
                      i18n-nzTooltipTitle="@@library.templateEditor.produces.extra"
                      nzFor="tt-produces"
                      i18n="@@library.templateEditor.produces"
                      >Produces</nz-form-label
                    >
                    <nz-form-control>
                      <nz-select
                        id="tt-produces"
                        name="produces"
                        nzMode="tags"
                        nzPlaceHolder="spec, review"
                        i18n-nzPlaceHolder="@@library.templateEditor.produces.placeholder"
                        [ngModel]="draft().produces ?? []"
                        (ngModelChange)="patch({ produces: $event })"
                      >
                        @for (n of SLOT_SUGGESTIONS; track n) {
                          <nz-option [nzValue]="n" [nzLabel]="n" />
                        }
                      </nz-select>
                    </nz-form-control>
                  </nz-form-item>
                  <nz-form-item>
                    <nz-form-label
                      nzTooltipTitle="Artefact names this step reads; the workflow check warns when a step reads something nobody writes."
                      i18n-nzTooltipTitle="@@library.templateEditor.consumes.extra"
                      nzFor="tt-consumes"
                      i18n="@@library.templateEditor.consumes"
                      >Consumes</nz-form-label
                    >
                    <nz-form-control>
                      <nz-select
                        id="tt-consumes"
                        name="consumes"
                        nzMode="tags"
                        nzPlaceHolder="issue, diff"
                        i18n-nzPlaceHolder="@@library.templateEditor.consumes.placeholder"
                        [ngModel]="draft().consumes ?? []"
                        (ngModelChange)="patch({ consumes: $event })"
                      >
                        @for (n of SLOT_SUGGESTIONS; track n) {
                          <nz-option [nzValue]="n" [nzLabel]="n" />
                        }
                      </nz-select>
                    </nz-form-control>
                  </nz-form-item>
                  <nz-form-item>
                    <nz-form-label
                      nzTooltipTitle="Field of the model answer copied to state.confidence, for decisions such as confidence < 0.7."
                      i18n-nzTooltipTitle="@@library.templateEditor.confidence.extra"
                      nzFor="tt-confidence"
                      i18n="@@library.templateEditor.confidence"
                      >Confidence field</nz-form-label
                    >
                    <nz-form-control>
                      <input
                        nz-input
                        id="tt-confidence"
                        name="confidenceField"
                        class="mono"
                        [ngModel]="draft().confidenceField ?? ''"
                        (ngModelChange)="patch({ confidenceField: $event || null })"
                        placeholder="confidence"
                        i18n-placeholder="@@library.templateEditor.confidence.placeholder"
                      />
                    </nz-form-control>
                  </nz-form-item>
                  <nz-form-item>
                    <nz-form-label
                      nzTooltipTitle="Free labels for search. The tag deprecated hides the template from new steps."
                      i18n-nzTooltipTitle="@@library.templateEditor.tags.extra"
                      nzFor="tt-tags"
                      i18n="@@library.templateEditor.tags"
                      >Tags</nz-form-label
                    >
                    <nz-form-control>
                      <nz-select
                        id="tt-tags"
                        name="tags"
                        nzMode="tags"
                        nzPlaceHolder="tags"
                        i18n-nzPlaceHolder="@@library.templateEditor.tags.placeholder"
                        [ngModel]="draft().tags ?? []"
                        (ngModelChange)="patch({ tags: $event })"
                      ></nz-select>
                    </nz-form-control>
                  </nz-form-item>
                </div>
                <nz-form-item data-output-files>
                  <nz-form-label
                    nzTooltipTitle="Globs of files the agent leaves in the run directory that are returned as artefacts, named like the file without its extension (e.g. specs/**/spec.md gives spec)."
                    i18n-nzTooltipTitle="@@library.templateEditor.outputFiles.help"
                    nzFor="tt-output-files"
                    i18n="@@library.templateEditor.outputFiles"
                    >Output files</nz-form-label
                  >
                  <nz-form-control>
                    <nz-select
                      id="tt-output-files"
                      name="outputFiles"
                      nzMode="tags"
                      [nzTokenSeparators]="[',']"
                      nzPlaceHolder="specs/**/spec.md, plan.md"
                      i18n-nzPlaceHolder="@@library.templateEditor.outputFiles.placeholder"
                      [ngModel]="draft().outputFiles ?? []"
                      (ngModelChange)="patch({ outputFiles: cleanGlobs($event) })"
                    ></nz-select>
                  </nz-form-control>
                </nz-form-item>
                <nz-form-item>
                  <nz-form-label
                    nzTooltipTitle="Shape of a structured answer; empty for free text. A model answers with it, a coding agent writes it to structured-answer.json."
                    i18n-nzTooltipTitle="@@library.templateEditor.outputSchema.extra"
                    nzFor="tt-schema"
                    i18n="@@library.templateEditor.outputSchema"
                    >Output schema (JSON Schema)</nz-form-label
                  >
                  <nz-form-control>
                    <textarea
                      nz-input
                      id="tt-schema"
                      name="outputSchema"
                      class="mono-textarea"
                      [ngModel]="draft().outputSchema ?? ''"
                      (ngModelChange)="onSchema($event)"
                      [nzAutosize]="{ minRows: 4, maxRows: 16 }"
                      [placeholder]="schemaPlaceholder"
                    ></textarea>
                    @if (schemaError()) {
                      <div class="danger" style="font-size: 12px">{{ schemaError() }}</div>
                    }
                  </nz-form-control>
                </nz-form-item>
              </nz-collapse-panel>
            </nz-collapse>
          </form>

          <aside
            class="preview"
            tabindex="0"
            aria-label="Resolved preview"
            i18n-aria-label="@@library.templateEditor.preview"
          >
            <div class="preview-head">
              <h3 class="sec flush">
                <span i18n="@@library.templateEditor.preview">Resolved preview</span>
                <bo-help-tip [text]="previewHelp" />
              </h3>
              <ng-template #previewHelp
                ><span i18n="@@library.templateEditor.preview.help">
                  What the agent receives after inheritance and project shadowing are applied, with
                  the skills and rules of the agent. Reflects the saved version.
                </span></ng-template
              >
              @if (resolving()) {
                <nz-icon nzType="loading" nzSpin />
              }
              <span class="spacer"></span>
              <nz-select
                nzSize="small"
                nzAllowClear
                nzShowSearch
                nzPlaceHolder="as global"
                i18n-nzPlaceHolder="@@library.templateEditor.asGlobal"
                [ngModel]="previewProjectId()"
                (ngModelChange)="previewProjectId.set($event ?? null)"
                style="width: 180px"
                aria-label="Preview for project"
                i18n-aria-label="@@library.templateEditor.previewProject"
              >
                @for (p of projects(); track p.id) {
                  <nz-option [nzValue]="p.id" [nzLabel]="p.key" />
                }
              </nz-select>
            </div>
            @if (preview(); as pv) {
              <div
                class="lineage"
                aria-label="Lineage"
                i18n-aria-label="@@library.templateEditor.lineage"
              >
                @for (k of pv.lineage; track k; let last = $last) {
                  <span [class.leaf]="last">{{ templateName(k) }}</span>
                  @if (!last) {
                    <nz-icon nzType="right" class="muted" />
                  }
                }
              </div>
              <div class="chips">
                @if (deprecated()) {
                  <nz-tag nzColor="red" nz-tooltip [nzTooltipTitle]="text.deprecatedHint">{{
                    text.deprecated
                  }}</nz-tag>
                }
                @if (pv.abstract) {
                  <nz-tag i18n="@@library.templateEditor.abstractChip">abstract</nz-tag>
                }
                <nz-tag nzColor="blue" data-category>{{ categoryLabel(pv.category) }}</nz-tag>
                @for (p of pv.ports; track p) {
                  <nz-tag i18n="@@library.templateEditor.port">port: {{ p }}</nz-tag>
                }
              </div>
              @if (!pv.ok) {
                <nz-alert
                  nzType="error"
                  nzShowIcon
                  nzMessage="The template has problems"
                  i18n-nzMessage="@@library.templateEditor.checkFailed"
                  [nzDescription]="problemsTpl"
                  style="margin-top: 8px"
                />
                <ng-template #problemsTpl>
                  <ul class="problems">
                    @for (p of pv.problems; track p) {
                      <li>{{ p }}</li>
                    }
                  </ul>
                </ng-template>
              }
              @if (pv.warnings.length) {
                <nz-alert
                  nzType="warning"
                  nzShowIcon
                  nzMessage="Warnings"
                  i18n-nzMessage="@@library.templateEditor.checkWarnings"
                  [nzDescription]="warningsTpl"
                  style="margin-top: 8px"
                  data-check-warnings
                />
                <ng-template #warningsTpl>
                  <ul class="problems">
                    @for (w of pv.warnings; track w) {
                      <li>{{ w }}</li>
                    }
                  </ul>
                </ng-template>
              }
              <nz-tabs nzSize="small" style="margin-top: 8px">
                <nz-tab nzTitle="Instructions" i18n-nzTitle="@@library.templateEditor.instructions">
                  <pre class="prompt" tabindex="0" data-preview-instructions>{{
                    pv.instructions || text.empty
                  }}</pre>
                </nz-tab>
                <nz-tab nzTitle="Skills" i18n-nzTitle="@@library.templateEditor.skills">
                  @if (!pv.skills.length) {
                    <p class="muted small" i18n="@@library.templateEditor.noSkills">No skills.</p>
                  }
                  <div class="chips">
                    @for (s of pv.skills; track s.key) {
                      <nz-tag>{{ s.name }}</nz-tag>
                    }
                  </div>
                </nz-tab>
                <nz-tab nzTitle="Rules" i18n-nzTitle="@@library.templateEditor.rules">
                  @if (!pv.rules.length) {
                    <p class="muted small" i18n="@@library.templateEditor.noRules">No rules.</p>
                  }
                  <div class="chips">
                    @for (r of pv.rules; track r.key) {
                      <nz-tag [nzColor]="r.automatic ? 'gold' : 'default'" [attr.data-rule]="r.key"
                        >{{ r.name }}
                        @if (r.automatic) {
                          <span i18n="@@library.templateEditor.automaticSuffix">(automatic)</span>
                        }
                      </nz-tag>
                    }
                  </div>
                </nz-tab>
                <nz-tab nzTitle="Config" i18n-nzTitle="@@library.templateEditor.configTab">
                  <app-json-view [value]="pv.config" maxHeight="320px" />
                  <div class="chips" style="margin-top: 8px">
                    @for (p of pv.produces; track p) {
                      <nz-tag nzColor="green" i18n="@@library.templateEditor.producesTag"
                        >produces {{ p }}</nz-tag
                      >
                    }
                    @for (c of pv.consumes; track c) {
                      <nz-tag nzColor="cyan" i18n="@@library.templateEditor.consumesTag"
                        >consumes {{ c }}</nz-tag
                      >
                    }
                  </div>
                </nz-tab>
                <nz-tab nzTitle="Parameters" i18n-nzTitle="@@library.templateEditor.parameters">
                  @if (!pv.parameters.length) {
                    <p class="muted small" i18n="@@library.templateEditor.parameters.none">
                      This template has no parameters.
                    </p>
                  }
                  <ul class="ri-resolved">
                    @for (p of pv.parameters; track p.name) {
                      <li [attr.data-resolved-parameter]="p.name">
                        <span class="mono">{{ p.name }}</span>
                        <span>{{ p.label }}</span>
                        <nz-tag>{{ parameterTypeLabel(p.type) }}</nz-tag>
                        @if (p.required) {
                          <nz-tag nzColor="red" i18n="@@library.templates.runInputs.requiredTag"
                            >required</nz-tag
                          >
                        }
                        <span class="muted small">{{ parameterSourceText(p) }}</span>
                      </li>
                    }
                  </ul>
                </nz-tab>
              </nz-tabs>
            } @else if (previewError()) {
              <nz-alert nzType="warning" nzShowIcon [nzMessage]="previewError()!" />
            } @else if (!template()) {
              <p class="muted small" i18n="@@library.templateEditor.saveToPreview">
                Save the template to see its resolved form.
              </p>
            }
          </aside>
        </div>
      </ng-container>
      <ng-template #footer>
        <bo-action-bar
          [sticky]="false"
          [dirty]="dirty()"
          [saving]="saving()"
          [canSave]="canSave()"
          [showSave]="open() && !readonly()"
          [saveLabel]="template()?.id ? text.save : text.create"
          [cleanLabel]="
            readonly() ? text.readOnlyClean : template()?.id ? text.allSaved : text.fillIn
          "
          (save)="submit()"
        >
          @if (!canSave() && !readonly() && saveBlocker()) {
            <span boActionStatus class="blocker">{{ saveBlocker() }}</span>
          }
          @if (canPreset()) {
            <button
              nz-button
              type="button"
              (click)="requestPreset()"
              nz-tooltip
              [nzTooltipTitle]="text.presetHint"
              data-create-preset
            >
              <nz-icon nzType="copy" />
              <span i18n="@@library.templateEditor.createPreset">Create preset</span>
            </button>
          }
          <button nz-button type="button" (click)="requestClose()">
            {{ readonly() || !dirty() ? text.close : text.discard }}
          </button>
        </bo-action-bar>
      </ng-template>
    </nz-drawer>
  `,
  styles: [
    `
      .split {
        display: grid;
        grid-template-columns: minmax(0, 7fr) minmax(320px, 5fr);
        flex: 1;
        min-height: 0;
        width: 100%;
      }
      .editor {
        padding: 16px 24px 32px;
        border-right: 1px solid var(--bo-line);
        overflow: auto;
        min-height: 0;
      }
      .preview {
        padding: 16px 20px 24px;
        background: var(--bo-surface-2);
        overflow: auto;
        min-height: 0;
      }
      .editor:focus-visible,
      .preview:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .sec-help {
        margin: -4px 0 12px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .sec-help.automatic {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        align-items: center;
        margin-top: -12px;
      }
      .ri-required,
      .ri-actions {
        display: inline-flex;
        gap: 4px;
        align-items: center;
        font-size: 12px;
      }
      .ri-add {
        margin-bottom: 16px;
      }
      .param-row {
        display: grid;
        grid-template-columns: 140px 1fr 150px auto;
        gap: 6px 8px;
        align-items: center;
        margin-bottom: 12px;
        padding-bottom: 10px;
        border-bottom: 1px dashed var(--bo-line);
      }
      .param-row .pa-wide {
        grid-column: 1 / 5;
      }
      .param-row .pa-default {
        grid-column: 1 / 3;
      }
      .param-row .pa-default > * {
        width: 100%;
      }
      .param-row .pa-help {
        grid-column: 3 / 4;
      }
      .param-values {
        margin-bottom: 8px;
      }
      .ri-resolved {
        list-style: none;
        padding: 0;
        margin: 0;
      }
      .ri-resolved li {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        align-items: center;
        padding: 4px 0;
      }
      .sec {
        margin: 20px 0 10px;
        font-size: 12px;
        font-weight: 600;
        letter-spacing: 0.02em;
        color: var(--bo-ink-2);
        display: flex;
        align-items: center;
        gap: 10px;
      }
      .sec::after {
        content: '';
        flex: 1;
        height: 1px;
        background: var(--bo-line);
      }
      .sec.flush {
        margin: 0;
        font-size: 14px;
        color: var(--bo-ink);
        letter-spacing: 0;
      }
      .sec.flush::after {
        display: none;
      }
      .drawer-title {
        display: inline-flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
      }
      .title-key {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        font-weight: 400;
        color: var(--bo-ink-3);
      }
      .title-chip {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        height: 20px;
        padding: 0 8px;
        border-radius: 999px;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
        font-size: 11.5px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      .blocker {
        color: var(--bo-you-ink);
      }
      .title-chip.deprecated {
        color: var(--bo-blocked-ink);
        box-shadow: inset 0 0 0 1px currentColor;
      }
      .title-chip.coding {
        color: var(--bo-accent);
        box-shadow: inset 0 0 0 1px currentColor;
      }
      .raw-toggle {
        display: inline-flex;
        gap: 6px;
        align-items: center;
        font-weight: 400;
        letter-spacing: 0;
        order: 2;
      }
      .config-form {
        margin-bottom: 8px;
      }
      .advanced {
        margin-top: 16px;
        border-top: 1px solid var(--bo-line);
      }
      .advanced ::ng-deep .ant-collapse-content-box {
        padding: 4px 0 0 !important;
      }
      .advanced ::ng-deep .ant-collapse-header {
        padding-left: 0 !important;
        font-weight: 600;
        color: var(--bo-ink-2);
      }
      /* The action bar is the footer (it brings its own hairline and padding). */
      ::ng-deep .bo-editor-drawer .ant-drawer-footer {
        padding: 0;
        border-top: 0;
      }
      .preview-head {
        display: flex;
        gap: 8px;
        align-items: center;
      }
      .spacer {
        flex: 1;
      }
      .small {
        font-size: 11px;
      }
      .grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0 16px;
      }
      .lineage {
        display: flex;
        gap: 4px;
        align-items: center;
        flex-wrap: wrap;
        font-size: 12px;
        margin-bottom: 6px;
      }
      .lineage .leaf {
        font-weight: 600;
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      .problems {
        margin: 0;
        padding-left: 18px;
        color: var(--bo-blocked-ink);
      }
      .prompt {
        white-space: pre-wrap;
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 12px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: 4px;
        padding: 10px;
        max-height: 420px;
        overflow: auto;
      }
      @media (max-width: 1000px) {
        .split {
          grid-template-columns: 1fr;
        }
        .split {
          overflow: auto;
        }
        .editor,
        .preview {
          overflow: visible;
        }
      }
    `,
  ],
})
export class TemplateEditorDrawerComponent {
  private readonly api = inject(TaskLibraryApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly confirm = inject(ConfirmService);

  readonly open = input(false);
  /** Existing template (edit) or null (create). */
  readonly template = input<TaskTemplate | null>(null);
  readonly templates = input<TaskTemplate[]>([]);
  /**
   * ADR 0055: a prefilled new template when `template` is null, e.g. "Customize for project" (a
   * PROJECT template with the global key, extending it).
   */
  readonly preset = input<TaskTemplateInput | null>(null);
  readonly skills = input<Skill[]>([]);
  readonly projects = input<Project[]>([]);
  readonly saving = input(false);
  /** Bumps when the template was saved, to refresh the preview. */
  readonly savedVersion = input(0);
  /** Without the permission for the template's scope: view only. */
  readonly readonly = input(false);
  /** `library.manage`: GLOBAL scope selectable. */
  readonly canGlobal = input(true);
  /** Projects selectable as scope (those with `library.project.manage`); default all. */
  readonly scopeProjects = input<Project[] | null>(null);

  readonly saved = output<TaskTemplateInput>();
  readonly closed = output<void>();
  /** ADR 0058: the user asked for a preset of the open template (the page opens a new drawer). */
  readonly createPreset = output<TaskTemplateInput>();

  /** ADR 0056: config schemas of model answer and coding agent steps; null until loaded. */
  readonly configSchemas = signal<TaskConfigSchemas | null>(null);
  /** The configuration is edited as raw JSON instead of the form. */
  readonly configRaw = signal(false);
  /** Bumped to re-create the configuration form (another template, back from raw JSON). */
  readonly configFormKey = signal(0);
  readonly SLOT_SUGGESTIONS = [
    'issue',
    'spec',
    'clarifications',
    'plan',
    'tasks',
    'diff',
    'mr',
    'review',
    'analysis',
    'confidence',
  ];

  readonly draft = signal<TaskTemplateInput>(emptyTemplate());
  private readonly initialJson = signal(JSON.stringify(emptyTemplate()));
  /** Unsaved edits in the open drawer (`unsavedChangesGuard` of the page asks before leaving). */
  readonly dirty = computed(
    () => this.open() && !this.readonly() && JSON.stringify(this.draft()) !== this.initialJson(),
  );
  readonly configText = signal('{}');
  readonly configError = signal<string | null>(null);
  readonly schemaError = signal<string | null>(null);
  readonly previewProjectId = signal<string | null>(null);
  readonly preview = signal<ResolvedPreview | null>(null);
  readonly previewError = signal<string | null>(null);
  readonly resolving = signal(false);
  private readonly resolve$ = new Subject<{ key: string | null; projectId: string | null }>();
  /** ADR 0058: the parent resolved (its parameters with the values its lineage sets); null without. */
  readonly parentResolved = signal<ResolvedTask | null>(null);
  private readonly parent$ = new Subject<{ key: string | null; projectId: string | null }>();

  readonly drawerWidth = 'min(1280px, 96vw)'; // i18n-ignore
  readonly schemaPlaceholder = '{ "type": "object", "properties": { ... } }'; // i18n-ignore
  readonly text = {
    abstractSuffix: $localize`:@@library.templateEditor.abstractSuffix: [abstract]`,
    empty: $localize`:@@library.templateEditor.empty:(empty)`,
    save: $localize`:@@library.templateEditor.save:Save template`,
    create: $localize`:@@library.templateEditor.create:Create template`,
    readOnlyClean: $localize`:@@library.templateEditor.readOnlyClean:Read-only: you cannot change templates in this scope`,
    allSaved: $localize`:@@library.templateEditor.allSaved:All changes saved`,
    fillIn: $localize`:@@library.templateEditor.fillIn:Fill in a name to create the template`,
    inherited: $localize`:@@library.templateEditor.inherited:inherited`,
    close: $localize`:@@library.templateEditor.close:Close`,
    discard: $localize`:@@library.templateEditor.discard:Discard changes`,
    deprecated: deprecatedLabel(),
    deprecatedHint: deprecatedHint(),
    yes: $localize`:@@library.templateEditor.parameters.yes:Yes`,
    no: $localize`:@@library.templateEditor.parameters.no:No`,
    presetHint: $localize`:@@library.templateEditor.createPreset.hint:A new template that extends this one and only sets its parameter values`,
    needsCoding: neededAgentLabel(true),
    needsCodingHint: $localize`:@@library.templateEditor.needsCoding.hint:Its configuration says it works in the repository, so it needs a coding agent. The workflow step chooses the agent.`,
  };
  /** ADR 0058: how the instructions read a parameter (help of the parameters section). */
  readonly paramsExample = '{{params.<name>}}'; // i18n-ignore
  /** ADR 0062: the placeholders of the standard data sections (help of the instructions). */
  readonly placeholders = {
    issue: '{{issue.*}}', // i18n-ignore
    artifacts: '{{artifacts.<name>}}', // i18n-ignore
    params: '{{params.<name>}}', // i18n-ignore
    feedback: '{{feedback}}', // i18n-ignore
    answers: '{{answers}}', // i18n-ignore
  };
  readonly parameterTypeLabel = parameterTypeLabel;
  readonly cleanGlobs = cleanGlobs;
  readonly parameterTypes = PARAMETER_TYPES.map((t) => ({
    value: t,
    label: parameterTypeLabel(t),
  }));

  /**
   * ADR 0061: the template needs a coding agent (read-only, from its merged config): the saved
   * value while the config is unchanged, else computed from the draft and its lineage.
   */
  readonly needsCodingAgent = computed(() => {
    const t = this.template();
    const d = this.draft();
    if (
      t?.needsCodingAgent !== undefined &&
      JSON.stringify(t.config ?? {}) === JSON.stringify(d.config ?? {}) &&
      (t.extendsKey ?? null) === (d.extendsKey ?? null)
    )
      return !!t.needsCodingAgent;
    return templateNeedsCodingAgent(d, this.templates());
  });
  /** Form of the configuration: the keys of model answer and coding agent steps; null = raw JSON only. */
  readonly configSchema = computed(() => templateConfigSchema(this.configSchemas()));
  /** The template carries the `deprecated` tag. */
  readonly deprecated = computed(() => isDeprecated(this.draft()));

  /** ADR 0055: a new project template that customises a global one (same key, extends it). */
  readonly customizing = computed(() => {
    const d = this.draft();
    return !this.template()?.id && !!d.key && d.extendsKey === d.key && d.scope.type === 'PROJECT';
  });
  /** ADR 0055: the category select; its placeholder names the inherited one. */
  readonly categories = TEMPLATE_CATEGORIES.map((c) => ({ value: c, label: categoryLabel(c) }));
  readonly categoryLabel = categoryLabel;
  readonly categoryPlaceholder = computed(() => {
    const d = this.draft();
    // the resolved view is the inherited one while the saved template has no own category
    const resolved = this.template()?.category ? null : this.preview()?.category;
    const c =
      resolved ??
      (d.extendsKey
        ? effectiveCategory({ ...d, category: null, key: '' }, this.templates())
        : null);
    return c
      ? $localize`:@@library.templateEditor.category.inheritedValue:inherited: ${categoryLabel(c)}:category:`
      : this.text.inherited;
  });
  /** ADR 0060: skills a template may require (by name; a key no longer in the library stays selectable). */
  readonly requiredSkillOptions = computed(() => {
    const byKey = new Map<string, { key: string; label: string }>();
    for (const s of this.skills())
      if (s.key) byKey.set(s.key, { key: s.key, label: s.name || s.key });
    for (const k of this.draft().requiresSkills ?? [])
      if (!byKey.has(k)) byKey.set(k, { key: k, label: k });
    return [...byKey.values()];
  });

  // ---------------------------------------------------------------- ADR 0058: parameters

  /**
   * Parameters of the parent lineage the draft may set values for (a preset): those of the
   * resolved parent that the draft does not declare itself (child wins by name).
   */
  readonly inheritedParameterFields = computed(() => {
    const own = new Set((this.draft().parameters ?? []).map((p) => p.name.trim()));
    const { declared, values } = fromResolved(this.parentResolved()?.parameters);
    return parameterFields(
      declared.filter((p) => !own.has(p.name)),
      values,
      this.draft().parameterValues,
    );
  });
  private readonly parameterBlocker = computed(() => {
    const p = parameterProblem(this.draft().parameters);
    if (!p) return null;
    switch (p.kind) {
      case 'blankName':
        return $localize`:@@library.templateEditor.parameters.blocker.name:Every parameter needs a name`;
      case 'badName':
        return $localize`:@@library.templateEditor.parameters.blocker.badName:Parameter name ${p.name}:name: must start with a letter and use only letters, digits and _ (at most 64)`;
      case 'duplicate':
        return $localize`:@@library.templateEditor.parameters.blocker.duplicate:Parameter name ${p.name}:name: is used twice`;
      case 'blankLabel':
        return $localize`:@@library.templateEditor.parameters.blocker.label:Parameter ${p.name}:name: needs a label`;
      case 'tooLong':
        return $localize`:@@library.templateEditor.parameters.blocker.tooLong:Parameter ${p.name}:name: has a label over 200 or a help text over 1000 characters`;
      case 'noOptions':
        return $localize`:@@library.templateEditor.parameters.blocker.options:Choice parameter ${p.name}:name: needs options`;
      case 'badDefault':
        return $localize`:@@library.templateEditor.parameters.blocker.default:The default of parameter ${p.name}:name: does not fit its type`;
    }
  });
  /** A value of the preset that does not fit its parameter. */
  private readonly parameterValueBlocker = computed(() => {
    const bad = this.inheritedParameterFields().find((f) => f.invalid);
    return bad
      ? $localize`:@@library.templateEditor.parameterValues.blocker:The value of parameter ${bad.param.name}:name: does not fit its type or options`
      : null;
  });
  /** ADR 0058: a saved template can be the parent of a preset. */
  readonly canPreset = computed(() => !!this.template()?.id && !!this.template()?.key);

  /** Where the value of a resolved parameter comes from, in words. */
  parameterSourceText(p: ResolvedParameter): string {
    const v = parameterValueText(p.value);
    switch (p.source) {
      case 'TEMPLATE':
        return $localize`:@@library.templateEditor.parameters.source.template:value from a template: ${v}:value:`;
      case 'DEFAULT':
        return $localize`:@@library.templateEditor.parameters.source.default:default: ${v}:value:`;
      case 'STEP':
        return $localize`:@@library.templateEditor.parameters.source.step:value of the step: ${v}:value:`;
      default:
        return p.required
          ? $localize`:@@library.templateEditor.parameters.source.needed:the step must set it`
          : $localize`:@@library.templateEditor.parameters.source.none:no value`;
    }
  }

  readonly canSave = computed(() => {
    const d = this.draft();
    return (
      !!d.name.trim() &&
      !this.configError() &&
      !this.schemaError() &&
      !this.parameterBlocker() &&
      !this.parameterValueBlocker() &&
      (d.scope.type !== 'PROJECT' || !!d.scope.projectId)
    );
  });
  /** Why Save is disabled, in words (shown in the action bar). */
  readonly saveBlocker = computed(() => {
    const d = this.draft();
    if (!d.name.trim()) return $localize`:@@library.templateEditor.blocker.name:Name is required`;
    if (this.configError())
      return $localize`:@@library.templateEditor.blocker.config:Config is not valid JSON`;
    if (this.schemaError())
      return $localize`:@@library.templateEditor.blocker.schema:Output schema is not valid JSON`;
    if (d.scope.type === 'PROJECT' && !d.scope.projectId)
      return $localize`:@@library.templateEditor.blocker.project:Pick the project`;
    return this.parameterBlocker() ?? this.parameterValueBlocker();
  });
  readonly parents = computed(() => {
    const self = this.template()?.key ?? null;
    const list = parentCandidates(this.templates(), self);
    // a project template customising the global one of its own key extends that global one
    const extendsKey = this.draft().extendsKey;
    const global =
      extendsKey && extendsKey === self && !list.some((t) => t.key === extendsKey)
        ? this.templates().filter((t) => t.key === extendsKey && t.scope?.type !== 'PROJECT')
        : [];
    const seen = new Set<string>();
    return [...global, ...list].filter((t) => !seen.has(t.key ?? '') && seen.add(t.key ?? ''));
  });
  constructor() {
    // ADR 0054/0056: static; without it the configuration stays a JSON text area.
    this.api
      .taskConfigSchemas()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (s) => this.configSchemas.set(s), error: () => undefined });
    effect(() => {
      const t = this.template();
      const open = this.open();
      untracked(() => {
        if (!open) return;
        const preset = this.preset();
        const d = t
          ? toTemplateInput(t as TaskTemplateInput & Record<string, unknown>)
          : preset
            ? structuredClone(preset)
            : emptyTemplate();
        this.draft.set(d);
        this.initialJson.set(JSON.stringify(d));
        this.configText.set(JSON.stringify(d.config ?? {}, null, 2));
        this.configError.set(null);
        this.configRaw.set(false);
        this.configFormKey.update((k) => k + 1);
        this.schemaError.set(null);
        this.previewProjectId.set(d.scope.type === 'PROJECT' ? (d.scope.projectId ?? null) : null);
        this.preview.set(null);
        this.previewError.set(null);
      });
    });
    effect(() => {
      // a "Customize for project" draft previews the global template it starts from
      const key = this.template()?.key ?? this.preset()?.key ?? null;
      const projectId = this.previewProjectId();
      this.savedVersion();
      if (this.open()) this.resolve$.next({ key, projectId });
    });
    this.resolve$
      .pipe(
        debounceTime(150),
        switchMap(({ key, projectId }) => {
          if (!key) return of(null);
          this.resolving.set(true);
          this.previewError.set(null);
          return this.api.resolveTemplate(key, projectId).pipe(
            catchError(() => {
              this.previewError.set(
                $localize`:@@library.templateEditor.resolveError:Could not resolve the template (missing parent?).`,
              );
              return of(null);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => {
        this.preview.set(toPreview(r));
        this.resolving.set(false);
      });
    // ADR 0058: the parent's parameters (and the values its lineage sets) for the preset values
    effect(() => {
      const key = this.parentKey();
      const projectId = this.parentProjectId();
      if (this.open()) untracked(() => this.parent$.next({ key, projectId }));
    });
    this.parent$
      .pipe(
        debounceTime(150),
        distinctUntilChanged((a, b) => a.key === b.key && a.projectId === b.projectId),
        switchMap(({ key, projectId }) =>
          key
            ? this.api.resolveTemplate(key, projectId).pipe(catchError(() => of(null)))
            : of(null),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => this.parentResolved.set(r));
  }

  /** The parent the draft extends (a signal of its own, so typing elsewhere does not resolve). */
  private readonly parentKey = computed(() => this.draft().extendsKey ?? null);
  private readonly parentProjectId = computed(() => {
    const s = this.draft().scope;
    return s.type === 'PROJECT' ? (s.projectId ?? null) : null;
  });

  patch(changes: Partial<TaskTemplateInput>): void {
    this.draft.update((d) => ({ ...d, ...changes }));
  }

  setScope(type: 'GLOBAL' | 'PROJECT'): void {
    this.patch({
      scope: {
        type,
        projectId:
          type === 'PROJECT'
            ? (this.draft().scope.projectId ?? this.projects()[0]?.id ?? null)
            : null,
      },
    });
  }

  onConfig(text: string): void {
    this.configText.set(text);
    try {
      const parsed = (text.trim() ? JSON.parse(text) : {}) as Record<string, unknown>;
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed))
        throw new Error($localize`:@@library.templateEditor.notObject:must be an object`);
      this.configError.set(null);
      this.patch({ config: parsed });
    } catch (e) {
      this.configError.set(invalidJson(e));
    }
  }

  /** A change in the configuration form; the JSON text follows it. */
  onConfigForm(config: Record<string, unknown>): void {
    this.patch({ config });
    this.configText.set(JSON.stringify(config, null, 2));
    this.configError.set(null);
  }

  /** Switches between the form and the raw JSON (only while the JSON is valid). */
  setConfigRaw(raw: boolean): void {
    if (!raw && this.configError()) return;
    this.configText.set(JSON.stringify(this.draft().config ?? {}, null, 2));
    this.configRaw.set(raw);
    if (!raw) this.configFormKey.update((k) => k + 1);
  }

  onSchema(text: string): void {
    this.patch({ outputSchema: text.trim() ? text : null });
    if (!text.trim()) {
      this.schemaError.set(null);
      return;
    }
    try {
      JSON.parse(text);
      this.schemaError.set(null);
    } catch (e) {
      this.schemaError.set(invalidJson(e));
    }
  }

  /** Close, asking first when there are unsaved edits. */
  async requestClose(): Promise<void> {
    if (this.dirty()) {
      const ok = await this.confirm.confirm({
        title: $localize`:@@library.templateEditor.discard.title:Discard unsaved changes?`,
        content: $localize`:@@library.templateEditor.discard.content:The changes to this template have not been saved.`,
        okText: $localize`:@@library.templateEditor.discard:Discard changes`,
        danger: true,
      });
      if (!ok) return;
    }
    this.closed.emit();
  }

  /** After a successful save the current draft is the saved state. */
  markSaved(): void {
    this.initialJson.set(JSON.stringify(this.draft()));
  }

  submit(): void {
    if (!this.canSave()) return;
    const d = this.draft();
    this.saved.emit(
      toSavePayload(
        {
          ...d,
          parameters: cleanParameters(d.parameters),
          parameterValues: { ...(d.parameterValues ?? {}) },
        },
        !!this.template()?.id,
      ),
    );
  }

  // ---------------------------------------------------------------- ADR 0058: parameters

  addParameter(): void {
    this.patch({ parameters: addParameter(this.draft().parameters) });
  }

  removeParameter(index: number): void {
    this.patch({ parameters: removeParameter(this.draft().parameters, index) });
  }

  moveParameter(index: number, delta: number): void {
    this.patch({ parameters: moveParameter(this.draft().parameters, index, delta) });
  }

  patchParameter(index: number, changes: Partial<TemplateParameter>): void {
    this.patch({ parameters: patchParameter(this.draft().parameters, index, changes) });
  }

  /** A preset value; empty = the parent's value or the default. */
  setParameterValue(name: string, value: unknown): void {
    this.patch({
      parameterValues: withParameterValue(this.draft().parameterValues, name, value) ?? {},
    });
  }

  /** ADR 0058: asks the page to open a new preset of this template. */
  requestPreset(): void {
    const t = this.template();
    if (!t?.key) return;
    this.createPreset.emit(presetOf(t));
  }

  /** A template referred to by key, shown by name. */
  templateName(key: string | null | undefined): string {
    return nameOfKey(this.templates(), key);
  }

  parentLabel(t: TaskTemplate): string {
    const scope =
      t.scope?.type === 'PROJECT'
        ? ''
        : this.draft().scope.type === 'PROJECT' && t.key === this.draft().key
          ? $localize`:@@library.templateEditor.globalSuffix: (global)`
          : '';
    return (t.name || t.key || '') + scope + (t.abstract ? this.text.abstractSuffix : '');
  }
}
