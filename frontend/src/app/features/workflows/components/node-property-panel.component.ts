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
import { Subject, catchError, of, switchMap } from 'rxjs';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import { ModelSelectComponent } from '../../../core/ui/model-select.component';
import type {
  DecisionBranch,
  ModelOption,
  ModelProfile,
  PaletteTask,
  LibrarySource,
  ProjectRepository,
  ProjectWorkspace,
  ResolvedTask,
  Skill,
  TaskOverrides,
  WorkflowNode,
  WorkflowPalette,
} from '../../../core/api/types';
import {
  JIRA_CONNECTION_KEY,
  NodeConnectionSectionComponent,
  pinnedConnection,
  withPinnedConnection,
} from '../../connections/public-api';
import { NODE_ID_PATTERN, NODE_KIND_META, portsFor, uniqueNodeId } from '../data/graph-model';
import type { JsonSchema } from '../data/schema-form';
import type { Issues } from '../data/workflow-validation';
import { ExpressionEditorComponent } from './expression-editor.component';
import { SchemaFormComponent } from './schema-form.component';
import { GateRulesEditorComponent } from './gate-rules-editor.component';
import { WriteApprovalSectionComponent } from './write-approval-section.component';
import { SECTION_CONFIG_KEYS } from '../data/write-approval';
import {
  STEP_REPOSITORIES_KEY,
  isRunnerTask,
  ownEditorKeys,
  stepPushes,
} from '../data/step-repositories';
import { StepRepositoriesSectionComponent } from './step-repositories-section.component';
import { StepWorkspaceSectionComponent } from './step-workspace-section.component';
import { WORKSPACE_CONFIG_KEYS, stepWorkspace } from '../data/workspaces';
import { type StepModelUse, stepModelUse, stepModelUseKey } from '../data/model-step';

type PromptMode = 'none' | 'append' | 'replace';

/**
 * Right drawer of the designer: edits the selected node. Emits an updated copy on every change
 * (the page owns the definition). Kind-specific sections per NodeKind.
 */
import { StartRunInputsComponent } from './designer/start-run-inputs.component';
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
    StepRepositoriesSectionComponent,
    StepWorkspaceSectionComponent,
    ModelSelectComponent,
    StartRunInputsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (node(); as n) {
      <div class="panel">
        <div class="head">
          <nz-icon [nzType]="meta().icon" [style.color]="meta().color" />
          <span class="kind">{{ meta().title }}</span>
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
        <p class="muted small">{{ meta().description }}</p>

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
                          <nz-option
                            [nzValue]="t.key"
                            [nzLabel]="(t.name || t.key) + ' (' + t.executor + ')'"
                          />
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
                    @if (r.executor) {
                      <nz-tag nzColor="purple">{{ r.executor }}</nz-tag>
                    }
                    @for (i of r.interfaces ?? []; track i) {
                      <nz-tag nzColor="blue">{{ i }}</nz-tag>
                    }
                    @for (p of r.ports ?? []; track p) {
                      <nz-tag>{{ p }}</nz-tag>
                    }
                  </div>
                  @if (r.interfaceCheck && r.interfaceCheck.ok === false) {
                    @for (p of r.interfaceCheck.problems ?? []; track p) {
                      <div class="danger small">{{ p }}</div>
                    }
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
                  [config]="n.task?.overrides?.config ?? null"
                  [readonly]="readonly()"
                  (configChange)="patchOverrides({ config: $event })"
                />
              }

              @if (runnerStep()) {
                <nz-divider
                  nzText="Repositories"
                  i18n-nzText="@@workflows.props.repositories"
                  nzOrientation="left"
                  nzPlain
                />
                <app-step-repositories-section
                  [repositories]="projectRepositories()"
                  [librarySources]="librarySources()"
                  [workspaceSources]="stepWorkspaceSources()"
                  [config]="n.task?.overrides?.config ?? null"
                  [pushes]="stepPushes()"
                  [nodeIssues]="allIssues()"
                  [readonly]="readonly()"
                  (configChange)="patchOverrides({ config: $event })"
                />
                <nz-divider
                  nzText="Workspace"
                  i18n-nzText="@@workflows.props.workspace"
                  nzOrientation="left"
                  nzPlain
                />
                <app-step-workspace-section
                  [workspaces]="workspaces()"
                  [config]="n.task?.overrides?.config ?? null"
                  [canPrepare]="paletteTask()?.executor === 'IMPLEMENT'"
                  [nodeIssues]="allIssues()"
                  [readonly]="readonly()"
                  (configChange)="patchOverrides({ config: $event })"
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
                  [config]="n.task?.overrides?.config ?? null"
                  [readonly]="readonly()"
                  (configChange)="patchOverrides({ config: $event })"
                />
              }

              <nz-divider
                nzText="Overrides"
                i18n-nzText="@@workflows.props.overrides"
                nzOrientation="left"
                nzPlain
              />
              <nz-form-item>
                <nz-form-label nzFor="pp-model" i18n="@@workflows.props.modelProfile"
                  >Model profile</nz-form-label
                >
                <nz-form-control
                  nzExtra="Overrides the template / project default for this node."
                  i18n-nzExtra="@@workflows.props.modelProfile.extra"
                >
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
              <nz-form-item>
                <nz-form-label i18n="@@workflows.props.userPrompt">User prompt</nz-form-label>
                <nz-form-control>
                  <nz-radio-group
                    [ngModel]="promptMode()"
                    (ngModelChange)="setPromptMode($event)"
                    name="promptMode"
                    nzSize="small"
                    [disabled]="readonly()"
                  >
                    <label nz-radio-button nzValue="none" i18n="@@workflows.props.inherited"
                      >Inherited</label
                    >
                    <label nz-radio-button nzValue="append" i18n="@@workflows.props.append"
                      >Append</label
                    >
                    <label nz-radio-button nzValue="replace" i18n="@@workflows.props.replace"
                      >Replace</label
                    >
                  </nz-radio-group>
                  @if (promptMode() === 'append') {
                    <textarea
                      nz-input
                      class="mono-textarea"
                      name="promptAppend"
                      [ngModel]="n.task?.overrides?.promptAppend ?? ''"
                      (ngModelChange)="patchOverrides({ promptAppend: $event || null })"
                      [nzAutosize]="{ minRows: 3, maxRows: 12 }"
                      placeholder="Appended after the template user prompt"
                      i18n-placeholder="@@workflows.props.appendPlaceholder"
                      [readOnly]="readonly()"
                    ></textarea>
                  }
                  @if (promptMode() === 'replace') {
                    <textarea
                      nz-input
                      class="mono-textarea"
                      name="promptReplace"
                      [ngModel]="n.task?.overrides?.promptReplace ?? ''"
                      (ngModelChange)="patchOverrides({ promptReplace: $event || null })"
                      [nzAutosize]="{ minRows: 3, maxRows: 12 }"
                      placeholder="Replaces the template user prompt"
                      i18n-placeholder="@@workflows.props.replacePlaceholder"
                      [readOnly]="readonly()"
                    ></textarea>
                  }
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="pp-sys" i18n="@@workflows.props.systemAppend"
                  >System prompt append</nz-form-label
                >
                <nz-form-control>
                  <textarea
                    nz-input
                    id="pp-sys"
                    class="mono-textarea"
                    name="systemPromptAppend"
                    [ngModel]="n.task?.overrides?.systemPromptAppend ?? ''"
                    (ngModelChange)="patchOverrides({ systemPromptAppend: $event || null })"
                    [nzAutosize]="{ minRows: 2, maxRows: 8 }"
                    [readOnly]="readonly()"
                  ></textarea>
                </nz-form-control>
              </nz-form-item>
              <div class="grid2">
                <nz-form-item>
                  <nz-form-label nzFor="pp-add-skills" i18n="@@workflows.props.addSkills"
                    >Add skills</nz-form-label
                  >
                  <nz-form-control>
                    <nz-select
                      id="pp-add-skills"
                      name="addSkills"
                      nzMode="multiple"
                      nzShowSearch
                      nzPlaceHolder="none"
                      i18n-nzPlaceHolder="@@workflows.props.none"
                      [ngModel]="n.task?.overrides?.addSkills ?? []"
                      (ngModelChange)="patchOverrides({ addSkills: $event })"
                      [disabled]="readonly()"
                    >
                      @for (s of skills(); track s.key) {
                        <nz-option [nzValue]="s.key" [nzLabel]="s.name || s.key" />
                      }
                    </nz-select>
                  </nz-form-control>
                </nz-form-item>
                <nz-form-item>
                  <nz-form-label nzFor="pp-rm-skills" i18n="@@workflows.props.removeSkills"
                    >Remove skills</nz-form-label
                  >
                  <nz-form-control>
                    <nz-select
                      id="pp-rm-skills"
                      name="removeSkills"
                      nzMode="tags"
                      nzPlaceHolder="none"
                      i18n-nzPlaceHolder="@@workflows.props.none"
                      [ngModel]="n.task?.overrides?.removeSkills ?? []"
                      (ngModelChange)="patchOverrides({ removeSkills: $event })"
                      [disabled]="readonly()"
                    >
                      @for (s of resolvedSkillKeys(); track s) {
                        <nz-option [nzValue]="s" [nzLabel]="s" />
                      }
                    </nz-select>
                  </nz-form-control>
                </nz-form-item>
              </div>
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
                  <nz-form-label nzFor="pp-cfg-json" i18n="@@workflows.props.configJson"
                    >Config overrides (JSON)</nz-form-label
                  >
                  <nz-form-control
                    nzExtra="Deep-merged into the template config; null deletes a key."
                    i18n-nzExtra="@@workflows.props.configJson.extra"
                  >
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

            @case ('DECISION') {
              <nz-divider
                nzText="Branches"
                i18n-nzText="@@workflows.props.branches"
                nzOrientation="left"
                nzPlain
              />
              <p class="muted small" i18n="@@workflows.props.branches.hint">
                Evaluated in order; the first true expression wins. Each branch id is an output
                port.
              </p>
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
                <nz-form-label nzRequired nzFor="pp-question" i18n="@@workflows.props.question"
                  >Question</nz-form-label
                >
                <nz-form-control [nzExtra]="text.questionHint">
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
                <nz-form-label nzFor="pp-artifacts" i18n="@@workflows.props.showArtifacts"
                  >Show artefacts</nz-form-label
                >
                <nz-form-control
                  nzExtra="Artefact names shown (and editable) in the inbox."
                  i18n-nzExtra="@@workflows.props.showArtifacts.extra"
                >
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
                <nz-form-label nzRequired nzFor="pp-signal" i18n="@@workflows.props.signal"
                  >Signal</nz-form-label
                >
                <nz-form-control
                  nzExtra="The CI job calls: backoffice-ci signal <name> --status success|fail"
                  i18n-nzExtra="@@workflows.props.signal.extra"
                >
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
                <nz-form-label nzFor="pp-ci-timeout" i18n="@@workflows.props.timeoutSeconds"
                  >Timeout (seconds)</nz-form-label
                >
                <nz-form-control
                  nzExtra="After the timeout the 'timeout' port is taken."
                  i18n-nzExtra="@@workflows.props.ciTimeout.extra"
                >
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
                <nz-form-label nzFor="pp-ci-watch" i18n="@@workflows.props.ciWatch"
                  >Watch pipeline</nz-form-label
                >
                <nz-form-control
                  nzExtra="Back Office reads the pipeline of the run's branch itself (CI_READ token of the repository); a failure sends the failed jobs and their logs to the next step. Your CI sends no signal."
                  i18n-nzExtra="@@workflows.props.ciWatch.extra"
                >
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
                  <nz-form-label nzFor="pp-ci-repo" i18n="@@workflows.props.ciRepository"
                    >Repository</nz-form-label
                  >
                  <nz-form-control
                    nzExtra="Empty = the primary repository."
                    i18n-nzExtra="@@workflows.props.ciRepository.extra"
                  >
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
                <nz-form-label nzFor="pp-max" i18n="@@workflows.props.maxVisits"
                  >Max visits</nz-form-label
                >
                <nz-form-control
                  nzExtra="Loop guard."
                  i18n-nzExtra="@@workflows.props.maxVisits.extra"
                >
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
    `,
  ],
})
export class NodePropertyPanelComponent {
  private readonly library = inject(TaskLibraryApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly node = input<WorkflowNode | null>(null);
  readonly allIds = input<string[]>([]);
  readonly palette = input<WorkflowPalette | null>(null);
  readonly issues = input<Issues | null>(null);
  readonly profiles = input<ModelProfile[]>([]);
  /**
   * Model options per step use (ADR 0050, key `stepModelUseKey`), cached by the page: missing = not loaded yet,
   * null = could not be loaded (the plain profile list is shown instead).
   */
  readonly modelOptions = input<Record<string, ModelOption[] | null>>({});
  readonly skills = input<Skill[]>([]);
  readonly projectId = input<string | null>(null);
  /** ADR 0053: the saved workflow, for the START node's read-only run inputs; null while unsaved. */
  readonly workflowId = input<string | null>(null);
  /** The project's repositories (ADR 0034), for the repositories of runner steps. */
  readonly projectRepositories = input<readonly ProjectRepository[]>([]);
  /** Sources of the library (ADR 0043) a runner step may attach. */
  readonly librarySources = input<readonly LibrarySource[]>([]);
  /** Run workspaces of the project (ADR 0044) a coding step may pick. */
  readonly workspaces = input<readonly ProjectWorkspace[]>([]);
  /** Sources of the node's workspace, shown as the default of its repositories; null without a workspace. */
  readonly stepWorkspaceSources = computed<string[] | null>(() => {
    const key = stepWorkspace(this.node()?.task?.overrides?.config).workspace;
    const ws = key ? this.workspaces().find((w) => w.key === key) : undefined;
    const keys = (ws?.sources ?? []).map((s) => (s.library ? `lib-${s.key}` : s.key));
    return keys.length ? keys : null;
  });
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
  };

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
  readonly allIssues = computed(() => [
    ...(this.issues()?.errors ?? []),
    ...(this.issues()?.warnings ?? []),
  ]);
  readonly ports = computed(() => {
    const n = this.node();
    if (!n) return [];
    const resolved = this.resolved();
    if (n.kind === 'TASK' && resolved?.ports?.length) return resolved.ports;
    const index = {
      tasks: new Map((this.palette()?.tasks ?? []).map((t) => [t.key!, t])),
      controlPorts: new Map(),
    };
    return portsFor(n, index);
  });
  readonly stateFields = computed(() => this.palette()?.stateFields ?? []);
  readonly templateGroups = computed(() => {
    const p = this.palette();
    const names = new Map((p?.interfaces ?? []).map((i) => [i.key, i.name || i.key]));
    const groups = new Map<string, { title: string; tasks: PaletteTask[] }>();
    for (const t of p?.tasks ?? []) {
      const k = t.interfaces?.[0] ?? '-';
      const title =
        k === '-' ? $localize`:@@workflows.props.otherGroup:Other` : (names.get(k) ?? k);
      if (!groups.has(k)) groups.set(k, { title, tasks: [] });
      groups.get(k)!.tasks.push(t);
    }
    return Array.from(groups.values());
  });
  readonly configSchema = computed<JsonSchema | null>(() => {
    const key = this.node()?.task?.templateKey;
    const t = key ? this.palette()?.tasks?.find((x) => x.key === key) : undefined;
    let schema = t?.configSchema as JsonSchema | undefined;
    // The Jira connection pin, the write approval and the step repositories have their own
    // sections; keep them out of the form.
    const own = [JIRA_CONNECTION_KEY, ...SECTION_CONFIG_KEYS, ...ownEditorKeys(schema)];
    if (schema?.properties && own.some((k) => k in schema!.properties!)) {
      const properties = { ...schema.properties };
      for (const k of own) delete properties[k];
      schema = { ...schema, properties };
    }
    return schema?.properties && Object.keys(schema.properties).length ? schema : null;
  });
  /** Connection capabilities the node's template needs (Jira tasks, ADR 0030). */
  readonly requiredCapabilities = computed<string[]>(() => {
    const key = this.node()?.task?.templateKey;
    const t = key ? this.palette()?.tasks?.find((x) => x.key === key) : undefined;
    return (t?.requiredCapabilities as string[] | undefined) ?? [];
  });
  /** The template writes to Jira: approval before write applies (ADR 0033). */
  readonly externalWrite = computed<boolean>(() => {
    const key = this.node()?.task?.templateKey;
    const t = key ? this.palette()?.tasks?.find((x) => x.key === key) : undefined;
    return t?.externalWrite === true;
  });
  /** The palette entry of the node's template. */
  readonly paletteTask = computed<PaletteTask | null>(() => {
    const key = this.node()?.task?.templateKey;
    return (key ? this.palette()?.tasks?.find((x) => x.key === key) : undefined) ?? null;
  });
  /** How the node's step uses a model (ADR 0050): step kind and coding harness; null = no model. */
  readonly modelUse = computed<StepModelUse | null>(
    () =>
      stepModelUse(
        this.paletteTask(),
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
  /** IMPLEMENT / SPEC_KIT steps run in the runner and use repositories (ADR 0034). */
  readonly runnerStep = computed(() => isRunnerTask(this.paletteTask()));
  /** Whether the runner step pushes (node config, template config, schema default). */
  readonly stepPushes = computed(() =>
    stepPushes(
      this.node()?.task?.overrides?.config,
      this.resolved()?.config,
      this.paletteTask()?.configSchema as JsonSchema | undefined,
    ),
  );
  /** Config keys with their own section (kept out of the schema form, kept on schema changes). */
  private readonly ownSectionKeys = computed<string[]>(() => {
    const keys: string[] = [];
    if (this.requiredCapabilities().length) keys.push(...SECTION_CONFIG_KEYS);
    if (this.runnerStep()) keys.push(STEP_REPOSITORIES_KEY, ...WORKSPACE_CONFIG_KEYS);
    return keys;
  });
  /** Config shown in the schema form: without the pin, the write approval and the repositories. */
  readonly formConfig = computed<Record<string, unknown>>(() => {
    const config = this.node()?.task?.overrides?.config ?? {};
    const own = this.ownSectionKeys();
    if (!this.requiredCapabilities().length && !own.length) return config;
    const rest = {
      ...((this.requiredCapabilities().length ? withPinnedConnection(config, null) : config) ?? {}),
    };
    for (const k of own) delete rest[k];
    return rest;
  });
  readonly promptMode = computed<PromptMode>(() => {
    const o = this.node()?.task?.overrides;
    if (o?.promptReplace) return 'replace';
    if (o?.promptAppend) return 'append';
    return this.promptModeDraft();
  });
  private readonly promptModeDraft = signal<PromptMode>('none');

  readonly idDraft = signal('');
  readonly idError = signal<string | null>(null);
  readonly configDraft = signal('');
  readonly configError = signal<string | null>(null);

  readonly resolved = signal<ResolvedTask | null>(null);
  readonly resolving = signal(false);
  readonly resolveError = signal<string | null>(null);
  readonly resolvedSkillKeys = computed(() =>
    (this.resolved()?.skills ?? []).map((s) => s.key).filter((k): k is string => !!k),
  );
  private readonly resolve$ = new Subject<string | null>();

  constructor() {
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
        this.configDraft.set(JSON.stringify(n?.task?.overrides?.config ?? {}, null, 2));
        this.configError.set(null);
        this.promptModeDraft.set('none');
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
    this.patch({
      task: { templateKey: key ?? '', overrides: n.task?.overrides ?? {} },
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

  /** Schema form changes keep the node's Jira connection pin, write approval and repositories. */
  onSchemaConfig(config: Record<string, unknown>): void {
    const current = this.node()?.task?.overrides?.config ?? {};
    const pin = this.requiredCapabilities().length ? pinnedConnection(current) : null;
    const next: Record<string, unknown> = {
      ...((pin ? withPinnedConnection(config, pin) : config) ?? {}),
    };
    for (const k of [...SECTION_CONFIG_KEYS, ...this.ownSectionKeys()])
      if (k in current) next[k] = current[k];
    this.patchOverrides({ config: Object.keys(next).length ? next : undefined });
  }

  setPromptMode(mode: PromptMode): void {
    this.promptModeDraft.set(mode);
    if (mode === 'none') this.patchOverrides({ promptAppend: null, promptReplace: null });
    else if (mode === 'append') this.patchOverrides({ promptReplace: null });
    else this.patchOverrides({ promptAppend: null });
  }

  onConfigJson(text: string): void {
    this.configDraft.set(text);
    try {
      const parsed = (text.trim() ? JSON.parse(text) : {}) as Record<string, unknown>;
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed))
        throw new Error($localize`:@@workflows.props.config.notObject:must be an object`);
      this.configError.set(null);
      this.patchOverrides({ config: Object.keys(parsed).length ? parsed : undefined });
    } catch (e) {
      this.configError.set(
        $localize`:@@workflows.props.config.invalid:Invalid JSON: ${(e as Error).message}:error:`,
      );
    }
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

  patchCi(changes: Partial<NonNullable<WorkflowNode['ci']>>): void {
    const n = this.node();
    if (!n) return;
    this.patch({ ci: { signal: n.ci?.signal ?? '', ...n.ci, ...changes } });
  }
}
