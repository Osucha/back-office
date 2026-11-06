import type { Agent, TaskConfigSchemas, TaskTemplate } from '../../../core/api/types';
import {
  agentByKey,
  agentRef,
  agentsForScope,
  effectiveAgentKey,
  templateConfigSchema,
} from './template-config';

const T = (over: Partial<TaskTemplate>): TaskTemplate =>
  ({ key: 'k', name: 'N', abstract: false, scope: { type: 'GLOBAL' }, ...over }) as TaskTemplate;

describe('template config (ADR 0054, 0056)', () => {
  const templates = [
    T({ key: 'base-agent', abstract: true }),
    T({ key: 'base-llm', extendsKey: 'base-agent', agentKey: 'model-answer' }),
    T({ key: 'implement-opencode', extendsKey: 'base-agent', agentKey: 'opencode' }),
    T({
      key: 'base-llm',
      extendsKey: 'base-agent',
      agentKey: 'claude-code',
      scope: { type: 'PROJECT', projectId: 'p1' },
    }),
    T({ key: 'loop-a', extendsKey: 'loop-b' }),
    T({ key: 'loop-b', extendsKey: 'loop-a' }),
  ];
  const global = { type: 'GLOBAL' as const };

  it('prefers the own agent', () => {
    expect(
      effectiveAgentKey({ agentKey: 'opencode', extendsKey: 'base-llm', scope: global }, templates),
    ).toBe('opencode');
  });

  it('inherits the agent of the nearest ancestor', () => {
    expect(
      effectiveAgentKey({ agentKey: null, extendsKey: 'base-llm', scope: global }, templates),
    ).toBe('model-answer');
    expect(
      effectiveAgentKey(
        { agentKey: null, extendsKey: 'base-llm', scope: { type: 'PROJECT', projectId: 'p1' } },
        templates,
      ),
    ).toBe('claude-code');
  });

  it('falls back when nothing up the chain has an agent (also on cycles)', () => {
    expect(
      effectiveAgentKey({ agentKey: null, extendsKey: 'base-agent', scope: global }, templates),
    ).toBeNull();
    expect(
      effectiveAgentKey(
        { agentKey: null, extendsKey: 'loop-a', scope: global },
        templates,
        'model-answer',
      ),
    ).toBe('model-answer');
    expect(
      effectiveAgentKey({ agentKey: null, extendsKey: 'missing', scope: global }, templates),
    ).toBeNull();
  });

  it('finds agents by key and scope (project first)', () => {
    const agents: Agent[] = [
      { key: 'coder', name: 'Global coder', scope: { type: 'GLOBAL' }, harness: 'OPENCODE' },
      {
        key: 'coder',
        name: 'P1 coder',
        scope: { type: 'PROJECT', projectId: 'p1' },
        harness: 'CLAUDE_CODE',
      },
      { key: 'p2-only', name: 'P2', scope: { type: 'PROJECT', projectId: 'p2' } },
    ];
    expect(agentByKey(agents, 'coder')?.name).toBe('Global coder');
    expect(agentByKey(agents, 'coder', { type: 'PROJECT', projectId: 'p1' })?.name).toBe(
      'P1 coder',
    );
    expect(agentByKey(agents, 'p2-only')).toBeNull();
    expect(agentByKey(agents, null)).toBeNull();
    expect(agentsForScope(agents, global).map((a) => a.name)).toEqual(['Global coder']);
    expect(agentsForScope(agents, { type: 'PROJECT', projectId: 'p1' }).map((a) => a.name)).toEqual(
      ['Global coder', 'P1 coder'],
    );
    expect(agentRef(agents[1])).toEqual({
      key: 'coder',
      name: 'P1 coder',
      harness: 'CLAUDE_CODE',
      modelProfileId: null,
    });
  });

  it('picks the schema by the kind of agent without required keys', () => {
    const schemas: TaskConfigSchemas = {
      codingAgent: {
        type: 'object',
        required: ['phase'],
        properties: { phase: { type: 'string' } },
      },
      modelAnswer: { type: 'object', properties: {} },
    };
    const schema = templateConfigSchema(schemas, true);
    expect(schema?.properties?.['phase']).toBeTruthy();
    expect(schema?.required).toBeUndefined();
    expect(templateConfigSchema(schemas, false)).toBeNull();
    expect(templateConfigSchema(null, true)).toBeNull();
  });
});
