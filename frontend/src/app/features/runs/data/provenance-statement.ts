/**
 * Provenance attestations (docs/architecture/11-governance.md): decode the DSSE payload (base64
 * in-toto Statement v1) and summarise the predicate's key facts for people. Tolerant of missing or
 * differently nested fields: the predicate is versioned by its type URI, not by the OpenAPI schema.
 */
import type { DsseEnvelope } from '../../../core/api/types';
import { formatCost } from '../../../core/ui/relay/format';

export type Statement = Record<string, unknown>;

/** base64 (or base64url) -> UTF-8 text. */
export function decodeBase64(b64: string): string {
  const norm = b64.replace(/-/g, '+').replace(/_/g, '/').replace(/\s+/g, '');
  const padded = norm + '='.repeat((4 - (norm.length % 4)) % 4);
  const bin = atob(padded);
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/** The in-toto statement of an envelope, or null when the payload is not JSON. */
export function decodeStatement(env: DsseEnvelope | null | undefined): Statement | null {
  if (!env?.payload) return null;
  try {
    const v = JSON.parse(decodeBase64(env.payload));
    return v && typeof v === 'object' ? (v as Statement) : null;
  } catch {
    return null;
  }
}

const get = (o: unknown, path: string): unknown =>
  path
    .split('.')
    .reduce<unknown>(
      (acc, k) =>
        acc && typeof acc === 'object' ? (acc as Record<string, unknown>)[k] : undefined,
      o,
    );

/** First defined value among several candidate paths. */
function pick(o: unknown, ...paths: string[]): unknown {
  for (const p of paths) {
    const v = get(o, p);
    if (v !== undefined && v !== null && v !== '') return v;
  }
  return undefined;
}

const str = (v: unknown): string | null =>
  v === undefined || v === null ? null : typeof v === 'object' ? JSON.stringify(v) : String(v);

export interface Fact {
  label: string;
  value: string;
  mono?: boolean;
}

export interface StatementSummary {
  predicateType: string | null;
  facts: Fact[];
  allowedHosts: string[];
  approvers: { who: string; option: string | null; at: string | null }[];
  subjects: { name: string; digest: string }[];
}

/** The key facts: model, template, isolation, allowed hosts, tokens/cost, verification, approvers. */
export function summarizeStatement(st: Statement | null): StatementSummary {
  const p = (st?.['predicate'] ?? {}) as Record<string, unknown>;
  const facts: Fact[] = [];
  const push = (label: string, value: unknown, mono = true) => {
    const s = str(value);
    if (s) facts.push({ label, value: s, mono });
  };

  push($localize`:@@runs.provenance.fact.model:Model`, modelFact(p));
  const tKey = pick(p, 'template.key', 'templateKey', 'task.templateKey');
  const tVer = pick(p, 'template.version', 'templateVersion', 'task.templateVersion');
  if (tKey) push($localize`:@@runs.provenance.fact.template:Template`, versioned(tKey, tVer));
  const lineage = pick(p, 'template.lineage', 'lineage');
  if (Array.isArray(lineage) && lineage.length)
    push($localize`:@@runs.provenance.fact.lineage:Lineage`, lineage.join(' → '));
  const wf = pick(p, 'workflow.name', 'workflow.id', 'workflowId');
  const wfv = pick(p, 'workflow.version', 'workflowVersion');
  if (wf) push($localize`:@@runs.provenance.fact.workflow:Workflow`, versioned(wf, wfv), false);
  push($localize`:@@runs.provenance.fact.node:Node`, pick(p, 'nodeId', 'node.id'));
  push(
    $localize`:@@runs.provenance.fact.isolation:Isolation`,
    pick(p, 'sandbox.isolation', 'isolation', 'sandboxPolicy.isolation'),
    false,
  );
  push(
    $localize`:@@runs.provenance.fact.image:Image`,
    pick(p, 'sandbox.imageDigest', 'imageDigest', 'image.digest'),
  );
  push(
    $localize`:@@runs.provenance.fact.policyDigest:Policy digest`,
    pick(p, 'sandbox.policyDigest', 'policyDigest', 'sandboxPolicyDigest'),
  );
  push($localize`:@@runs.provenance.fact.tokens:Tokens`, tokensFact(p));
  push($localize`:@@runs.provenance.fact.cost:Cost`, costFact(p));
  push($localize`:@@runs.provenance.fact.verification:Verification`, verificationFact(p));
  push(
    $localize`:@@runs.provenance.fact.egressLog:Egress log`,
    pick(p, 'egress.logDigest', 'egressLogDigest'),
  );
  push($localize`:@@runs.provenance.fact.time:Time`, timeFact(p));

  const hosts = pick(
    p,
    'egress.allowedHosts',
    'allowedHosts',
    'sandbox.allowedHosts',
    'network.allow',
  );
  return {
    predicateType: str(st?.['predicateType']),
    facts,
    allowedHosts: Array.isArray(hosts) ? hosts.map((h) => String(h)) : [],
    approvers: approversOf(pick(p, 'gateDecisions', 'gates', 'approvals')),
    subjects: subjectsOf(st?.['subject']),
  };
}

// Fact values; undefined = the fact is not shown.

/** "claude-sonnet · anthropic · effort high". */
function modelFact(p: unknown): string | undefined {
  const model = pick(p, 'model.model', 'model.ref', 'modelRef.model', 'model');
  if (!model) return undefined;
  const provider = pick(p, 'model.provider', 'modelRef.provider');
  const effort = pick(p, 'model.effort', 'model.reasoning', 'modelRef.reasoning', 'effort');
  return [
    str(model),
    provider ? str(provider)!.toLowerCase() : null,
    effort
      ? $localize`:@@runs.provenance.fact.effort:effort ${str(effort)!.toLowerCase()}:effort:`
      : null,
  ]
    .filter(Boolean)
    .join(' · ');
}

/** "key v3" (the version only when present). */
function versioned(name: unknown, version: unknown): string {
  return `${str(name)}${version !== undefined ? ' v' + str(version) : ''}`;
}

function tokensFact(p: unknown): string | undefined {
  const inTok = pick(p, 'usage.inputTokens', 'tokens.input', 'tokens.inputTokens');
  const outTok = pick(p, 'usage.outputTokens', 'tokens.output', 'tokens.outputTokens');
  if (inTok === undefined && outTok === undefined) return undefined;
  return $localize`:@@runs.provenance.fact.tokensValue:in ${str(inTok) ?? '0'}:input: · out ${str(outTok) ?? '0'}:output:`;
}

/** Formatted when numeric, as recorded otherwise. */
function costFact(p: unknown): unknown {
  const cost = pick(p, 'cost.amount', 'costUsd', 'cost');
  if (cost === undefined) return undefined;
  const n = Number(cost);
  return Number.isFinite(n) ? formatCost(n) : cost;
}

/** "./gradlew check → exit 0 (passed)". */
function verificationFact(p: unknown): string | undefined {
  const cmd = pick(p, 'verification.command', 'verifyCommand');
  const code = pick(p, 'verification.exitCode', 'verifyExitCode');
  if (cmd === undefined && code === undefined) return undefined;
  const command = str(cmd) ?? $localize`:@@runs.provenance.fact.command:command`;
  if (code === undefined) return command;
  const exit =
    Number(code) === 0
      ? $localize`:@@runs.provenance.fact.exitPassed:exit ${str(code)}:code: (passed)`
      : $localize`:@@runs.provenance.fact.exitFailed:exit ${str(code)}:code: (failed)`;
  return `${command} → ${exit}`;
}

function timeFact(p: unknown): string | undefined {
  const started = pick(p, 'timestamps.startedAt', 'startedAt');
  const finished = pick(p, 'timestamps.finishedAt', 'finishedAt');
  if (!started && !finished) return undefined;
  return `${str(started) ?? '?'} → ${str(finished) ?? '?'}`;
}

function approversOf(decisions: unknown): StatementSummary['approvers'] {
  if (!Array.isArray(decisions)) return [];
  return decisions.map((d) => ({
    who:
      str(pick(d, 'decidedBy.displayName', 'decidedBy.email', 'who', 'by', 'approver')) ??
      $localize`:@@runs.provenance.someone:someone`,
    option: str(pick(d, 'option', 'decision')),
    at: str(pick(d, 'at', 'decidedAt', 'when')),
  }));
}

function subjectsOf(subject: unknown): StatementSummary['subjects'] {
  if (!Array.isArray(subject)) return [];
  return (subject as { name?: string; digest?: Record<string, string> }[]).map((s) => ({
    name: s.name ?? '-',
    digest: Object.entries(s.digest ?? {})
      .map(([k, v]) => `${k}:${v}`)
      .join(' '),
  }));
}

/** `.intoto.jsonl` line: the envelope as one JSON line (DSSE bundles are line-delimited). */
export function toJsonl(env: DsseEnvelope): string {
  return JSON.stringify(env) + '\n';
}

export function attestationFileName(runId: string, attestationId: string): string {
  return `run-${runId.slice(0, 8)}-${attestationId.slice(0, 8)}.intoto.jsonl`;
}

/** Short subject name: `git+https://gitlab/x/y@abc123…` -> `x/y @ abc12345`. */
export function shortSubject(name: string | null | undefined): string {
  if (!name) return '-';
  const m = /^git\+(.+?)@([0-9a-f]{7,})$/.exec(name);
  if (!m) return name;
  const repo = m[1].replace(/^https?:\/\/[^/]+\//, '').replace(/\.git$/, '');
  return `${repo} @ ${m[2].slice(0, 8)}`;
}
