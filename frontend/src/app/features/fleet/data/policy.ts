/**
 * Sandbox policy narrowing (docs/architecture/10-agent-execution.md, "Sandbox policy"): a lower level
 * (pool < platform, project < pool, template < project, node < template) can restrict but never widen
 * the ceiling of the level above. These pure functions mirror the backend rules so the editor can mark
 * fields the user cannot widen before saving; the backend still decides (400 with field errors).
 *
 * | Field | Rule |
 * |---|---|
 * | isolation | the stronger wins (DEV < STANDARD < HIGH) |
 * | resources, timeout, cost and token caps | the smaller wins |
 * | network.allow | every pattern must lie inside a ceiling pattern |
 * | tools.deny, tools.ask | union (the ceiling's entries always apply) |
 * | tools.allow | intersection (only entries the ceiling allows) |
 * | secrets, caches | subset of the ceiling's (when it lists any) |
 * | environment.image | must match the pool's allowed image patterns |
 * | poolSelector | the ceiling's labels must be kept |
 */
import type {
  SandboxCache,
  SandboxIsolation,
  SandboxPolicy,
  SandboxResources,
} from '../../../core/api/types';
import { formatNumber } from '../../../core/i18n/numbers';

export const ISOLATION_RANK: Record<SandboxIsolation, number> = { DEV: 0, STANDARD: 1, HIGH: 2 };

export interface IsolationOption {
  value: SandboxIsolation;
  label: string;
  runtime: string;
  explanation: string;
}

export const ISOLATION_OPTIONS: readonly IsolationOption[] = [
  {
    value: 'STANDARD',
    label: $localize`:@@fleet.isolation.standard:Standard`,
    runtime: 'gVisor',
    explanation: $localize`:@@fleet.isolation.standard.explanation:A user-space kernel between the agent and the node. The default for model-written code.`,
  },
  {
    value: 'HIGH',
    label: $localize`:@@fleet.isolation.high:High`,
    runtime: 'Kata microVM', // i18n-ignore: product name
    explanation: $localize`:@@fleet.isolation.high.explanation:Each job in its own lightweight virtual machine with a separate kernel. For untrusted repositories.`,
  },
  {
    value: 'DEV',
    label: $localize`:@@fleet.isolation.dev:Dev`,
    runtime: 'runc',
    explanation: $localize`:@@fleet.isolation.dev.explanation:A plain container on the docker backend, for local development only. No kernel isolation.`,
  },
];

/** The stronger of two isolation levels (undefined = not set at that level). */
export function strongerIsolation(
  a: SandboxIsolation | null | undefined,
  b: SandboxIsolation | null | undefined,
): SandboxIsolation | undefined {
  if (!a) return b ?? undefined;
  if (!b) return a;
  return ISOLATION_RANK[a] >= ISOLATION_RANK[b] ? a : b;
}

/** Isolation levels a lower level may pick: the ceiling's or stronger. */
export function allowedIsolations(
  ceiling: SandboxIsolation | null | undefined,
): SandboxIsolation[] {
  const min = ceiling ? ISOLATION_RANK[ceiling] : 0;
  return (Object.keys(ISOLATION_RANK) as SandboxIsolation[]).filter(
    (i) => ISOLATION_RANK[i] >= min,
  );
}

// ---------------------------------------------------------------- quantities

const BINARY: Record<string, number> = {
  Ki: 2 ** 10,
  Mi: 2 ** 20,
  Gi: 2 ** 30,
  Ti: 2 ** 40,
};
const DECIMAL: Record<string, number> = { k: 1e3, K: 1e3, M: 1e6, G: 1e9, T: 1e12, m: 1e-3 };

/**
 * Kubernetes quantity to a number in base units: `"2"` -> 2, `"500m"` -> 0.5, `"4Gi"` -> bytes,
 * `"1G"` -> 1e9. `null` when it does not parse.
 */
export function parseQuantity(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  const m = /^\s*(\d+(?:\.\d+)?)\s*(Ki|Mi|Gi|Ti|k|K|M|G|T|m)?\s*$/.exec(value);
  if (!m) return null;
  const n = Number(m[1]);
  const unit = m[2];
  if (!unit) return n;
  return n * (BINARY[unit] ?? DECIMAL[unit] ?? 1);
}

export function isQuantity(value: string | null | undefined, kind: 'cpu' | 'bytes'): boolean {
  if (!value) return true;
  const re = kind === 'cpu' ? /^\d+(\.\d+)?m?$/ : /^\d+(\.\d+)?(Ki|Mi|Gi|Ti|k|K|M|G|T)?$/;
  return re.test(value.trim()) && (parseQuantity(value) ?? 0) > 0;
}

/** The smaller of two limits (strings compared as quantities, numbers directly). */
function smaller<T extends string | number>(a: T | null | undefined, b: T | null | undefined) {
  if (a === null || a === undefined) return b ?? undefined;
  if (b === null || b === undefined) return a;
  const qa = parseQuantity(a);
  const qb = parseQuantity(b);
  if (qa === null || qb === null) return a;
  return qa <= qb ? a : b;
}

// ---------------------------------------------------------------- host patterns

const LABEL = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

/**
 * Validates a `host[:port]` allowlist pattern (`*` stands for exactly one label). Returns the
 * reason in words, or `null` when valid.
 */
export function hostPatternError(raw: string): string | null {
  const value = raw.trim().toLowerCase();
  if (!value) return $localize`:@@fleet.host.error.empty:Enter a host name.`;
  if (/^[a-z]+:\/\//.test(value))
    return $localize`:@@fleet.host.error.scheme:Enter the host only, without https://.`;
  if (/[/?#@\s]/.test(value))
    return $localize`:@@fleet.host.error.path:Enter the host only, without a path or spaces.`;
  const [host, port, extra] = value.split(':');
  if (extra !== undefined) return $localize`:@@fleet.host.error.form:Use host or host:port.`;
  if (port !== undefined) {
    if (!/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535)
      return $localize`:@@fleet.host.error.port:The port must be a number from 1 to 65535.`;
  }
  const labels = host.split('.');
  if (labels.length < 2)
    return $localize`:@@fleet.host.error.full:Use a full host name such as registry.npmjs.org.`;
  if (labels.some((l) => l !== '*' && !LABEL.test(l)))
    return $localize`:@@fleet.host.error.chars:Letters, digits and hyphens only; * stands for one whole label.`;
  if (labels[labels.length - 1] === '*')
    return $localize`:@@fleet.host.error.tld:The top-level domain cannot be a wildcard.`;
  if (labels.filter((l) => l !== '*').length < 2)
    return $localize`:@@fleet.host.error.wildcard:A wildcard needs at least two fixed labels (e.g. *.gradle.org).`;
  return null;
}

function splitPattern(p: string): { labels: string[]; port: string | null } {
  const [host, port] = p.trim().toLowerCase().split(':');
  return { labels: host.split('.'), port: port ?? null };
}

/** Does `pattern` (from the ceiling) allow the concrete `host[:port]`? */
export function hostMatches(pattern: string, host: string): boolean {
  const p = splitPattern(pattern);
  const h = splitPattern(host);
  if (p.port && p.port !== (h.port ?? '443')) return false;
  if (p.labels.length !== h.labels.length) return false;
  return p.labels.every((l, i) => l === '*' || l === h.labels[i]);
}

/** Is every host that `inner` allows also allowed by `outer`? (`*` only inside `*`.) */
export function patternWithin(inner: string, outer: string): boolean {
  const i = splitPattern(inner);
  const o = splitPattern(outer);
  if (o.port && o.port !== (i.port ?? '443')) return false;
  if (i.labels.length !== o.labels.length) return false;
  return o.labels.every((l, k) => l === '*' || l === i.labels[k]);
}

/** The ceiling pattern that covers `pattern`, or null. Empty ceiling = anything. */
export function coveringPattern(
  pattern: string,
  ceiling: readonly string[] | null | undefined,
): string | null {
  if (!ceiling?.length) return '*';
  return ceiling.find((c) => patternWithin(pattern, c)) ?? null;
}

// ---------------------------------------------------------------- tools and lists

/** Glob with `*` = any text (tool patterns such as `npm *`). */
export function globMatches(pattern: string, value: string): boolean {
  const re = new RegExp(
    '^' +
      pattern
        .trim()
        .split('*')
        .map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&'))
        .join('.*') +
      '$',
  );
  return re.test(value.trim());
}

/** Is the tool pattern allowed by the ceiling's allow list? An empty list means any tool. */
export function toolAllowedBy(entry: string, ceilingAllow: readonly string[] | null | undefined) {
  if (!ceilingAllow?.length) return true;
  return ceilingAllow.some((c) => c.trim() === entry.trim() || globMatches(c, entry));
}

const uniq = (xs: readonly string[]) => [...new Set(xs.map((x) => x.trim()).filter(Boolean))];

// ---------------------------------------------------------------- composition and checks

/**
 * The effective policy of `lower` under `ceiling` (what the backend resolves for one step).
 * Unset fields inherit the ceiling.
 */
export function narrow(
  ceiling: SandboxPolicy | null | undefined,
  lower: SandboxPolicy | null | undefined,
): SandboxPolicy {
  const c = ceiling ?? {};
  const l = lower ?? {};
  const res = narrowResources(c.resources, l.resources);
  const allow = narrowSubset(c.network?.allow, l.network?.allow, coveringPattern, uniq);
  return withoutUndefined<SandboxPolicy>({
    isolation: strongerIsolation(c.isolation, l.isolation),
    resources: Object.keys(res).length ? res : undefined,
    timeoutSeconds: smaller(c.timeoutSeconds, l.timeoutSeconds),
    network: allow === undefined ? undefined : { allow },
    tools: narrowTools(c.tools, l.tools),
    secrets: narrowSubset(c.secrets, l.secrets, (s, cs) => cs.includes(s), uniq),
    environment: narrowEnvironment(c.environment, l.environment),
    maxCostUsd: smaller(c.maxCostUsd, l.maxCostUsd),
    maxTokens: smaller(c.maxTokens, l.maxTokens),
    poolSelector: narrowSelector(c.poolSelector, l.poolSelector),
  });
}

/** The object without its undefined properties (unset = inherited). */
function withoutUndefined<T extends object>(o: T): T {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as T;
}

/** The lower level may add labels; the ceiling's labels always win. */
function narrowSelector(
  c: Record<string, string> | undefined,
  l: Record<string, string> | undefined,
): Record<string, string> | undefined {
  return c || l ? { ...l, ...c } : undefined;
}

type SandboxTools = NonNullable<SandboxPolicy['tools']>;
type SandboxEnvironment = NonNullable<SandboxPolicy['environment']>;

/** The smaller of each resource limit. */
function narrowResources(
  c: SandboxResources | undefined,
  l: SandboxResources | undefined,
): SandboxResources {
  const res: SandboxResources = {};
  for (const k of ['cpu', 'memory', 'ephemeralStorage'] as const) {
    const v = smaller(c?.[k], l?.[k]);
    if (v !== undefined) res[k] = v;
  }
  const pids = smaller(c?.pids, l?.pids);
  if (pids !== undefined) res.pids = pids;
  return res;
}

/**
 * A list that must stay inside the ceiling's list: unset inherits the ceiling, an empty ceiling list
 * puts no limit on it, otherwise only the entries `allowedBy` the ceiling are kept (after `clean`).
 */
function narrowSubset<T>(
  ceiling: T[] | undefined,
  lower: T[] | undefined,
  allowedBy: (entry: T, ceiling: readonly T[]) => unknown,
  clean: (entries: T[]) => T[] = (entries) => entries,
): T[] | undefined {
  if (lower === undefined) return ceiling;
  if (!ceiling?.length) return clean(lower);
  return clean(lower).filter((x) => allowedBy(x, ceiling));
}

/** `allow` intersects with the ceiling's, `deny` and `ask` are unions; undefined when all are unset. */
function narrowTools(
  c: SandboxTools | undefined,
  l: SandboxTools | undefined,
): SandboxTools | undefined {
  const allow =
    l?.allow === undefined ? c?.allow : uniq(l.allow).filter((t) => toolAllowedBy(t, c?.allow));
  const deny = uniq([...(c?.deny ?? []), ...(l?.deny ?? [])]);
  const ask = uniq([...(c?.ask ?? []), ...(l?.ask ?? [])]);
  if (allow === undefined && !deny.length && !ask.length) return undefined;
  return {
    ...(allow !== undefined ? { allow } : {}),
    ...(deny.length ? { deny } : {}),
    ...(ask.length ? { ask } : {}),
  };
}

/** The lower image and setup command win; caches stay inside the ceiling's. */
function narrowEnvironment(
  c: SandboxEnvironment | undefined,
  l: SandboxEnvironment | undefined,
): SandboxEnvironment | undefined {
  const image = l?.image ?? c?.image;
  const setup = l?.setupCommand !== undefined ? l.setupCommand : c?.setupCommand;
  const caches = narrowSubset<SandboxCache>(c?.caches, l?.caches, (x, cc) => cc.includes(x));
  if (image === undefined && setup === undefined && caches === undefined) return undefined;
  return {
    ...(image !== undefined ? { image } : {}),
    ...(setup !== undefined ? { setupCommand: setup } : {}),
    ...(caches !== undefined ? { caches } : {}),
  };
}

export interface PolicyIssue {
  /** Field path, e.g. `resources.cpu`, `network.allow.2`, `tools.allow.0`. */
  path: string;
  message: string;
}

export interface NarrowingContext {
  /** The pool's allowed image patterns (`ghcr.io/org/back-office-runner:*`). */
  allowedImages?: readonly string[] | null;
}

/**
 * Everything in `policy` that would widen `ceiling`, plus malformed values. Empty = the policy is a
 * valid narrowing. Messages name the ceiling so the user knows the limit.
 */
export function narrowingIssues(
  policy: SandboxPolicy | null | undefined,
  ceiling: SandboxPolicy | null | undefined,
  ctx: NarrowingContext = {},
): PolicyIssue[] {
  const p = policy ?? {};
  const c = ceiling ?? {};
  return [
    ...isolationIssues(p.isolation, c.isolation),
    ...resourceIssues(p.resources ?? {}, c.resources),
    ...capIssues(p, c),
    ...networkIssues(p.network?.allow, c.network?.allow),
    ...toolIssues(p.tools?.allow, c.tools),
    ...listedIssues(
      'secrets',
      p.secrets,
      c.secrets,
      (s) => $localize`:@@fleet.policy.issue.secret:${s}:secret: is not available at this level.`,
    ),
    ...imageIssues(p.environment?.image, c.environment?.image, ctx.allowedImages),
    ...listedIssues(
      'environment.caches',
      p.environment?.caches,
      c.environment?.caches,
      (x) => $localize`:@@fleet.policy.issue.cache:The ${x}:cache: cache is not available.`,
    ),
    ...selectorIssues(p.poolSelector, c.poolSelector),
  ];
}

function isolationIssues(
  mine: SandboxIsolation | undefined,
  ceiling: SandboxIsolation | undefined,
): PolicyIssue[] {
  if (!mine || !ceiling || ISOLATION_RANK[mine] >= ISOLATION_RANK[ceiling]) return [];
  return [
    {
      path: 'isolation',
      message: $localize`:@@fleet.policy.issue.isolation:The ceiling requires ${isolationName(ceiling)}:isolation: isolation or stronger.`,
    },
  ];
}

function resourceIssues(
  res: SandboxResources,
  ceiling: SandboxResources | undefined,
): PolicyIssue[] {
  const out: PolicyIssue[] = [];
  for (const [k, kind] of [
    ['cpu', 'cpu'],
    ['memory', 'bytes'],
    ['ephemeralStorage', 'bytes'],
  ] as const) {
    const issue = quantityIssue(res[k], ceiling?.[k], kind);
    if (issue) out.push({ path: `resources.${k}`, message: issue });
  }
  checkNumber(out, 'resources.pids', res.pids, ceiling?.pids, (v) => atMost(String(v)));
  return out;
}

/** Malformed quantity or above the ceiling's; null when fine (or unset). */
function quantityIssue(
  v: string | null | undefined,
  max: string | undefined,
  kind: 'cpu' | 'bytes',
): string | null {
  if (v === undefined || v === null || v === '') return null;
  if (!isQuantity(v, kind))
    return kind === 'cpu'
      ? $localize`:@@fleet.policy.issue.cpu:Use cores (2) or millicores (500m).`
      : $localize`:@@fleet.policy.issue.size:Use a size such as 4Gi.`;
  if (max && (parseQuantity(v) ?? 0) > (parseQuantity(max) ?? Infinity)) return atMost(max);
  return null;
}

function capIssues(p: SandboxPolicy, c: SandboxPolicy): PolicyIssue[] {
  const out: PolicyIssue[] = [];
  checkNumber(out, 'timeoutSeconds', p.timeoutSeconds, c.timeoutSeconds, (v) =>
    atMost(formatSeconds(v)),
  );
  checkNumber(out, 'maxCostUsd', p.maxCostUsd, c.maxCostUsd, (v) => atMost('$' + formatNumber(v)));
  checkNumber(out, 'maxTokens', p.maxTokens, c.maxTokens, (v) => {
    const tokens = formatNumber(v);
    return $localize`:@@fleet.policy.issue.maxTokens:At most ${tokens}:tokens: tokens (ceiling).`;
  });
  return out;
}

function networkIssues(
  allow: readonly string[] | undefined,
  ceiling: readonly string[] | undefined,
): PolicyIssue[] {
  const out: PolicyIssue[] = [];
  (allow ?? []).forEach((pattern, i) => {
    const err = hostPatternError(pattern);
    if (err) out.push({ path: `network.allow.${i}`, message: `${pattern}: ${err}` });
    else if (!coveringPattern(pattern, ceiling))
      out.push({
        path: `network.allow.${i}`,
        message: $localize`:@@fleet.policy.issue.hostOutside:${pattern}:pattern: is not inside the ceiling's allowlist.`,
      });
  });
  return out;
}

function toolIssues(
  allow: readonly string[] | undefined,
  ceiling: SandboxTools | undefined,
): PolicyIssue[] {
  const out: PolicyIssue[] = [];
  (allow ?? []).forEach((t, i) => {
    if (!toolAllowedBy(t, ceiling?.allow))
      out.push({
        path: `tools.allow.${i}`,
        message: $localize`:@@fleet.policy.issue.toolNotAllowed:${t}:tool: is not allowed by the ceiling.`,
      });
    const denied = (ceiling?.deny ?? []).find((d) => d.trim() === t.trim() || globMatches(d, t));
    if (denied)
      out.push({
        path: `tools.allow.${i}`,
        message: $localize`:@@fleet.policy.issue.toolDenied:${t}:tool: is denied by the ceiling (${denied}:rule:).`,
      });
  });
  return out;
}

/** Entries of `mine` missing from the ceiling's list (no issues when the ceiling lists none). */
function listedIssues<T extends string>(
  path: string,
  mine: readonly T[] | undefined,
  ceiling: readonly T[] | undefined,
  message: (entry: T) => string,
): PolicyIssue[] {
  if (!ceiling?.length) return [];
  const out: PolicyIssue[] = [];
  (mine ?? []).forEach((x, i) => {
    if (!ceiling.includes(x)) out.push({ path: `${path}.${i}`, message: message(x) });
  });
  return out;
}

function imageIssues(
  image: string | undefined,
  ceilingImage: string | undefined,
  allowedImages: readonly string[] | null | undefined,
): PolicyIssue[] {
  if (!image) return [];
  let patterns: readonly string[] = [];
  if (allowedImages?.length) patterns = allowedImages;
  else if (ceilingImage) patterns = [ceilingImage];
  if (!patterns.length || patterns.some((pat) => globMatches(pat, image))) return [];
  const list = patterns.join($localize`:@@fleet.policy.issue.or: or `);
  return [
    {
      path: 'environment.image',
      message: $localize`:@@fleet.policy.issue.image:The image must match ${list}:patterns:.`,
    },
  ];
}

function selectorIssues(
  mine: Record<string, string> | undefined,
  ceiling: Record<string, string> | undefined,
): PolicyIssue[] {
  const out: PolicyIssue[] = [];
  for (const [k, v] of Object.entries(ceiling ?? {})) {
    const own = mine?.[k];
    if (own !== undefined && own !== v)
      out.push({
        path: `poolSelector.${k}`,
        message: $localize`:@@fleet.policy.issue.selector:The ceiling pins ${k}:key:=${v}:value:.`,
      });
  }
  return out;
}

function atMost(max: string): string {
  return $localize`:@@fleet.policy.issue.atMost:At most ${max}:max: (ceiling).`;
}

function checkNumber(
  out: PolicyIssue[],
  path: string,
  value: number | null | undefined,
  max: number | null | undefined,
  message: (max: number) => string,
): void {
  if (value === null || value === undefined) return;
  if (!(value > 0)) {
    out.push({ path, message: $localize`:@@fleet.policy.issue.positive:Enter a positive number.` });
    return;
  }
  if (max !== null && max !== undefined && value > max) out.push({ path, message: message(max) });
}

export function isolationName(i: SandboxIsolation | null | undefined): string {
  const o = ISOLATION_OPTIONS.find((x) => x.value === i);
  return o ? `${o.label} (${o.runtime})` : $localize`:@@fleet.isolation.inherited:inherited`;
}

/** "$5 · 2,000,000 tokens" / "no cost cap · no token cap" for a policy's caps. */
export function policyCaps(p: SandboxPolicy): string {
  const cost = p.maxCostUsd
    ? '$' + formatNumber(p.maxCostUsd)
    : $localize`:@@fleet.policy.noCostCap:no cost cap`;
  let tokens: string;
  if (p.maxTokens) {
    const n = formatNumber(p.maxTokens);
    tokens = $localize`:@@fleet.policy.tokenCap:${n}:tokens: tokens`;
  } else tokens = $localize`:@@fleet.policy.noTokenCap:no token cap`;
  return cost + ' · ' + tokens;
}

/** 3600 -> "1h", 5400 -> "1h 30m", 90 -> "1m 30s". */
export function formatSeconds(s: number | null | undefined): string {
  if (s === null || s === undefined) return '-';
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const parts: string[] = [];
  if (h) parts.push($localize`:@@fleet.seconds.hours:${h}:hours:h`);
  if (m) parts.push($localize`:@@fleet.seconds.minutes:${m}:minutes:m`);
  if (sec || !parts.length) parts.push($localize`:@@fleet.seconds.seconds:${sec}:seconds:s`);
  return parts.join(' ');
}

/** Issues grouped by their top-level section (`resources.cpu` -> `resources`). */
export function issuesBySection(issues: readonly PolicyIssue[]): Record<string, PolicyIssue[]> {
  const out: Record<string, PolicyIssue[]> = {};
  for (const i of issues) (out[i.path.split('.')[0]] ??= []).push(i);
  return out;
}

/**
 * Removes empty values so an untouched editor sends `{}` (inherit everything) rather than empty
 * arrays that would mean "nothing allowed".
 */
export function compactPolicy(p: SandboxPolicy): SandboxPolicy {
  const out: SandboxPolicy = {};
  if (p.isolation) out.isolation = p.isolation;
  const r = compactResources(p.resources ?? {});
  if (Object.keys(r).length) out.resources = r;
  if (p.timeoutSeconds) out.timeoutSeconds = p.timeoutSeconds;
  if (p.network?.allow?.length) out.network = { allow: uniq(p.network.allow) };
  const tools = compactTools(p.tools ?? {});
  if (Object.keys(tools).length) out.tools = tools;
  if (p.secrets?.length) out.secrets = uniq(p.secrets);
  const env = compactEnvironment(p.environment ?? {});
  if (Object.keys(env).length) out.environment = env;
  if (p.maxCostUsd !== null && p.maxCostUsd !== undefined) out.maxCostUsd = p.maxCostUsd;
  if (p.maxTokens !== null && p.maxTokens !== undefined) out.maxTokens = p.maxTokens;
  const sel = Object.entries(p.poolSelector ?? {}).filter(([k, v]) => k.trim() && `${v}`.trim());
  if (sel.length) out.poolSelector = Object.fromEntries(sel);
  return out;
}

function compactResources(resources: SandboxResources): SandboxResources {
  const r: SandboxResources = {};
  for (const k of ['cpu', 'memory', 'ephemeralStorage'] as const) {
    const v = resources[k]?.trim();
    if (v) r[k] = v;
  }
  if (resources.pids) r.pids = resources.pids;
  return r;
}

function compactTools(tools: SandboxTools): SandboxTools {
  return {
    ...(tools.allow?.length ? { allow: uniq(tools.allow) } : {}),
    ...(tools.deny?.length ? { deny: uniq(tools.deny) } : {}),
    ...(tools.ask?.length ? { ask: uniq(tools.ask) } : {}),
  };
}

function compactEnvironment(env: SandboxEnvironment): SandboxEnvironment {
  const image = env.image?.trim();
  const setupCommand = env.setupCommand?.trim();
  return {
    ...(image ? { image } : {}),
    ...(setupCommand ? { setupCommand } : {}),
    ...(env.caches?.length ? { caches: [...new Set(env.caches)] } : {}),
  };
}

/** "key=value, key2=value2" <-> labels object (pool labels and pool selectors). */
export function parseLabels(text: string): {
  labels: Record<string, string>;
  error: string | null;
} {
  const labels: Record<string, string> = {};
  for (const part of text
    .split(/[,\n]/)
    .map((s) => s.trim())
    .filter(Boolean)) {
    const m = /^([a-z0-9]([a-z0-9._/-]*[a-z0-9])?)\s*=\s*([A-Za-z0-9._-]*)$/i.exec(part);
    if (!m)
      return { labels, error: $localize`:@@fleet.labels.error:"${part}:part:" is not key=value.` };
    labels[m[1]] = m[3];
  }
  return { labels, error: null };
}

export function formatLabels(labels: Record<string, string> | null | undefined): string {
  return Object.entries(labels ?? {})
    .map(([k, v]) => `${k}=${v}`)
    .join(', ');
}

/** Human name of a sources badge (`SandboxPolicyView.sources`). */
export const SOURCE_LABELS: Record<string, string> = {
  PLATFORM: $localize`:@@fleet.source.platform:platform`,
  POOL: $localize`:@@fleet.source.pool:pool`,
  PROJECT: $localize`:@@fleet.source.project:project`,
  TEMPLATE: $localize`:@@fleet.source.template:template`,
  NODE: $localize`:@@fleet.source.node:node`,
};

/** The level that set `path` or its closest parent (`network.allow.1` -> `network.allow`). */
export function sourceOf(
  sources: Record<string, string> | null | undefined,
  path: string,
): string | null {
  if (!sources) return null;
  const parts = path.split('.');
  while (parts.length) {
    const hit = sources[parts.join('.')];
    if (hit) return hit;
    parts.pop();
  }
  return null;
}
