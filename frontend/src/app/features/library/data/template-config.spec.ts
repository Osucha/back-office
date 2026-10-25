import type { TaskExecutorDescriptor, TaskTemplate } from '../../../core/api/types';
import { effectiveExecutor, templateConfigSchema } from './template-config';

const T = (over: Partial<TaskTemplate>): TaskTemplate =>
  ({ key: 'k', name: 'N', abstract: false, scope: { type: 'GLOBAL' }, ...over }) as TaskTemplate;

describe('template config (ADR 0054)', () => {
  const templates = [
    T({ key: 'base-agent', abstract: true }),
    T({ key: 'base-llm', extendsKey: 'base-agent', executor: 'LLM' }),
    T({ key: 'implement-opencode', extendsKey: 'base-agent', executor: 'IMPLEMENT' }),
    T({
      key: 'base-llm',
      extendsKey: 'base-agent',
      executor: 'SPEC_KIT',
      scope: { type: 'PROJECT', projectId: 'p1' },
    }),
    T({ key: 'loop-a', extendsKey: 'loop-b' }),
    T({ key: 'loop-b', extendsKey: 'loop-a' }),
  ];
  const global = { type: 'GLOBAL' as const };

  it('prefers the own executor', () => {
    expect(
      effectiveExecutor({ executor: 'NOTIFY', extendsKey: 'base-llm', scope: global }, templates),
    ).toBe('NOTIFY');
  });

  it('inherits the executor of the nearest ancestor', () => {
    expect(
      effectiveExecutor({ executor: null, extendsKey: 'base-llm', scope: global }, templates),
    ).toBe('LLM');
    expect(
      effectiveExecutor(
        { executor: null, extendsKey: 'base-llm', scope: { type: 'PROJECT', projectId: 'p1' } },
        templates,
      ),
    ).toBe('SPEC_KIT');
  });

  it('falls back when nothing up the chain has an executor (also on cycles)', () => {
    expect(
      effectiveExecutor({ executor: null, extendsKey: 'base-agent', scope: global }, templates),
    ).toBeNull();
    expect(
      effectiveExecutor({ executor: null, extendsKey: 'loop-a', scope: global }, templates, 'LLM'),
    ).toBe('LLM');
    expect(
      effectiveExecutor({ executor: null, extendsKey: 'missing', scope: global }, templates),
    ).toBeNull();
  });

  it('picks the schema of the executor without required keys', () => {
    const descriptors: TaskExecutorDescriptor[] = [
      {
        executor: 'IMPLEMENT',
        configSchema: {
          type: 'object',
          required: ['agent'],
          properties: { agent: { type: 'string', enum: ['OPENCODE', 'CLAUDE_CODE'] } },
        },
      },
      { executor: 'LLM', configSchema: { type: 'object', properties: {} } },
    ];
    const schema = templateConfigSchema(descriptors, 'IMPLEMENT');
    expect(schema?.properties?.['agent']).toBeTruthy();
    expect(schema?.required).toBeUndefined();
    expect(templateConfigSchema(descriptors, 'LLM')).toBeNull();
    expect(templateConfigSchema(descriptors, 'NOTIFY')).toBeNull();
    expect(templateConfigSchema(null, 'IMPLEMENT')).toBeNull();
    expect(templateConfigSchema(descriptors, null)).toBeNull();
  });
});
