import { ACTION_TYPES } from '../api/types';
import {
  actionDescription,
  actionTitle,
  agentKindDescription,
  agentKindLabel,
  isCodingAgent,
} from './agents';

describe('agent and action labels (ADR 0056)', () => {
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
