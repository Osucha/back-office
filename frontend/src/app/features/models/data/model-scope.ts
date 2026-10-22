/**
 * Platform and personal model profiles (ADR 0050 step 2): who may edit a profile, which scope a new profile gets,
 * the scope filter of the models page and the default models per step kind. Pure; tested in model-scope.spec.ts.
 */
import type {
  CodingHarness,
  ModelDefaults,
  ModelProfile,
  ModelRuntime,
  StepKind,
} from '../../../core/api/types';

export type ProfileScope = 'PLATFORM' | 'PERSONAL';

/**
 * Whether the user may edit, test and delete the profile: platform profiles need models.manage, personal ones belong
 * to the caller (the list holds only the caller's own personal profiles). A new profile (no id) can always be
 * created: without models.manage it becomes personal.
 */
export function canEditProfile(
  profile: Pick<ModelProfile, 'id' | 'personal'> | null | undefined,
  canManage: boolean,
): boolean {
  if (!profile?.id) return true;
  return canManage || !!profile.personal;
}

/**
 * Scopes a new profile may get: a Claude Code login profile, a profile on the caller's personal connection (ADR 0050
 * step 2b) and users without models.manage: personal only.
 */
export function allowedScopes(
  canManage: boolean,
  runtime: ModelRuntime | null | undefined,
  personalConnection = false,
): ProfileScope[] {
  return canManage && runtime !== 'CLAUDE_CODE_LOGIN' && !personalConnection
    ? ['PLATFORM', 'PERSONAL']
    : ['PERSONAL'];
}

/** The scope of a new profile: the requested one when allowed, otherwise the first allowed one. */
export function effectiveScope(
  requested: ProfileScope,
  canManage: boolean,
  runtime: ModelRuntime | null | undefined,
  personalConnection = false,
): ProfileScope {
  const allowed = allowedScopes(canManage, runtime, personalConnection);
  return allowed.includes(requested) ? requested : allowed[0];
}

export type ScopeFilter = 'platform' | 'mine';

/** Profiles of one scope; no filter: all. */
export function filterByScope(
  list: readonly ModelProfile[],
  scope: string | null | undefined,
): ModelProfile[] {
  if (scope === 'platform') return list.filter((p) => !p.personal);
  if (scope === 'mine') return list.filter((p) => !!p.personal);
  return [...list];
}

export interface DefaultModelRow {
  stepKind: StepKind;
  /** Coding agent the options are asked for (coding steps: Claude Code). */
  harness: CodingHarness | null;
  label: string;
}

/** The rows of the "Default models" card: LLM steps and coding steps. */
export function defaultModelRows(): DefaultModelRow[] {
  return [
    {
      stepKind: 'LLM_CALL',
      harness: null,
      label: $localize`:@@models.defaults.llm:LLM steps`,
    },
    {
      stepKind: 'CODING_AGENT',
      harness: 'CLAUDE_CODE',
      label: $localize`:@@models.defaults.coding:Coding steps`,
    },
  ];
}

/** The default profile of a step kind in one scope, or null. */
export function defaultProfile(
  defaults: ModelDefaults | null | undefined,
  scope: ProfileScope,
  stepKind: StepKind,
): ModelProfile | null {
  const byKind = scope === 'PLATFORM' ? defaults?.platform : defaults?.mine;
  return byKind?.[stepKind] ?? null;
}
