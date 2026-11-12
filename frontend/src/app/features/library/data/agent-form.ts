/**
 * ADR 0056/0057: the agent editor as pure functions (tested in agent-form.spec.ts). An agent is
 * who works and how: a harness (none = one model answer), optionally a model (none = the defaults
 * for the step), instructions, rules, skills and - for coding agents - connectors (ADR 0064), access
 * (what it may change, which directory) and the session (ADR 0062).
 */
import type {
  Agent,
  AgentAccess,
  AgentAccessMode,
  AgentDirectory,
  AgentHarness,
  AgentInput,
  AgentSession,
} from '../../../core/api/types';
import { badConnectorKey, cleanConnectorKeys } from './connector-form';

/** ADR 0060: the access modes the editor offers, in display order. */
export const ACCESS_MODES: readonly AgentAccessMode[] = ['FULL', 'READ_ONLY', 'PATHS'];
/** ADR 0062: the sessions of a coding agent, in display order (the first is the default). */
export const AGENT_SESSIONS: readonly AgentSession[] = ['PER_STEP', 'PER_RUN', 'FRESH'];
/** ADR 0062: where a coding agent works, in display order (the first is the default). */
export const AGENT_DIRECTORIES: readonly AgentDirectory[] = ['RUN', 'SEPARATE'];

/** The kinds the editor offers, in display order (`null` = model answer). */
export const AGENT_KINDS: ReadonlyArray<AgentHarness | null> = [null, 'OPENCODE', 'CLAUDE_CODE'];

/** The radio value of a kind (a radio group cannot hold `null` reliably). */
export type AgentKindValue = 'MODEL_ANSWER' | AgentHarness;

export function kindValue(harness: AgentHarness | null | undefined): AgentKindValue {
  return harness ?? 'MODEL_ANSWER';
}

export function harnessOf(value: AgentKindValue): AgentHarness | null {
  return value === 'MODEL_ANSWER' ? null : value;
}

/** Blank agent draft (no key: the server derives it from the name). */
export function emptyAgent(): AgentInput {
  return {
    name: '',
    description: '',
    scope: { type: 'GLOBAL', projectId: null },
    harness: null,
    modelProfileId: null,
    instructions: '',
    rules: [],
    skills: [],
    connectors: [],
    session: 'PER_STEP',
    tags: [],
  };
}

/** The editable fields of a saved agent (the key is kept by the server, never sent). */
export function agentDraft(a: Agent): AgentInput {
  return {
    name: a.name,
    description: a.description ?? '',
    scope: { type: a.scope?.type ?? 'GLOBAL', projectId: a.scope?.projectId ?? null },
    harness: a.harness ?? null,
    modelProfileId: a.modelProfileId ?? null,
    instructions: a.instructions ?? '',
    rules: [...(a.rules ?? [])],
    skills: [...(a.skills ?? [])],
    connectors: [...(a.connectors ?? [])],
    ...(a.access
      ? {
          access: {
            mode: a.access.mode ?? 'FULL',
            directory: a.access.directory ?? 'RUN',
            paths: [...(a.access.paths ?? [])],
          },
        }
      : {}),
    session: a.session ?? 'PER_STEP',
    tags: [...(a.tags ?? [])],
  };
}

/** ADR 0060: the access mode of a draft (FULL when not set). */
export function accessMode(d: Pick<AgentInput, 'access'>): AgentAccessMode {
  return d.access?.mode ?? 'FULL';
}

/** ADR 0062: the directory of a draft (the run directory when not set). */
export function accessDirectory(d: Pick<AgentInput, 'access'>): AgentDirectory {
  return d.access?.directory ?? 'RUN';
}

/** The draft's access with some fields changed (the paths are kept while editing). */
function withAccess(d: AgentInput, change: Partial<AgentAccess>): AgentInput {
  return {
    ...d,
    access: {
      mode: accessMode(d),
      directory: accessDirectory(d),
      paths: [...(d.access?.paths ?? [])],
      ...change,
    },
  };
}

/** ADR 0060: the draft with another access mode; the paths are kept while editing. */
export function withAccessMode(d: AgentInput, mode: AgentAccessMode): AgentInput {
  return withAccess(d, { mode });
}

/** ADR 0060: the draft with the path globs of a PATHS access. */
export function withAccessPaths(d: AgentInput, paths: readonly string[]): AgentInput {
  return withAccess(d, { paths: [...paths] });
}

/** ADR 0062: the draft working in the run directory or a separate one. */
export function withAccessDirectory(d: AgentInput, directory: AgentDirectory): AgentInput {
  return withAccess(d, { directory });
}

/** ADR 0062: the session of a draft (per step when not set). */
export function agentSession(d: Pick<AgentInput, 'session'>): AgentSession {
  return d.session ?? 'PER_STEP';
}

/** ADR 0060: the path globs that count (trimmed, not empty, once). */
export function accessPaths(d: Pick<AgentInput, 'access'>): string[] {
  return [...new Set((d.access?.paths ?? []).map((p) => p.trim()).filter((p) => !!p))];
}

/** ADR 0060: a coding agent limited to paths names at least one. */
export function accessProblem(d: Pick<AgentInput, 'harness' | 'access'>): boolean {
  return !!d.harness && accessMode(d) === 'PATHS' && !accessPaths(d).length;
}

/** Whether the draft may be saved: a name, the project of a project agent, valid connector keys. */
export function agentSavable(d: AgentInput): boolean {
  return (
    !!d.name.trim() &&
    (d.scope.type !== 'PROJECT' || !!d.scope.projectId) &&
    (!d.harness || !badConnectorKey(d.connectors)) &&
    !accessProblem(d)
  );
}

/**
 * What the editor sends: the connector keys trimmed and once, and none for a model answer (it has no
 * tools, so connectors kept from an earlier coding kind would only confuse; ADR 0064). Access and session only matter
 * for coding agents; a model answer gets the defaults.
 */
export function agentPayload(d: AgentInput): AgentInput {
  const { access, ...rest } = d;
  const mode = accessMode(d);
  const directory = accessDirectory(d);
  return {
    ...rest,
    connectors: d.harness ? cleanConnectorKeys(d.connectors) : [],
    // ADR 0062: the conversation of a coding agent; a model answer has none to continue
    session: d.harness ? agentSession(d) : 'PER_STEP',
    // ADR 0060: only coding agents change files; the paths only with PATHS
    ...(d.harness && access
      ? {
          access:
            mode === 'PATHS' ? { mode, directory, paths: accessPaths(d) } : { mode, directory },
        }
      : {}),
  };
}

/**
 * The first lines of the instructions as a short read-only excerpt (markdown kept as text);
 * empty when there are none.
 */
export function instructionsExcerpt(text: string | null | undefined, max = 240): string {
  const t = (text ?? '').trim();
  if (t.length <= max) return t;
  const cut = t.slice(0, max);
  const space = cut.lastIndexOf(' ');
  return `${(space > max * 0.6 ? cut.slice(0, space) : cut).trimEnd()}…`;
}

/**
 * ADR 0055/0056: a new PROJECT agent that customises the global agent `a` for one project: the
 * same key, name, kind, model and environment (a project agent with a global agent's key replaces
 * it there).
 */
export function customizeAgentForProject(a: Agent, projectId: string | null): AgentInput {
  return { ...agentDraft(a), key: a.key ?? null, scope: { type: 'PROJECT', projectId } };
}
