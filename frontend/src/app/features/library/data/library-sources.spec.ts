import { emptySource, sourceInputOf, sourceProblems } from './library-sources';

describe('library sources', () => {
  it('a new source needs a key, a name and a URL', () => {
    expect(sourceProblems(emptySource())).toHaveLength(3);
    const ok = {
      ...emptySource(),
      key: 'standards',
      name: 'Standards',
      url: 'https://git.example.com/a/s.git',
    };
    expect(sourceProblems(ok)).toEqual([]);
    expect(sourceProblems({ ...ok, key: 'Bad Key' })).toHaveLength(1);
    expect(sourceProblems({ ...ok, key: 'x'.repeat(28) })).toHaveLength(1);
  });

  it('editing starts from the stored source', () => {
    const input = sourceInputOf({
      id: '1',
      key: 'templates',
      name: 'Templates',
      url: 'https://git.example.com/a/t.git',
      defaultBranch: 'trunk',
      purpose: 'TEMPLATES',
      jobKey: 'lib-templates',
      createdAt: '2026-10-17T10:00:00Z',
      updatedAt: '2026-10-17T10:00:00Z',
    });
    expect(input.defaultBranch).toBe('trunk');
    expect(input.purpose).toBe('TEMPLATES');
    expect(input.kind).toBe('GIT');
  });
});
