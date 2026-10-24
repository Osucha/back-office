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
import { NzDrawerModule } from 'ng-zorro-antd/drawer';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { Subject, catchError, debounceTime, of, switchMap } from 'rxjs';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import {
  TASK_EXECUTORS,
  type ModelProfile,
  type Project,
  type RunInputField,
  type Skill,
  type TaskInterface,
  type TaskTemplate,
  type TaskTemplateInput,
} from '../../../core/api/types';
import { JsonViewComponent } from '../../../core/ui/json-view.component';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { SLUG_PATTERN } from '../../../core/forms/patterns';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { SandboxPolicyEditorComponent } from '../../fleet/public-api';
import {
  draftSkillDelta,
  emptyTemplate,
  parentCandidates,
  toPreview,
  toTemplateInput,
  type ResolvedPreview,
} from '../data/resolved-preview';
import {
  addRunInput,
  moveRunInput,
  patchRunInput,
  removeRunInput,
  runInputProblem,
  withOrigins,
} from '../data/run-inputs-editor';

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
    JsonViewComponent,
    SandboxPolicyEditorComponent,
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
          @if (template()?.key) {
            <span class="title-key">{{ template()?.key }}</span>
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
            <div class="grid">
              <nz-form-item>
                <nz-form-label nzRequired nzFor="tt-key" i18n="@@library.templateEditor.key"
                  >Key</nz-form-label
                >
                <nz-form-control
                  [nzValidateStatus]="keyError() ? 'error' : ''"
                  [nzErrorTip]="keyError() ?? ''"
                  nzExtra="Slug. A PROJECT template with the same key shadows the global one."
                  i18n-nzExtra="@@library.templateEditor.key.extra"
                >
                  <input
                    nz-input
                    id="tt-key"
                    name="key"
                    class="mono"
                    [ngModel]="draft().key"
                    (ngModelChange)="patch({ key: $event })"
                    [readOnly]="!!template()"
                    placeholder="code-review-strict"
                    i18n-placeholder="@@library.templateEditor.key.placeholder"
                  />
                </nz-form-control>
              </nz-form-item>
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
                <nz-form-label nzFor="tt-extends" i18n="@@library.templateEditor.extends"
                  >Extends</nz-form-label
                >
                <nz-form-control
                  nzExtra="Single parent; prompts append or replace, config deep-merges."
                  i18n-nzExtra="@@library.templateEditor.extends.extra"
                >
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
                      <nz-option
                        [nzValue]="t.key"
                        [nzLabel]="
                          t.name + ' (' + t.key + ')' + (t.abstract ? text.abstractSuffix : '')
                        "
                      />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="tt-impl" i18n="@@library.templateEditor.implements"
                  >Implements</nz-form-label
                >
                <nz-form-control
                  nzExtra="Interfaces this template must satisfy (checked on resolve)."
                  i18n-nzExtra="@@library.templateEditor.implements.extra"
                >
                  <nz-select
                    id="tt-impl"
                    name="implements"
                    nzMode="multiple"
                    nzShowSearch
                    nzPlaceHolder="none"
                    i18n-nzPlaceHolder="@@library.templateEditor.none"
                    [ngModel]="draft().implements ?? []"
                    (ngModelChange)="patch({ implements: $event })"
                  >
                    @for (i of interfaces(); track i.key) {
                      <nz-option [nzValue]="i.key" [nzLabel]="i.name + ' (' + i.key + ')'" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="tt-exec" i18n="@@library.templateEditor.executor"
                  >Executor</nz-form-label
                >
                <nz-form-control
                  nzExtra="Required on concrete templates unless inherited."
                  i18n-nzExtra="@@library.templateEditor.executor.extra"
                >
                  <nz-select
                    id="tt-exec"
                    name="executor"
                    nzAllowClear
                    nzPlaceHolder="inherited"
                    i18n-nzPlaceHolder="@@library.templateEditor.inherited"
                    [ngModel]="draft().executor ?? null"
                    (ngModelChange)="patch({ executor: $event ?? null })"
                  >
                    @for (e of executors; track e) {
                      <nz-option [nzValue]="e" [nzLabel]="e" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label i18n="@@library.templateEditor.abstract">Abstract</nz-form-label>
                <nz-form-control
                  nzExtra="Abstract templates can only be extended, never executed."
                  i18n-nzExtra="@@library.templateEditor.abstract.extra"
                >
                  <nz-switch
                    name="abstract"
                    [ngModel]="draft().abstract"
                    (ngModelChange)="patch({ abstract: $event })"
                  />
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
              <nz-form-item>
                <nz-form-label nzFor="tt-model" i18n="@@library.templateEditor.modelProfile"
                  >Model profile</nz-form-label
                >
                <nz-form-control>
                  <nz-select
                    id="tt-model"
                    name="modelProfileId"
                    nzAllowClear
                    nzShowSearch
                    nzPlaceHolder="inherited / project default"
                    i18n-nzPlaceHolder="@@library.templateEditor.modelProfile.placeholder"
                    [ngModel]="draft().modelProfileId ?? null"
                    (ngModelChange)="patch({ modelProfileId: $event ?? null })"
                  >
                    @for (p of profiles(); track p.id) {
                      <nz-option
                        [nzValue]="p.id"
                        [nzLabel]="p.displayName + ' (' + p.ref.modelId + ')'"
                      />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
            </div>

            <h3 class="sec" i18n="@@library.templateEditor.prompt">Prompt</h3>
            <nz-form-item>
              <nz-form-control>
                <nz-radio-group
                  name="promptMode"
                  [ngModel]="draft().prompt?.mode ?? 'APPEND'"
                  (ngModelChange)="patchPrompt({ mode: $event })"
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
            <nz-form-item>
              <nz-form-label nzFor="tt-sys" i18n="@@library.templateEditor.systemPrompt"
                >System prompt</nz-form-label
              >
              <nz-form-control>
                <textarea
                  nz-input
                  id="tt-sys"
                  name="system"
                  class="mono-textarea"
                  [ngModel]="draft().prompt?.system ?? ''"
                  (ngModelChange)="patchPrompt({ system: $event })"
                  [nzAutosize]="{ minRows: 4, maxRows: 18 }"
                ></textarea>
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzFor="tt-user" i18n="@@library.templateEditor.userPrompt"
                >User prompt</nz-form-label
              >
              <nz-form-control
                nzExtra="Template variables: issue, artifacts.*, feedback, project.constitution, input.ci.*"
                i18n-nzExtra="@@library.templateEditor.userPrompt.extra"
              >
                <textarea
                  nz-input
                  id="tt-user"
                  name="user"
                  class="mono-textarea"
                  [ngModel]="draft().prompt?.user ?? ''"
                  (ngModelChange)="patchPrompt({ user: $event })"
                  [nzAutosize]="{ minRows: 4, maxRows: 18 }"
                ></textarea>
              </nz-form-control>
            </nz-form-item>

            <h3 class="sec" i18n="@@library.templateEditor.skills">Skills</h3>
            <div class="grid">
              <nz-form-item>
                <nz-form-label nzFor="tt-skills" i18n="@@library.templateEditor.skills"
                  >Skills</nz-form-label
                >
                <nz-form-control
                  nzExtra="Appended to the system prompt (LLM) or written as SKILL.md files (runner)."
                  i18n-nzExtra="@@library.templateEditor.skills.extra"
                >
                  <nz-select
                    id="tt-skills"
                    name="skills"
                    nzMode="multiple"
                    nzShowSearch
                    nzPlaceHolder="none"
                    i18n-nzPlaceHolder="@@library.templateEditor.none"
                    [ngModel]="draft().skills ?? []"
                    (ngModelChange)="patch({ skills: $event })"
                  >
                    @for (s of skills(); track s.key) {
                      <nz-option [nzValue]="s.key" [nzLabel]="s.name + ' (' + s.key + ')'" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="tt-rm-skills" i18n="@@library.templateEditor.removeSkills"
                  >Remove inherited skills</nz-form-label
                >
                <nz-form-control>
                  <nz-select
                    id="tt-rm-skills"
                    name="removeSkills"
                    nzMode="tags"
                    nzPlaceHolder="none"
                    i18n-nzPlaceHolder="@@library.templateEditor.none"
                    [ngModel]="draft().removeSkills ?? []"
                    (ngModelChange)="patch({ removeSkills: $event })"
                  >
                    @for (s of preview()?.skills ?? []; track s.key) {
                      <nz-option [nzValue]="s.key" [nzLabel]="s.key" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
            </div>

            <h3 class="sec" i18n="@@library.templateEditor.contract">Contract and configuration</h3>
            <div class="grid">
              <nz-form-item>
                <nz-form-label nzFor="tt-produces" i18n="@@library.templateEditor.produces"
                  >Produces</nz-form-label
                >
                <nz-form-control
                  nzExtra="Artefact / state names this task writes."
                  i18n-nzExtra="@@library.templateEditor.produces.extra"
                >
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
                <nz-form-label nzFor="tt-consumes" i18n="@@library.templateEditor.consumes"
                  >Consumes</nz-form-label
                >
                <nz-form-control
                  nzExtra="Artefact / state names this task reads."
                  i18n-nzExtra="@@library.templateEditor.consumes.extra"
                >
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
                <nz-form-label nzFor="tt-confidence" i18n="@@library.templateEditor.confidence"
                  >Confidence field</nz-form-label
                >
                <nz-form-control
                  nzExtra="Field of the structured output copied to state.confidence."
                  i18n-nzExtra="@@library.templateEditor.confidence.extra"
                >
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
                <nz-form-label nzFor="tt-tags" i18n="@@library.templateEditor.tags"
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
            <h3 class="sec" i18n="@@library.templates.runInputs.title">Run inputs</h3>
            <p class="sec-help" i18n="@@library.templates.runInputs.help">
              Fields of the start form this task asks for. A workflow asks for the fields of all its
              steps; a field with the name of an inherited one replaces it. The values reach the prompt
              as the Run input list; an issue key field is also the run's issue key.
            </p>
            @for (f of draft().runInputs ?? []; track $index; let i = $index) {
              <div class="run-input-row" [attr.data-run-input]="i">
                <input
                  nz-input
                  class="mono"
                  [name]="'ri-name-' + i"
                  [ngModel]="f.name"
                  (ngModelChange)="patchRunInput(i, { name: $event })"
                  placeholder="name"
                  i18n-placeholder="@@library.templates.runInputs.name"
                  aria-label="Name"
                  i18n-aria-label="@@library.templates.runInputs.nameLabel"
                />
                <input
                  nz-input
                  [name]="'ri-label-' + i"
                  [ngModel]="f.label"
                  (ngModelChange)="patchRunInput(i, { label: $event })"
                  placeholder="Label shown in the start form"
                  i18n-placeholder="@@library.templates.runInputs.label"
                  aria-label="Label"
                  i18n-aria-label="@@library.templates.runInputs.labelLabel"
                />
                <nz-select
                  [name]="'ri-type-' + i"
                  [ngModel]="f.type"
                  (ngModelChange)="patchRunInput(i, { type: $event })"
                  aria-label="Type"
                  i18n-aria-label="@@library.templates.runInputs.typeLabel"
                >
                  @for (t of runInputTypes; track t.value) {
                    <nz-option [nzValue]="t.value" [nzLabel]="t.label" />
                  }
                </nz-select>
                <label class="ri-required">
                  <nz-switch
                    nzSize="small"
                    [name]="'ri-required-' + i"
                    [ngModel]="f.required"
                    (ngModelChange)="patchRunInput(i, { required: $event })"
                  />
                  <span i18n="@@library.templates.runInputs.required">Required</span>
                </label>
                <input
                  nz-input
                  class="ri-help"
                  [name]="'ri-help-' + i"
                  [ngModel]="f.help ?? ''"
                  (ngModelChange)="patchRunInput(i, { help: $event || null })"
                  placeholder="Help text (optional)"
                  i18n-placeholder="@@library.templates.runInputs.helpText"
                  aria-label="Help text"
                  i18n-aria-label="@@library.templates.runInputs.helpLabel"
                />
                @if (!readonly()) {
                  <span class="ri-actions">
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      [disabled]="i === 0"
                      (click)="moveRunInput(i, -1)"
                      aria-label="Move up"
                      i18n-aria-label="@@library.templates.runInputs.up"
                    >
                      <nz-icon nzType="arrow-up" />
                    </button>
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      [disabled]="i === (draft().runInputs ?? []).length - 1"
                      (click)="moveRunInput(i, 1)"
                      aria-label="Move down"
                      i18n-aria-label="@@library.templates.runInputs.down"
                    >
                      <nz-icon nzType="arrow-down" />
                    </button>
                    <button
                      nz-button
                      nzSize="small"
                      nzDanger
                      type="button"
                      (click)="removeRunInput(i)"
                      aria-label="Remove"
                      i18n-aria-label="@@library.templates.runInputs.remove"
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
                class="ri-add"
                (click)="addRunInput()"
                i18n="@@library.templates.runInputs.add"
              >
                Add run input
              </button>
            }
            <nz-form-item>
              <nz-form-label nzFor="tt-config" i18n="@@library.templateEditor.config"
                >Config (JSON)</nz-form-label
              >
              <nz-form-control
                nzExtra="Executor-specific keys (phase, agent, verifyCommand, push, ...). Deep-merged over the parent; null deletes."
                i18n-nzExtra="@@library.templateEditor.config.extra"
              >
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
            <nz-form-item>
              <nz-form-label nzFor="tt-schema" i18n="@@library.templateEditor.outputSchema"
                >Output schema (JSON Schema, LLM executor)</nz-form-label
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
            <h3 class="sec" i18n="@@library.templateEditor.sandbox">Sandbox</h3>
            <p class="sec-help" i18n="@@library.templateEditor.sandbox.help">
              Narrows the project's sandbox policy for jobs of this template (stronger isolation,
              fewer hosts and tools, lower caps). Empty fields inherit. The platform, pool and
              project ceilings are checked when the job is resolved.
            </p>
            @defer (on viewport) {
              <app-sandbox-policy-editor
                [policy]="draft().sandboxPolicy ?? null"
                [readonly]="readonly()"
                ceilingLabel="The project"
                i18n-ceilingLabel="@@library.templateEditor.ceilingLabel"
                (policyChange)="
                  patch({ sandboxPolicy: $any(isEmpty($event) ? undefined : $event) })
                "
              />
            } @placeholder {
              <div class="sandbox-ph" aria-hidden="true"></div>
            }
          </form>

          <aside
            class="preview"
            tabindex="0"
            aria-label="Resolved preview"
            i18n-aria-label="@@library.templateEditor.preview"
          >
            <div class="preview-head">
              <h3 class="sec flush" i18n="@@library.templateEditor.preview">Resolved preview</h3>
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
            <p class="muted small" i18n="@@library.templateEditor.preview.help">
              What an agent receives after inheritance and project shadowing are applied. Reflects
              the saved version; unsaved edits are listed as deltas.
            </p>
            @if (preview(); as pv) {
              <div
                class="lineage"
                aria-label="Lineage"
                i18n-aria-label="@@library.templateEditor.lineage"
              >
                @for (k of pv.lineage; track k; let last = $last) {
                  <span class="mono" [class.leaf]="last">{{ k }}</span>
                  @if (!last) {
                    <nz-icon nzType="right" class="muted" />
                  }
                }
              </div>
              <div class="chips">
                @if (pv.executor) {
                  <nz-tag nzColor="purple">{{ pv.executor }}</nz-tag>
                }
                @if (pv.abstract) {
                  <nz-tag i18n="@@library.templateEditor.abstractChip">abstract</nz-tag>
                }
                @for (i of pv.interfaces; track i) {
                  <nz-tag nzColor="blue">{{ i }}</nz-tag>
                }
                @for (p of pv.ports; track p) {
                  <nz-tag i18n="@@library.templateEditor.port">port: {{ p }}</nz-tag>
                }
              </div>
              @if (!pv.ok) {
                <nz-alert
                  nzType="error"
                  nzShowIcon
                  nzMessage="Interface check failed"
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
              } @else if (pv.interfaces.length) {
                <nz-alert
                  nzType="success"
                  nzShowIcon
                  nzMessage="All implemented interfaces are satisfied"
                  i18n-nzMessage="@@library.templateEditor.checkOk"
                  style="margin-top: 8px"
                />
              }
              @if (delta().added.length || delta().removed.length) {
                <p class="muted small" style="margin-top: 8px">
                  <span i18n="@@library.templateEditor.unsaved">Unsaved:</span>
                  @for (k of delta().added; track k) {
                    <nz-tag nzColor="green">+{{ k }}</nz-tag>
                  }
                  @for (k of delta().removed; track k) {
                    <nz-tag nzColor="red">-{{ k }}</nz-tag>
                  }
                </p>
              }
              <nz-tabs nzSize="small" style="margin-top: 8px">
                <nz-tab
                  nzTitle="System prompt"
                  i18n-nzTitle="@@library.templateEditor.systemPrompt"
                >
                  <pre class="prompt" tabindex="0">{{ pv.systemPrompt || text.empty }}</pre>
                </nz-tab>
                <nz-tab nzTitle="User prompt" i18n-nzTitle="@@library.templateEditor.userPrompt">
                  <pre class="prompt" tabindex="0">{{ pv.userPrompt || text.empty }}</pre>
                </nz-tab>
                <nz-tab nzTitle="Skills" i18n-nzTitle="@@library.templateEditor.skills">
                  @if (!pv.skills.length) {
                    <p class="muted small" i18n="@@library.templateEditor.noSkills">No skills.</p>
                  }
                  <div class="chips">
                    @for (s of pv.skills; track s.key) {
                      <nz-tag nz-tooltip [nzTooltipTitle]="s.key">{{ s.name }}</nz-tag>
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
                <nz-tab nzTitle="Run inputs" i18n-nzTitle="@@library.templates.runInputs.tab">
                  @if (!previewRunInputs().length) {
                    <p class="muted small" i18n="@@library.templates.runInputs.none">
                      This task asks for no run input.
                    </p>
                  }
                  <ul class="ri-resolved">
                    @for (f of previewRunInputs(); track f.name) {
                      <li [attr.data-resolved-input]="f.name">
                        <span class="mono">{{ f.name }}</span>
                        <span>{{ f.label }}</span>
                        <nz-tag>{{ typeLabel(f.type) }}</nz-tag>
                        @if (f.required) {
                          <nz-tag nzColor="red" i18n="@@library.templates.runInputs.requiredTag"
                            >required</nz-tag
                          >
                        }
                        @if (f.from && f.from !== template()?.key) {
                          <span class="muted small" i18n="@@library.templates.runInputs.inherited"
                            >inherited from {{ f.from }}</span
                          >
                        }
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
      .sandbox-ph {
        height: 320px;
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
  readonly interfaces = input<TaskInterface[]>([]);
  readonly skills = input<Skill[]>([]);
  readonly profiles = input<ModelProfile[]>([]);
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

  readonly executors = TASK_EXECUTORS;
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

  readonly drawerWidth = 'min(1280px, 96vw)'; // i18n-ignore
  readonly schemaPlaceholder = '{ "type": "object", "properties": { ... } }'; // i18n-ignore
  readonly text = {
    abstractSuffix: $localize`:@@library.templateEditor.abstractSuffix: [abstract]`,
    empty: $localize`:@@library.templateEditor.empty:(empty)`,
    save: $localize`:@@library.templateEditor.save:Save template`,
    create: $localize`:@@library.templateEditor.create:Create template`,
    readOnlyClean: $localize`:@@library.templateEditor.readOnlyClean:Read-only: you cannot change templates in this scope`,
    allSaved: $localize`:@@library.templateEditor.allSaved:All changes saved`,
    fillIn: $localize`:@@library.templateEditor.fillIn:Fill in a key and a name to create the template`,
    close: $localize`:@@library.templateEditor.close:Close`,
    discard: $localize`:@@library.templateEditor.discard:Discard changes`,
  };

  readonly keyError = computed(() => {
    const k = this.draft().key;
    if (!k) return $localize`:@@library.templateEditor.required:Required`;
    if (!SLUG_PATTERN.test(k) || k.length < 2)
      return $localize`:@@library.templateEditor.keySlug:Slug: lowercase letters, digits, dashes (2-64 chars)`;
    if (
      !this.template() &&
      this.templates().some(
        (t) =>
          t.key === k &&
          t.scope?.type === this.draft().scope.type &&
          (t.scope?.projectId ?? null) === (this.draft().scope.projectId ?? null),
      )
    )
      return $localize`:@@library.templateEditor.keyTaken:A template with this key already exists in this scope`;
    return null;
  });
  readonly canSave = computed(() => {
    const d = this.draft();
    return (
      !this.keyError() &&
      !!d.name.trim() &&
      !this.configError() &&
      !this.schemaError() &&
      !this.runInputBlocker() &&
      (d.scope.type !== 'PROJECT' || !!d.scope.projectId)
    );
  });
  /** Why Save is disabled, in words (shown in the action bar). */
  readonly saveBlocker = computed(() => {
    const d = this.draft();
    const keyError = this.keyError();
    if (keyError) return $localize`:@@library.templateEditor.blocker.key:Key: ${keyError}:error:`;
    if (!d.name.trim()) return $localize`:@@library.templateEditor.blocker.name:Name is required`;
    if (this.configError())
      return $localize`:@@library.templateEditor.blocker.config:Config is not valid JSON`;
    if (this.schemaError())
      return $localize`:@@library.templateEditor.blocker.schema:Output schema is not valid JSON`;
    if (d.scope.type === 'PROJECT' && !d.scope.projectId)
      return $localize`:@@library.templateEditor.blocker.project:Pick the project`;
    return this.runInputBlocker();
  });
  readonly parents = computed(() =>
    parentCandidates(this.templates(), this.template()?.key ?? null),
  );
  readonly delta = computed(() => draftSkillDelta(this.draft(), this.preview()));
  /** ADR 0052: the resolved run inputs and the template each comes from. */
  readonly previewRunInputs = computed(() => {
    const pv = this.preview();
    return pv ? withOrigins(pv.runInputs, pv.lineage, this.templates()) : [];
  });
  readonly runInputTypes: Array<{ value: RunInputField['type']; label: string }> = [
    { value: 'TEXT', label: $localize`:@@library.templates.runInputs.type.text:Single line` },
    { value: 'MULTILINE', label: $localize`:@@library.templates.runInputs.type.multiline:Multi-line text` },
    { value: 'ISSUE_KEY', label: $localize`:@@library.templates.runInputs.type.issueKey:Jira issue key` },
  ];
  private readonly runInputBlocker = computed(() => {
    const p = runInputProblem(this.draft().runInputs ?? []);
    if (!p) return null;
    switch (p.kind) {
      case 'blankName':
        return $localize`:@@library.templates.runInputs.blocker.name:Every run input needs a name`;
      case 'blankLabel':
        return $localize`:@@library.templates.runInputs.blocker.label:Run input ${p.name}:name: needs a label`;
      case 'duplicate':
        return $localize`:@@library.templates.runInputs.blocker.duplicate:Run input name ${p.name}:name: is used twice`;
    }
  });

  constructor() {
    effect(() => {
      const t = this.template();
      const open = this.open();
      untracked(() => {
        if (!open) return;
        const d = t
          ? toTemplateInput(t as TaskTemplateInput & Record<string, unknown>)
          : emptyTemplate();
        this.draft.set(d);
        this.initialJson.set(JSON.stringify(d));
        this.configText.set(JSON.stringify(d.config ?? {}, null, 2));
        this.configError.set(null);
        this.schemaError.set(null);
        this.previewProjectId.set(d.scope.type === 'PROJECT' ? (d.scope.projectId ?? null) : null);
        this.preview.set(null);
        this.previewError.set(null);
      });
    });
    effect(() => {
      const key = this.template()?.key ?? null;
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
                $localize`:@@library.templateEditor.resolveError:Could not resolve the template (missing parent or interface?).`,
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
  }

  isEmpty(o: object | null | undefined): boolean {
    return !o || !Object.keys(o).length;
  }

  patch(changes: Partial<TaskTemplateInput>): void {
    this.draft.update((d) => ({ ...d, ...changes }));
  }

  patchPrompt(changes: Partial<NonNullable<TaskTemplateInput['prompt']>>): void {
    this.draft.update((d) => ({
      ...d,
      prompt: { system: '', user: '', mode: 'APPEND', ...d.prompt, ...changes },
    }));
  }

  addRunInput(): void {
    this.patch({ runInputs: addRunInput(this.draft().runInputs ?? []) });
  }

  removeRunInput(index: number): void {
    this.patch({ runInputs: removeRunInput(this.draft().runInputs ?? [], index) });
  }

  moveRunInput(index: number, delta: number): void {
    this.patch({ runInputs: [...moveRunInput(this.draft().runInputs ?? [], index, delta)] });
  }

  patchRunInput(index: number, changes: Partial<RunInputField>): void {
    this.patch({ runInputs: patchRunInput(this.draft().runInputs ?? [], index, changes) });
  }

  typeLabel(type: RunInputField['type']): string {
    return this.runInputTypes.find((t) => t.value === type)?.label ?? type;
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
    this.saved.emit(this.draft());
  }
}
