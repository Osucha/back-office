/**
 * Monthly budgets (docs/architecture/11-governance.md): pure formatting shared by the project and
 * platform budget editors, the Costs header and the *Your turn* budget cards.
 */
import type { BudgetStatus } from '../api/types';
import type { Baton } from '../ui/relay/baton';
import { intlLocale } from '../i18n/locale';
import { formatCost } from '../ui/relay/format';

/** Spend as a share of the limit (0..n, 1 = the whole limit), `null` without a limit. */
export function budgetShare(
  spent: number | null | undefined,
  limit: number | null | undefined,
): number | null {
  if (limit === null || limit === undefined || limit <= 0) return null;
  return Math.max(0, spent ?? 0) / limit;
}

/** "42%" (whole numbers; "<1%" for a sliver, "999%+" caps silly values). */
export function formatShare(share: number | null): string {
  if (share === null) return '-';
  const pct = share * 100;
  if (pct > 0 && pct < 1) return '<1%';
  if (pct >= 999) return '999%+';
  return `${Math.round(pct)}%`;
}

const T = {
  within: () => $localize`:@@core.budget.within:within budget`,
  alert: () => $localize`:@@core.budget.alert:budget alert`,
  over: () => $localize`:@@core.budget.over:over budget`,
  stopped: () => $localize`:@@core.budget.agentsStopped:agents stopped`,
  used: (pct: string) => $localize`:@@core.budget.used:${pct}:share: used`,
  noLimit: () => $localize`:@@core.budget.noLimit:no limit`,
  noBudget: () => $localize`:@@core.budget.noBudget:no budget`,
};

/**
 * The budget's state as a baton. `canManage`: the viewer can raise the limit, so an alert or an
 * exceeded limit is their turn (amber); otherwise it is shown without the loud colour.
 */
export function budgetBaton(b: BudgetStatus | null | undefined, canManage = false): Baton {
  const pct = formatShare(budgetShare(b?.spentUsd, b?.monthlyLimitUsd));
  switch (b?.state) {
    case 'OK':
      return { holder: 'done', label: T.within(), detail: pct };
    case 'ALERT':
      return { holder: canManage ? 'you' : 'idle', label: T.alert(), detail: T.used(pct) };
    case 'EXCEEDED':
      return canManage
        ? { holder: 'you', label: T.over(), detail: b.hardStop ? T.stopped() : T.used(pct) }
        : { holder: 'blocked', label: T.over(), detail: b.hardStop ? T.stopped() : pct };
    case 'NO_LIMIT':
      return { holder: 'idle', label: T.noLimit() };
    default:
      return { holder: 'idle', label: T.noBudget() };
  }
}

/** "$12.40 of $50.00" or "$12.40 spent, no limit". */
export function spendText(b: BudgetStatus | null | undefined): string {
  if (!b) return '-';
  const spent = formatCost(b.spentUsd ?? 0);
  return b.monthlyLimitUsd === null || b.monthlyLimitUsd === undefined
    ? $localize`:@@core.budget.spentNoLimit:${spent}:spent: spent, no limit`
    : $localize`:@@core.budget.spentOf:${spent}:spent: of ${formatCost(b.monthlyLimitUsd)}:limit:`;
}

export interface ForecastView {
  /** "$41.20 by the end of September" */
  text: string;
  /** "On track" / "18% over the limit" / "Crosses the alert threshold" */
  verdict: string;
  tone: 'done' | 'you' | 'blocked' | 'idle';
  /** Forecast as a share of the limit, for the bar marker (null without a limit). */
  share: number | null;
}

/** The linear month-end forecast and what it means against the limit and alert threshold. */
export function forecastView(b: BudgetStatus | null | undefined): ForecastView {
  const forecast = b?.forecastUsd ?? null;
  const month = monthName(b?.period);
  const text =
    forecast === null
      ? $localize`:@@core.budget.noForecast:No forecast yet`
      : $localize`:@@core.budget.forecast:${formatCost(forecast)}:cost: by the end of ${month}:month:`;
  const share = budgetShare(forecast, b?.monthlyLimitUsd);
  if (share === null || forecast === null)
    return {
      text,
      verdict: forecast === null ? '' : $localize`:@@core.budget.noLimitSet:No limit set`,
      tone: 'idle',
      share,
    };
  if (share > 1)
    return {
      text,
      verdict: $localize`:@@core.budget.overLimit:${formatShare(share - 1)}:share: over the limit`,
      tone: 'blocked',
      share,
    };
  const alertAt = (b?.alertAtPercent ?? 80) / 100;
  if (share >= alertAt)
    return {
      text,
      verdict: $localize`:@@core.budget.crossesAlert:Crosses the alert threshold`,
      tone: 'you',
      share,
    };
  return { text, verdict: $localize`:@@core.budget.onTrack:On track`, tone: 'done', share };
}

/** "2026-09" -> "September" (the whole "September 2026" with `withYear`). */
export function monthName(period: string | null | undefined, withYear = false): string {
  const m = /^(\d{4})-(\d{2})$/.exec(period ?? '');
  if (!m) return $localize`:@@core.budget.theMonth:the month`;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, 1));
  return d.toLocaleString(intlLocale(), {
    month: 'long',
    ...(withYear ? { year: 'numeric' } : {}),
    timeZone: 'UTC',
  });
}

/** Budgets that need the owner's attention: over the alert threshold or the limit. */
export function needsAttention(b: BudgetStatus): boolean {
  return b.state === 'ALERT' || b.state === 'EXCEEDED';
}

/** Validation of a budget form: a positive limit (or none) and an alert between 1 and 100 %. */
export function budgetInputErrors(input: {
  monthlyLimitUsd: number | null;
  alertAtPercent: number | null;
}): { monthlyLimitUsd?: string; alertAtPercent?: string } {
  const out: { monthlyLimitUsd?: string; alertAtPercent?: string } = {};
  if (input.monthlyLimitUsd !== null && !(input.monthlyLimitUsd > 0))
    out.monthlyLimitUsd = $localize`:@@core.budget.error.limit:Enter a limit above $0, or turn the limit off.`;
  const a = input.alertAtPercent;
  if (a === null || !Number.isInteger(a) || a < 1 || a > 100)
    out.alertAtPercent = $localize`:@@core.budget.error.alert:Alert between 1 and 100 %.`;
  return out;
}
