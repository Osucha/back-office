import manifestJson from '../../../../../docs/manual/manual.json';
import manifestPlJson from '../../../../../docs/manual/pl/manual.json';
import { HELP_TOPICS, helpTopicForUrl, type HelpKey } from './help-map';
import {
  allChapters,
  humanizeAnchor,
  manualLink,
  neighbours,
  refExists,
  type ManualManifest,
} from './manual';

const manifest = manifestJson as ManualManifest;
const manifestPl = manifestPlJson as ManualManifest;

describe('help map', () => {
  it('points every topic at a chapter and anchor that exist in docs/manual/manual.json', () => {
    const broken = Object.entries(HELP_TOPICS)
      .filter(([, t]) => !refExists(manifest, t))
      .map(([k, t]) => `${k} -> ${t.chapter}#${t.anchor}`);
    expect(broken).toEqual([]);
  });

  it('points every topic at an anchor of the Polish manual where it lists the chapter', () => {
    // docs/manual/pl mirrors the English slugs and anchors; the help service falls back to the
    // English file while a chapter is not translated (scripts/tests checks the files' anchors).
    const listed = new Set(allChapters(manifestPl).map((c) => c.slug));
    const broken = Object.entries(HELP_TOPICS)
      .filter(([, t]) => listed.has(t.chapter) && !refExists(manifestPl, t))
      .map(([k, t]) => `${k} -> pl/${t.chapter}#${t.anchor}`);
    expect(broken).toEqual([]);
  });

  it('finds the topic of every main screen from its URL', () => {
    const cases: Array<[string, HelpKey | null]> = [
      ['/', 'home'],
      ['/home', 'home'],
      ['/inbox', 'inbox'],
      ['/inbox/a1', 'inbox'],
      ['/projects', 'projects'],
      ['/projects?create=1', 'projects'],
      ['/projects/p1', 'project.workflows'],
      ['/projects/p1?tab=workflows', 'project.workflows'],
      ['/projects/p1?tab=runs', 'project.runs'],
      ['/projects/p1?tab=ci', 'project.ci'],
      ['/projects/p1?tab=members', 'project.members'],
      ['/projects/p1?tab=connections', 'project.connections'],
      ['/projects/p1?tab=sandbox', 'project.sandbox'],
      ['/projects/p1?tab=budget', 'project.budget'],
      ['/projects/p1?tab=settings', 'project.settings'],
      ['/projects/p1/workflows/new', 'designer'],
      ['/workflows/w1/edit', 'designer'],
      ['/runs', 'runs'],
      ['/runs?holder=agent', 'runs'],
      ['/runs/r1', 'run'],
      ['/insights', 'insights'],
      ['/costs', 'costs'],
      ['/ci', 'ci'],
      ['/ci/e1', 'ci'],
      ['/library/templates', 'templates'],
      ['/library/interfaces', 'interfaces'],
      ['/library/skills', 'skills'],
      ['/my-work', 'myWork'],
      ['/library/workflows', 'libraryWorkflows'],
      ['/library/workflows/l1', 'libraryMetrics'],
      ['/library/workflows/l1?tab=versions', 'libraryWorkflows'],
      ['/models', 'models'],
      ['/evaluation/suites', 'suites'],
      ['/evaluation/suites/s1', 'suites'],
      ['/evaluation/benchmarks', 'benchmarks'],
      ['/evaluation/benchmarks/b1', 'benchmarks'],
      ['/settings', 'settings'],
      ['/account', 'account'],
      ['/account?section=tokens', 'account.tokens'],
      ['/account?section=ide', 'account.ide'],
      ['/account?section=notifications', 'account.notifications'],
      ['/account?section=security&setup=mfa', 'account.mfa'],
      ['/admin/users', 'admin.users'],
      ['/admin/users/u1', 'admin.users'],
      ['/admin/groups/new', 'admin.groups'],
      ['/admin/roles/owner', 'admin.roles'],
      ['/admin/grants', 'admin.grants'],
      ['/admin/settings?section=budget', 'admin.settings'],
      ['/admin/audit', 'admin.audit'],
      ['/admin/connections', 'admin.connections'],
      ['/admin/operations', 'admin.operations'],
      ['/admin/fleet/pools', 'fleet.pools'],
      ['/admin/fleet/pools?addRunner=eu', 'fleet.addRunner'],
      ['/admin/fleet/pools/eu', 'fleet.poolEditor'],
      ['/admin/fleet/pools/new', 'fleet.poolEditor'],
      ['/admin/fleet/runners', 'fleet.runners'],
      ['/admin/fleet/jobs?job=j1', 'fleet.jobs'],
      ['/runs/r1#x', 'run'],
      ['/help', null],
      ['/help/projects', null],
      ['/forbidden', null],
    ];
    for (const [url, key] of cases) expect([url, helpTopicForUrl(url)]).toEqual([url, key]);
  });

  it('links the multi-repository topics (ADR 0034) to their manual sections', () => {
    expect(manualLink(HELP_TOPICS['project.repositories'])).toBe('/help/projects#repositories');
    expect(manualLink(HELP_TOPICS['project.noRepository'])).toBe('/help/projects#no-repository');
    expect(manualLink(HELP_TOPICS['step.repositories'])).toBe('/help/workflows#step-repositories');
    expect(manualLink(HELP_TOPICS['run.repositories'])).toBe('/help/runs#repository-results');
  });

  it('builds links, humanises anchors and pages through the chapters', () => {
    expect(manualLink({ chapter: 'runs', anchor: 'provenance' })).toBe('/help/runs#provenance');
    expect(manualLink({ chapter: 'glossary' })).toBe('/help/glossary');
    expect(humanizeAnchor('ci-variables')).toBe('CI variables');
    expect(humanizeAnchor('jira-token')).toBe('Jira token');
    expect(humanizeAnchor('what-is')).toBe('What is');
    expect(humanizeAnchor('openai-compatible')).toBe('OpenAI compatible');
    const chapters = allChapters(manifest);
    expect(chapters.length).toBeGreaterThan(10);
    expect(neighbours(manifest, chapters[0].slug).prev).toBeNull();
    expect(neighbours(manifest, chapters[1].slug).prev?.slug).toBe(chapters[0].slug);
    expect(neighbours(manifest, chapters.at(-1)!.slug).next).toBeNull();
    expect(neighbours(manifest, 'nope')).toEqual({ prev: null, next: null });
  });
});
