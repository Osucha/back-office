import type { Agent, AgentAudit, AgentInput } from '../../../core/api/types';
import {
  addSkillPlan,
  auditFindingTitle,
  auditView,
  coherenceSeverityLabel,
  sortCoherenceFindings,
} from './agent-audit';

const audit: AgentAudit = {
  agents: [
    {
      agent: { key: 'model-answer', name: 'Model answer', harness: null },
      agentId: 'a0',
      scope: { type: 'GLOBAL' },
      nodeIds: ['review'],
      rules: [],
      skills: [],
      requiredSkills: [],
      missingSkills: [],
      findings: [],
    },
    {
      agent: { key: 'opencode', name: 'OpenCode', harness: 'OPENCODE' },
      agentId: 'a1',
      scope: { type: 'GLOBAL' },
      nodeIds: ['impl', 'fix'],
      rules: [{ key: 'no-secrets', name: 'No secrets', automatic: true }, { key: 'polish' }],
      skills: [{ key: 'gradle', name: 'Gradle' }],
      requiredSkills: [
        { key: 'kotlin', name: 'Kotlin', nodeIds: ['impl'] },
        { key: 'gradle', name: 'Gradle', nodeIds: ['impl', 'fix'] },
      ],
      missingSkills: ['kotlin'],
      findings: [
        {
          kind: 'UNKNOWN_RULE',
          severity: 'WARNING',
          message: 'Rule polish no longer exists',
          key: 'polish',
        },
        {
          kind: 'MISSING_SKILL',
          severity: 'ERROR',
          message: 'OpenCode lacks skill kotlin',
          nodeIds: ['impl'],
          key: 'kotlin',
        },
      ],
    },
  ],
  findings: [
    {
      kind: 'DUPLICATE_SKILL_NAME',
      severity: 'WARNING',
      message: 'Two skills are named Kotlin',
    },
  ],
};

const names = new Map([
  ['impl', 'Implement'],
  ['fix', 'Fix review'],
]);

describe('agent audit view (ADR 0061)', () => {
  it('maps agents with steps, rules, skills and required skills by name', () => {
    const view = auditView(audit, names);
    const oc = view.agents[0];
    expect(oc.key).toBe('opencode');
    expect(oc.kind).toBe('OpenCode');
    expect(oc.coding).toBe(true);
    expect(oc.scope).toBe('GLOBAL');
    expect(oc.nodes).toEqual([
      { id: 'impl', name: 'Implement' },
      { id: 'fix', name: 'Fix review' },
    ]);
    expect(oc.rules).toEqual([
      { key: 'no-secrets', name: 'No secrets', automatic: true },
      { key: 'polish', name: 'polish', automatic: false },
    ]);
    expect(oc.required.map((r) => [r.key, r.missing, r.nodes.map((n) => n.name)])).toEqual([
      ['kotlin', true, ['Implement']],
      ['gradle', false, ['Implement', 'Fix review']],
    ]);
    expect(oc.missing).toEqual(['kotlin']);
  });

  it('lists errors before warnings, agents with problems first, and counts all findings', () => {
    const view = auditView(audit, names);
    expect(view.agents.map((a) => a.key)).toEqual(['opencode', 'model-answer']);
    expect(view.agents[0].findings.map((f) => f.kind)).toEqual(['MISSING_SKILL', 'UNKNOWN_RULE']);
    expect(view.agents[0].findings[0].title).toBe('Missing skill');
    expect(view.agents[0].findings[0].nodes).toEqual([{ id: 'impl', name: 'Implement' }]);
    expect(view.agents[1].nodes).toEqual([{ id: 'review', name: 'review' }]);
    expect(view.findings[0].title).toBe('Two skills with the same name');
    expect(view.problems).toBe(3);
    expect(auditView(null, names)).toEqual({ agents: [], findings: [], problems: 0 });
  });

  it('names every finding kind and coherence severity', () => {
    for (const k of [
      'MISSING_SKILL',
      'NEEDS_CODING_AGENT',
      'READ_ONLY_PUSH',
      'UNKNOWN_RULE',
      'UNKNOWN_SKILL',
      'DUPLICATE_SKILL_NAME',
      'UNKNOWN_AGENT',
    ] as const)
      expect(auditFindingTitle(k)).not.toBe(k);
    expect(coherenceSeverityLabel('CONTRADICTION')).toBe('Contradiction');
    expect(coherenceSeverityLabel('AMBIGUITY')).toBe('Ambiguity');
    expect(coherenceSeverityLabel('REDUNDANCY')).toBe('Redundancy');
    expect(
      sortCoherenceFindings([
        { severity: 'REDUNDANCY', summary: 'r' },
        { severity: 'CONTRADICTION', summary: 'c' },
        { severity: 'AMBIGUITY', summary: 'a' },
      ]).map((f) => f.summary),
    ).toEqual(['c', 'a', 'r']);
  });
});

describe('add a missing skill to the agent (ADR 0061)', () => {
  const draftOf = (a: Agent): AgentInput => ({
    session: 'PER_STEP',
    name: a.name,
    scope: a.scope,
    skills: [...(a.skills ?? [])],
  });
  const copyOf = (a: Agent, projectId: string): AgentInput => ({
    ...draftOf(a),
    key: a.key,
    scope: { type: 'PROJECT', projectId },
  });

  it('copies a global agent into the project with the skill added', () => {
    const global: Agent = {
      session: 'PER_STEP',
      id: 'g',
      key: 'opencode',
      name: 'OpenCode',
      scope: { type: 'GLOBAL' },
    };
    expect(addSkillPlan(global, 'kotlin', 'p1', draftOf, copyOf)).toEqual({
      kind: 'copy',
      input: {
        session: 'PER_STEP',
        name: 'OpenCode',
        key: 'opencode',
        scope: { type: 'PROJECT', projectId: 'p1' },
        skills: ['kotlin'],
      },
    });
  });

  it('adds the skill to a project agent once', () => {
    const own: Agent = {
      session: 'PER_STEP',
      id: 'p',
      key: 'coder',
      name: 'Coder',
      scope: { type: 'PROJECT', projectId: 'p1' },
      skills: ['gradle', 'kotlin'],
    };
    const plan = addSkillPlan(own, 'kotlin', 'p1', draftOf, copyOf);
    expect(plan.kind).toBe('update');
    expect(plan.kind === 'update' && plan.agentId).toBe('p');
    expect(plan.kind === 'update' && plan.input.skills).toEqual(['gradle', 'kotlin']);
    expect(addSkillPlan({ ...own, skills: [] }, 'kotlin', 'p1', draftOf, copyOf)).toEqual({
      kind: 'update',
      agentId: 'p',
      input: { session: 'PER_STEP', name: 'Coder', scope: own.scope, skills: ['kotlin'] },
    });
  });

  it('does nothing without a saved agent', () => {
    expect(addSkillPlan(null, 'kotlin', 'p1', draftOf, copyOf).kind).toBe('none');
    expect(
      addSkillPlan(
        { session: 'PER_STEP', key: 'x', name: 'X', scope: { type: 'GLOBAL' } },
        'k',
        'p1',
        draftOf,
        copyOf,
      ).kind,
    ).toBe('none');
  });
});
