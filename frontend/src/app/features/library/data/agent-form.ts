/**
 * ADR 0056/0057: the agent editor as pure functions (tested in agent-form.spec.ts). An agent is
 * who works and how: a harness (none = one model answer), optionally a model (none = the defaults
 * for the step), instructions, rules, skills and - for coding agents - remote MCP servers.
 */
import type {
  Agent,
  AgentAccessMode,
  AgentHarness,
  AgentInput,
  AgentMcpServer,
} from '../../../core/api/types';

/** ADR 0060: the access modes the editor offers, in display order. */
export const ACCESS_MODES: readonly AgentAccessMode[] = ['FULL', 'READ_ONLY', 'PATHS'];

/** The kinds the editor offers, in display order (`null` = model answer). */
export const AGENT_KINDS: ReadonlyArray<AgentHarness | null> = [null, 'OPENCODE', 'CLAUDE_CODE'];

/** ADR 0057: the name of an MCP server (as the contract's `AgentMcpServer.name`). */
export const MCP_NAME_PATTERN = /^[a-z][a-z0-9-]{0,39}$/;
/** ADR 0057: longest MCP server URL. */
export const MCP_URL_MAX = 500;

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
    mcpServers: [],
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
    mcpServers: (a.mcpServers ?? []).map((s) => ({ name: s.name, url: s.url })),
    ...(a.access ? { access: { mode: a.access.mode, paths: [...(a.access.paths ?? [])] } } : {}),
    tags: [...(a.tags ?? [])],
  };
}

/** ADR 0060: the access mode of a draft (FULL when not set). */
export function accessMode(d: Pick<AgentInput, 'access'>): AgentAccessMode {
  return d.access?.mode ?? 'FULL';
}

/** ADR 0060: the draft with another access mode; the paths are kept while editing. */
export function withAccessMode(d: AgentInput, mode: AgentAccessMode): AgentInput {
  return { ...d, access: { mode, paths: [...(d.access?.paths ?? [])] } };
}

/** ADR 0060: the draft with the path globs of a PATHS access. */
export function withAccessPaths(d: AgentInput, paths: readonly string[]): AgentInput {
  return { ...d, access: { mode: accessMode(d), paths: [...paths] } };
}

/** ADR 0060: the path globs that count (trimmed, not empty, once). */
export function accessPaths(d: Pick<AgentInput, 'access'>): string[] {
  return [...new Set((d.access?.paths ?? []).map((p) => p.trim()).filter((p) => !!p))];
}

/** ADR 0060: a coding agent limited to paths names at least one. */
export function accessProblem(d: Pick<AgentInput, 'harness' | 'access'>): boolean {
  return !!d.harness && accessMode(d) === 'PATHS' && !accessPaths(d).length;
}

/** What is wrong with the MCP servers of a draft; null when nothing. */
export type McpServerProblem =
  | { kind: 'badName'; index: number; name: string }
  | { kind: 'duplicate'; index: number; name: string }
  | { kind: 'badUrl'; index: number; name: string };

/** Whether a text is an http(s) URL (no credentials checked: the field help says not to put any). */
export function isHttpUrl(value: string): boolean {
  const v = value.trim();
  if (!v || v.length > MCP_URL_MAX || !/^https?:\/\//i.test(v)) return false;
  try {
    const u = new URL(v);
    return (u.protocol === 'http:' || u.protocol === 'https:') && !!u.hostname;
  } catch {
    return false;
  }
}

/** ADR 0057: the first problem of the MCP server list (name pattern, unique names, http(s) URL). */
export function mcpServerProblem(
  servers: readonly AgentMcpServer[] | null | undefined,
): McpServerProblem | null {
  const seen = new Set<string>();
  for (const [index, s] of (servers ?? []).entries()) {
    const name = s.name.trim();
    if (!MCP_NAME_PATTERN.test(name)) return { kind: 'badName', index, name };
    if (seen.has(name)) return { kind: 'duplicate', index, name };
    seen.add(name);
    if (!isHttpUrl(s.url)) return { kind: 'badUrl', index, name };
  }
  return null;
}

/** Whether the draft may be saved: a name, the project of a project agent, valid MCP servers. */
export function agentSavable(d: AgentInput): boolean {
  return (
    !!d.name.trim() &&
    (d.scope.type !== 'PROJECT' || !!d.scope.projectId) &&
    (!d.harness || !mcpServerProblem(d.mcpServers)) &&
    !accessProblem(d)
  );
}

/**
 * What the editor sends: trimmed MCP servers, and none for a model answer (it has no tools, so
 * servers kept from an earlier coding kind would only confuse).
 */
export function agentPayload(d: AgentInput): AgentInput {
  const { access, ...rest } = d;
  const mode = accessMode(d);
  return {
    ...rest,
    mcpServers: d.harness
      ? (d.mcpServers ?? []).map((s) => ({ name: s.name.trim(), url: s.url.trim() }))
      : [],
    // ADR 0060: only coding agents change files; the paths only with PATHS
    ...(d.harness && access
      ? { access: mode === 'PATHS' ? { mode, paths: accessPaths(d) } : { mode } }
      : {}),
  };
}

/** A new MCP server row. */
export function addMcpServer(servers: readonly AgentMcpServer[] | undefined): AgentMcpServer[] {
  return [...(servers ?? []), { name: '', url: '' }];
}

export function patchMcpServer(
  servers: readonly AgentMcpServer[] | undefined,
  index: number,
  changes: Partial<AgentMcpServer>,
): AgentMcpServer[] {
  return (servers ?? []).map((s, i) => (i === index ? { ...s, ...changes } : s));
}

export function removeMcpServer(
  servers: readonly AgentMcpServer[] | undefined,
  index: number,
): AgentMcpServer[] {
  return (servers ?? []).filter((_, i) => i !== index);
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
