import type { WorkflowNode } from '../../../core/api/types';
import {
  DEFAULT_QUESTION_ROUNDS,
  continuedStep,
  continuesFromName,
  sessionCandidates,
  stepDirectory,
  stepParameterFields,
  stepQuestions,
  stepSessionMode,
  withContinueSession,
  withSessionMode,
  withStepDirectory,
  withStepQuestions,
} from './step-session';

const node = (id: string, kind: WorkflowNode['kind'], extra: Partial<WorkflowNode> = {}) =>
  ({ id, kind, name: id.toUpperCase(), position: { x: 0, y: 0 }, ...extra }) as WorkflowNode;

describe('step session (ADR 0057)', () => {
  it('reads and writes continueSession, keeping the other config keys', () => {
    expect(continuedStep(undefined)).toBeNull();
    expect(continuedStep({ continueSession: ' ' })).toBeNull();
    expect(continuedStep({ continueSession: 'spec' })).toBe('spec');
    expect(withContinueSession({ push: true }, 'spec')).toEqual({
      push: true,
      session: 'CONTINUE',
      continueSession: 'spec',
    });
    expect(withContinueSession({ push: true, continueSession: 'spec' }, null)).toEqual({
      push: true,
    });
    expect(withContinueSession({ continueSession: 'spec' }, null)).toBeUndefined();
  });

  it('offers the other coding agent steps; only the same agent outside a separate directory', () => {
    const nodes = [
      node('start', 'START'),
      node('spec', 'TASK'),
      node('review', 'TASK'),
      node('impl', 'TASK'),
      node('fix', 'TASK'),
      node('iso', 'TASK', {
        task: { templateKey: 't', overrides: { config: { directory: 'SEPARATE' } } },
      }),
    ];
    const agent: Record<string, string | null> = {
      spec: 'dev',
      review: null,
      impl: 'dev',
      fix: 'fixer',
      iso: 'dev',
    };
    const c = sessionCandidates(nodes, 'impl', 'dev', (n) => {
      const key = agent[n.id];
      return key ? { key, name: key, harness: 'CLAUDE_CODE' } : null;
    });
    expect(c).toEqual([
      { id: 'spec', name: 'SPEC', agent: 'dev', blocked: null },
      { id: 'fix', name: 'FIX', agent: 'fixer', blocked: 'OTHER_AGENT' },
      { id: 'iso', name: 'ISO', agent: 'dev', blocked: 'SEPARATE' },
    ]);
  });

  it('names the continued step for the canvas marker', () => {
    const impl = node('impl', 'TASK', {
      task: { templateKey: 't', overrides: { config: { continueSession: 'spec' } } },
    });
    expect(continuesFromName(impl, new Map([['spec', 'Write spec']]))).toBe('Write spec');
    expect(continuesFromName(impl, null)).toBe('spec');
    expect(continuesFromName(node('n', 'NOTE'), null)).toBeNull();
    expect(continuesFromName(node('t', 'TASK'), null)).toBeNull();
  });
});

describe('step directory, session and questions (ADR 0060)', () => {
  it('reads the directory and writes SEPARATE without session settings', () => {
    expect(stepDirectory(undefined)).toBe('RUN');
    expect(stepDirectory({ directory: 'separate' })).toBe('SEPARATE');
    expect(
      withStepDirectory({ session: 'NEW', continueSession: 'a', workspace: 'w' }, 'SEPARATE'),
    ).toEqual({ directory: 'SEPARATE' });
    expect(withStepDirectory({ directory: 'SEPARATE', push: true }, 'RUN')).toEqual({ push: true });
    expect(withStepDirectory({ directory: 'SEPARATE' }, 'RUN')).toBeUndefined();
  });

  it('reads and writes the session mode; OWN is the default and not written', () => {
    expect(stepSessionMode(null)).toBe('OWN');
    expect(stepSessionMode({ session: 'new' })).toBe('NEW');
    expect(stepSessionMode({ continueSession: 'spec' })).toBe('CONTINUE');
    expect(withSessionMode({ session: 'NEW', sessionRole: 'dev' }, 'OWN')).toBeUndefined();
    expect(withSessionMode({ continueSession: 'spec' }, 'NEW')).toEqual({ session: 'NEW' });
    expect(withSessionMode(null, 'CONTINUE', 'spec')).toEqual({
      session: 'CONTINUE',
      continueSession: 'spec',
    });
  });

  it('reads and writes the questions; defaults are not written', () => {
    expect(stepQuestions(undefined)).toEqual({ allow: true, rounds: DEFAULT_QUESTION_ROUNDS });
    expect(stepQuestions({ allowQuestions: false, maxQuestionRounds: 0 })).toEqual({
      allow: false,
      rounds: 2,
    });
    expect(stepQuestions({ maxQuestionRounds: 3 }).rounds).toBe(3);
    expect(withStepQuestions({ push: true }, { allow: false })).toEqual({
      push: true,
      allowQuestions: false,
    });
    expect(withStepQuestions({ allowQuestions: false }, { allow: true })).toBeUndefined();
    expect(withStepQuestions(null, { rounds: 4 })).toEqual({ maxQuestionRounds: 4 });
    expect(withStepQuestions({ maxQuestionRounds: 4 }, { rounds: 2 })).toBeUndefined();
  });
});

describe('step parameters (ADR 0058)', () => {
  const task = {
    key: 'llm-task',
    parameters: [{ name: 'task', label: 'Task', type: 'MULTILINE' as const, required: true }],
    parameterValues: {},
  };

  it('uses the palette declarations and the step values', () => {
    const missing = stepParameterFields(task, null, {});
    expect(missing[0].missing).toBe(true);
    const set = stepParameterFields(task, null, { parameters: { task: 'Summarise' } });
    expect(set[0].value).toBe('Summarise');
    expect(set[0].missing).toBe(false);
  });

  it('falls back to the resolved template', () => {
    const fields = stepParameterFields(
      { parameters: [] },
      {
        parameters: [
          {
            name: 'tone',
            label: 'Tone',
            type: 'TEXT',
            required: true,
            value: 'formal',
            source: 'TEMPLATE',
          },
        ],
      },
      null,
    );
    expect(fields[0].inherited).toBe('formal');
    expect(fields[0].inheritedSource).toBe('TEMPLATE');
    expect(fields[0].missing).toBe(false);
    expect(stepParameterFields(null, null, null)).toEqual([]);
  });
});
