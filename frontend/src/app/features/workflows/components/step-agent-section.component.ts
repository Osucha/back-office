import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import {
  type SessionCandidate,
  type SessionMode,
  type StepDirectory,
  type StepQuestions,
  continuedStep,
  stepDirectory,
  stepQuestions,
  stepSessionMode,
  withSessionMode,
  withStepDirectory,
  withStepQuestions,
} from '../data/step-session';

/**
 * ADR 0060: where and how the agent of a step works. Coding agent steps: the directory (the run directory shared by
 * the coding steps of a run, or a separate fresh copy of only the step's repositories) and the agent session (its own,
 * new each time, or the session of an earlier step of the same agent). Every agent step: whether the agent may stop
 * with questions to the owner, and in how many rounds.
 */
@Component({
  selector: 'app-step-agent-section',
  imports: [
    FormsModule,
    NzFormModule,
    NzInputNumberModule,
    NzRadioModule,
    NzSelectModule,
    NzSwitchModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (coding()) {
      <nz-form-item data-step-directory>
        <nz-form-label
          [nzTooltipTitle]="directory() === 'SEPARATE' ? text.separateHelp : text.runHelp"
          i18n="@@workflows.stepAgent.directory"
          >Directory</nz-form-label
        >
        <nz-form-control>
          <nz-radio-group
            name="directory"
            nzSize="small"
            [ngModel]="directory()"
            (ngModelChange)="setDirectory($event)"
            [nzDisabled]="readonly()"
          >
            <label nz-radio-button nzValue="RUN" i18n="@@workflows.stepAgent.directory.run"
              >Run directory (shared)</label
            >
            <label
              nz-radio-button
              nzValue="SEPARATE"
              i18n="@@workflows.stepAgent.directory.separate"
              >Separate directory</label
            >
          </nz-radio-group>
        </nz-form-control>
      </nz-form-item>
      <nz-form-item data-agent-session>
        <nz-form-label
          [nzTooltipTitle]="directory() === 'SEPARATE' ? text.sessionSeparate : text.sessionHelp"
          i18n="@@workflows.stepAgent.session"
          >Agent session</nz-form-label
        >
        <nz-form-control
          [nzValidateStatus]="sessionProblem() ? 'error' : ''"
          [nzErrorTip]="sessionProblem() ?? ''"
        >
          <nz-radio-group
            class="stack"
            name="sessionMode"
            [ngModel]="sessionMode()"
            (ngModelChange)="setSessionMode($event)"
            [nzDisabled]="readonly() || directory() === 'SEPARATE'"
          >
            <label nz-radio nzValue="OWN" i18n="@@workflows.stepAgent.session.own"
              >Own (repeats continue)</label
            >
            <label nz-radio nzValue="NEW" i18n="@@workflows.stepAgent.session.new"
              >New each time</label
            >
            <label
              nz-radio
              nzValue="CONTINUE"
              [nzDisabled]="!pickable().length && !continued()"
              i18n="@@workflows.stepAgent.session.continue"
              >Continue the session of step …</label
            >
          </nz-radio-group>
          @if (sessionMode() === 'CONTINUE' && directory() !== 'SEPARATE') {
            <nz-select
              class="session-select"
              name="continueSession"
              nzShowSearch
              nzPlaceHolder="Pick the step"
              i18n-nzPlaceHolder="@@workflows.stepAgent.session.pick"
              [ngModel]="continued()"
              (ngModelChange)="setContinue($event)"
              [nzDisabled]="readonly()"
              aria-label="Step whose session this step continues"
              i18n-aria-label="@@workflows.stepAgent.session.pickLabel"
            >
              @for (c of candidates(); track c.id) {
                <nz-option [nzValue]="c.id" [nzLabel]="optionLabel(c)" [nzDisabled]="!!c.blocked" />
              }
              @if (unknownContinued(); as id) {
                <nz-option [nzValue]="id" [nzLabel]="id" nzDisabled />
              }
            </nz-select>
          }
        </nz-form-control>
      </nz-form-item>
    }
    <nz-form-item data-step-questions>
      <nz-form-label [nzTooltipTitle]="text.questionsHelp" i18n="@@workflows.stepAgent.questions"
        >Agent may ask questions</nz-form-label
      >
      <nz-form-control>
        <nz-switch
          name="allowQuestions"
          [ngModel]="questions().allow"
          (ngModelChange)="setQuestions({ allow: $event })"
          [nzDisabled]="readonly()"
          [attr.aria-label]="text.questionsLabel"
        />
      </nz-form-control>
    </nz-form-item>
    @if (questions().allow) {
      <nz-form-item>
        <nz-form-label
          [nzTooltipTitle]="text.roundsHelp"
          nzFor="pp-question-rounds"
          i18n="@@workflows.stepAgent.rounds"
          >Question rounds</nz-form-label
        >
        <nz-form-control>
          <nz-input-number
            id="pp-question-rounds"
            name="maxQuestionRounds"
            [nzMin]="1"
            [nzMax]="10"
            [nzStep]="1"
            [ngModel]="questions().rounds"
            (ngModelChange)="setRounds($event)"
            [nzDisabled]="readonly()"
          />
        </nz-form-control>
      </nz-form-item>
    }
  `,
  styles: [
    `
      .stack {
        display: flex;
        flex-direction: column;
        gap: 4px;
        margin-bottom: 6px;
      }
      .session-select {
        width: 100%;
      }
    `,
  ],
})
export class StepAgentSectionComponent {
  /** The node (a draft choice is dropped when another node is selected). */
  readonly nodeId = input<string | null>(null);
  /** The node's `overrides.config`. */
  readonly config = input<Record<string, unknown> | null | undefined>(null);
  /** A coding agent runs the step (directory and session apply). */
  readonly coding = input(false);
  /** The other coding agent steps of the workflow; blocked ones are offered disabled with the reason. */
  readonly candidates = input<readonly SessionCandidate[]>([]);
  readonly readonly = input(false);
  /** The new `overrides.config` (undefined when it became empty). */
  readonly configChange = output<Record<string, unknown> | undefined>();

  readonly text = {
    runHelp: $localize`:@@workflows.stepAgent.directory.runHelp:The coding steps of a run work here one after another and see what earlier steps left, uncommitted work included.`,
    separateHelp: $localize`:@@workflows.stepAgent.directory.separateHelp:A fresh copy of only this step's repositories, thrown away after the step. For isolation (an agent that must not see the rest); no session continuation.`,
    sessionHelp: $localize`:@@workflows.stepAgent.session.help:Own: repeated executions of this step (e.g. rework) continue its conversation. Continuing another step needs the same agent.`,
    sessionSeparate: $localize`:@@workflows.stepAgent.session.separate:A step in a separate directory always starts a new session.`,
    questionsHelp: $localize`:@@workflows.stepAgent.questions.help:When the agent cannot do the step well without your answers, it stops with questions and the run waits for you.`,
    questionsLabel: $localize`:@@workflows.stepAgent.questions:Agent may ask questions`,
    roundsHelp: $localize`:@@workflows.stepAgent.rounds.help:After this many rounds the agent must finish with what it has.`,
  };

  readonly directory = computed<StepDirectory>(() => stepDirectory(this.config()));
  readonly continued = computed(() => continuedStep(this.config()));
  readonly questions = computed<StepQuestions>(() => stepQuestions(this.config()));
  /** The radio shows "continue" while the user picks the step (before a value is set). */
  private readonly draft = signal<SessionMode | null>(null);
  readonly sessionMode = computed<SessionMode>(() => {
    const stored = stepSessionMode(this.config());
    return stored === 'CONTINUE' ? stored : (this.draft() ?? stored);
  });
  readonly pickable = computed(() => this.candidates().filter((c) => !c.blocked));
  readonly unknownContinued = computed(() => {
    const id = this.continued();
    return id && !this.candidates().some((c) => c.id === id) ? id : null;
  });
  readonly sessionProblem = computed(() => {
    if (this.directory() === 'SEPARATE') return null;
    const id = this.continued();
    if (this.sessionMode() === 'CONTINUE' && !id)
      return $localize`:@@workflows.stepAgent.session.pickRequired:Pick the step whose session this step continues`;
    if (!id) return null;
    const c = this.candidates().find((x) => x.id === id);
    if (!c)
      return $localize`:@@workflows.stepAgent.session.unknown:Step ${id}:id: is not a coding agent step of this workflow`;
    return c.blocked ? this.blockedReason(c) : null;
  });

  constructor() {
    // another node selected, or a stored mode written: the draft is done
    effect(() => {
      this.nodeId();
      untracked(() => this.draft.set(null));
    });
    effect(() => {
      const mode = stepSessionMode(this.config());
      untracked(() => {
        if (mode !== 'OWN') this.draft.set(null);
      });
    });
  }

  optionLabel(c: SessionCandidate): string {
    return c.blocked ? `${c.name} (${this.blockedReason(c)})` : c.name;
  }

  blockedReason(c: SessionCandidate): string {
    return c.blocked === 'SEPARATE'
      ? $localize`:@@workflows.stepAgent.session.blockedSeparate:works in a separate directory`
      : $localize`:@@workflows.stepAgent.session.blockedAgent:another agent: ${c.agent}:agent:`;
  }

  setDirectory(d: StepDirectory): void {
    this.draft.set(null);
    this.configChange.emit(withStepDirectory(this.config(), d));
  }

  setSessionMode(mode: SessionMode): void {
    if (mode === 'CONTINUE') {
      // written once a step is picked
      this.draft.set('CONTINUE');
      if (this.continued()) return;
      this.configChange.emit(withSessionMode(this.config(), 'OWN'));
      return;
    }
    this.draft.set(null);
    this.configChange.emit(withSessionMode(this.config(), mode));
  }

  setContinue(nodeId: string | null): void {
    this.configChange.emit(
      withSessionMode(this.config(), nodeId ? 'CONTINUE' : 'OWN', nodeId ?? null),
    );
  }

  setQuestions(change: Partial<StepQuestions>): void {
    this.configChange.emit(withStepQuestions(this.config(), change));
  }

  setRounds(value: number | null): void {
    const n = Math.round(Number(value));
    if (Number.isFinite(n) && n >= 1) this.setQuestions({ rounds: n });
  }
}
