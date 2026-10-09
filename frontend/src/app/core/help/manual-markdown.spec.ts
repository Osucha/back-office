import {
  buildSearchIndex,
  chapterIntro,
  extractSection,
  highlightParts,
  isMarkdownResponse,
  plainText,
  rewriteManualLinks,
  searchManual,
  snippet,
} from './manual-markdown';

const CHAPTER = [
  '# GitLab CI and other CI',
  '',
  'Back Office never calls GitLab; the pipeline calls Back Office.',
  '',
  '<a id="ci-overview"></a>',
  '## Overview',
  '',
  'A pipeline job runs `backoffice-ci run review`.',
  '',
  '<a id="ci-token"></a>',
  '## Create a CI token',
  '',
  'Open the project, tab **CI**, and press *Create token*.',
  '',
  '### Naming',
  '',
  'Name it after the pipeline, see [variables](#ci-variables).',
  '',
  '```bash',
  '## not a heading inside code',
  'echo "<a id=\\"ci-variables\\"></a>"',
  '```',
  '',
  '<a id="ci-variables"></a>',
  '## Add the CI/CD variables',
  '',
  'Masked, not protected. See [Jira](integrations-jira.md#jira-token) and',
  '[the overview](./getting-started.md).',
  '',
  '![Variables](images/ci-variables.png)',
  '',
  '# Appendix',
  'Last words.',
].join('\n');

describe('manual markdown', () => {
  describe('extractSection', () => {
    it('takes the anchor heading up to the next heading of the same level', () => {
      const s = extractSection(CHAPTER, 'ci-token')!;
      expect(s.title).toBe('Create a CI token');
      expect(s.level).toBe(2);
      expect(s.body).toContain('press *Create token*');
      // a deeper heading stays inside, code is not mistaken for headings
      expect(s.body).toContain('### Naming');
      expect(s.body).toContain('## not a heading inside code');
      // the next section and its anchor line are excluded
      expect(s.body).not.toContain('<a id="ci-variables"></a>\n## Add');
      expect(s.body.trim().endsWith('```')).toBe(true);
    });

    it('ends at a higher-level heading and at the end of the file', () => {
      const s = extractSection(CHAPTER, 'ci-variables')!;
      expect(s.title).toBe('Add the CI/CD variables');
      expect(s.body).toContain('Masked, not protected');
      expect(s.body).not.toContain('Appendix');
    });

    it('accepts the anchor inside the heading line and single quotes', () => {
      const md = "# T\n\n## <a id='x'></a>Inline anchor\n\nBody.\n\n## Next\n";
      expect(extractSection(md, 'x')).toEqual({ title: 'Inline anchor', level: 2, body: 'Body.' });
    });

    it('accepts name=, several anchor lines and anchors with more attributes', () => {
      const md = [
        '# T',
        '<A Name="old"></A>',
        '<a class="x" id="first"></a> <a id="second" title="t" ></a>',
        '## Section',
        'Body.',
      ].join('\n');
      expect(extractSection(md, 'old')?.title).toBe('Section');
      expect(extractSection(md, 'second')?.body).toBe('Body.');
      // data-id is not an id; an anchor with content is not an anchor-only line
      expect(extractSection('<a data-id="z"></a>\n## H', 'z')).toBeNull();
      expect(extractSection('<a id="y">text</a>\nmore\n## H', 'y')).toBeNull();
    });

    it('stays linear on adversarial anchor lines (no catastrophic backtracking, S5852)', () => {
      const evil = [
        '<a ' + ' '.repeat(50_000) + 'id="x"' + ' '.repeat(50_000),
        '<a id="x"' + '\t'.repeat(50_000) + '>' + ' '.repeat(50_000) + '</a',
        '<a ' + 'name= '.repeat(20_000),
        '<a id="a"></a>'.repeat(5_000) + '<a id="b">',
        '<a '.repeat(30_000),
      ];
      const md = ['# T', ...evil, '## H', 'x'].join('\n');
      const started = performance.now();
      for (const a of ['x', 'a', 'b', 'nope']) extractSection(md, a);
      buildSearchIndex([{ slug: 's', title: 'T', markdown: md }]);
      expect(performance.now() - started).toBeLessThan(1000);
    });

    it('returns null for a missing anchor or an anchor that is not before a heading', () => {
      expect(extractSection(CHAPTER, 'nope')).toBeNull();
      expect(extractSection('<a id="a"></a>\nSome text\n## Heading', 'a')).toBeNull();
    });

    it('chapterIntro is the title and the text before the first section', () => {
      const s = chapterIntro(CHAPTER)!;
      expect(s.title).toBe('GitLab CI and other CI');
      expect(s.body).toBe('Back Office never calls GitLab; the pipeline calls Back Office.');
    });
  });

  describe('rewriteManualLinks', () => {
    it('turns chapter links into router links and files into /manual/ URLs', () => {
      const out = rewriteManualLinks(CHAPTER, 'integrations-gitlab-ci');
      expect(out).toContain('[variables](/help/integrations-gitlab-ci#ci-variables)');
      expect(out).toContain('[Jira](/help/integrations-jira#jira-token)');
      expect(out).toContain('[the overview](/help/getting-started)');
      expect(out).toContain('![Variables](/manual/images/ci-variables.png)');
    });

    it('keeps absolute links, titles, raw HTML anchors and code blocks', () => {
      const md = [
        '[site](https://example.com/a.md#x "Title") [app](/runs) [mail](mailto:a@b.c)',
        '[ref]: other.md#y',
        '<a href="runs.md#run-list">runs</a> <img src="./img/a.png">',
        '```',
        '[code](other.md)',
        '```',
      ].join('\n');
      const out = rewriteManualLinks(md, 'here').split('\n');
      expect(out[0]).toBe(
        '[site](https://example.com/a.md#x "Title") [app](/runs) [mail](mailto:a@b.c)',
      );
      expect(out[1]).toBe('[ref]: /help/other#y');
      expect(out[2]).toBe('<a href="/help/runs#run-list">runs</a> <img src="/manual/img/a.png">');
      expect(out[4]).toBe('[code](other.md)');
    });
  });

  it('recognises the SPA fallback instead of Markdown', () => {
    expect(isMarkdownResponse('# Title')).toBe(true);
    expect(isMarkdownResponse('<!doctype html><html>')).toBe(false);
    expect(isMarkdownResponse('  <html lang="en">')).toBe(false);
    expect(isMarkdownResponse('')).toBe(false);
    expect(isMarkdownResponse(null)).toBe(false);
  });

  describe('search', () => {
    const index = buildSearchIndex([
      { slug: 'integrations-gitlab-ci', title: 'GitLab CI and other CI', markdown: CHAPTER },
      {
        slug: 'runs',
        title: 'Runs',
        markdown: '# Runs\n\n<a id="run-list"></a>\n## Run list\n\nFilter runs by holder.\n',
      },
    ]);

    it('splits chapters into sections with their anchors', () => {
      const ci = index.filter((s) => s.chapter === 'integrations-gitlab-ci');
      expect(ci.map((s) => [s.title, s.anchor])).toEqual([
        ['GitLab CI and other CI', null],
        ['Overview', 'ci-overview'],
        ['Create a CI token', 'ci-token'],
        // a sub-heading without its own anchor links to the enclosing section
        ['Naming', 'ci-token'],
        ['Add the CI/CD variables', 'ci-variables'],
        ['Appendix', null],
      ]);
      expect(ci[2].text).toBe('Open the project, tab CI, and press Create token.');
    });

    it('finds sections containing every term, title hits first, with a link', () => {
      const hits = searchManual(index, 'ci token');
      expect(hits[0].section.title).toBe('Create a CI token');
      expect(hits[0].link).toBe('/help/integrations-gitlab-ci#ci-token');
      expect(searchManual(index, 'holder')[0].link).toBe('/help/runs#run-list');
      expect(searchManual(index, 'holder gitlab-nonexistent')).toEqual([]);
      expect(searchManual(index, ' ')).toEqual([]);
    });

    it('cuts a snippet around the first hit and highlights terms', () => {
      const text = 'word '.repeat(80) + 'masked variable ' + 'tail '.repeat(80);
      const s = snippet(text, ['masked']);
      expect(s.startsWith('…')).toBe(true);
      expect(s.endsWith('…')).toBe(true);
      expect(s).toContain('masked variable');
      expect(s.length).toBeLessThanOrEqual(184);
      expect(highlightParts('Masked, not protected', ['masked'])).toEqual([
        { text: 'Masked', hit: true },
        { text: ', not protected', hit: false },
      ]);
    });

    it('plainText drops markup', () => {
      expect(plainText('**Bold** [link](x.md) `code` <a id="x"></a>\n- item')).toBe(
        'Bold link code item',
      );
    });
  });
});
