import type { DsseEnvelope } from '../../../core/api/types';
import {
  attestationFileName,
  decodeBase64,
  decodeStatement,
  shortSubject,
  summarizeStatement,
  toJsonl,
} from './provenance-statement';

const statement = {
  _type: 'https://in-toto.io/Statement/v1',
  subject: [
    { name: 'git+https://gitlab.example.com/shop/web@9f2c1ab4de', digest: { sha256: 'aa11' } },
    { name: 'diff', digest: { sha256: 'bb22' } },
  ],
  predicateType: 'https://back-office.dev/attestations/agent-run/v1',
  predicate: {
    runId: 'r1',
    nodeId: 'implement',
    workflow: { id: 'w1', name: 'Issue to MR', version: 3 },
    template: {
      key: 'implement-opencode',
      version: 4,
      lineage: ['base-agent', 'implement-opencode'],
    },
    model: { provider: 'ANTHROPIC', model: 'claude-opus-5', effort: 'HIGH' },
    sandbox: { isolation: 'STANDARD', policyDigest: 'sha256:pp', imageDigest: 'sha256:ii' },
    egress: { allowedHosts: ['registry.npmjs.org', '*.gradle.org'], logDigest: 'sha256:ee' },
    usage: { inputTokens: 120000, outputTokens: 8000 },
    cost: { amount: 0.31, currency: 'USD' },
    verification: { command: './gradlew test', exitCode: 0 },
    gateDecisions: [
      { decidedBy: { displayName: 'Anna' }, option: 'APPROVE', at: '2026-09-27T10:00:00Z' },
    ],
  },
};
const b64 = (o: unknown) => btoa(unescape(encodeURIComponent(JSON.stringify(o))));
const env: DsseEnvelope = {
  payloadType: 'application/vnd.in-toto+json',
  payload: b64(statement),
  signatures: [{ keyid: 'k1', sig: 'c2ln' }],
};

describe('provenance statement', () => {
  it('decodes base64 and base64url payloads, including UTF-8', () => {
    expect(decodeBase64(btoa('hello'))).toBe('hello');
    const utf = b64({ who: 'Zoë' }).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    expect(JSON.parse(decodeBase64(utf))).toEqual({ who: 'Zoë' });
    expect(decodeStatement(env)?.['predicateType']).toBe(statement.predicateType);
    expect(decodeStatement({ ...env, payload: btoa('not json') })).toBeNull();
    expect(decodeStatement(null)).toBeNull();
  });

  it('summarises the key facts', () => {
    const s = summarizeStatement(decodeStatement(env));
    const facts = Object.fromEntries(s.facts.map((f) => [f.label, f.value]));
    expect(facts).toMatchObject({
      Model: 'claude-opus-5 · anthropic · effort high',
      Template: 'implement-opencode v4',
      Lineage: 'base-agent → implement-opencode',
      Workflow: 'Issue to MR v3',
      Isolation: 'STANDARD',
      Tokens: 'in 120000 · out 8000',
      Cost: '$0.31',
      Verification: './gradlew test → exit 0 (passed)',
    });
    expect(s.allowedHosts).toEqual(['registry.npmjs.org', '*.gradle.org']);
    expect(s.approvers).toEqual([{ who: 'Anna', option: 'APPROVE', at: '2026-09-27T10:00:00Z' }]);
    expect(s.subjects[0]).toEqual({
      name: 'git+https://gitlab.example.com/shop/web@9f2c1ab4de',
      digest: 'sha256:aa11',
    });
  });

  it('tolerates flat or missing predicates', () => {
    const s = summarizeStatement({
      predicate: { model: 'gpt-5', verifyExitCode: 2, costUsd: 1.5 },
    });
    const facts = Object.fromEntries(s.facts.map((f) => [f.label, f.value]));
    expect(facts['Model']).toBe('gpt-5');
    expect(facts['Verification']).toBe('command → exit 2 (failed)');
    expect(facts['Cost']).toBe('$1.50');
    expect(summarizeStatement(null)).toEqual({
      predicateType: null,
      facts: [],
      allowedHosts: [],
      approvers: [],
      subjects: [],
    });
  });

  it('writes the download', () => {
    expect(toJsonl(env).endsWith('\n')).toBe(true);
    expect(JSON.parse(toJsonl(env))).toEqual(env);
    expect(attestationFileName('1f32aa00-x', 'a7b8c9d0-y')).toBe(
      'run-1f32aa00-a7b8c9d0.intoto.jsonl',
    );
    expect(shortSubject('git+https://gitlab.example.com/shop/web.git@9f2c1ab4de')).toBe(
      'shop/web @ 9f2c1ab4',
    );
    expect(shortSubject('diff')).toBe('diff');
  });
});
