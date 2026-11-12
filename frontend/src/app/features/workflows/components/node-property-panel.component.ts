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
import { RouterLink } from '@angular/router';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCollapseModule } from 'ng-zorro-antd/collapse';
import { NzDividerModule } from 'ng-zorro-antd/divider';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import {
  actionDescription,
  actionTitle,
  agentKindDescription,
  agentKindLabel,
  configNeedsCodingAgent,
  isCodingAgent,
  neededAgentLabel,
} from '../../../core/ui/agents';
import { HelpTipComponent } from '../../../core/ui/help-tip.component';
import { defaultStepAgentKey, pickAgent } from '../data/agent-choice';
import { JsonViewComponent } from '../../../core/ui/json-view.component';
import { ModelSelectComponent } from '../../../core/ui/model-select.component';
import { deprecatedHint, deprecatedLabel } from '../../../core/ui/template-tags';
import { categoryLabel, categoryOrder } from '../../../core/ui/template-categories';
import { deprecatedPaletteTask } from '../data/palette-tasks';
import type {
  ActionDescriptor,
  Agent,
  AgentRef,
  DecisionBranch,
  ModelOption,
  ModelProfile,
  PaletteTask,
  ProjectRepository,
  ResolvedTask,
  TaskOverrides,
  WorkflowEdge,
  WorkflowNode,
  WorkflowPalette,
} from '../../../core/api/types';
import {
  JIRA_CONNECTION_KEY,
  NodeConnectionSectionComponent,
  pinnedConnection,
  withPinnedConnection,
} from '../../connections/public-api';
import {
  NODE_ID_PATTERN,
  NODE_KIND_META,
  indexPalette,
  portsFor,
  uniqueNodeId,
} from '../data/graph-model';
import type { JsonSchema } from '../data/schema-form';
import type { Issues } from '../data/workflow-validation';
import { ExpressionEditorComponent } from './expression-editor.component';
import { SchemaFormComponent } from './schema-form.component';
import { GateRulesEditorComponent } from './gate-rules-editor.component';
import { WriteApprovalSectionComponent } from './write-approval-section.component';
import { SECTION_CONFIG_KEYS } from '../data/write-approval';
import { REMOVED_STEP_KEYS, localizedConfigSchema } from '../data/step-config-keys';
import {
  DELIVER_CHANGES,
  deliverChangesSchema,
  deliverFormConfig,
  deliverRepositories,
  withDeliverRepositories,
  withFormConfig,
} from '../data/deliver-changes';
import { type WorkflowSource, deliverableRepositories } from '../data/workflow-sources';
import { StepAgentSectionComponent } from './step-agent-section.component';
import {
  type StepModelUse,
  effectiveStepAgent,
  stepModelUse,
  stepModelUseKey,
} from '../data/model-step';

/** ADR 0054: one POST resolve of a step (template key, the step's overrides, project). */
interface StepResolveRequest {
  key: string;
  overrides: TaskOverrides;
  projectId: string | null;
}

/**
 * Right drawer of the designer: edits the selected node. Emits an updated copy on every change
 * (the page owns the definition). Kind-specific sections per NodeKind.
 */
import { StartRunInputsComponent } from './designer/start-run-inputs.component';
import { CODING_STEP_KEYS, QUESTION_KEYS, stepParameterFields } from '../data/step-session';
import {
  type StepInstructions,
  earlierArtifacts,
  stepInstructions,
  withInstructions,
} from '../data/step-bindings';
import { StepParameterBindingsComponent } from './step-parameter-bindings.component';
@Component({
  selector: 'app-node-property-panel',
  imports: [
    FormsModule,
    RouterLink,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzSelectModule,
    NzSwitchModule,
    NzRadioModule,
    NzButtonModule,
    NzIconModule,
    NzTagModule,
    NzDividerModule,
    NzAlertModule,
    NzCollapseModule,
    NzTooltipModule,
    ExpressionEditorComponent,
    SchemaFormComponent,
    GateRulesEditorComponent,
    NodeConnectionSectionComponent,
    WriteApprovalSectionComponent,
    StepAgentSectionComponent,
    ModelSelectComponent,
    StartRunInputsComponent,
    JsonViewComponent,
    StepParameterBindingsComponent,
    HelpTipComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (node(); as n) {
      <div class="panel">
        <div class="head">
          <nz-icon [nzType]="meta().icon" [style.color]="meta().color" />
          <span class="kind">{{ meta().title }}</span>
          <bo-help-tip [text]="meta().description" />
          <span class="spacer"></span>
          @if (!readonly()) {
            @if (n.kind !== 'START') {
              <button
                nz-button
                nzSize="small"
                nzType="text"
                type="button"
                (click)="duplicate.emit()"
                aria-label="Duplicate node"
                i18n-aria-label="@@workflows.props.duplicateNode"
                nz-tooltip
                nzTooltipTitle="Duplicate"
                i18n-nzTooltipTitle="@@workflows.props.duplicate"
              >
                <nz-icon nzType="copy" />
              </button>
            }
            <button
              nz-button
              nzSize="small"
              nzType="text"
              nzDanger
              type="button"
              (click)="remove.emit()"
              aria-label="Delete node"
              i18n-aria-label="@@workflows.props.deleteNode"
              nz-tooltip
              nzTooltipTitle="Delete (Del)"
              i18n-nzTooltipTitle="@@workflows.props.delete"
            >
              <nz-icon nzType="delete" />
            </button>
          }
        </div>
        @for (e of issues()?.errors ?? []; track $index) {
          <nz-alert
            nzType="error"
            nzShowIcon
            [nzMessage]="(e.field ? e.field + ': ' : '') + e.message"
            class="issue"
          />
        }
        @for (w of issues()?.warnings ?? []; track $index) {
          <nz-alert nzType="warning" nzShowIcon [nzMessage]="w.message" class="issue" />
        }

        <form nz-form nzLayout="vertical" (submit)="$event.preventDefault()">
          @if (n.kind !== 'NOTE') {
            <nz-form-item>
              <nz-form-label nzRequired nzFor="pp-name" i18n="@@workflows.props.name"
                >Name</nz-form-label
              >
              <nz-form-control>
                <input
                  nz-input
                  id="pp-name"
                  name="name"
                  [ngModel]="n.name"
                  (ngModelChange)="patch({ name: $event })"
                  [readOnly]="readonly()"
                />
              </nz-form-control>
            </nz-form-item>
          }
          <nz-form-item>
            <nz-form-label nzFor="pp-id" i18n="@@workflows.props.id">Id</nz-form-label>
            <nz-form-control
              [nzValidateStatus]="idError() ? 'error' : ''"
              [nzErrorTip]="idError() ?? ''"
              [nzExtra]="idLocked() ? text.idLocked : text.idHint"
            >
              <input
                nz-input
                id="pp-id"
                name="id"
                class="mono"
                [ngModel]="idDraft()"
                (ngModelChange)="onIdChange($event)"
                [readOnly]="idLocked() || readonly()"
              />
            </nz-form-control>
          </nz-form-item>

          @switch (n.kind) {
            @case ('START') {
              <nz-divider
                nzText="Run inputs"
                i18n-nzText="@@workflows.props.runInputs"
                nzOrientation="left"
                nzPlain
              />
              <app-start-run-inputs [workflowId]="workflowId()" />
            }
            @case ('TASK') {
              <nz-divider
                nzText="Task template"
                i18n-nzText="@@workflows.props.taskTemplate"
                nzOrientation="left"
                nzPlain
              />
              <nz-form-item>
                <nz-form-label nzRequired nzFor="pp-template" i18n="@@workflows.props.template"
                  >Template</nz-form-label
                >
                <nz-form-control>
                  <nz-select
                    id="pp-template"
                    name="templateKey"
                    nzShowSearch
                    [ngModel]="n.task?.templateKey ?? null"
                    (ngModelChange)="setTemplate($event)"
                    nzPlaceHolder="Pick a template"
                    i18n-nzPlaceHolder="@@workflows.props.pickTemplate"
                    [disabled]="readonly()"
                  >
                    @for (g of templateGroups(); track g.title) {
                      <nz-option-group [nzLabel]="g.title">
                        @for (t of g.tasks; track t.key) {
                          <nz-option [nzValue]="t.key" [nzLabel]="templateOptionLabel(t)" />
                        }
                      </nz-option-group>
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              @if (resolving()) {
                <p class="muted small">
                  <nz-icon nzType="loading" nzSpin />
                  <span i18n="@@workflows.props.resolving">resolving template</span>
                </p>
              } @else if (resolved(); as r) {
                <div class="resolved">
                  <div
                    class="lineage"
                    aria-label="Template lineage"
                    i18n-aria-label="@@workflows.props.lineage"
                  >
                    @for (k of r.lineage ?? []; track k; let last = $last) {
                      <span class="mono" [class.leaf]="last">{{ k }}</span>
                      @if (!last) {
                        <nz-icon nzType="right" class="muted" />
                      }
                    }
                  </div>
                  <div class="chips">
                    @if (needsCodingAgent()) {
                      <nz-tag nzColor="purple" data-needs-coding-agent>{{
                        text.needsCoding
                      }}</nz-tag>
                    }
                    @if (templateDeprecated()) {
                      <nz-tag
                        nzColor="red"
                        nz-tooltip
                        [nzTooltipTitle]="text.deprecatedHint"
                        data-deprecated
                        >{{ text.deprecated }}</nz-tag
                      >
                    }
                    <nz-tag nzColor="blue" data-category>{{ categoryLabel(r.category) }}</nz-tag>
                    @for (p of r.ports ?? []; track p) {
                      <nz-tag>{{ p }}</nz-tag>
                    }
                  </div>
                  @if (r.check && r.check.ok === false) {
                    @for (p of r.check.problems ?? []; track p) {
                      <div class="danger small">{{ p }}</div>
                    }
                  }
                  @for (w of r.check?.warnings ?? []; track w) {
                    <div class="warn small" data-check-warning>{{ w }}</div>
                  }
                  <a
                    class="small"
                    [routerLink]="['/library/templates']"
                    [queryParams]="{ key: r.key }"
                    i18n="@@workflows.props.openInLibrary"
                    >Open in library</a
                  >
                </div>
              } @else if (resolveError()) {
                <p class="muted small">{{ resolveError() }}</p>
                @if (templateDeprecated()) {
                  <nz-tag nzColor="red" data-deprecated>{{ text.deprecated }}</nz-tag>
                }
              }

              <nz-divider [nzText]="instructionsTitle" nzOrientation="left" nzPlain />
              <ng-template #instructionsTitle
                ><span i18n="@@workflows.props.instructions">Instructions for this step</span>
                <bo-help-tip [text]="instructionsHelp"
              /></ng-template>
              <ng-template #instructionsHelp
                ><span i18n="@@workflows.props.instructions.help">
                  The template says what to do. Add notes for this step here; replace its
                  instructions only when this step needs a different task.
                </span></ng-template
              >
              <nz-form-item data-step-instructions>
                <nz-form-label
                  nzFor="pp-instr-append"
                  nzTooltipTitle="Added after the template's instructions, e.g. what to focus on in this workflow."
                  i18n-nzTooltipTitle="@@workflows.props.instructions.append.help"
                  i18n="@@workflows.props.instructions.append"
                  >Append</nz-form-label
                >
                <nz-form-control>
                  <textarea
                    nz-input
                    id="pp-instr-append"
                    class="mono-textarea"
                    name="instructionsAppend"
                    [ngModel]="instructions().append"
                    (ngModelChange)="setInstructions({ append: $event })"
                    [nzAutosize]="{ minRows: 3, maxRows: 12 }"
                    placeholder="Notes for this step"
                    i18n-placeholder="@@workflows.props.instructions.appendPlaceholder"
                    [readOnly]="readonly()"
                  ></textarea>
                </nz-form-control>
              </nz-form-item>
              <nz-collapse nzGhost class="replace" data-step-instructions-replace>
                <nz-collapse-panel
                  nzHeader="Replace (advanced)"
                  i18n-nzHeader="@@workflows.props.instructions.replace"
                  [nzActive]="replaceOpen()"
                  (nzActiveChange)="replaceOpen.set($event)"
                >
                  <nz-form-item>
                    <nz-form-label
                      nzFor="pp-instr-replace"
                      nzTooltipTitle="Used instead of the template's instructions for this step. The platform still adds the issue, earlier results, start inputs, feedback and answers."
                      i18n-nzTooltipTitle="@@workflows.props.instructions.replace.help"
                      i18n="@@workflows.props.instructions.replaceLabel"
                      >Instructions instead of the template's</nz-form-label
                    >
                    <nz-form-control>
                      <textarea
                        nz-input
                        id="pp-instr-replace"
                        class="mono-textarea"
                        name="instructionsReplace"
                        [ngModel]="instructions().replace"
                        (ngModelChange)="setInstructions({ replace: $event })"
                        [nzAutosize]="{ minRows: 4, maxRows: 16 }"
                        [readOnly]="readonly()"
                      ></textarea>
                    </nz-form-control>
                  </nz-form-item>
                </nz-collapse-panel>
              </nz-collapse>

              @if (parameterFields().length) {
                <nz-divider [nzText]="parametersTitle" nzOrientation="left" nzPlain />
                <ng-template #parametersTitle
                  ><span i18n="@@workflows.props.parameters">Parameters</span>
                  <bo-help-tip [text]="parametersHelp"
                /></ng-template>
                <ng-template #parametersHelp
                  ><span i18n="@@workflows.props.parameters.bindingHelp">
                    Where each parameter of the template takes its value from: a value set here, a
                    field of the run start form, or the result of an earlier step. Empty value: the
                    template's value or the default (shown greyed out).
                  </span></ng-template
                >
                <div data-step-parameters>
                  <app-step-parameter-bindings
                    [fields]="parameterFields()"
                    [overrides]="n.task?.overrides ?? null"
                    [artifacts]="earlierArtifacts()"
                    [readonly]="readonly()"
                    idPrefix="pp-param"
                    (overridesChange)="setOverrides($event)"
                  />
                </div>
              }

              @if (n.task?.templateKey) {
                <nz-collapse nzGhost class="step-gets">
                  <nz-collapse-panel
                    nzHeader="What this step gets"
                    i18n-nzHeader="@@workflows.props.stepGets"
                    [nzActive]="stepGetsOpen()"
                    (nzActiveChange)="stepGetsOpen.set($event)"
                    [nzExtra]="stepGetsTip"
                  >
                    <ng-template #stepGetsTip>
                      <bo-help-tip [text]="stepGetsHelp" (click)="$event.stopPropagation()" />
                    </ng-template>
                    <ng-template #stepGetsHelp
                      ><span i18n="@@workflows.props.stepGets.help">
                        The template with this step's overrides applied: exactly what the model or
                        the coding agent receives (before the run fills in the variables).
                      </span></ng-template
                    >
                    @if (stepResolving()) {
                      <p class="muted small">
                        <nz-icon nzType="loading" nzSpin />
                        <span i18n="@@workflows.props.stepGets.resolving">resolving the step</span>
                      </p>
                    }
                    @if (stepView(); as v) {
                      <dl class="gets" data-step-gets>
                        <dt i18n="@@workflows.props.stepGets.model">Model profile</dt>
                        <dd>{{ profileName(v.modelProfileId) }}</dd>
                        <dt i18n="@@workflows.props.stepGets.instructions">Instructions</dt>
                        <dd>
                          <pre class="prompt" tabindex="0" data-step-gets-instructions>{{
                            v.instructions || text.empty
                          }}</pre>
                        </dd>
                        <dt i18n="@@workflows.props.stepGets.skills">Skills</dt>
                        <dd class="chips">
                          @for (s of v.skills ?? []; track s.key) {
                            <nz-tag>{{ s.name || s.key }}</nz-tag>
                          } @empty {
                            <span class="muted small" i18n="@@workflows.props.stepGets.noSkills"
                              >none</span
                            >
                          }
                        </dd>
                        <dt i18n="@@workflows.props.stepGets.rules">Rules</dt>
                        <dd class="chips" data-step-rules>
                          @for (r of v.rules ?? []; track r.key) {
                            <nz-tag
                              [nzColor]="r.automatic ? 'gold' : 'default'"
                              [attr.data-rule]="r.key"
                              >{{ r.name || r.key }}
                              @if (r.automatic) {
                                <span i18n="@@workflows.props.stepGets.automatic">(automatic)</span>
                              }
                            </nz-tag>
                          } @empty {
                            <span class="muted small" i18n="@@workflows.props.stepGets.noRules"
                              >none</span
                            >
                          }
                        </dd>
                        @if (v.check && v.check.ok === false) {
                          <dt i18n="@@workflows.props.stepGets.problems">Problems</dt>
                          <dd>
                            @for (p of v.check.problems ?? []; track p) {
                              <div class="danger small">{{ p }}</div>
                            }
                          </dd>
                        }
                        @if (v.check?.warnings?.length) {
                          <dt i18n="@@workflows.props.stepGets.warnings">Warnings</dt>
                          <dd>
                            @for (w of v.check?.warnings ?? []; track w) {
                              <div class="warn small" data-step-warning>{{ w }}</div>
                            }
                          </dd>
                        }
                        <dt i18n="@@workflows.props.stepGets.config">Configuration</dt>
                        <dd>
                          <app-json-view [value]="v.config ?? {}" maxHeight="240px" />
                        </dd>
                        <dt i18n="@@workflows.props.stepGets.askedAtStart">Asked at start</dt>
                        <dd>
                          @for (f of v.runInputs ?? []; track f.name) {
                            <div class="run-input">
                              <span class="mono">{{ f.name }}</span>
                              <span>{{ f.label }}</span>
                              @if (f.required) {
                                <nz-tag nzColor="red" i18n="@@workflows.props.stepGets.required"
                                  >required</nz-tag
                                >
                              }
                            </div>
                          } @empty {
                            <span
                              class="muted small"
                              i18n="@@workflows.props.stepGets.noAskedAtStart"
                              >This step asks nothing at start.</span
                            >
                          }
                        </dd>
                      </dl>
                    } @else if (stepError()) {
                      <p class="muted small">{{ stepError() }}</p>
                    }
                  </nz-collapse-panel>
                </nz-collapse>
              }

              <nz-divider
                nzText="Overrides"
                i18n-nzText="@@workflows.props.overrides"
                nzOrientation="left"
                nzPlain
              />
              <nz-form-item>
                <nz-form-label
                  nzFor="pp-agent"
                  [nzTooltipTitle]="agentHelp()"
                  i18n="@@workflows.props.agent"
                  >Agent</nz-form-label
                >
                <nz-form-control
                  [nzValidateStatus]="agentMismatch() ? 'warning' : ''"
                  [nzExtra]="agentMismatch() ? text.agentMismatch : ''"
                >
                  <nz-select
                    id="pp-agent"
                    name="agentKey"
                    nzAllowClear
                    nzShowSearch
                    [nzPlaceHolder]="agentPlaceholder()"
                    [ngModel]="n.task?.overrides?.agentKey ?? null"
                    (ngModelChange)="patchOverrides({ agentKey: $event ?? null })"
                    [disabled]="readonly()"
                  >
                    @for (a of agentOptions(); track a.key) {
                      <nz-option [nzValue]="a.key" [nzLabel]="a.label" />
                    }
                    @if (missingAgentKey(); as key) {
                      <nz-option [nzValue]="key" [nzLabel]="key" nzDisabled />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              @if (effectiveAgent()) {
                <app-step-agent-section
                  [config]="n.task?.overrides?.config ?? null"
                  [readonly]="readonly()"
                  (configChange)="patchOverrides({ config: $event })"
                />
              }
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="Empty: the agent's model, else the defaults for its kind (project, starting user, platform)."
                  i18n-nzTooltipTitle="@@workflows.props.modelProfile.help"
                  nzFor="pp-model"
                  i18n="@@workflows.props.modelProfile"
                  >Model profile</nz-form-label
                >
                <nz-form-control>
                  @if (modelUse() && modelChoices() !== null) {
                    <app-model-select
                      name="modelProfileId"
                      selectId="pp-model"
                      [placeholder]="text.inherited"
                      [options]="modelChoices() ?? []"
                      [loading]="modelChoices() === undefined"
                      [ngModel]="n.task?.overrides?.modelProfileId ?? null"
                      (ngModelChange)="patchOverrides({ modelProfileId: $event ?? null })"
                      [disabled]="readonly()"
                    />
                  } @else {
                    <nz-select
                      id="pp-model"
                      name="modelProfileId"
                      nzAllowClear
                      nzShowSearch
                      nzPlaceHolder="Inherited"
                      i18n-nzPlaceHolder="@@workflows.props.inherited"
                      [ngModel]="n.task?.overrides?.modelProfileId ?? null"
                      (ngModelChange)="patchOverrides({ modelProfileId: $event ?? null })"
                      [disabled]="readonly()"
                    >
                      @for (p of profiles(); track p.id) {
                        <nz-option
                          [nzValue]="p.id"
                          [nzLabel]="p.displayName + ' (' + p.ref.modelId + ')'"
                        />
                      }
                    </nz-select>
                  }
                </nz-form-control>
              </nz-form-item>
              <nz-divider
                nzText="Config"
                i18n-nzText="@@workflows.props.config"
                nzOrientation="left"
                nzPlain
              />
              @if (configSchema(); as schema) {
                <app-schema-form
                  [schema]="schema"
                  [config]="formConfig()"
                  (configChange)="onSchemaConfig($event)"
                  idPrefix="pp-cfg"
                />
              } @else {
                <nz-form-item>
                  <nz-form-label
                    nzTooltipTitle="Deep-merged into the template config; null deletes a key."
                    i18n-nzTooltipTitle="@@workflows.props.configJson.extra"
                    nzFor="pp-cfg-json"
                    i18n="@@workflows.props.configJson"
                    >Config overrides (JSON)</nz-form-label
                  >
                  <nz-form-control>
                    <textarea
                      nz-input
                      id="pp-cfg-json"
                      class="mono-textarea"
                      name="config"
                      [ngModel]="configDraft()"
                      (ngModelChange)="onConfigJson($event)"
                      [nzAutosize]="{ minRows: 3, maxRows: 14 }"
                      [readOnly]="readonly()"
                    ></textarea>
                    @if (configError()) {
                      <div class="danger small">{{ configError() }}</div>
                    }
                  </nz-form-control>
                </nz-form-item>
              }
            }

            @case ('ACTION') {
              <nz-divider
                nzText="Action"
                i18n-nzText="@@workflows.props.action"
                nzOrientation="left"
                nzPlain
              />
              @if (actionDescriptor(); as a) {
                <div class="resolved" data-action-info>
                  <div class="chips">
                    <nz-tag nzColor="cyan" [attr.data-action]="a.type">{{
                      actionLabel(a.type, a.title)
                    }}</nz-tag>
                    <nz-tag i18n="@@workflows.props.action.noModel">no model</nz-tag>
                    @for (p of a.ports; track p) {
                      <nz-tag>{{ p }}</nz-tag>
                    }
                  </div>
                  <p class="muted small">{{ actionText(a.type, a.description) }}</p>
                </div>
              } @else {
                <p class="muted small" data-action-unknown>
                  <span i18n="@@workflows.props.action.unknown"
                    >This action is not offered for this project:</span
                  >
                  <span class="mono">{{ n.action?.type ?? '-' }}</span>
                </p>
              }

              @if (requiredCapabilities().length) {
                <nz-divider
                  nzText="Jira connection"
                  i18n-nzText="@@workflows.props.jiraConnection"
                  nzOrientation="left"
                  nzPlain
                />
                <app-node-connection-section
                  [projectId]="projectId()"
                  [requiredCapabilities]="requiredCapabilities()"
                  [config]="n.action?.config ?? null"
                  [readonly]="readonly()"
                  (configChange)="patchActionConfig($event)"
                />
              }

              @if (externalWrite()) {
                <nz-divider
                  nzText="Approval before writing to Jira"
                  i18n-nzText="@@workflows.props.writeApproval"
                  nzOrientation="left"
                  nzPlain
                />
                <app-write-approval-section
                  [config]="n.action?.config ?? null"
                  [readonly]="readonly()"
                  (configChange)="patchActionConfig($event)"
                />
              }

              @if (isDelivery()) {
                <nz-divider [nzText]="deliverReposTitle" nzOrientation="left" nzPlain />
                <ng-template #deliverReposTitle
                  ><span i18n="@@workflows.deliver.repositories">Repositories to deliver</span>
                  <bo-help-tip [text]="deliverReposHelp"
                /></ng-template>
                <ng-template #deliverReposHelp
                  ><span i18n="@@workflows.deliver.repositories.help">
                    Project repositories among the workflow's sources that get a branch and a merge
                    request. Empty: every one the agents changed. Library sources are never written
                    to.
                  </span></ng-template
                >
                <nz-form-item data-deliver-repositories>
                  <nz-form-control>
                    <nz-select
                      nzId="pp-deliver-repos"
                      name="deliverRepositories"
                      nzMode="multiple"
                      nzAllowClear
                      nzPlaceHolder="Every changed repository"
                      i18n-nzPlaceHolder="@@workflows.deliver.repositories.all"
                      aria-label="Repositories to deliver"
                      i18n-aria-label="@@workflows.deliver.repositories"
                      [ngModel]="deliverRepositories()"
                      (ngModelChange)="setDeliverRepositories($event)"
                      [nzDisabled]="readonly()"
                    >
                      @for (k of deliverOptions(); track k) {
                        <nz-option [nzValue]="k" [nzLabel]="k" />
                      }
                    </nz-select>
                  </nz-form-control>
                </nz-form-item>
              }

              <nz-divider
                nzText="Config"
                i18n-nzText="@@workflows.props.config"
                nzOrientation="left"
                nzPlain
              />
              @if (actionConfigSchema(); as schema) {
                <app-schema-form
                  [schema]="schema"
                  [config]="actionFormConfig()"
                  (configChange)="onActionSchemaConfig($event)"
                  idPrefix="pp-act"
                />
              } @else {
                <nz-form-item>
                  <nz-form-label
                    nzTooltipTitle="Missing keys take the action's defaults."
                    i18n-nzTooltipTitle="@@workflows.props.actionConfigJson.extra"
                    nzFor="pp-act-json"
                    i18n="@@workflows.props.actionConfigJson"
                    >Action config (JSON)</nz-form-label
                  >
                  <nz-form-control>
                    <textarea
                      nz-input
                      id="pp-act-json"
                      class="mono-textarea"
                      name="actionConfig"
                      [ngModel]="configDraft()"
                      (ngModelChange)="onConfigJson($event)"
                      [nzAutosize]="{ minRows: 3, maxRows: 14 }"
                      [readOnly]="readonly()"
                    ></textarea>
                    @if (configError()) {
                      <div class="danger small">{{ configError() }}</div>
                    }
                  </nz-form-control>
                </nz-form-item>
              }
            }

            @case ('DECISION') {
              <nz-divider [nzText]="branchesTitle" nzOrientation="left" nzPlain />
              <ng-template #branchesTitle
                ><span i18n="@@workflows.props.branches">Branches</span>
                <bo-help-tip [text]="branchesHelp"
              /></ng-template>
              <ng-template #branchesHelp
                ><span i18n="@@workflows.props.branches.hint">
                  Evaluated in order; the first true expression wins. Each branch id is an output
                  port.
                </span></ng-template
              >
              @for (b of n.decision?.branches ?? []; track $index; let i = $index) {
                <div class="branch">
                  <div class="branch-row">
                    <input
                      nz-input
                      nzSize="small"
                      class="mono"
                      style="width: 110px"
                      [ngModel]="b.id"
                      (ngModelChange)="patchBranch(i, { id: $event })"
                      [name]="'bid' + i"
                      placeholder="id"
                      i18n-placeholder="@@workflows.props.branchIdPlaceholder"
                      aria-label="Branch id"
                      i18n-aria-label="@@workflows.props.branchId"
                      [readOnly]="readonly()"
                    />
                    <input
                      nz-input
                      nzSize="small"
                      style="flex: 1"
                      [ngModel]="b.label"
                      (ngModelChange)="patchBranch(i, { label: $event })"
                      [name]="'blabel' + i"
                      placeholder="label"
                      i18n-placeholder="@@workflows.props.branchLabelPlaceholder"
                      aria-label="Branch label"
                      i18n-aria-label="@@workflows.props.branchLabel"
                      [readOnly]="readonly()"
                    />
                    @if (!readonly()) {
                      <button
                        nz-button
                        nzSize="small"
                        nzType="text"
                        nzDanger
                        type="button"
                        (click)="removeBranch(i)"
                        aria-label="Remove branch"
                        i18n-aria-label="@@workflows.props.removeBranch"
                      >
                        <nz-icon nzType="delete" />
                      </button>
                    }
                  </div>
                  <app-expression-editor
                    [value]="b.expression"
                    [fields]="stateFields()"
                    (valueChange)="patchBranch(i, { expression: $event })"
                    [ariaLabel]="branchExpressionLabel(b.id)"
                  />
                </div>
              }
              @if (!readonly()) {
                <button
                  nz-button
                  nzType="dashed"
                  nzSize="small"
                  nzBlock
                  type="button"
                  (click)="addBranch()"
                >
                  <nz-icon nzType="plus" />
                  <span i18n="@@workflows.props.addBranch">Add branch</span>
                </button>
              }
              <nz-form-item style="margin-top: 12px">
                <nz-form-label nzFor="pp-default" i18n="@@workflows.props.defaultBranch"
                  >Default branch</nz-form-label
                >
                <nz-form-control
                  nzExtra="Port taken when no expression is true: a branch id, or a port of its own such as 'no' or 'otherwise'. Empty: the run fails."
                  i18n-nzExtra="@@workflows.props.defaultBranch.extra"
                >
                  <input
                    nz-input
                    class="mono"
                    id="pp-default"
                    name="defaultBranch"
                    list="pp-default-options"
                    placeholder="none (the run fails)"
                    i18n-placeholder="@@workflows.props.defaultBranch.placeholder"
                    autocomplete="off"
                    [ngModel]="n.decision?.defaultBranch ?? ''"
                    (ngModelChange)="patchDecision({ defaultBranch: $event?.trim() || null })"
                    [readOnly]="readonly()"
                  />
                  <datalist id="pp-default-options">
                    @for (b of n.decision?.branches ?? []; track b.id) {
                      <option [value]="b.id"></option>
                    }
                  </datalist>
                </nz-form-control>
              </nz-form-item>
              @if (stateFields().length) {
                <nz-collapse nzGhost>
                  <nz-collapse-panel
                    nzHeader="State fields available in expressions"
                    i18n-nzHeader="@@workflows.props.stateFields"
                  >
                    <ul class="fields">
                      @for (f of stateFields(); track f.path) {
                        <li>
                          <span class="mono">{{ f.path }}</span>
                          <span class="muted small">{{ f.description }}</span>
                        </li>
                      }
                    </ul>
                  </nz-collapse-panel>
                </nz-collapse>
              }
            }

            @case ('HUMAN_GATE') {
              <nz-divider
                nzText="Gate"
                i18n-nzText="@@workflows.props.gate"
                nzOrientation="left"
                nzPlain
              />
              <nz-form-item>
                <nz-form-label
                  [nzTooltipTitle]="text.questionHint"
                  nzRequired
                  nzFor="pp-question"
                  i18n="@@workflows.props.question"
                  >Question</nz-form-label
                >
                <nz-form-control>
                  <textarea
                    nz-input
                    id="pp-question"
                    name="question"
                    [ngModel]="n.gate?.question ?? ''"
                    (ngModelChange)="patchGate({ question: $event })"
                    [nzAutosize]="{ minRows: 2, maxRows: 6 }"
                    [readOnly]="readonly()"
                  ></textarea>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzRequired nzFor="pp-options" i18n="@@workflows.props.options"
                  >Options (ports)</nz-form-label
                >
                <nz-form-control>
                  <nz-select
                    id="pp-options"
                    name="options"
                    nzMode="tags"
                    [ngModel]="n.gate?.options ?? []"
                    (ngModelChange)="patchGate({ options: $event })"
                    nzPlaceHolder="approve, revise, reject"
                    i18n-nzPlaceHolder="@@workflows.props.options.placeholder"
                    [disabled]="readonly()"
                  >
                    <nz-option
                      nzValue="approve"
                      nzLabel="approve"
                      i18n-nzLabel="@@workflows.props.option.approve"
                    />
                    <nz-option
                      nzValue="revise"
                      nzLabel="revise"
                      i18n-nzLabel="@@workflows.props.option.revise"
                    />
                    <nz-option
                      nzValue="reject"
                      nzLabel="reject"
                      i18n-nzLabel="@@workflows.props.option.reject"
                    />
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="Artefact names shown (and editable) in the inbox."
                  i18n-nzTooltipTitle="@@workflows.props.showArtifacts.extra"
                  nzFor="pp-artifacts"
                  i18n="@@workflows.props.showArtifacts"
                  >Show artefacts</nz-form-label
                >
                <nz-form-control>
                  <nz-select
                    id="pp-artifacts"
                    name="showArtifacts"
                    nzMode="tags"
                    [ngModel]="n.gate?.showArtifacts ?? []"
                    (ngModelChange)="patchGate({ showArtifacts: $event })"
                    nzPlaceHolder="spec, plan, review"
                    i18n-nzPlaceHolder="@@workflows.props.showArtifacts.placeholder"
                    [disabled]="readonly()"
                  >
                    @for (a of KNOWN_ARTIFACTS; track a) {
                      <nz-option [nzValue]="a" [nzLabel]="a" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="pp-gate-timeout" i18n="@@workflows.props.timeoutSeconds"
                  >Timeout (seconds)</nz-form-label
                >
                <nz-form-control>
                  <nz-input-number
                    id="pp-gate-timeout"
                    name="gateTimeout"
                    [ngModel]="n.gate?.timeoutSeconds ?? null"
                    (ngModelChange)="patchGate({ timeoutSeconds: $event ?? null })"
                    [nzMin]="1"
                    [nzStep]="60"
                    style="width: 160px"
                    [disabled]="readonly()"
                  />
                </nz-form-control>
              </nz-form-item>
              <app-gate-rules-editor
                [approvers]="n.gate?.approvers ?? null"
                [checklist]="n.gate?.checklist ?? []"
                [readonly]="readonly()"
                (approversChange)="patchGate({ approvers: $event })"
                (checklistChange)="patchGate({ checklist: $event })"
              />
            }

            @case ('WAIT_FOR_CI') {
              <nz-divider
                nzText="CI signal"
                i18n-nzText="@@workflows.props.ciSignal"
                nzOrientation="left"
                nzPlain
              />
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="The CI job calls: backoffice-ci signal <name> --status success|fail"
                  i18n-nzTooltipTitle="@@workflows.props.signal.extra"
                  nzRequired
                  nzFor="pp-signal"
                  i18n="@@workflows.props.signal"
                  >Signal</nz-form-label
                >
                <nz-form-control>
                  <input
                    nz-input
                    id="pp-signal"
                    name="signal"
                    class="mono"
                    [ngModel]="n.ci?.signal ?? ''"
                    (ngModelChange)="patchCi({ signal: $event })"
                    placeholder="tests"
                    i18n-placeholder="@@workflows.props.signal.placeholder"
                    [readOnly]="readonly()"
                  />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="After the timeout the 'timeout' port is taken."
                  i18n-nzTooltipTitle="@@workflows.props.ciTimeout.extra"
                  nzFor="pp-ci-timeout"
                  i18n="@@workflows.props.timeoutSeconds"
                  >Timeout (seconds)</nz-form-label
                >
                <nz-form-control>
                  <nz-input-number
                    id="pp-ci-timeout"
                    name="ciTimeout"
                    [ngModel]="n.ci?.timeoutSeconds ?? null"
                    (ngModelChange)="patchCi({ timeoutSeconds: $event ?? null })"
                    [nzMin]="1"
                    [nzStep]="60"
                    style="width: 160px"
                    [disabled]="readonly()"
                  />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="Back Office reads the pipeline of the run's branch itself (CI_READ token of the repository); a failure sends the failed jobs and their logs to the next step. Your CI sends no signal."
                  i18n-nzTooltipTitle="@@workflows.props.ciWatch.extra"
                  nzFor="pp-ci-watch"
                  i18n="@@workflows.props.ciWatch"
                  >Watch pipeline</nz-form-label
                >
                <nz-form-control>
                  <nz-switch
                    id="pp-ci-watch"
                    name="ciWatch"
                    [ngModel]="n.ci?.watchPipeline ?? false"
                    (ngModelChange)="patchCi({ watchPipeline: $event || null })"
                    [nzDisabled]="readonly()"
                  />
                </nz-form-control>
              </nz-form-item>
              @if (n.ci?.watchPipeline) {
                <nz-form-item>
                  <nz-form-label
                    nzTooltipTitle="Empty = the primary repository."
                    i18n-nzTooltipTitle="@@workflows.props.ciRepository.extra"
                    nzFor="pp-ci-repo"
                    i18n="@@workflows.props.ciRepository"
                    >Repository</nz-form-label
                  >
                  <nz-form-control>
                    <nz-select
                      nzId="pp-ci-repo"
                      name="ciRepository"
                      nzAllowClear
                      [ngModel]="n.ci?.repository ?? null"
                      (ngModelChange)="patchCi({ repository: $event ?? null })"
                      [nzDisabled]="readonly()"
                      style="width: 220px"
                    >
                      @for (r of projectRepositories(); track r.key) {
                        <nz-option [nzValue]="r.key" [nzLabel]="r.key" />
                      }
                    </nz-select>
                  </nz-form-control>
                </nz-form-item>
              }
            }

            @case ('END') {
              <nz-divider
                nzText="Outcome"
                i18n-nzText="@@workflows.props.outcome"
                nzOrientation="left"
                nzPlain
              />
              <nz-form-item>
                <nz-form-control
                  nzExtra="SUCCESS passes a CI gate; FAILURE fails it and marks the run outcome."
                  i18n-nzExtra="@@workflows.props.outcome.extra"
                >
                  <nz-radio-group
                    [ngModel]="n.end?.outcome ?? 'SUCCESS'"
                    (ngModelChange)="patch({ end: { outcome: $event } })"
                    name="outcome"
                    [disabled]="readonly()"
                  >
                    <label nz-radio-button nzValue="SUCCESS" i18n="@@workflows.props.success"
                      >Success</label
                    >
                    <label nz-radio-button nzValue="FAILURE" i18n="@@workflows.props.failure"
                      >Failure</label
                    >
                  </nz-radio-group>
                </nz-form-control>
              </nz-form-item>
            }

            @case ('NOTE') {
              <nz-form-item>
                <nz-form-label nzFor="pp-note" i18n="@@workflows.props.noteText"
                  >Text</nz-form-label
                >
                <nz-form-control>
                  <textarea
                    nz-input
                    id="pp-note"
                    name="note"
                    [ngModel]="n.note?.text ?? ''"
                    (ngModelChange)="patch({ note: { text: $event } })"
                    [nzAutosize]="{ minRows: 4, maxRows: 16 }"
                    [readOnly]="readonly()"
                  ></textarea>
                </nz-form-control>
              </nz-form-item>
            }
          }

          @if (n.kind !== 'START' && n.kind !== 'END' && n.kind !== 'NOTE') {
            <nz-divider
              nzText="Execution"
              i18n-nzText="@@workflows.props.execution"
              nzOrientation="left"
              nzPlain
            />
            <div class="grid2">
              <nz-form-item>
                <nz-form-label
                  nzTooltipTitle="Loop guard."
                  i18n-nzTooltipTitle="@@workflows.props.maxVisits.extra"
                  nzFor="pp-max"
                  i18n="@@workflows.props.maxVisits"
                  >Max visits</nz-form-label
                >
                <nz-form-control>
                  <nz-input-number
                    id="pp-max"
                    name="maxVisits"
                    [ngModel]="n.maxVisits"
                    (ngModelChange)="patch({ maxVisits: $event ?? 3 })"
                    [nzMin]="1"
                    [nzMax]="50"
                    [nzPrecision]="0"
                    style="width: 100%"
                    [disabled]="readonly()"
                  />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="pp-timeout" i18n="@@workflows.props.timeout"
                  >Timeout (s)</nz-form-label
                >
                <nz-form-control>
                  <nz-input-number
                    id="pp-timeout"
                    name="timeoutSeconds"
                    [ngModel]="n.timeoutSeconds ?? null"
                    (ngModelChange)="patch({ timeoutSeconds: $event ?? null })"
                    [nzMin]="1"
                    [nzStep]="60"
                    style="width: 100%"
                    [disabled]="readonly()"
                  />
                </nz-form-control>
              </nz-form-item>
            </div>
          }

          <nz-divider
            nzText="Ports"
            i18n-nzText="@@workflows.props.ports"
            nzOrientation="left"
            nzPlain
          />
          <div class="chips">
            @for (p of ports(); track p) {
              <nz-tag>{{ p }}</nz-tag>
            }
            @if (!ports().length) {
              <span class="muted small" i18n="@@workflows.props.noPorts">No output ports.</span>
            }
          </div>
        </form>
      </div>
    }
  `,
  styles: [
    `
      .panel {
        padding: 12px 14px 24px;
      }
      .head {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 15px;
        font-weight: 600;
      }
      .spacer {
        flex: 1;
      }
      .small {
        font-size: 11px;
      }
      .issue {
        margin-bottom: 6px;
      }
      .grid2 {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 0 10px;
      }
      nz-divider {
        margin: 10px 0 8px;
      }
      .resolved {
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        border-radius: 6px;
        padding: 8px;
        margin-bottom: 8px;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .lineage {
        display: flex;
        gap: 4px;
        align-items: center;
        flex-wrap: wrap;
        font-size: 11px;
      }
      .lineage .leaf {
        font-weight: 600;
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      .branch {
        border: 1px solid var(--bo-line);
        border-radius: 6px;
        padding: 6px;
        margin-bottom: 6px;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .branch-row {
        display: flex;
        gap: 4px;
        align-items: center;
      }
      .fields {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      nz-radio-group {
        display: block;
        margin-bottom: 6px;
      }
      .step-gets {
        margin-bottom: 8px;
      }
      .step-gets ::ng-deep .ant-collapse-header {
        padding-left: 0 !important;
        font-weight: 600;
      }
      .step-gets ::ng-deep .ant-collapse-content-box {
        padding: 0 !important;
      }
      .gets {
        margin: 0;
      }
      .gets dt {
        font-size: 11px;
        font-weight: 600;
        color: var(--bo-ink-2);
        margin: 8px 0 2px;
      }
      .gets dd {
        margin: 0;
      }
      .prompt {
        white-space: pre-wrap;
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: 4px;
        padding: 8px;
        margin: 0;
        max-height: 240px;
        overflow: auto;
      }
      .warn {
        color: var(--bo-you-ink);
      }
      .run-input {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        align-items: center;
        font-size: 12px;
        padding: 2px 0;
      }
    `,
  ],
})
export class NodePropertyPanelComponent {
  private readonly library = inject(TaskLibraryApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly node = input<WorkflowNode | null>(null);
  readonly allIds = input<string[]>([]);
  /** The nodes of the workflow (ADR 0062: the artefacts of earlier steps a parameter may read). */
  readonly nodes = input<readonly WorkflowNode[]>([]);
  /** The edges of the workflow (which nodes come before the step). */
  readonly edges = input<readonly WorkflowEdge[]>([]);
  /** ADR 0062: what each template key produces (its lineage included), from the library. */
  readonly templateProduces = input<ReadonlyMap<string, readonly string[]>>(new Map());
  readonly palette = input<WorkflowPalette | null>(null);
  /** ADR 0054: keys of deprecated templates (not offered; a step using one is tagged). */
  readonly deprecatedKeys = input<ReadonlySet<string>>(new Set());
  readonly issues = input<Issues | null>(null);
  readonly profiles = input<ModelProfile[]>([]);
  /**
   * Model options per step use (ADR 0050, key `stepModelUseKey`), cached by the page: missing = not loaded yet,
   * null = could not be loaded (the plain profile list is shown instead).
   */
  readonly modelOptions = input<Record<string, ModelOption[] | null>>({});
  /** ADR 0056: the agents a step of this project may choose (global plus the project's). */
  readonly agents = input<Agent[]>([]);
  readonly projectId = input<string | null>(null);
  /** ADR 0053: the saved workflow, for the START node's read-only run inputs; null while unsaved. */
  readonly workflowId = input<string | null>(null);
  /** The project's repositories (ADR 0034), for the delivery and the CI pipeline watch. */
  readonly projectRepositories = input<readonly ProjectRepository[]>([]);
  /** ADR 0063: the workflow's sources (empty = the project's primary repository), for "Deliver changes". */
  readonly sources = input<readonly WorkflowSource[] | null | undefined>([]);
  /** Ids of nodes that exist in the saved version (renaming them is not allowed). */
  readonly idLocked = input(false);
  readonly readonly = input(false);

  readonly nodeChange = output<WorkflowNode>();
  readonly remove = output<void>();
  readonly duplicate = output<void>();
  /** The node needs the model options of this step use and they are not cached yet. */
  readonly modelOptionsNeeded = output<StepModelUse>();

  readonly text = {
    idLocked: $localize`:@@workflows.props.id.locked:Ids of saved nodes are fixed (runs and logs reference them).`,
    inherited: $localize`:@@workflows.props.inherited:Inherited`,
    idHint: $localize`:@@workflows.props.id.hint:Slug, unique in the workflow; edges follow a rename.`,
    questionHint: $localize`:@@workflows.props.question.hint:Template; ${'{{issue.key}}'}:example1:, ${'{{review.score}}'}:example2: ... are rendered.`,
    deprecated: deprecatedLabel(),
    deprecatedHint: deprecatedHint(),
    empty: $localize`:@@workflows.props.stepGets.empty:(empty)`,
    needsCoding: neededAgentLabel(true),
    agentMismatch: $localize`:@@workflows.props.agent.mismatch:The template needs a coding agent: this step cannot run with a model answer agent.`,
  };
  readonly categoryLabel = categoryLabel;
  readonly agentKindDescription = agentKindDescription;
  readonly actionLabel = actionTitle;
  readonly actionText = actionDescription;

  readonly KNOWN_ARTIFACTS = [
    'spec',
    'clarifications',
    'plan',
    'tasks',
    'analysis',
    'diff',
    'review',
  ];

  readonly meta = computed(() => NODE_KIND_META[this.node()?.kind ?? 'NOTE']);
  readonly ports = computed(() => {
    const n = this.node();
    if (!n) return [];
    const resolved = this.resolved();
    if (n.kind === 'TASK' && resolved?.ports?.length) return resolved.ports;
    return portsFor(n, indexPalette(this.palette()));
  });
  readonly stateFields = computed(() => this.palette()?.stateFields ?? []);
  readonly templateGroups = computed(() => {
    const p = this.palette();
    // ADR 0055: grouped by category, in display order
    const groups = new Map<string, { title: string; tasks: PaletteTask[] }>();
    // ADR 0054: deprecated templates are not offered; the step's own template stays selectable.
    const current = this.node()?.task?.templateKey;
    const hidden = this.deprecatedKeys();
    for (const t of p?.tasks ?? []) {
      if (t.key !== current && deprecatedPaletteTask(t, hidden)) continue;
      const k = t.category || 'GENERAL';
      if (!groups.has(k)) groups.set(k, { title: categoryLabel(k), tasks: [] });
      groups.get(k)!.tasks.push(t);
    }
    const ordered = new Map(
      [...groups.entries()].sort(([a], [b]) => categoryOrder(a) - categoryOrder(b)),
    );
    // A template the palette no longer offers (deprecated) still shows as the step's choice.
    if (current && !(p?.tasks ?? []).some((t) => t.key === current)) {
      const r = this.resolved();
      ordered.set('\u0000current', {
        title: $localize`:@@workflows.props.currentGroup:Current template`,
        tasks: [
          {
            key: current,
            name: r?.key === current ? (r.name ?? current) : current,
          },
        ],
      });
    }
    return Array.from(ordered.values());
  });
  /** ADR 0054: the step's template carries the `deprecated` tag. */
  readonly templateDeprecated = computed(() => {
    const key = this.node()?.task?.templateKey;
    if (!key) return false;
    const t = this.palette()?.tasks?.find((x) => x.key === key);
    return this.deprecatedKeys().has(key) || (!!t && deprecatedPaletteTask(t));
  });
  readonly configSchema = computed<JsonSchema | null>(() => {
    const key = this.node()?.task?.templateKey;
    const t = key ? this.palette()?.tasks?.find((x) => x.key === key) : undefined;
    const schema = t?.configSchema as JsonSchema | undefined;
    // ADR 0060: the directory, the session and the questions have their own controls.
    // ADR 0063: git, the step repositories, the setup command and Spec Kit left the step.
    return localizedConfigSchema(schema, [
      ...REMOVED_STEP_KEYS,
      ...CODING_STEP_KEYS,
      ...QUESTION_KEYS,
    ]);
  });

  // ---------------------------------------------------------------- ADR 0056: actions

  /** The palette descriptor of an ACTION node's action. */
  readonly actionDescriptor = computed<ActionDescriptor | null>(() => {
    const n = this.node();
    const type = n?.kind === 'ACTION' ? n.action?.type : null;
    return (type ? this.palette()?.actions?.find((a) => a.type === type) : undefined) ?? null;
  });
  /** Connection capabilities the node's action needs (Jira actions, ADR 0030). */
  readonly requiredCapabilities = computed<string[]>(
    () => (this.actionDescriptor()?.requiredCapabilities as string[] | undefined) ?? [],
  );
  /** The action writes to Jira: approval before write applies (ADR 0033). */
  readonly externalWrite = computed<boolean>(() => this.actionDescriptor()?.externalWrite === true);
  /** ADR 0063: the node is a "Deliver changes" action. */
  readonly isDelivery = computed(() => {
    const n = this.node();
    return n?.kind === 'ACTION' && n.action?.type === DELIVER_CHANGES;
  });
  /** Form of the action's config, without the keys that have their own section. */
  readonly actionConfigSchema = computed<JsonSchema | null>(() => {
    const server = this.actionDescriptor()?.configSchema as JsonSchema | undefined;
    // ADR 0063: the delivery form renders (translated) even when the palette has no schema for it
    if (this.isDelivery()) return deliverChangesSchema(server, this.node()?.action?.config);
    return withoutKeys(server, [JIRA_CONNECTION_KEY, ...SECTION_CONFIG_KEYS]);
  });
  /** ADR 0063: the repositories the delivery writes to (empty = every changed one). */
  readonly deliverRepositories = computed(() => deliverRepositories(this.node()?.action?.config));
  /** ADR 0063: project repositories among the workflow's sources, plus keys the node names already. */
  readonly deliverOptions = computed(() => {
    const keys = deliverableRepositories(this.sources(), this.projectRepositories());
    for (const k of this.deliverRepositories()) if (!keys.includes(k)) keys.push(k);
    return keys;
  });
  /** Action config shown in the schema form: without the pin and the write approval. */
  readonly actionFormConfig = computed<Record<string, unknown>>(() => {
    if (this.isDelivery()) return deliverFormConfig(this.node()?.action?.config);
    const rest = { ...(withPinnedConnection(this.node()?.action?.config ?? {}, null) ?? {}) };
    for (const k of SECTION_CONFIG_KEYS) delete rest[k];
    return rest;
  });

  // ---------------------------------------------------------------- ADR 0056: agents

  /**
   * ADR 0061: the step's template needs a coding agent (palette; for a template the palette no
   * longer offers, its resolved config).
   */
  readonly needsCodingAgent = computed(() => {
    const t = this.paletteTask();
    if (t) return !!t.needsCodingAgent;
    const r = this.resolved();
    return !!r && configNeedsCodingAgent(r.config);
  });
  /** The agent that runs the step: its own (`overrides.agentKey`), else the default for its template. */
  readonly effectiveAgent = computed<AgentRef | null>(() =>
    effectiveStepAgent(
      this.node()?.task?.templateKey ? { needsCodingAgent: this.needsCodingAgent() } : null,
      this.node()?.task?.overrides?.agentKey,
      this.agents(),
    ),
  );
  /** ADR 0061: the template needs a coding agent and the step's agent is a model answer. */
  readonly agentMismatch = computed(() => {
    const a = this.effectiveAgent();
    return this.needsCodingAgent() && !!a && !a.harness;
  });
  /** The agents a step may choose, "name (kind)". */
  readonly agentOptions = computed(() =>
    uniqueAgents(this.agents()).map((a) => ({
      key: a.key ?? '',
      label: `${a.name} (${agentKindLabel(a.harness)})`,
    })),
  );
  /** A step agent key that is not among the loaded agents. */
  readonly missingAgentKey = computed(() => {
    const key = this.node()?.task?.overrides?.agentKey;
    return key && !this.agentOptions().some((a) => a.key === key) ? key : null;
  });
  /** Placeholder of the step's agent select: the agent a step without one runs with. */
  readonly agentPlaceholder = computed(() => {
    const a = effectiveStepAgent(
      { needsCodingAgent: this.needsCodingAgent() },
      null,
      this.agents(),
    );
    const name = a?.name ?? defaultStepAgentKey(this.needsCodingAgent());
    return $localize`:@@workflows.props.agent.defaultAgent:Default: ${name}:agent:`;
  });
  /** Tooltip of the agent label: who runs the step and how the chosen kind works. */
  readonly agentHelp = computed(() => {
    const a = this.effectiveAgent();
    const base = $localize`:@@workflows.props.agent.whoRuns:Who runs this step. Empty: OpenCode when the template needs a coding agent, else Model answer.`;
    return a ? `${base} ${agentKindLabel(a.harness)}: ${agentKindDescription(a.harness)}` : base;
  });
  // ---------------------------------------------------------------- ADR 0058/0062: instructions, parameters

  /** The step's parameters with its own values and the inherited ones. */
  readonly parameterFields = computed(() => {
    const n = this.node();
    if (n?.kind !== 'TASK' || !n.task?.templateKey) return [];
    return stepParameterFields(this.paletteTask(), this.resolved(), n.task.overrides);
  });
  /** ADR 0062: the artefacts the steps before this one produce (offered for "Result of an earlier step"). */
  readonly earlierArtifacts = computed(() => {
    const n = this.node();
    if (!n) return [];
    const produces = this.templateProduces();
    const actions = this.palette()?.actions ?? [];
    return earlierArtifacts(this.nodes(), this.edges(), n.id, (other) =>
      other.kind === 'ACTION'
        ? actions.find((a) => a.type === other.action?.type)?.produces
        : produces.get(other.task?.templateKey ?? ''),
    );
  });
  /** ADR 0062: the step's instructions (legacy prompt overrides read as append / replace). */
  readonly instructions = computed<StepInstructions>(() =>
    stepInstructions(this.node()?.task?.overrides),
  );
  /** "Replace (advanced)" is expanded; opened for a step that replaces the instructions. */
  readonly replaceOpen = signal(false);

  setInstructions(change: Partial<StepInstructions>): void {
    this.setOverrides(withInstructions(this.node()?.task?.overrides, change));
  }

  /** Replaces the step's overrides (bindings and instructions write the whole object). */
  setOverrides(overrides: TaskOverrides): void {
    const n = this.node();
    if (!n) return;
    this.patch({ task: { templateKey: n.task?.templateKey ?? '', overrides } });
  }

  templateOptionLabel(t: PaletteTask): string {
    return t.name || t.key || '';
  }
  /** The palette entry of the node's template. */
  readonly paletteTask = computed<PaletteTask | null>(() => {
    const key = this.node()?.task?.templateKey;
    return (key ? this.palette()?.tasks?.find((x) => x.key === key) : undefined) ?? null;
  });
  /** How the node's step uses a model (ADR 0050): step kind and coding harness; null = no model. */
  readonly modelUse = computed<StepModelUse | null>(
    () =>
      stepModelUse(
        this.effectiveAgent(),
        this.node()?.task?.overrides?.config,
        this.resolved()?.config,
      ),
    { equal: (a, b) => (a ? stepModelUseKey(a) : null) === (b ? stepModelUseKey(b) : null) },
  );
  /** Options of the node's step use: undefined while loading, null when they could not be loaded. */
  readonly modelChoices = computed<ModelOption[] | null | undefined>(() => {
    const use = this.modelUse();
    return use ? this.modelOptions()[stepModelUseKey(use)] : null;
  });
  /** Steps a coding agent runs are runner steps (ADR 0056). */
  readonly runnerStep = computed(() => isCodingAgent(this.effectiveAgent()));
  /**
   * Config keys with their own section or no longer edited (ADR 0063: git, repositories, setup command, Spec Kit);
   * kept out of the schema form and kept as they are on schema changes.
   */
  private readonly ownSectionKeys = computed<string[]>(() => [
    ...REMOVED_STEP_KEYS,
    ...(this.runnerStep() ? CODING_STEP_KEYS : []),
    ...(this.effectiveAgent() ? QUESTION_KEYS : []),
  ]);
  /** Config shown in the schema form: without the keys of the own sections. */
  readonly formConfig = computed<Record<string, unknown>>(() => {
    const config = this.node()?.task?.overrides?.config ?? {};
    const own = this.ownSectionKeys();
    if (!own.length) return config;
    const rest = { ...config };
    for (const k of own) delete rest[k];
    return rest;
  });
  readonly idDraft = signal('');
  readonly idError = signal<string | null>(null);
  readonly configDraft = signal('');
  readonly configError = signal<string | null>(null);

  readonly resolved = signal<ResolvedTask | null>(null);
  readonly resolving = signal(false);
  readonly resolveError = signal<string | null>(null);
  private readonly resolve$ = new Subject<string | null>();

  /** ADR 0054: "What this step gets" is expanded (kept while other nodes are selected). */
  readonly stepGetsOpen = signal(false);
  /** The template with the step's overrides applied (POST resolve); null while closed. */
  readonly stepView = signal<ResolvedTask | null>(null);
  readonly stepResolving = signal(false);
  readonly stepError = signal<string | null>(null);
  private readonly step$ = new Subject<StepResolveRequest | null>();

  constructor() {
    // ADR 0054: resolve the step with its overrides while the section is open, debounced.
    effect(() => {
      const n = this.node();
      const key = n?.kind === 'TASK' ? n.task?.templateKey : null;
      const request: StepResolveRequest | null =
        this.stepGetsOpen() && key
          ? { key, overrides: n?.task?.overrides ?? {}, projectId: this.projectId() }
          : null;
      untracked(() => this.step$.next(request));
    });
    this.step$
      .pipe(
        debounceTime(300),
        distinctUntilChanged((a, b) => JSON.stringify(a) === JSON.stringify(b)),
        switchMap((req) => {
          this.stepError.set(null);
          if (!req) {
            this.stepView.set(null);
            this.stepResolving.set(false);
            return of(undefined);
          }
          this.stepResolving.set(true);
          return this.library.resolveStep(req.key, req.overrides, req.projectId).pipe(
            catchError(() => {
              this.stepError.set(
                $localize`:@@workflows.props.stepGets.error:Could not resolve this step with its overrides.`,
              );
              return of(null);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => {
        if (r === undefined) return;
        this.stepView.set(r);
        this.stepResolving.set(false);
      });

    effect(() => {
      const use = this.modelUse();
      if (use && !(stepModelUseKey(use) in this.modelOptions()))
        untracked(() => this.modelOptionsNeeded.emit(use));
    });
    // Reset drafts when another node is selected.
    effect(() => {
      const n = this.node();
      untracked(() => {
        this.idDraft.set(n?.id ?? '');
        this.idError.set(null);
        const config = n?.kind === 'ACTION' ? n.action?.config : n?.task?.overrides?.config;
        this.configDraft.set(JSON.stringify(config ?? {}, null, 2));
        this.configError.set(null);
        this.replaceOpen.set(!!stepInstructions(n?.task?.overrides).replace.trim());
        this.resolve$.next(n?.kind === 'TASK' ? (n.task?.templateKey ?? null) : null);
      });
    });
    this.resolve$
      .pipe(
        switchMap((key) => {
          this.resolved.set(null);
          this.resolveError.set(null);
          if (!key) return of(null);
          this.resolving.set(true);
          return this.library.resolveTemplate(key, this.projectId()).pipe(
            catchError(() => {
              this.resolveError.set(
                $localize`:@@workflows.props.resolveError:Template could not be resolved for this project.`,
              );
              return of(null);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => {
        this.resolved.set(r);
        this.resolving.set(false);
      });
  }

  // ---------------------------------------------------------------- patch helpers

  patch(changes: Partial<WorkflowNode>): void {
    const n = this.node();
    if (!n || this.readonly()) return;
    this.nodeChange.emit({ ...n, ...changes });
  }

  onIdChange(value: string): void {
    this.idDraft.set(value);
    const n = this.node();
    if (!n) return;
    if (!NODE_ID_PATTERN.test(value)) {
      this.idError.set(
        $localize`:@@workflows.props.id.invalid:Lowercase slug: letters, digits, dashes; max 40 chars`,
      );
      return;
    }
    if (value !== n.id && this.allIds().includes(value)) {
      this.idError.set($localize`:@@workflows.props.id.taken:Another node already uses this id`);
      return;
    }
    this.idError.set(null);
    if (value !== n.id) this.patch({ id: value });
  }

  setTemplate(key: string | null): void {
    const n = this.node();
    if (!n) return;
    const t = this.palette()?.tasks?.find((x) => x.key === key);
    // ADR 0061: a step without an agent, or with one of the wrong kind, gets a fitting agent
    const overrides = { ...(n.task?.overrides ?? {}) };
    const own = overrides.agentKey
      ? effectiveStepAgent(t ?? null, overrides.agentKey, this.agents())
      : null;
    if (t && (!overrides.agentKey || (t.needsCodingAgent && own && !own.harness))) {
      const pick = pickAgent(t, this.agents());
      if (pick?.key) overrides.agentKey = pick.key;
    }
    this.patch({
      task: { templateKey: key ?? '', overrides },
      name: n.name === (this.resolved()?.name ?? '') || !n.name ? (t?.name ?? n.name) : n.name,
    });
    this.resolve$.next(key);
  }

  patchOverrides(changes: Partial<TaskOverrides>): void {
    const n = this.node();
    if (!n) return;
    const overrides: TaskOverrides = { ...(n.task?.overrides ?? {}), ...changes };
    for (const [k, v] of Object.entries(overrides)) {
      if (v === null || v === undefined || (Array.isArray(v) && v.length === 0))
        delete overrides[k as keyof TaskOverrides];
    }
    this.patch({ task: { templateKey: n.task?.templateKey ?? '', overrides } });
  }

  /** Schema form changes keep the keys of the own sections (repositories, directory, session, questions). */
  onSchemaConfig(config: Record<string, unknown>): void {
    const current = this.node()?.task?.overrides?.config ?? {};
    const next: Record<string, unknown> = { ...config };
    for (const k of this.ownSectionKeys()) if (k in current) next[k] = current[k];
    this.patchOverrides({ config: Object.keys(next).length ? next : undefined });
  }

  /** ADR 0056: replaces the config of an ACTION node. */
  patchActionConfig(config: Record<string, unknown> | null | undefined): void {
    const n = this.node();
    if (!n?.action) return;
    this.patch({ action: { type: n.action.type, config: config ?? {} } });
  }

  /** Action schema form changes keep the node's Jira connection pin and write approval. */
  onActionSchemaConfig(config: Record<string, unknown>): void {
    const current = this.node()?.action?.config ?? {};
    if (this.isDelivery()) {
      this.patchActionConfig(withFormConfig(current, config));
      return;
    }
    const pin = pinnedConnection(current);
    const next: Record<string, unknown> = {
      ...((pin ? withPinnedConnection(config, pin) : config) ?? {}),
    };
    for (const k of SECTION_CONFIG_KEYS) if (k in current) next[k] = current[k];
    this.patchActionConfig(next);
  }

  onConfigJson(text: string): void {
    this.configDraft.set(text);
    try {
      const parsed = (text.trim() ? JSON.parse(text) : {}) as Record<string, unknown>;
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed))
        throw new Error($localize`:@@workflows.props.config.notObject:must be an object`);
      this.configError.set(null);
      if (this.node()?.kind === 'ACTION') this.patchActionConfig(parsed);
      else this.patchOverrides({ config: Object.keys(parsed).length ? parsed : undefined });
    } catch (e) {
      this.configError.set(
        $localize`:@@workflows.props.config.invalid:Invalid JSON: ${(e as Error).message}:error:`,
      );
    }
  }

  /** Display name of a model profile; the project default when none is set. */
  profileName(id: string | null | undefined): string {
    if (!id) return $localize`:@@workflows.props.stepGets.defaultModel:Project default`;
    const p = this.profiles().find((x) => x.id === id);
    return p ? `${p.displayName} (${p.ref.modelId})` : id;
  }

  branchExpressionLabel(id: string): string {
    return $localize`:@@workflows.props.branchExpression:Expression of branch ${id}:id:`;
  }

  patchDecision(changes: Partial<NonNullable<WorkflowNode['decision']>>): void {
    const n = this.node();
    if (!n) return;
    this.patch({ decision: { branches: n.decision?.branches ?? [], ...n.decision, ...changes } });
  }

  patchBranch(index: number, changes: Partial<DecisionBranch>): void {
    const branches = (this.node()?.decision?.branches ?? []).map((b, i) =>
      i === index ? { ...b, ...changes } : b,
    );
    this.patchDecision({ branches });
  }

  addBranch(): void {
    const branches = this.node()?.decision?.branches ?? [];
    const id = uniqueNodeId(
      'branch',
      branches.map((b) => b.id),
    );
    this.patchDecision({ branches: [...branches, { id, label: id, expression: 'true' }] });
  }

  removeBranch(index: number): void {
    const n = this.node();
    const branches = (n?.decision?.branches ?? []).filter((_, i) => i !== index);
    const removed = n?.decision?.branches?.[index]?.id;
    this.patchDecision({
      branches,
      defaultBranch: n?.decision?.defaultBranch === removed ? null : n?.decision?.defaultBranch,
    });
  }

  patchGate(changes: Partial<NonNullable<WorkflowNode['gate']>>): void {
    const n = this.node();
    if (!n) return;
    this.patch({
      gate: {
        question: n.gate?.question ?? '',
        options: n.gate?.options ?? ['approve', 'revise', 'reject'],
        ...n.gate,
        ...changes,
      },
    });
  }

  /** ADR 0063: the repositories the delivery writes to; none = every changed one. */
  setDeliverRepositories(keys: string[] | null): void {
    this.patchActionConfig(withDeliverRepositories(this.node()?.action?.config, keys ?? []));
  }

  patchCi(changes: Partial<NonNullable<WorkflowNode['ci']>>): void {
    const n = this.node();
    if (!n) return;
    this.patch({ ci: { signal: n.ci?.signal ?? '', ...n.ci, ...changes } });
  }
}

/** The schema without the given properties; null when no property is left. */
function withoutKeys(
  schema: JsonSchema | null | undefined,
  keys: readonly string[],
): JsonSchema | null {
  if (!schema?.properties) return null;
  let s = schema;
  if (keys.some((k) => k in schema.properties!)) {
    const properties = { ...schema.properties };
    for (const k of keys) delete properties[k];
    s = { ...schema, properties };
  }
  return Object.keys(s.properties ?? {}).length ? s : null;
}

/** One agent per key: a project agent replaces the global one of the same key (ADR 0056). */
function uniqueAgents(agents: readonly Agent[]): Agent[] {
  const byKey = new Map<string, Agent>();
  for (const a of agents) {
    const key = a.key ?? '';
    if (!byKey.has(key) || a.scope?.type === 'PROJECT') byKey.set(key, a);
  }
  return [...byKey.values()];
}
