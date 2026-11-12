import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { type StepQuestions, stepQuestions, withStepQuestions } from '../data/step-session';

/**
 * ADR 0060: whether the agent of a step may stop with questions to the owner, and in how many rounds. ADR 0062: the
 * session and the directory of a coding agent are the agent's settings, not the step's.
 */
@Component({
  selector: 'app-step-agent-section',
  imports: [FormsModule, NzFormModule, NzInputNumberModule, NzSwitchModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
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
})
export class StepAgentSectionComponent {
  /** The node's `overrides.config`. */
  readonly config = input<Record<string, unknown> | null | undefined>(null);
  readonly readonly = input(false);
  /** The new `overrides.config` (undefined when it became empty). */
  readonly configChange = output<Record<string, unknown> | undefined>();

  readonly text = {
    questionsHelp: $localize`:@@workflows.stepAgent.questions.help:When the agent cannot do the step well without your answers, it stops with questions and the run waits for you.`,
    questionsLabel: $localize`:@@workflows.stepAgent.questions:Agent may ask questions`,
    roundsHelp: $localize`:@@workflows.stepAgent.rounds.help:After this many rounds the agent must finish with what it has.`,
  };

  readonly questions = computed<StepQuestions>(() => stepQuestions(this.config()));

  setQuestions(change: Partial<StepQuestions>): void {
    this.configChange.emit(withStepQuestions(this.config(), change));
  }

  setRounds(value: number | null): void {
    const n = Math.round(Number(value));
    if (Number.isFinite(n) && n >= 1) this.setQuestions({ rounds: n });
  }
}
