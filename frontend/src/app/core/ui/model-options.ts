/**
 * Model availability in the pickers (ADR 0050): grouping of the model options a step may use, the route tag,
 * the merge of the options of two step kinds (the project default model) and the model plan summary of the
 * start dialog. Pure functions; the select itself is `ModelSelectComponent`.
 */
import type { ModelOption, StepModelPlan } from '../api/types';

/** A model option as a picker shows it; `hint` is a neutral remark under the select (not a warning). */
export type ModelChoice = ModelOption & { hint?: string | null };

export interface ModelChoiceGroup {
  key: 'platform' | 'personal';
  label: string;
  options: ModelChoice[];
}

/** Platform models (provider connections) first, then the user's personal ones; empty groups are left out. */
export function groupModelOptions(options: readonly ModelChoice[]): ModelChoiceGroup[] {
  const platform = options.filter((o) => !o.personal);
  const personal = options.filter((o) => o.personal);
  const groups: ModelChoiceGroup[] = [];
  if (platform.length)
    groups.push({
      key: 'platform',
      label: $localize`:@@core.modelSelect.platform:Platform`,
      options: platform,
    });
  if (personal.length)
    groups.push({
      key: 'personal',
      label: $localize`:@@core.modelSelect.personal:Personal (your Claude login)`,
      options: personal,
    });
  return groups;
}

/** Short tag of the route a model is called by. */
export function routeLabel(route: ModelOption['route'] | null | undefined): string {
  return route === 'OWN_LOGIN'
    ? $localize`:@@core.modelSelect.route.ownLogin:your login`
    : $localize`:@@core.modelSelect.route.api:API`;
}

/** Usable options first, otherwise in the given order. */
function usableFirst<T extends ModelOption>(options: T[]): T[] {
  return [...options.filter((o) => o.usable), ...options.filter((o) => !o.usable)];
}

/**
 * Options of the project default model, which applies to LLM steps and to coding steps without their own model:
 * a model is selectable when either kind may use it. Usable only for coding steps: a hint says so. Usable for
 * neither: the reason of the kind the model is meant for (a login model: coding steps).
 */
export function mergeDefaultModelOptions(
  llm: readonly ModelOption[],
  coding: readonly ModelOption[],
): ModelChoice[] {
  const byId = new Map<string, { llm?: ModelOption; coding?: ModelOption }>();
  for (const o of llm) byId.set(o.profileId, { ...byId.get(o.profileId), llm: o });
  for (const o of coding) byId.set(o.profileId, { ...byId.get(o.profileId), coding: o });
  const merged: ModelChoice[] = [];
  for (const { llm: l, coding: c } of byId.values()) {
    if (l?.usable) merged.push(l);
    else if (c?.usable)
      merged.push({
        ...c,
        hint: $localize`:@@core.modelSelect.codingOnly:coding steps only (LLM steps need their own model)`,
      });
    else {
      const base = (c?.route === 'OWN_LOGIN' ? c : (l ?? c))!;
      merged.push(base);
    }
  }
  return usableFirst(merged);
}

/** Where the model of a step comes from, as the start dialog names it. */
export function planSourceLabel(source: StepModelPlan['source']): string {
  switch (source) {
    case 'NODE':
      return $localize`:@@core.modelPlan.source.node:step`;
    case 'TEMPLATE':
      return $localize`:@@core.modelPlan.source.template:template`;
    case 'PROJECT':
      return $localize`:@@core.modelPlan.source.project:project default`;
    case 'PROJECT_REVIEW':
      return $localize`:@@core.modelPlan.source.review:review default`;
    default:
      return $localize`:@@core.modelPlan.source.none:not set`;
  }
}

export interface ModelPlanSummary {
  /** Steps whose model stops the run from starting. */
  blocked: number;
  /** Steps that run but with a warning. */
  warnings: number;
}

export function summarizeModelPlan(plan: readonly StepModelPlan[]): ModelPlanSummary {
  let blocked = 0;
  let warnings = 0;
  for (const s of plan) {
    if (s.problem) blocked++;
    else if (s.note || s.model?.note) warnings++;
  }
  return { blocked, warnings };
}
