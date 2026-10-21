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
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators,
  type AbstractControl,
  type ValidationErrors,
} from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import {
  DIFFICULTIES,
  TASK_CATEGORIES,
  type Difficulty,
  type EvalTask,
  type EvalTaskInput,
  type TaskCategory,
  type TaskKind,
} from '../../../../core/api/types';
import { ReadonlyFormDirective } from '../../../../core/forms/readonly-form.directive';
import { MarkdownViewerComponent } from '../../../../core/ui/markdown-viewer.component';
import { ActionBarComponent } from '../../../../core/ui/relay/action-bar.component';
import { SidePanelComponent } from '../../../../core/ui/relay/side-panel.component';
import { humanize } from '../../../../core/ui/status-tag.component';
import { categoryLabel } from '../data/suite-view';

type TaskAcceptanceInput = NonNullable<NonNullable<EvalTaskInput['repository']>['acceptance']>;

/** ADR 0047: the acceptance JSON of the form; null when empty or not an object. */
export function parseAcceptance(raw: string | null | undefined): TaskAcceptanceInput | null {
  const text = (raw ?? '').trim();
  if (!text) return null;
  try {
    const v = JSON.parse(text) as unknown;
    if (!v || typeof v !== 'object' || Array.isArray(v)) return null;
    const o = v as Record<string, unknown>;
    const files = o['files'] ?? {};
    const paths = o['protectedPaths'] ?? [];
    if (typeof files !== 'object' || Array.isArray(files) || !Array.isArray(paths)) return null;
    if (Object.values(files as Record<string, unknown>).some((c) => typeof c !== 'string'))
      return null;
    return {
      files: files as Record<string, string>,
      protectedPaths: (paths as unknown[]).map(String),
      requireChanges: o['requireChanges'] !== false,
    };
  } catch {
    return null;
  }
}

function acceptanceValidator(c: AbstractControl): ValidationErrors | null {
  const text = String(c.value ?? '').trim();
  return !text || parseAcceptance(text) ? null : { acceptance: true };
}

/**
 * Task editor as a side panel next to the suite's task list: an edit tab (form) and a preview tab
 * (rendered instructions, rubric and reference answer); sticky action bar with `Ctrl/⌘ S`.
 * Read-only users land on the preview.
 */
@Component({
  selector: 'app-task-editor-drawer',
  imports: [
    ReadonlyFormDirective,
    ReactiveFormsModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzSelectModule,
    NzRadioModule,
    NzButtonModule,
    NzTabsModule,
    ActionBarComponent,
    MarkdownViewerComponent,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      [open]="open()"
      [title]="title()"
      [subtitle]="subtitle()"
      [defaultWidth]="680"
      storageKey="backoffice.panelWidth.task"
      bodyLabel="Task"
      i18n-bodyLabel="@@evaluation.task.bodyLabel"
      (closed)="closed.emit()"
    >
      <nz-tabs nzSize="small" [nzSelectedIndex]="tab()" (nzSelectedIndexChange)="tab.set($event)">
        <nz-tab [nzTitle]="readonly() ? text.details : text.edit">
          <form
            nz-form
            nzLayout="vertical"
            [formGroup]="form"
            [boReadonly]="readonly()"
            id="task-form"
            (ngSubmit)="submit()"
          >
            <nz-form-item>
              <nz-form-label nzRequired nzFor="t-name" i18n="@@evaluation.task.name"
                >Name</nz-form-label
              >
              <nz-form-control nzErrorTip="Required" i18n-nzErrorTip="@@evaluation.task.required"
                ><input
                  nz-input
                  id="t-name"
                  formControlName="name"
                  placeholder="Write acceptance criteria for payment retry"
                  i18n-placeholder="@@evaluation.task.namePlaceholder"
              /></nz-form-control>
            </nz-form-item>
            <div class="grid">
              <nz-form-item>
                <nz-form-label nzRequired nzFor="t-cat" i18n="@@evaluation.task.category"
                  >Category</nz-form-label
                >
                <nz-form-control>
                  <nz-select id="t-cat" formControlName="category">
                    @for (c of categories; track c) {
                      <nz-option [nzValue]="c" [nzLabel]="category(c)" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzRequired nzFor="t-diff" i18n="@@evaluation.task.difficulty"
                  >Difficulty</nz-form-label
                >
                <nz-form-control>
                  <nz-select id="t-diff" formControlName="difficulty">
                    @for (d of difficulties; track d) {
                      <nz-option [nzValue]="d" [nzLabel]="humanize(d)" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzRequired i18n="@@evaluation.task.kind">Kind</nz-form-label>
                <nz-form-control>
                  <nz-radio-group
                    formControlName="kind"
                    aria-label="Kind"
                    i18n-aria-label="@@evaluation.task.kind"
                  >
                    <label nz-radio-button nzValue="PROMPT" i18n="@@evaluation.task.kind.prompt"
                      >Prompt</label
                    >
                    <label
                      nz-radio-button
                      nzValue="CODING_JOB"
                      i18n="@@evaluation.task.kind.codingJob"
                      >Coding job</label
                    >
                  </nz-radio-group>
                </nz-form-control>
              </nz-form-item>
            </div>
            <p class="kind-help">
              @if (kind() === 'PROMPT') {
                <ng-container i18n="@@evaluation.task.kindHelp.prompt"
                  >One LLM call with the instructions (and context); the judge scores the answer
                  against the rubric.</ng-container
                >
              } @else {
                <ng-container i18n="@@evaluation.task.kindHelp.codingJob"
                  >Runs in the agent runner: clone the repository, run the setup command, let the
                  agent work, run the verify command; the judge scores the diff.</ng-container
                >
              }
            </p>

            <nz-form-item>
              <nz-form-label nzRequired nzFor="t-instr" i18n="@@evaluation.task.instructions"
                >Instructions (markdown)</nz-form-label
              >
              <nz-form-control nzErrorTip="Required" i18n-nzErrorTip="@@evaluation.task.required">
                <textarea
                  nz-input
                  id="t-instr"
                  class="mono-textarea"
                  formControlName="instructions"
                  [nzAutosize]="{ minRows: 6, maxRows: 20 }"
                ></textarea>
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzFor="t-ctx" i18n="@@evaluation.task.context"
                >Context (optional)</nz-form-label
              >
              <nz-form-control
                nzExtra="Extra material handed to the model: existing spec, code excerpt, stack trace."
                i18n-nzExtra="@@evaluation.task.contextHint"
              >
                <textarea
                  nz-input
                  id="t-ctx"
                  class="mono-textarea"
                  formControlName="context"
                  [nzAutosize]="{ minRows: 3, maxRows: 20 }"
                ></textarea>
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzRequired nzFor="t-rubric" i18n="@@evaluation.task.rubric"
                >Rubric (markdown)</nz-form-label
              >
              <nz-form-control
                nzErrorTip="Required"
                i18n-nzErrorTip="@@evaluation.task.required"
                nzExtra="Criteria the judge model scores 0-10 each."
                i18n-nzExtra="@@evaluation.task.rubricHint"
              >
                <textarea
                  nz-input
                  id="t-rubric"
                  class="mono-textarea"
                  formControlName="rubric"
                  [nzAutosize]="{ minRows: 4, maxRows: 20 }"
                ></textarea>
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzFor="t-ref" i18n="@@evaluation.task.reference"
                >Reference answer (optional)</nz-form-label
              >
              <nz-form-control>
                <textarea
                  nz-input
                  id="t-ref"
                  class="mono-textarea"
                  formControlName="referenceAnswer"
                  [nzAutosize]="{ minRows: 3, maxRows: 20 }"
                ></textarea>
              </nz-form-control>
            </nz-form-item>

            @if (kind() === 'CODING_JOB') {
              <h3 class="sec" i18n="@@evaluation.task.repository">Repository</h3>
              <div class="grid" formGroupName="repository">
                <nz-form-item class="span-2">
                  <nz-form-label nzRequired nzFor="t-url" i18n="@@evaluation.task.cloneUrl"
                    >Clone URL</nz-form-label
                  >
                  <nz-form-control
                    nzErrorTip="Required for coding jobs"
                    i18n-nzErrorTip="@@evaluation.task.requiredCoding"
                    ><input
                      nz-input
                      id="t-url"
                      class="mono"
                      formControlName="url"
                      [placeholder]="examples.url"
                  /></nz-form-control>
                </nz-form-item>
                <nz-form-item>
                  <nz-form-label nzRequired nzFor="t-ref-branch" i18n="@@evaluation.task.baseRef"
                    >Base ref</nz-form-label
                  >
                  <nz-form-control
                    nzErrorTip="Required"
                    i18n-nzErrorTip="@@evaluation.task.required"
                    ><input
                      nz-input
                      id="t-ref-branch"
                      class="mono"
                      formControlName="baseRef"
                      [placeholder]="examples.baseRef"
                  /></nz-form-control>
                </nz-form-item>
                <nz-form-item class="span-3">
                  <nz-form-label nzFor="t-setup" i18n="@@evaluation.task.setup"
                    >Setup command</nz-form-label
                  >
                  <nz-form-control
                    ><input
                      nz-input
                      id="t-setup"
                      class="mono"
                      formControlName="setupCommand"
                      [placeholder]="examples.setup"
                  /></nz-form-control>
                </nz-form-item>
                <nz-form-item class="span-3">
                  <nz-form-label nzRequired nzFor="t-verify" i18n="@@evaluation.task.verify"
                    >Verify command</nz-form-label
                  >
                  <nz-form-control
                    nzErrorTip="Required"
                    i18n-nzErrorTip="@@evaluation.task.required"
                    nzExtra="Exit code 0 means passed."
                    i18n-nzExtra="@@evaluation.task.verifyHint"
                    ><input
                      nz-input
                      id="t-verify"
                      class="mono"
                      formControlName="verifyCommand"
                      [placeholder]="examples.verify"
                  /></nz-form-control>
                </nz-form-item>
                <nz-form-item class="full">
                  <nz-form-label nzFor="t-acceptance" i18n="@@evaluation.task.acceptance"
                    >Acceptance checks (JSON)</nz-form-label
                  >
                  <nz-form-control
                    nzErrorTip="Not valid: an object with files (path to content), protectedPaths and requireChanges"
                    i18n-nzErrorTip="@@evaluation.task.acceptanceInvalid"
                    nzExtra="Hidden from the agent: files (e.g. acceptance tests) are written only for the verification command, protectedPaths (git pathspecs such as existing tests) must stay unchanged, requireChanges fails a run that changed nothing. Empty = none."
                    i18n-nzExtra="@@evaluation.task.acceptanceHint"
                  >
                    <textarea
                      nz-input
                      id="t-acceptance"
                      class="mono"
                      formControlName="acceptance"
                      rows="6"
                      [placeholder]="examples.acceptance"
                    ></textarea>
                  </nz-form-control>
                </nz-form-item>
              </div>
            }

            <h3 class="sec" i18n="@@evaluation.task.execution">Execution</h3>
            <div class="grid">
              <nz-form-item>
                <nz-form-label nzFor="t-timeout" i18n="@@evaluation.task.timeout"
                  >Timeout (seconds)</nz-form-label
                >
                <nz-form-control
                  ><nz-input-number
                    id="t-timeout"
                    formControlName="timeoutSeconds"
                    [nzMin]="30"
                    [nzStep]="60"
                    [nzPrecision]="0"
                    style="width: 100%"
                /></nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="t-weight" i18n="@@evaluation.task.weight"
                  >Weight</nz-form-label
                >
                <nz-form-control
                  nzExtra="Relative weight in suite aggregates."
                  i18n-nzExtra="@@evaluation.task.weightHint"
                  ><nz-input-number
                    id="t-weight"
                    formControlName="weight"
                    [nzMin]="0"
                    [nzStep]="0.5"
                    style="width: 100%"
                /></nz-form-control>
              </nz-form-item>
            </div>
          </form>
        </nz-tab>
        <nz-tab nzTitle="Preview" i18n-nzTitle="@@evaluation.task.tab.preview">
          @if (tab() === 1) {
            <div class="preview">
              <h3 class="sec" i18n="@@evaluation.task.preview.instructions">Instructions</h3>
              <app-markdown [source]="value().instructions || text.noInstructions" />
              @if (value().context) {
                <h3 class="sec" i18n="@@evaluation.task.preview.context">Context</h3>
                <pre class="code-block ctx">{{ value().context }}</pre>
              }
              <h3 class="sec" i18n="@@evaluation.task.preview.rubric">Rubric</h3>
              <app-markdown [source]="value().rubric || text.noRubric" />
              @if (value().referenceAnswer) {
                <h3 class="sec" i18n="@@evaluation.task.preview.reference">Reference answer</h3>
                <app-markdown [source]="value().referenceAnswer" />
              }
              @if (value().kind === 'CODING_JOB') {
                <h3 class="sec" i18n="@@evaluation.task.repository">Repository</h3>
                <pre class="code-block ctx">{{ repoText() }}</pre>
              }
            </div>
          }
        </nz-tab>
      </nz-tabs>
      <div boPanelFooter>
        <bo-action-bar
          [sticky]="false"
          [dirty]="dirty()"
          [saving]="saving()"
          [canSave]="valid()"
          [requireDirty]="!!task()?.id"
          [showSave]="open() && !readonly()"
          [saveLabel]="task()?.id ? text.save : text.add"
          [cleanLabel]="readonly() ? text.readOnly : task()?.id ? text.noChanges : text.notSaved"
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
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 0 16px;
      }
      .full {
        grid-column: 1 / -1;
      }
      .span-2 {
        grid-column: span 2;
      }
      .span-3 {
        grid-column: span 3;
      }
      .kind-help {
        margin: -8px 0 16px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .sec {
        margin: 12px 0 8px;
        font-size: 12px;
        font-weight: 600;
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
      .ctx {
        max-height: 220px;
      }
      [boPanelFooter] {
        display: block;
        margin: -13px -20px -12px;
      }
    `,
  ],
})
export class TaskEditorDrawerComponent {
  private readonly fb = inject(FormBuilder);

  readonly open = input(false);
  readonly task = input<EvalTask | null>(null);
  readonly saving = input(false);
  /** Without `evaluation.run`: view only. */
  readonly readonly = input(false);
  readonly saved = output<EvalTaskInput>();
  readonly closed = output<void>();

  readonly categories = TASK_CATEGORIES;
  readonly difficulties = DIFFICULTIES;
  readonly humanize = humanize;
  readonly category = categoryLabel;
  /** Sample values (code, the same in every language). */
  readonly examples = {
    url: 'https://gitlab.example.com/samples/kotlin-shop.git', // i18n-ignore: sample URL
    baseRef: 'main', // i18n-ignore: branch name
    setup: './gradlew assemble', // i18n-ignore: command
    verify: './gradlew test', // i18n-ignore: command
    acceptance:
      '{ "files": { "src/test/java/acceptance/HiddenTest.java": "..." }, "protectedPaths": ["src/test/java/**"], "requireChanges": true }', // i18n-ignore: sample JSON
  };
  readonly text = {
    details: $localize`:@@evaluation.task.tab.details:Details`,
    edit: $localize`:@@evaluation.task.tab.edit:Edit`,
    noInstructions: $localize`:@@evaluation.task.noInstructions:_No instructions yet._`,
    noRubric: $localize`:@@evaluation.task.noRubric:_No rubric yet._`,
    save: $localize`:@@evaluation.task.save:Save task`,
    add: $localize`:@@evaluation.suite.addTaskAction:Add task`,
    readOnly: $localize`:@@evaluation.task.readOnly:Read-only: needs evaluation.run`,
    noChanges: $localize`:@@evaluation.task.noChanges:No changes`,
    notSaved: $localize`:@@evaluation.task.notSaved:Not saved yet`,
    discard: $localize`:@@evaluation.task.discard:Discard changes`,
    close: $localize`:@@evaluation.task.close:Close`,
  };
  readonly title = computed(() => {
    const t = this.task();
    if (t?.id) return t.name;
    if (!t) return $localize`:@@evaluation.task.new:New task`;
    const name = t.name;
    return $localize`:@@evaluation.task.copyOf:Copy of ${name}:name:`;
  });
  readonly tab = signal(0);
  readonly dirty = signal(false);
  private readonly version = signal(0);

  readonly form = this.fb.group({
    name: this.fb.nonNullable.control('', Validators.required),
    category: this.fb.nonNullable.control<TaskCategory>('ANALYSIS', Validators.required),
    difficulty: this.fb.nonNullable.control<Difficulty>('MEDIUM', Validators.required),
    kind: this.fb.nonNullable.control<TaskKind>('PROMPT', Validators.required),
    instructions: this.fb.nonNullable.control('', Validators.required),
    context: this.fb.nonNullable.control(''),
    rubric: this.fb.nonNullable.control('', Validators.required),
    referenceAnswer: this.fb.nonNullable.control(''),
    repository: this.fb.group({
      url: this.fb.nonNullable.control(''),
      baseRef: this.fb.nonNullable.control('main'),
      setupCommand: this.fb.nonNullable.control(''),
      verifyCommand: this.fb.nonNullable.control(''),
      acceptance: this.fb.nonNullable.control('', [acceptanceValidator]),
    }),
    timeoutSeconds: this.fb.nonNullable.control(900),
    weight: this.fb.nonNullable.control(1),
  });

  readonly value = computed(() => {
    this.version();
    return this.form.getRawValue();
  });
  readonly kind = computed(() => this.value().kind);
  readonly valid = computed(() => {
    this.version();
    return this.form.valid;
  });
  readonly subtitle = computed(() => {
    const t = this.task();
    if (!t)
      return $localize`:@@evaluation.task.subtitleNew:A prompt judged by rubric, or a coding job verified by tests`;
    const kind =
      t.kind === 'CODING_JOB'
        ? $localize`:@@evaluation.suite.kind.codingJob:Coding job`
        : $localize`:@@evaluation.suite.kind.prompt:Prompt`;
    return `${kind} · ${categoryLabel(t.category)} · ${humanize(t.difficulty).toLowerCase()}`;
  });
  readonly repoText = computed(() => {
    const r = this.value().repository;
    const a = parseAcceptance(r.acceptance);
    const acceptance = a
      ? `\nacceptance: ${Object.keys(a.files ?? {}).length} hidden file(s), ${(a.protectedPaths ?? []).length} protected path(s)${a.requireChanges === false ? '' : ', changes required'}`
      : '';
    return `${r.url} @ ${r.baseRef}\nsetup:  ${r.setupCommand || '-'}\nverify: ${r.verifyCommand || '-'}${acceptance}`;
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.form.valueChanges.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => {
      this.dirty.set(this.form.dirty);
      this.version.update((v) => v + 1);
    });
    this.form.controls.kind.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe((kind) => this.applyKindValidators(kind));
    effect(() => {
      if (!this.open()) return;
      const t = this.task();
      const ro = this.readonly();
      untracked(() => {
        this.form.reset({
          name: t?.name ?? '',
          category: t?.category ?? 'ANALYSIS',
          difficulty: t?.difficulty ?? 'MEDIUM',
          kind: t?.kind ?? 'PROMPT',
          instructions: t?.instructions ?? '',
          context: t?.context ?? '',
          rubric: t?.rubric ?? '',
          referenceAnswer: t?.referenceAnswer ?? '',
          repository: {
            url: t?.repository?.url ?? '',
            baseRef: t?.repository?.baseRef ?? 'main',
            setupCommand: t?.repository?.setupCommand ?? '',
            verifyCommand: t?.repository?.verifyCommand ?? '',
            acceptance: t?.repository?.acceptance
              ? JSON.stringify(t.repository.acceptance, null, 2)
              : '',
          },
          timeoutSeconds: t?.timeoutSeconds ?? 900,
          weight: t?.weight ?? 1,
        });
        this.applyKindValidators(this.form.controls.kind.value);
        this.form.markAsPristine();
        this.dirty.set(false);
        this.tab.set(ro && t ? 1 : 0);
        this.version.update((v) => v + 1);
      });
    });
  }

  private applyKindValidators(kind: TaskKind): void {
    const repo = this.form.controls.repository.controls;
    const required = kind === 'CODING_JOB' ? [Validators.required] : [];
    repo.url.setValidators(required);
    repo.baseRef.setValidators(required);
    repo.verifyCommand.setValidators(required);
    repo.url.updateValueAndValidity({ emitEvent: false });
    repo.baseRef.updateValueAndValidity({ emitEvent: false });
    repo.verifyCommand.updateValueAndValidity({ emitEvent: false });
    this.form.updateValueAndValidity({ emitEvent: false });
    this.version.update((v) => v + 1);
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.tab.set(0);
      return;
    }
    const v = this.form.getRawValue();
    const input: EvalTaskInput = {
      name: v.name.trim(),
      category: v.category,
      difficulty: v.difficulty,
      kind: v.kind,
      instructions: v.instructions,
      context: v.context.trim() || null,
      rubric: v.rubric,
      referenceAnswer: v.referenceAnswer.trim() || null,
      repository:
        v.kind === 'CODING_JOB'
          ? {
              url: v.repository.url.trim(),
              baseRef: v.repository.baseRef.trim(),
              setupCommand: v.repository.setupCommand.trim() || null,
              verifyCommand: v.repository.verifyCommand.trim(),
              acceptance: parseAcceptance(v.repository.acceptance),
            }
          : null,
      timeoutSeconds: v.timeoutSeconds,
      weight: v.weight,
    };
    this.saved.emit(input);
  }
}
