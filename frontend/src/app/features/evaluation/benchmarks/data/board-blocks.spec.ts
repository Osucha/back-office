import type { Attempt, LeaderboardRow } from '../../../../core/api/types';
import {
  agentLabel,
  allOnSubscription,
  blockVerdict,
  unreliable,
  attemptKind,
  blockTitle,
  mixedAgents,
  passedCount,
  rankRows,
  rowKind,
  splitByKind,
  tasksPerKind,
} from './board-blocks';
import { leaderboardToScatter, paretoFrontier } from './leaderboard-chart';

const usd = (amount: number) => ({ amount, currency: 'USD' });

function row(
  agentKey: string,
  harness: 'CLAUDE_CODE' | 'OPENCODE' | null,
  model: string,
  extra: Partial<LeaderboardRow> = {},
): LeaderboardRow {
  return {
    candidate: {
      agentKey,
      agentName: agentKey,
      harness,
      modelProfileId: model,
      label: `${agentKey} · ${model}`,
    },
    modelProfileId: model,
    modelDisplayName: model,
    attempts: 5,
    ...extra,
  };
}

const ANSWER_OPUS = row('model-answer', null, 'Opus', {
  meanScore: 90,
  passRate: 0.8,
  costPerAccepted: usd(0.3),
  notApplicable: 5,
});
const ANSWER_HAIKU = row('model-answer', null, 'Haiku', {
  meanScore: 60,
  passRate: 0,
  costPerAccepted: null,
  notApplicable: 5,
});
const ANSWER_SONNET = row('model-answer', null, 'Sonnet', {
  meanScore: 85,
  passRate: 1,
  costPerAccepted: usd(0.1),
  notApplicable: 5,
});
const CODE_OPUS = row('claude-code', 'CLAUDE_CODE', 'Opus', {
  meanScore: 70,
  passRate: 0.4,
  costPerAccepted: usd(3),
  notApplicable: 7,
});
const CODE_SONNET = row('opencode', 'OPENCODE', 'Sonnet', {
  meanScore: 95,
  passRate: 0.6,
  costPerAccepted: usd(2),
  notApplicable: 7,
});
const ROWS = [CODE_OPUS, ANSWER_OPUS, ANSWER_HAIKU, CODE_SONNET, ANSWER_SONNET];

describe('kind of work', () => {
  it('derives the kind from the harness; rows without a candidate are model answers', () => {
    expect(rowKind(ANSWER_OPUS)).toBe('answer');
    expect(rowKind(CODE_OPUS)).toBe('coding');
    expect(rowKind({ modelDisplayName: 'old' } as LeaderboardRow)).toBe('answer');
  });

  it('derives an attempt kind from its task kind, else its candidate', () => {
    expect(attemptKind({ kind: 'CODING_JOB' })).toBe('coding');
    expect(attemptKind({ kind: 'PROMPT', candidate: CODE_OPUS.candidate })).toBe('answer');
    expect(attemptKind({ candidate: CODE_OPUS.candidate })).toBe('coding');
    expect(attemptKind({})).toBe('answer');
  });
});

describe('splitByKind', () => {
  it('gives one block per kind, model answers first, with the harnesses of the coding block', () => {
    const blocks = splitByKind(ROWS);
    expect(blocks.map((b) => b.kind)).toEqual(['answer', 'coding']);
    expect(blocks[0].rows).toEqual([ANSWER_OPUS, ANSWER_HAIKU, ANSWER_SONNET]);
    expect(blocks[1].harnesses).toEqual(['CLAUDE_CODE', 'OPENCODE']);
  });

  it('gives one block for a benchmark of one kind', () => {
    expect(splitByKind([ANSWER_OPUS, ANSWER_SONNET]).length).toBe(1);
    expect(splitByKind(null)).toEqual([]);
  });

  it('ranks and draws the Pareto front within a block only', () => {
    const [answers, coding] = splitByKind(ROWS);
    expect(rankRows(answers.rows).map((r) => r.modelDisplayName)).toEqual([
      'Sonnet',
      'Opus',
      'Haiku',
    ]);
    expect(rankRows(coding.rows).map((r) => r.candidate!.agentKey)).toEqual([
      'opencode',
      'claude-code',
    ]);
    expect(paretoFrontier(leaderboardToScatter(answers.rows)).map((p) => p.name)).toEqual([
      'Sonnet',
      'Opus',
    ]);
    expect(paretoFrontier(leaderboardToScatter(coding.rows)).map((p) => p.title)).toEqual([
      'opencode · Sonnet',
    ]);
  });

  it('flags blocks with candidates of different agents', () => {
    const [answers, coding] = splitByKind(ROWS);
    expect(mixedAgents(answers.rows)).toBe(false);
    expect(mixedAgents(coding.rows)).toBe(true);
  });
});

describe('rankRows', () => {
  it('orders by cost per accepted result, rows without one last, ties by score', () => {
    const a = row('x', null, 'A', { costPerAccepted: usd(1), meanScore: 50 });
    const b = row('x', null, 'B', { costPerAccepted: usd(1), meanScore: 80 });
    const c = row('x', null, 'C', { costPerAccepted: null, meanScore: 99 });
    const d = row('x', null, 'D', { costPerAccepted: usd(0.5), meanScore: 10 });
    expect(rankRows([c, a, b, d]).map((r) => r.modelDisplayName)).toEqual(['D', 'B', 'A', 'C']);
  });

  it('compares subscription candidates on the API figure', () => {
    const sub = row('cc', 'CLAUDE_CODE', 'Login', {
      subscription: true,
      costPerAccepted: usd(0),
      apiCostPerAccepted: usd(5),
    });
    const api = row('oc', 'OPENCODE', 'Api', { costPerAccepted: usd(1) });
    expect(rankRows([sub, api]).map((r) => r.modelDisplayName)).toEqual(['Api', 'Login']);
  });
});

describe('passedCount and allOnSubscription', () => {
  it('counts passed attempts from the pass rate', () => {
    expect(passedCount({ passRate: 0.8, attempts: 5 })).toBe(4);
    expect(passedCount({ passRate: null, attempts: 5 })).toBe(0);
  });

  it('is true only when every candidate that ran was on a subscription', () => {
    const sub = row('cc', 'CLAUDE_CODE', 'A', { subscription: true });
    const empty = row('ma', null, 'B', { attempts: 0 });
    expect(allOnSubscription([sub, empty])).toBe(true);
    expect(allOnSubscription([sub, ANSWER_OPUS])).toBe(false);
    expect(allOnSubscription([empty])).toBe(false);
    expect(allOnSubscription(null)).toBe(false);
  });
});

describe('tasksPerKind', () => {
  const attempt = (taskId: string, kind: Attempt['kind'], repetition = 1): Attempt =>
    ({ taskId, kind, repetition }) as Attempt;

  it('counts distinct tasks of the attempts, repetitions once', () => {
    const attempts = [
      attempt('t1', 'PROMPT', 1),
      attempt('t1', 'PROMPT', 2),
      attempt('t2', 'PROMPT', 1),
      attempt('t3', 'CODING_JOB', 1),
      attempt('t3', 'CODING_JOB', 2),
    ];
    expect(tasksPerKind({ attempts, leaderboard: { overall: ROWS } })).toEqual({
      answer: 2,
      coding: 1,
    });
  });

  it('falls back to the other kind’s not applicable count', () => {
    expect(tasksPerKind({ leaderboard: { overall: ROWS } })).toEqual({ answer: 7, coding: 5 });
  });

  it('divides all attempts by repetitions and candidates for a single kind', () => {
    const rows = [row('ma', null, 'A'), row('ma', null, 'B')];
    expect(
      tasksPerKind({
        leaderboard: { overall: rows },
        candidates: [{ harness: null }, { harness: null }],
        repetitions: 3,
        totalAttempts: 42,
      }),
    ).toEqual({ answer: 7, coding: null });
  });

  it('falls back to the leaderboard by task', () => {
    const rows = [row('ma', null, 'A')];
    expect(
      tasksPerKind({
        leaderboard: {
          overall: rows,
          byTask: [
            { taskId: 'a', rows },
            { taskId: 'b', rows },
          ],
        },
      }).answer,
    ).toBe(2);
  });
});

describe('blockTitle', () => {
  it('names the kind and the number of tasks', () => {
    expect(blockTitle({ kind: 'answer', harnesses: [] }, 7)).toBe('Model answer · 7 tasks');
    expect(blockTitle({ kind: 'coding', harnesses: ['CLAUDE_CODE'] }, 1)).toBe(
      'Coding agent (Claude Code) · 1 task',
    );
    expect(blockTitle({ kind: 'coding', harnesses: [] }, null)).toBe('Coding agent');
  });
});

describe('blockVerdict, unreliable and agentLabel', () => {
  it('names the cheapest accepted result and a different best score', () => {
    const v = blockVerdict([ANSWER_OPUS, ANSWER_HAIKU, ANSWER_SONNET]);
    expect(v.cheapest).toBe(ANSWER_SONNET);
    expect(v.best).toBe(ANSWER_OPUS);
    expect(blockVerdict([ANSWER_SONNET]).best).toBeNull();
    expect(blockVerdict([ANSWER_HAIKU])).toEqual({ cheapest: null, best: ANSWER_HAIKU });
  });

  it('flags rows with less than half of the attempts accepted', () => {
    expect(unreliable(CODE_OPUS)).toBe(true);
    expect(unreliable(ANSWER_OPUS)).toBe(false);
    expect(unreliable({ passRate: null, attempts: 0 })).toBe(false);
  });

  it('names built-in agents in the UI language and others by name', () => {
    expect(agentLabel(ANSWER_OPUS.candidate)).toBe('Model answer');
    expect(agentLabel(CODE_OPUS.candidate)).toBe('Claude Code');
    expect(agentLabel({ agentKey: 'reviewer', agentName: 'Reviewer', harness: 'OPENCODE' })).toBe(
      'Reviewer',
    );
  });
});
