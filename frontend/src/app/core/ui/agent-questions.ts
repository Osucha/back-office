/**
 * ADR 0060: an agent step that cannot do its work well without the owner's answers stops with questions; the run
 * waits on an approval of kind AGENT_QUESTIONS. Option `answer` (the answers in the comment) re-runs the step with
 * them, `cancel` fails it. Pure helpers shared by the gate panel, the run page, the turn queue and notifications.
 */
import type { AgentQuestion, ApprovalRequest } from '../api/types';
import { icu } from '../i18n/icu';

export const ANSWER_OPTION = 'answer';
export const CANCEL_OPTION = 'cancel';

export function isAgentQuestions(a: Pick<ApprovalRequest, 'kind'> | null | undefined): boolean {
  return a?.kind === 'AGENT_QUESTIONS';
}

/** The questions of an AGENT_QUESTIONS approval (empty texts dropped). */
export function agentQuestions(
  a: Pick<ApprovalRequest, 'questions'> | null | undefined,
): AgentQuestion[] {
  return (a?.questions ?? []).filter((q) => !!q.question?.trim());
}

/**
 * The comment sent with `answer`: one numbered line per question ("1. <answer>"), an empty answer as "-" so the
 * numbers stay with their questions; a single question gets its answer as is. Empty when nothing was answered.
 */
export function combineAnswers(answers: readonly (string | null | undefined)[]): string {
  const clean = answers.map((a) => (a ?? '').trim());
  if (!clean.some((a) => !!a)) return '';
  if (clean.length === 1) return clean[0];
  return clean.map((a, i) => `${i + 1}. ${a || '-'}`).join('\n');
}

/** "Round 1 of 2" (or "Round 1" while the limit is not known). */
export function questionRoundText(
  round: number | null | undefined,
  max?: number | null,
): string | null {
  if (!round) return null;
  return max
    ? $localize`:@@core.questions.roundOf:Round ${round}:round: of ${max}:max:`
    : $localize`:@@core.questions.round:Round ${round}:round:`;
}

/** "1 question", "3 questions". */
export function questionsCountText(count: number): string {
  return icu(
    $localize`:@@core.questions.count:{count, plural, one {# question} other {# questions}}`,
    { count },
  );
}
