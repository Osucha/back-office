import {
  CODING_STEP_KEYS,
  DEFAULT_QUESTION_ROUNDS,
  stepParameterFields,
  stepQuestions,
  withStepQuestions,
} from './step-session';

describe('step questions (ADR 0060)', () => {
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
    const set = stepParameterFields(task, null, {
      parameterBindings: { task: { source: 'VALUE', value: 'Summarise' } },
    });
    expect(set[0].value).toBe('Summarise');
    expect(set[0].missing).toBe(false);
  });

  it('reads legacy step values as fixed values; START and ARTIFACT bindings set none', () => {
    const legacy = stepParameterFields(task, null, { parameters: { task: 'Old' } } as never);
    expect(legacy[0].value).toBe('Old');
    const start = stepParameterFields(task, null, {
      parameterBindings: { task: { source: 'START' } },
    });
    expect(start[0].value).toBeNull();
  });

  it('keeps the ignored ADR 0060 step keys out of the config form (ADR 0062)', () => {
    expect(CODING_STEP_KEYS).toEqual(
      expect.arrayContaining(['directory', 'session', 'continueSession', 'sessionRole']),
    );
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
