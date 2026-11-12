import type { Agent } from '../../../core/api/types';
import {
  ACCESS_MODES,
  AGENT_DIRECTORIES,
  AGENT_KINDS,
  AGENT_SESSIONS,
  accessDirectory,
  accessMode,
  accessPaths,
  accessProblem,
  agentDraft,
  agentPayload,
  agentSavable,
  agentSession,
  customizeAgentForProject,
  emptyAgent,
  harnessOf,
  instructionsExcerpt,
  kindValue,
  withAccessDirectory,
  withAccessMode,
  withAccessPaths,
} from './agent-form';

describe('agent form (ADR 0056/0057)', () => {
  const saved: Agent = {
    session: 'PER_STEP',
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
    connectors: ['gitlab-read', 'docs'],
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
    expect(d.connectors).toEqual([]);
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
      connectors: ['gitlab-read', 'docs'],
      session: 'PER_STEP',
      tags: ['budget'],
    });
    expect(d.tags).not.toBe(saved.tags);
    expect(d.rules).not.toBe(saved.rules);
    expect(d.connectors).not.toBe(saved.connectors);
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

  it('blocks saving a coding agent with a malformed connector key, not a model answer (ADR 0064)', () => {
    const bad = ['Bad Key'];
    expect(agentSavable({ ...emptyAgent(), name: 'A', harness: 'OPENCODE', connectors: bad })).toBe(
      false,
    );
    expect(agentSavable({ ...emptyAgent(), name: 'A', harness: null, connectors: bad })).toBe(true);
    expect(
      agentSavable({ ...emptyAgent(), name: 'A', harness: 'OPENCODE', connectors: ['jira'] }),
    ).toBe(true);
  });

  it('sends trimmed connector keys once for coding agents and none for a model answer (ADR 0064)', () => {
    const keys = [' docs ', 'docs', '', 'gitlab-read'];
    expect(
      agentPayload({ ...emptyAgent(), harness: 'CLAUDE_CODE', connectors: keys }).connectors,
    ).toEqual(['docs', 'gitlab-read']);
    expect(agentPayload({ ...emptyAgent(), harness: null, connectors: keys }).connectors).toEqual(
      [],
    );
  });

  it('edits the access of a coding agent and sends it only for coding agents (ADR 0060)', () => {
    const coder = { ...emptyAgent(), name: 'Doc writer', harness: 'OPENCODE' as const };
    expect(accessMode(coder)).toBe('FULL');
    const paths = withAccessMode(coder, 'PATHS');
    expect(accessProblem(paths)).toBe(true);
    expect(agentSavable(paths)).toBe(false);
    const docs = withAccessPaths(paths, [' docs/** ', '', 'docs/**', 'README.md']);
    expect(accessPaths(docs)).toEqual(['docs/**', 'README.md']);
    expect(agentSavable(docs)).toBe(true);
    expect(agentPayload(docs).access).toEqual({
      mode: 'PATHS',
      directory: 'RUN',
      paths: ['docs/**', 'README.md'],
    });
    // switching mode keeps the paths for a switch back, but sends none
    const readOnly = withAccessMode(docs, 'READ_ONLY');
    expect(readOnly.access?.paths).toHaveLength(4);
    expect(agentPayload(readOnly).access).toEqual({ mode: 'READ_ONLY', directory: 'RUN' });
    expect(agentPayload({ ...docs, harness: null }).access).toBeUndefined();
    expect(agentPayload(coder).access).toBeUndefined();
    expect(
      agentDraft({ ...saved, access: { directory: 'RUN', mode: 'READ_ONLY' } }).access,
    ).toEqual({
      mode: 'READ_ONLY',
      directory: 'RUN',
      paths: [],
    });
    expect(ACCESS_MODES).toEqual(['FULL', 'READ_ONLY', 'PATHS']);
  });

  it('edits the directory and the session of a coding agent (ADR 0062)', () => {
    const coder = { ...emptyAgent(), name: 'Isolated', harness: 'CLAUDE_CODE' as const };
    expect(agentSession(coder)).toBe('PER_STEP');
    expect(accessDirectory(coder)).toBe('RUN');
    const separate = withAccessDirectory(coder, 'SEPARATE');
    expect(accessDirectory(separate)).toBe('SEPARATE');
    // the mode stays, the directory is kept when the mode changes
    expect(accessMode(separate)).toBe('FULL');
    expect(accessDirectory(withAccessMode(separate, 'READ_ONLY'))).toBe('SEPARATE');
    const perRun = { ...separate, session: 'PER_RUN' as const };
    expect(agentPayload(perRun)).toMatchObject({
      session: 'PER_RUN',
      access: { mode: 'FULL', directory: 'SEPARATE' },
    });
    // a model answer has no session to continue and no directory
    const model = agentPayload({ ...perRun, harness: null });
    expect(model.session).toBe('PER_STEP');
    expect(model.access).toBeUndefined();
    // a saved agent without the fields reads the defaults
    const draft = agentDraft({ ...saved, session: undefined as never });
    expect(draft.session).toBe('PER_STEP');
    expect(
      agentDraft({ ...saved, access: { mode: 'PATHS', paths: ['a'] } as never }).access?.directory,
    ).toBe('RUN');
    expect(AGENT_SESSIONS).toEqual(['PER_STEP', 'PER_RUN', 'FRESH']);
    expect(AGENT_DIRECTORIES).toEqual(['RUN', 'SEPARATE']);
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
