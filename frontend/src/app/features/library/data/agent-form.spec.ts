import type { Agent } from '../../../core/api/types';
import {
  AGENT_KINDS,
  addMcpServer,
  agentDraft,
  agentPayload,
  agentSavable,
  customizeAgentForProject,
  emptyAgent,
  harnessOf,
  instructionsExcerpt,
  isHttpUrl,
  kindValue,
  mcpServerProblem,
  patchMcpServer,
  removeMcpServer,
} from './agent-form';

describe('agent form (ADR 0056/0057)', () => {
  const saved: Agent = {
    id: 'a1',
    key: 'cheap-coder',
    name: 'Cheap coder',
    description: 'OpenCode on a small model',
    scope: { type: 'GLOBAL' },
    harness: 'OPENCODE',
    modelProfileId: 'm1',
    instructions: 'You write small, tested changes.',
    rules: ['agent-ground-rules'],
    skills: ['spec-writing'],
    mcpServers: [{ name: 'docs', url: 'https://mcp.example.com/docs' }],
    tags: ['budget'],
    builtIn: false,
  };

  it('starts as a global model answer without a key and an empty environment', () => {
    const d = emptyAgent();
    expect(d.harness).toBeNull();
    expect(d.key).toBeUndefined();
    expect(d.scope.type).toBe('GLOBAL');
    expect(d.instructions).toBe('');
    expect(d.rules).toEqual([]);
    expect(d.skills).toEqual([]);
    expect(d.mcpServers).toEqual([]);
    expect(agentSavable(d)).toBe(false);
    expect(agentSavable({ ...d, name: 'X' })).toBe(true);
    expect(agentSavable({ ...d, name: 'X', scope: { type: 'PROJECT', projectId: null } })).toBe(
      false,
    );
  });

  it('takes the editable fields of a saved agent (no key, no id), copying the lists', () => {
    const d = agentDraft(saved);
    expect(d).toEqual({
      name: 'Cheap coder',
      description: 'OpenCode on a small model',
      scope: { type: 'GLOBAL', projectId: null },
      harness: 'OPENCODE',
      modelProfileId: 'm1',
      instructions: 'You write small, tested changes.',
      rules: ['agent-ground-rules'],
      skills: ['spec-writing'],
      mcpServers: [{ name: 'docs', url: 'https://mcp.example.com/docs' }],
      tags: ['budget'],
    });
    expect(d.tags).not.toBe(saved.tags);
    expect(d.rules).not.toBe(saved.rules);
    expect(d.mcpServers![0]).not.toBe(saved.mcpServers![0]);
  });

  it('maps kinds to radio values and back', () => {
    expect(AGENT_KINDS.map(kindValue)).toEqual(['MODEL_ANSWER', 'OPENCODE', 'CLAUDE_CODE']);
    expect(harnessOf('MODEL_ANSWER')).toBeNull();
    expect(harnessOf('CLAUDE_CODE')).toBe('CLAUDE_CODE');
  });

  it('customizes a global agent for a project with the same key and environment', () => {
    const d = customizeAgentForProject(saved, 'p1');
    expect(d.key).toBe('cheap-coder');
    expect(d.scope).toEqual({ type: 'PROJECT', projectId: 'p1' });
    expect(d.harness).toBe('OPENCODE');
    expect(d.skills).toEqual(['spec-writing']);
  });

  it('accepts only http(s) URLs', () => {
    expect(isHttpUrl('https://mcp.example.com/x')).toBe(true);
    expect(isHttpUrl('http://localhost:8080/mcp')).toBe(true);
    expect(isHttpUrl('ftp://example.com')).toBe(false);
    expect(isHttpUrl('mcp.example.com')).toBe(false);
    expect(isHttpUrl('https://')).toBe(false);
    expect(isHttpUrl('')).toBe(false);
    expect(isHttpUrl(`https://e.com/${'a'.repeat(500)}`)).toBe(false);
  });

  it('finds the first MCP server problem', () => {
    const ok = { name: 'docs', url: 'https://e.com/mcp' };
    expect(mcpServerProblem([ok])).toBeNull();
    expect(mcpServerProblem(undefined)).toBeNull();
    expect(mcpServerProblem([{ name: 'Docs', url: ok.url }])).toEqual({
      kind: 'badName',
      index: 0,
      name: 'Docs',
    });
    expect(mcpServerProblem([{ name: '', url: ok.url }])?.kind).toBe('badName');
    expect(mcpServerProblem([ok, { ...ok }])).toEqual({
      kind: 'duplicate',
      index: 1,
      name: 'docs',
    });
    expect(mcpServerProblem([{ name: 'docs', url: 'file:///etc' }])?.kind).toBe('badUrl');
  });

  it('blocks saving a coding agent with a wrong MCP server, not a model answer', () => {
    const bad = [{ name: 'Bad Name', url: 'x' }];
    expect(agentSavable({ ...emptyAgent(), name: 'A', harness: 'OPENCODE', mcpServers: bad })).toBe(
      false,
    );
    expect(agentSavable({ ...emptyAgent(), name: 'A', harness: null, mcpServers: bad })).toBe(true);
  });

  it('sends trimmed MCP servers for coding agents and none for a model answer', () => {
    const servers = [{ name: ' docs ', url: ' https://e.com/mcp ' }];
    expect(
      agentPayload({ ...emptyAgent(), harness: 'CLAUDE_CODE', mcpServers: servers }).mcpServers,
    ).toEqual([{ name: 'docs', url: 'https://e.com/mcp' }]);
    expect(
      agentPayload({ ...emptyAgent(), harness: null, mcpServers: servers }).mcpServers,
    ).toEqual([]);
  });

  it('edits the MCP server rows', () => {
    const one = addMcpServer(undefined);
    expect(one).toEqual([{ name: '', url: '' }]);
    const two = patchMcpServer(addMcpServer(one), 1, { name: 'b' });
    expect(two[1]).toEqual({ name: 'b', url: '' });
    expect(removeMcpServer(two, 0)).toEqual([{ name: 'b', url: '' }]);
  });

  it('cuts the instructions to a short excerpt at a word boundary', () => {
    expect(instructionsExcerpt(null)).toBe('');
    expect(instructionsExcerpt('  short  ')).toBe('short');
    const long = 'word '.repeat(100);
    const excerpt = instructionsExcerpt(long, 50);
    expect(excerpt.endsWith('…')).toBe(true);
    expect(excerpt.length).toBeLessThanOrEqual(51);
    expect(excerpt).not.toContain('wor…');
  });
});
