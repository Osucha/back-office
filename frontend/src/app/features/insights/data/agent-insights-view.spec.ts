import type { AgentInsights, AgentMetrics } from '../../../core/api/types';
import {
  acceptedShare,
  agentRowKey,
  agentRows,
  hasAgentData,
  templateGroups,
} from './agent-insights-view';

const usd = (amount: number) => ({ amount, currency: 'USD' });

const m = (over: Partial<AgentMetrics> = {}): AgentMetrics => ({
  agentKey: 'claude-code',
  agentName: 'Claude Code',
  harness: 'CLAUDE_CODE',
  modelProfileId: 'sonnet',
  modelLabel: 'Claude Sonnet',
  executions: 10,
  succeeded: 8,
  failed: 2,
  accepted: 6,
  firstPassRate: 0.5,
  cost: usd(6),
  apiCost: usd(6),
  costPerAccepted: usd(1),
  ...over,
});

describe('agent insights (ADR 0059)', () => {
  it('orders agents by the cost of an accepted result, those without one last', () => {
    const rows = agentRows([
      m({ agentKey: 'none', agentName: 'None', costPerAccepted: null, accepted: 0 }),
      m({ agentKey: 'dear', agentName: 'Dear', costPerAccepted: usd(3) }),
      m({ agentKey: 'cheap', agentName: 'Cheap', harness: null, modelLabel: null }),
    ]);
    expect(rows.map((r) => r.agentKey)).toEqual(['cheap', 'dear', 'none']);
    expect(rows[0].kind).toBe('Model answer');
    expect(rows[0].model).toBe('-');
    expect(rows[1].kind).toBe('Claude Code');
    expect(rows[0].key).toBe('cheap|sonnet');
    expect(agentRowKey({ agentKey: 'a', modelProfileId: null })).toBe('a|');
  });

  it('breaks ties by executions and keeps an empty answer empty', () => {
    const rows = agentRows([
      m({ agentKey: 'few', agentName: 'Few', executions: 2 }),
      m({ agentKey: 'many', agentName: 'Many', executions: 20 }),
    ]);
    expect(rows.map((r) => r.agentKey)).toEqual(['many', 'few']);
    expect(agentRows(null)).toEqual([]);
  });

  it('groups per template, busiest template first, and drops empty ones', () => {
    const i: AgentInsights = {
      byAgent: [m()],
      byTemplate: [
        { templateKey: 'review', templateName: null, rows: [m({ executions: 3 })] },
        { templateKey: 'empty', rows: [] },
        {
          templateKey: 'implement',
          templateName: 'Implement',
          rows: [m({ executions: 4 }), m({ agentKey: 'open', executions: 5 })],
        },
      ],
    };
    const groups = templateGroups(i);
    expect(groups.map((g) => g.title)).toEqual(['Implement', 'review']);
    expect(groups[0].executions).toBe(9);
    expect(groups[0].rows).toHaveLength(2);
    expect(templateGroups(null)).toEqual([]);
  });

  it('knows when there is data and the accepted share', () => {
    expect(hasAgentData(null)).toBe(false);
    expect(hasAgentData({ byAgent: [] })).toBe(false);
    expect(hasAgentData({ byAgent: [], unattributed: 3 })).toBe(true);
    expect(hasAgentData({ byAgent: [m()] })).toBe(true);
    expect(acceptedShare(m())).toBe(0.6);
    expect(acceptedShare(m({ executions: 0, accepted: 0 }))).toBeNull();
  });
});
