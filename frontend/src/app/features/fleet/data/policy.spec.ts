import type { SandboxPolicy } from '../../../core/api/types';
import {
  allowedIsolations,
  compactPolicy,
  coveringPattern,
  formatSeconds,
  hostMatches,
  hostPatternError,
  narrow,
  narrowingIssues,
  parseLabels,
  parseQuantity,
  patternWithin,
  sourceOf,
  strongerIsolation,
  toolAllowedBy,
} from './policy';

const ceiling: SandboxPolicy = {
  isolation: 'STANDARD',
  resources: { cpu: '4', memory: '8Gi', ephemeralStorage: '20Gi', pids: 4096 },
  timeoutSeconds: 7200,
  network: { allow: ['registry.npmjs.org', '*.gradle.org', 'repo.maven.apache.org:443'] },
  tools: {
    allow: ['git *', 'npm *', './gradlew *'],
    deny: ['curl *', 'sudo *'],
    ask: ['rm -rf *'],
  },
  secrets: ['npm-read-token', 'gradle-cache-key'],
  environment: { image: 'ghcr.io/org/back-office-runner:*', caches: ['gradle', 'npm'] },
  maxCostUsd: 5,
  maxTokens: 2_000_000,
  poolSelector: { region: 'eu' },
};

describe('sandbox policy narrowing', () => {
  describe('isolation: the stronger wins', () => {
    it('orders DEV < STANDARD < HIGH', () => {
      expect(strongerIsolation('STANDARD', 'HIGH')).toBe('HIGH');
      expect(strongerIsolation('HIGH', 'DEV')).toBe('HIGH');
      expect(strongerIsolation(undefined, 'DEV')).toBe('DEV');
      expect(strongerIsolation('STANDARD', null)).toBe('STANDARD');
    });

    it('offers only the ceiling or stronger', () => {
      expect(allowedIsolations('STANDARD')).toEqual(['STANDARD', 'HIGH']);
      expect(allowedIsolations('HIGH')).toEqual(['HIGH']);
      expect(allowedIsolations(undefined)).toEqual(['DEV', 'STANDARD', 'HIGH']);
    });

    it('reports a weaker isolation', () => {
      const issues = narrowingIssues({ isolation: 'DEV' }, ceiling);
      expect(issues).toEqual([
        {
          path: 'isolation',
          message: 'The ceiling requires Standard (gVisor) isolation or stronger.',
        },
      ]);
      expect(narrowingIssues({ isolation: 'HIGH' }, ceiling)).toEqual([]);
    });
  });

  describe('resources: the smaller wins', () => {
    it('parses quantities', () => {
      expect(parseQuantity('2')).toBe(2);
      expect(parseQuantity('500m')).toBe(0.5);
      expect(parseQuantity('4Gi')).toBe(4 * 2 ** 30);
      expect(parseQuantity('512Mi')).toBe(512 * 2 ** 20);
      expect(parseQuantity('1G')).toBe(1e9);
      expect(parseQuantity('lots')).toBeNull();
    });

    it('rejects values above the ceiling and malformed ones', () => {
      const issues = narrowingIssues(
        {
          resources: { cpu: '6', memory: '4096Mi', ephemeralStorage: 'big', pids: 9000 },
          timeoutSeconds: 10_000,
        },
        ceiling,
      );
      expect(issues.map((i) => i.path)).toEqual([
        'resources.cpu',
        'resources.ephemeralStorage',
        'resources.pids',
        'timeoutSeconds',
      ]);
      expect(issues[0].message).toBe('At most 4 (ceiling).');
      expect(issues[3].message).toBe('At most 2h (ceiling).');
    });

    it('accepts smaller values, including different units', () => {
      expect(
        narrowingIssues(
          { resources: { cpu: '1500m', memory: '6Gi' }, timeoutSeconds: 600 },
          ceiling,
        ),
      ).toEqual([]);
    });

    it('takes the minimum when composing', () => {
      const eff = narrow(ceiling, { resources: { cpu: '8', memory: '2Gi' }, timeoutSeconds: 60 });
      expect(eff.resources).toEqual({
        cpu: '4',
        memory: '2Gi',
        ephemeralStorage: '20Gi',
        pids: 4096,
      });
      expect(eff.timeoutSeconds).toBe(60);
    });

    it('caps cost and tokens', () => {
      const issues = narrowingIssues({ maxCostUsd: 10, maxTokens: 3_000_000 }, ceiling);
      expect(issues.map((i) => i.message)).toEqual([
        'At most $5 (ceiling).',
        'At most 2,000,000 tokens (ceiling).',
      ]);
      expect(narrow(ceiling, { maxCostUsd: 1 }).maxCostUsd).toBe(1);
      expect(narrow(ceiling, { maxCostUsd: 9 }).maxCostUsd).toBe(5);
    });
  });

  describe('network: every pattern inside a ceiling pattern', () => {
    it('validates host patterns', () => {
      expect(hostPatternError('registry.npmjs.org')).toBeNull();
      expect(hostPatternError('*.gradle.org')).toBeNull();
      expect(hostPatternError('repo.example.com:8443')).toBeNull();
      expect(hostPatternError('https://registry.npmjs.org')).toMatch(/without https/);
      expect(hostPatternError('example.com/path')).toMatch(/without a path/);
      expect(hostPatternError('localhost')).toMatch(/full host name/);
      expect(hostPatternError('*.com')).toMatch(/two fixed labels/);
      expect(hostPatternError('example.*')).toMatch(/top-level/);
      expect(hostPatternError('ex_ample.com')).toMatch(/Letters, digits/);
      expect(hostPatternError('example.com:0')).toMatch(/port/);
      expect(hostPatternError('')).toMatch(/Enter/);
    });

    it('matches one label per wildcard and the port', () => {
      expect(hostMatches('*.gradle.org', 'plugins.gradle.org')).toBe(true);
      expect(hostMatches('*.gradle.org', 'a.b.gradle.org')).toBe(false);
      expect(hostMatches('*.gradle.org', 'gradle.org')).toBe(false);
      expect(hostMatches('repo.maven.apache.org:443', 'repo.maven.apache.org')).toBe(true);
      expect(hostMatches('repo.maven.apache.org:443', 'repo.maven.apache.org:80')).toBe(false);
    });

    it('a pattern is within a ceiling pattern only when narrower or equal', () => {
      expect(patternWithin('plugins.gradle.org', '*.gradle.org')).toBe(true);
      expect(patternWithin('*.gradle.org', '*.gradle.org')).toBe(true);
      expect(patternWithin('*.gradle.org', 'plugins.gradle.org')).toBe(false);
      expect(coveringPattern('services.gradle.org', ceiling.network!.allow)).toBe('*.gradle.org');
      expect(coveringPattern('evil.example.com', ceiling.network!.allow)).toBeNull();
      expect(coveringPattern('anything.example.com', [])).toBe('*');
    });

    it('reports hosts outside the ceiling and drops them when composing', () => {
      const p: SandboxPolicy = {
        network: { allow: ['registry.npmjs.org', 'pypi.org', 'plugins.gradle.org', 'bad host'] },
      };
      expect(narrowingIssues(p, ceiling).map((i) => i.path)).toEqual([
        'network.allow.1',
        'network.allow.3',
      ]);
      expect(narrow(ceiling, p).network?.allow).toEqual([
        'registry.npmjs.org',
        'plugins.gradle.org',
      ]);
      expect(narrow(ceiling, {}).network?.allow).toEqual(ceiling.network!.allow);
    });
  });

  describe('tools: deny union, allow intersection', () => {
    it('allows only what the ceiling allows', () => {
      expect(toolAllowedBy('npm ci', ['npm *'])).toBe(true);
      expect(toolAllowedBy('git *', ['git *'])).toBe(true);
      expect(toolAllowedBy('docker build', ['npm *'])).toBe(false);
      expect(toolAllowedBy('anything', [])).toBe(true);
    });

    it('reports allow entries that the ceiling does not allow or denies', () => {
      const issues = narrowingIssues(
        { tools: { allow: ['git *', 'docker *', 'curl *'] } },
        { tools: { allow: ['git *', 'docker *', 'curl *'], deny: ['curl *'] } },
      );
      expect(issues).toEqual([
        { path: 'tools.allow.2', message: 'curl * is denied by the ceiling (curl *).' },
      ]);
      expect(narrowingIssues({ tools: { allow: ['docker *'] } }, ceiling)[0].message).toBe(
        'docker * is not allowed by the ceiling.',
      );
    });

    it('composes deny and ask as unions and allow as the intersection', () => {
      const eff = narrow(ceiling, {
        tools: { allow: ['git *', 'docker *'], deny: ['npm publish *'], ask: ['git push *'] },
      });
      expect(eff.tools).toEqual({
        allow: ['git *'],
        deny: ['curl *', 'sudo *', 'npm publish *'],
        ask: ['rm -rf *', 'git push *'],
      });
    });
  });

  describe('secrets, image, caches and pool selector', () => {
    it('keeps secrets and caches within the ceiling', () => {
      const issues = narrowingIssues(
        { secrets: ['npm-read-token', 'prod-db'], environment: { caches: ['npm', 'cargo'] } },
        ceiling,
      );
      expect(issues.map((i) => i.path)).toEqual(['secrets.1', 'environment.caches.1']);
      expect(narrow(ceiling, { secrets: ['prod-db', 'npm-read-token'] }).secrets).toEqual([
        'npm-read-token',
      ]);
    });

    it('checks the image against the pool patterns', () => {
      const ok = { environment: { image: 'ghcr.io/org/back-office-runner:2.0-jdk21' } };
      const bad = { environment: { image: 'docker.io/library/ubuntu:latest' } };
      expect(narrowingIssues(ok, ceiling)).toEqual([]);
      expect(narrowingIssues(bad, ceiling)[0].path).toBe('environment.image');
      expect(narrowingIssues(bad, {}, { allowedImages: ['docker.io/library/*'] })).toEqual([]);
    });

    it('keeps the ceiling’s pool labels', () => {
      expect(narrowingIssues({ poolSelector: { region: 'us' } }, ceiling)[0].message).toBe(
        'The ceiling pins region=eu.',
      );
      expect(narrow(ceiling, { poolSelector: { region: 'us', gpu: 'no' } }).poolSelector).toEqual({
        region: 'eu',
        gpu: 'no',
      });
    });
  });

  it('an empty policy inherits the ceiling without issues', () => {
    expect(narrowingIssues({}, ceiling)).toEqual([]);
    expect(narrow(ceiling, {}).isolation).toBe('STANDARD');
    expect(narrowingIssues(null, null)).toEqual([]);
  });

  it('compacts empty values away', () => {
    expect(
      compactPolicy({
        resources: { cpu: ' ', memory: '2Gi' },
        network: { allow: [] },
        tools: { allow: [], deny: ['curl *', 'curl *'] },
        environment: { image: '', setupCommand: '  ', caches: [] },
        poolSelector: { '': 'x' },
        maxCostUsd: null,
      }),
    ).toEqual({ resources: { memory: '2Gi' }, tools: { deny: ['curl *'] } });
  });

  it('parses labels and finds the source of a field', () => {
    expect(parseLabels('region=eu, gpu=no')).toEqual({
      labels: { region: 'eu', gpu: 'no' },
      error: null,
    });
    expect(parseLabels('region').error).toMatch(/key=value/);
    expect(sourceOf({ 'network.allow': 'POOL', isolation: 'PROJECT' }, 'network.allow.1')).toBe(
      'POOL',
    );
    expect(sourceOf({ isolation: 'PROJECT' }, 'resources.cpu')).toBeNull();
  });

  it('formats seconds', () => {
    expect(formatSeconds(3600)).toBe('1h');
    expect(formatSeconds(5400)).toBe('1h 30m');
    expect(formatSeconds(90)).toBe('1m 30s');
    expect(formatSeconds(0)).toBe('0s');
  });
});
