import {
  agentQuestions,
  combineAnswers,
  isAgentQuestions,
  questionRoundText,
  questionsCountText,
} from './agent-questions';

describe('agent questions (ADR 0060)', () => {
  it('tells an AGENT_QUESTIONS approval and lists its questions', () => {
    expect(isAgentQuestions({ kind: 'AGENT_QUESTIONS' })).toBe(true);
    expect(isAgentQuestions({ kind: 'GATE' })).toBe(false);
    expect(isAgentQuestions(null)).toBe(false);
    expect(
      agentQuestions({
        questions: [{ question: 'Which database?', why: 'Two are configured' }, { question: ' ' }],
      }),
    ).toEqual([{ question: 'Which database?', why: 'Two are configured' }]);
    expect(agentQuestions(undefined)).toEqual([]);
  });

  it('combines the answers into one numbered comment', () => {
    expect(combineAnswers(['Postgres', ' keep it ', ''])).toBe('1. Postgres\n2. keep it\n3. -');
    expect(combineAnswers([' Postgres '])).toBe('Postgres');
    expect(combineAnswers(['', null, undefined])).toBe('');
    expect(combineAnswers([])).toBe('');
  });

  it('says the round and counts questions', () => {
    expect(questionRoundText(1, 2)).toBe('Round 1 of 2');
    expect(questionRoundText(2)).toBe('Round 2');
    expect(questionRoundText(null, 2)).toBeNull();
    expect(questionsCountText(1)).toBe('1 question');
    expect(questionsCountText(3)).toBe('3 questions');
  });
});
