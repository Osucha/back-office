import type { ModelOption, StepModelPlan } from '../api/types';
import {
  groupModelOptions,
  mergeDefaultModelOptions,
  planSourceLabel,
  routeLabel,
  summarizeModelPlan,
} from './model-options';

const option = (profileId: string, changes: Partial<ModelOption> = {}): ModelOption => ({
  profileId,
  displayName: profileId,
  provider: 'anthropic',
  modelId: `${profileId}-model`,
  runtime: 'API',
  route: 'GATEWAY',
  personal: false,
  usable: true,
  ...changes,
});
const login = (profileId: string, changes: Partial<ModelOption> = {}) =>
  option(profileId, {
    runtime: 'CLAUDE_CODE_LOGIN',
    route: 'OWN_LOGIN',
    personal: true,
    ...changes,
  });

describe('model options (ADR 0050)', () => {
  it('groups platform models before personal ones and leaves out empty groups', () => {
    const groups = groupModelOptions([login('mine'), option('a'), option('b')]);
    expect(groups.map((g) => [g.key, g.options.map((o) => o.profileId)])).toEqual([
      ['platform', ['a', 'b']],
      ['personal', ['mine']],
    ]);
    expect(groupModelOptions([option('a')]).map((g) => g.key)).toEqual(['platform']);
    expect(groupModelOptions([])).toEqual([]);
  });

  it('names the route', () => {
    expect(routeLabel('GATEWAY')).toBe('API');
    expect(routeLabel('OWN_LOGIN')).toBe('your login');
  });

  it('merges LLM and coding options of the project default model', () => {
    const merged = mergeDefaultModelOptions(
      [
        option('api'),
        option('broken', { usable: false, problem: 'provider not connected' }),
        option('agent', { usable: false, problem: 'not for LLM steps' }),
      ],
      [
        option('api'),
        option('broken', { usable: false, problem: 'provider not connected (coding)' }),
        option('agent', { note: 'runner offline' }),
      ],
    );
    expect(merged.map((o) => o.profileId)).toEqual(['api', 'agent', 'broken']);
    expect(merged[0].hint).toBeUndefined();
    expect(merged[1]).toMatchObject({
      usable: true,
      note: 'runner offline',
      hint: 'coding steps only (LLM steps need their own model)',
    });
    expect(merged[2]).toMatchObject({ usable: false, problem: 'provider not connected' });
  });

  it('does not call a login model coding-only when the server lets LLM steps use it (ADR 0050 step 4)', () => {
    const merged = mergeDefaultModelOptions(
      [login('mine', { note: 'runner offline' })],
      [login('mine', { note: 'runner offline' })],
    );
    expect(merged).toHaveLength(1);
    expect(merged[0]).toMatchObject({ usable: true, route: 'OWN_LOGIN' });
    expect(merged[0].hint).toBeUndefined();
  });

  it('gives an unusable login model the reason of coding steps', () => {
    const merged = mergeDefaultModelOptions(
      [login('mine', { usable: false, problem: 'LLM steps cannot use it' })],
      [login('mine', { usable: false, problem: 'only in private projects' })],
    );
    expect(merged[0].problem).toBe('only in private projects');
  });

  it('labels plan sources and counts blocked and warned steps', () => {
    expect(planSourceLabel('PROJECT')).toBe('project default');
    expect(planSourceLabel('PROJECT_REVIEW')).toBe('review default');
    expect(planSourceLabel('PERSONAL_DEFAULT')).toBe('your default');
    expect(planSourceLabel('PLATFORM_DEFAULT')).toBe('platform default');
    expect(planSourceLabel('NONE')).toBe('not set');
    const step = (changes: Partial<StepModelPlan>): StepModelPlan => ({
      nodeId: 'n',
      nodeName: 'N',
      stepKind: 'LLM_CALL',
      source: 'NODE',
      ...changes,
    });
    expect(
      summarizeModelPlan([
        step({ problem: 'no model' }),
        step({ note: 'runner offline' }),
        step({ model: login('mine', { note: 'offline' }) }),
        step({}),
      ]),
    ).toEqual({ blocked: 1, warnings: 2 });
  });
});
