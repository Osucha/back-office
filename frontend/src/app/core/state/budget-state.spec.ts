import type { BudgetStatus } from '../api/types';
import {
  budgetBaton,
  budgetInputErrors,
  budgetShare,
  forecastView,
  formatShare,
  monthName,
  needsAttention,
  spendText,
} from './budget-state';

const status = (extra: Partial<BudgetStatus> = {}): BudgetStatus => ({
  scope: 'PROJECT',
  projectId: 'p1',
  projectKey: 'SHOP',
  period: '2026-09',
  monthlyLimitUsd: 100,
  alertAtPercent: 80,
  hardStop: true,
  spentUsd: 25,
  forecastUsd: 60,
  state: 'OK',
  ...extra,
});

describe('budget state', () => {
  it('computes and formats the share of the limit', () => {
    expect(budgetShare(25, 100)).toBe(0.25);
    expect(budgetShare(25, null)).toBeNull();
    expect(budgetShare(25, 0)).toBeNull();
    expect(budgetShare(null, 100)).toBe(0);
    expect(formatShare(0.254)).toBe('25%');
    expect(formatShare(0.004)).toBe('<1%');
    expect(formatShare(0)).toBe('0%');
    expect(formatShare(12)).toBe('999%+');
    expect(formatShare(null)).toBe('-');
  });

  it('maps every state to a baton, loud only for someone who can raise the limit', () => {
    expect(budgetBaton(status())).toEqual({
      holder: 'done',
      label: 'within budget',
      detail: '25%',
    });
    expect(budgetBaton(status({ state: 'ALERT', spentUsd: 85 }), true)).toEqual({
      holder: 'you',
      label: 'budget alert',
      detail: '85% used',
    });
    expect(budgetBaton(status({ state: 'ALERT', spentUsd: 85 })).holder).toBe('idle');
    expect(budgetBaton(status({ state: 'EXCEEDED', spentUsd: 110 }), true)).toEqual({
      holder: 'you',
      label: 'over budget',
      detail: 'agents stopped',
    });
    expect(budgetBaton(status({ state: 'EXCEEDED', spentUsd: 110, hardStop: false })).detail).toBe(
      '110%',
    );
    expect(budgetBaton(status({ state: 'EXCEEDED' })).holder).toBe('blocked');
    expect(budgetBaton(status({ state: 'NO_LIMIT', monthlyLimitUsd: null }))).toEqual({
      holder: 'idle',
      label: 'no limit',
    });
    expect(budgetBaton(null).label).toBe('no budget');
  });

  it('says what was spent against the limit', () => {
    expect(spendText(status())).toBe('$25.00 of $100.00');
    expect(spendText(status({ monthlyLimitUsd: null, spentUsd: 3.5 }))).toBe(
      '$3.50 spent, no limit',
    );
    expect(spendText(null)).toBe('-');
  });

  it('turns the linear forecast into a verdict', () => {
    expect(forecastView(status())).toEqual({
      text: '$60.00 by the end of September',
      verdict: 'On track',
      tone: 'done',
      share: 0.6,
    });
    expect(forecastView(status({ forecastUsd: 85 })).tone).toBe('you');
    expect(forecastView(status({ forecastUsd: 85 })).verdict).toBe('Crosses the alert threshold');
    const over = forecastView(status({ forecastUsd: 124 }));
    expect(over.tone).toBe('blocked');
    expect(over.verdict).toBe('24% over the limit');
    expect(forecastView(status({ monthlyLimitUsd: null })).verdict).toBe('No limit set');
    expect(forecastView(status({ forecastUsd: undefined })).text).toBe('No forecast yet');
  });

  it('names the period', () => {
    expect(monthName('2026-09')).toBe('September');
    expect(monthName('2026-01', true)).toBe('January 2026');
    expect(monthName('bad')).toBe('the month');
  });

  it('needs attention on alert and exceeded only', () => {
    expect(needsAttention(status({ state: 'ALERT' }))).toBe(true);
    expect(needsAttention(status({ state: 'EXCEEDED' }))).toBe(true);
    expect(needsAttention(status({ state: 'OK' }))).toBe(false);
    expect(needsAttention(status({ state: 'NO_LIMIT' }))).toBe(false);
  });

  it('validates the budget form', () => {
    expect(budgetInputErrors({ monthlyLimitUsd: 50, alertAtPercent: 80 })).toEqual({});
    expect(budgetInputErrors({ monthlyLimitUsd: null, alertAtPercent: 80 })).toEqual({});
    expect(
      budgetInputErrors({ monthlyLimitUsd: 0, alertAtPercent: 80 }).monthlyLimitUsd,
    ).toBeTruthy();
    expect(
      budgetInputErrors({ monthlyLimitUsd: 10, alertAtPercent: 0 }).alertAtPercent,
    ).toBeTruthy();
    expect(
      budgetInputErrors({ monthlyLimitUsd: 10, alertAtPercent: 101 }).alertAtPercent,
    ).toBeTruthy();
    expect(
      budgetInputErrors({ monthlyLimitUsd: 10, alertAtPercent: null }).alertAtPercent,
    ).toBeTruthy();
  });
});
