import { ACTION_TYPES } from '../api/types';
import {
  actionDescription,
  actionTitle,
  agentAccessText,
  agentKindDescription,
  agentKindLabel,
  agentModelText,
  configNeedsCodingAgent,
  isCodingAgent,
  neededAgentLabel,
} from './agents';

describe('agent and action labels (ADR 0056)', () => {
  it('names who did a step: agent and model (ADR 0059)', () => {
    expect(agentModelText({ agentName: 'Claude Code', modelLabel: 'Claude Sonnet' })).toBe(
      'Claude Code · Claude Sonnet',
    );
    expect(agentModelText({ agentKey: 'reviewer', modelLabel: null })).toBe('reviewer');
    expect(agentModelText(null)).toBe('');
  });

  it('names agent kinds in plain words', () => {
    expect(agentKindLabel(null)).toBe('Model answer');
    expect(agentKindLabel(undefined)).toBe('Model answer');
    expect(agentKindLabel('OPENCODE')).toBe('OpenCode');
    expect(agentKindLabel('CLAUDE_CODE')).toBe('Claude Code');
    expect(agentKindLabel('SOMETHING_NEW')).toBe('Something new');
  });

  it('describes a model answer and a coding agent in one line', () => {
    expect(agentKindDescription(null)).toContain('no tools or repository');
    expect(agentKindDescription('OPENCODE')).toContain('sandbox');
  });

  it('names the access of a coding agent (ADR 0060)', () => {
    expect(agentAccessText(undefined)).toBe('Full');
    expect(agentAccessText({ directory: 'RUN', mode: 'READ_ONLY' })).toBe('Read only');
    expect(
      agentAccessText({ directory: 'RUN', mode: 'PATHS', paths: ['docs/**', 'README.md'] }),
    ).toBe('Only docs/**, README.md');
    expect(agentAccessText({ directory: 'RUN', mode: 'PATHS', paths: [] })).toBe(
      'Only these paths',
    );
  });

  it('derives the kind of worker a config needs (ADR 0061)', () => {
    expect(configNeedsCodingAgent({ phase: 'specify' })).toBe(true);
    expect(configNeedsCodingAgent({ phase: '  ' })).toBe(false);
    expect(configNeedsCodingAgent({ push: true })).toBe(true);
    expect(configNeedsCodingAgent({ prepareOnly: 'true' })).toBe(true);
    expect(configNeedsCodingAgent({ push: false, temperature: 0.2 })).toBe(false);
    expect(configNeedsCodingAgent(null)).toBe(false);
    expect(neededAgentLabel(true)).toBe('Needs a coding agent');
    expect(neededAgentLabel(false)).toBe('Any agent');
  });

  it('tells a coding agent by its harness', () => {
    expect(isCodingAgent({ harness: 'CLAUDE_CODE' })).toBe(true);
    expect(isCodingAgent({ harness: null })).toBe(false);
    expect(isCodingAgent(null)).toBe(false);
  });

  it('translates every action type and falls back to the backend text', () => {
    for (const t of ACTION_TYPES) {
      expect(actionTitle(t, 'x')).not.toBe('x');
      expect(actionDescription(t).endsWith('.')).toBe(true);
    }
    expect(actionTitle('COMMENT_ISSUE')).toBe('Comment on issue');
    expect(actionTitle('NEW_ONE', 'New one from server')).toBe('New one from server');
    expect(actionTitle('NEW_ONE')).toBe('New one');
    expect(actionDescription('NEW_ONE', 'Server text')).toBe('Server text');
    expect(actionTitle(null, 'Fallback')).toBe('Fallback');
  });
});
