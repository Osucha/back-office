import type { Agent } from '../../../core/api/types';
import {
  AGENT_KINDS,
  agentDraft,
  agentSavable,
  customizeAgentForProject,
  emptyAgent,
  harnessOf,
  kindValue,
} from './agent-form';

describe('agent form (ADR 0056)', () => {
  const saved: Agent = {
    id: 'a1',
    key: 'cheap-coder',
    name: 'Cheap coder',
    description: 'OpenCode on a small model',
    scope: { type: 'GLOBAL' },
    harness: 'OPENCODE',
    modelProfileId: 'm1',
    tags: ['budget'],
    builtIn: false,
  };

  it('starts as a global model answer without a key', () => {
    const d = emptyAgent();
    expect(d.harness).toBeNull();
    expect(d.key).toBeUndefined();
    expect(d.scope.type).toBe('GLOBAL');
    expect(agentSavable(d)).toBe(false);
    expect(agentSavable({ ...d, name: 'X' })).toBe(true);
    expect(agentSavable({ ...d, name: 'X', scope: { type: 'PROJECT', projectId: null } })).toBe(
      false,
    );
  });

  it('takes the editable fields of a saved agent (no key, no id)', () => {
    const d = agentDraft(saved);
    expect(d).toEqual({
      name: 'Cheap coder',
      description: 'OpenCode on a small model',
      scope: { type: 'GLOBAL', projectId: null },
      harness: 'OPENCODE',
      modelProfileId: 'm1',
      tags: ['budget'],
    });
    expect(d.tags).not.toBe(saved.tags);
  });

  it('maps kinds to radio values and back', () => {
    expect(AGENT_KINDS.map(kindValue)).toEqual(['MODEL_ANSWER', 'OPENCODE', 'CLAUDE_CODE']);
    expect(harnessOf('MODEL_ANSWER')).toBeNull();
    expect(harnessOf('CLAUDE_CODE')).toBe('CLAUDE_CODE');
  });

  it('customizes a global agent for a project with the same key', () => {
    const d = customizeAgentForProject(saved, 'p1');
    expect(d.key).toBe('cheap-coder');
    expect(d.scope).toEqual({ type: 'PROJECT', projectId: 'p1' });
    expect(d.harness).toBe('OPENCODE');
  });
});
