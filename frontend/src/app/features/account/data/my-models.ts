import type { ExecutionTarget, ModelOption } from '../../../core/api/types';

/** What the personal runner reported about a Claude login (ADR 0050 step 3). */
export type LoginReport = 'reported' | 'none' | 'unknown';

export interface PersonalRunnerStatus {
  /** A personal runner pool exists. */
  setUp: boolean;
  online: number;
  /** The pool lets Claude Code use the owner's own login. */
  localLogin: boolean;
  login: LoginReport;
  /** Coding agents the online runners reported, sorted. */
  harnesses: string[];
}

/** Account → My models: the personal runner as the execution targets describe it (null when unknown). */
export function personalRunnerStatus(
  targets: readonly ExecutionTarget[] | null | undefined,
): PersonalRunnerStatus | null {
  if (!targets) return null;
  const t = targets.find((x) => x.target === 'PERSONAL');
  const setUp = !!t?.pools;
  const online = setUp ? (t?.onlineRunners ?? 0) : 0;
  const claudeLogin = online ? t?.claudeLogin : null;
  return {
    setUp,
    online,
    localLogin: setUp && !!t?.localLogin,
    login: claudeLogin === true ? 'reported' : claudeLogin === false ? 'none' : 'unknown',
    harnesses: online ? [...(t?.harnesses ?? [])].sort() : [],
  };
}

/** Readable name of a coding agent a runner reports. */
export function harnessName(harness: string): string {
  switch (harness) {
    case 'CLAUDE_CODE':
      return 'Claude Code';
    case 'OPENCODE':
      return 'OpenCode';
    case 'SPEC_KIT':
      return 'Spec Kit';
    default:
      return harness;
  }
}

export interface SplitOptions {
  usable: ModelOption[];
  unusable: ModelOption[];
}

/** Usable models (platform first, then personal; by name) and the ones a step cannot run with. */
export function splitModelOptions(
  options: readonly ModelOption[] | null | undefined,
): SplitOptions {
  const sorted = [...(options ?? [])].sort(
    (a, b) => Number(a.personal) - Number(b.personal) || a.displayName.localeCompare(b.displayName),
  );
  return {
    usable: sorted.filter((o) => o.usable),
    unusable: sorted.filter((o) => !o.usable),
  };
}
