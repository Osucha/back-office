import type { Agent, TaskConfigSchemas, TaskTemplate } from '../../../core/api/types';
import {
  agentByKey,
  agentRef,
  agentsForScope,
  cleanGlobs,
  lineageConfig,
  templateConfigSchema,
  templateNeedsCodingAgent,
} from './template-config';

const T = (over: Partial<TaskTemplate>): TaskTemplate =>
  ({ key: 'k', name: 'N', abstract: false, scope: { type: 'GLOBAL' }, ...over }) as TaskTemplate;

describe('template config (ADR 0054, 0061)', () => {
  const templates = [
    T({ key: 'base', abstract: true, config: { temperature: 0.2 } }),
    T({ key: 'specify', extendsKey: 'base', config: { codingAgent: true } }),
    T({ key: 'review', extendsKey: 'base', config: {} }),
    T({
      key: 'review',
      extendsKey: 'base',
      config: { codingAgent: true },
      scope: { type: 'PROJECT', projectId: 'p1' },
    }),
    T({ key: 'loop-a', extendsKey: 'loop-b' }),
    T({ key: 'loop-b', extendsKey: 'loop-a' }),
  ];
  const global = { type: 'GLOBAL' as const };
  const p1 = { type: 'PROJECT' as const, projectId: 'p1' };

  it('merges the config of the lineage, the own keys win and null deletes', () => {
    expect(
      lineageConfig({ config: { x: 1 }, extendsKey: 'specify', scope: global }, templates),
    ).toEqual({ temperature: 0.2, codingAgent: true, x: 1 });
    expect(
      lineageConfig(
        { config: { codingAgent: null }, extendsKey: 'specify', scope: global },
        templates,
      ),
    ).toEqual({ temperature: 0.2 });
    expect(lineageConfig({ config: {}, extendsKey: 'loop-a', scope: global }, templates)).toEqual(
      {},
    );
  });

  it('needs a coding agent for codingAgent or prepareOnly anywhere in the lineage', () => {
    expect(
      templateNeedsCodingAgent({ config: {}, extendsKey: 'specify', scope: global }, templates),
    ).toBe(true);
    expect(
      templateNeedsCodingAgent({ config: {}, extendsKey: 'review', scope: global }, templates),
    ).toBe(false);
    // the project's version of the parent works in the repository
    expect(
      templateNeedsCodingAgent({ config: {}, extendsKey: 'review', scope: p1 }, templates),
    ).toBe(true);
    expect(
      templateNeedsCodingAgent({ config: { prepareOnly: true }, scope: global }, templates),
    ).toBe(true);
  });

  it('a project template customising the global one of its key extends the global one', () => {
    expect(
      templateNeedsCodingAgent(
        { key: 'review', config: {}, extendsKey: 'review', scope: p1 },
        templates,
      ),
    ).toBe(false);
  });

  it('finds agents by key and scope (project first)', () => {
    const agents: Agent[] = [
      {
        session: 'PER_STEP',
        key: 'coder',
        name: 'Global coder',
        scope: { type: 'GLOBAL' },
        harness: 'OPENCODE',
      },
      {
        session: 'PER_STEP',
        key: 'coder',
        name: 'P1 coder',
        scope: { type: 'PROJECT', projectId: 'p1' },
        harness: 'CLAUDE_CODE',
      },
      {
        session: 'PER_STEP',
        key: 'p2-only',
        name: 'P2',
        scope: { type: 'PROJECT', projectId: 'p2' },
      },
    ];
    expect(agentByKey(agents, 'coder')?.name).toBe('Global coder');
    expect(agentByKey(agents, 'coder', p1)?.name).toBe('P1 coder');
    expect(agentByKey(agents, 'p2-only')).toBeNull();
    expect(agentByKey(agents, null)).toBeNull();
    expect(agentsForScope(agents, global).map((a) => a.name)).toEqual(['Global coder']);
    expect(agentsForScope(agents, p1).map((a) => a.name)).toEqual(['Global coder', 'P1 coder']);
    expect(agentRef(agents[1])).toEqual({
      key: 'coder',
      name: 'P1 coder',
      harness: 'CLAUDE_CODE',
      modelProfileId: null,
    });
  });

  it('offers the model answer keys and the coding keys a template still sets (ADR 0063)', () => {
    const schemas: TaskConfigSchemas = {
      codingAgent: {
        type: 'object',
        required: ['verifyCommand'],
        properties: {
          phase: { type: 'string' },
          push: { type: 'boolean' },
          branchTemplate: { type: 'string' },
          setupCommand: { type: 'string' },
          collectArtifacts: { type: 'array', items: { type: 'string' } },
          repositories: { type: 'array' },
          directory: { type: 'string' },
          verifyCommand: { type: 'string' },
          agentTools: { type: 'array', items: { type: 'string' } },
          allowQuestions: { type: 'boolean' },
          maxQuestionRounds: { type: 'integer' },
        },
      },
      modelAnswer: { type: 'object', properties: { temperature: { type: 'number' } } },
    };
    const schema = templateConfigSchema(schemas);
    expect(Object.keys(schema?.properties ?? {}).sort()).toEqual([
      'agentTools',
      'allowQuestions',
      'codingAgent',
      'maxQuestionRounds',
      'temperature',
      'verifyCommand',
    ]);
    expect(schema?.required).toBeUndefined();
    // codingAgent is offered as a checkbox with its own label even when the server lacks it
    expect(schema?.properties?.['codingAgent']?.['x-widget']).toBe('checkbox');
    expect(schema?.properties?.['codingAgent']?.title).toBe(
      'Works in the repository (needs a coding agent)',
    );
    expect(schema?.properties?.['verifyCommand']?.title).toBe('Verify command');
    expect(templateConfigSchema({ codingAgent: {}, modelAnswer: {} })).toBeNull();
    expect(templateConfigSchema(null)).toBeNull();
  });

  it('cleans the output file globs (ADR 0063)', () => {
    expect(cleanGlobs([' specs/**/spec.md ', '', 'plan.md', 'plan.md', null])).toEqual([
      'specs/**/spec.md',
      'plan.md',
    ]);
    expect(cleanGlobs(null)).toEqual([]);
  });
});
