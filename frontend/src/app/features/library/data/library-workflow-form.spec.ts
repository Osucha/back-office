import { libraryKeyError, suggestLibraryKey } from './library-workflow-form';

describe('library workflow form', () => {
  it('suggests a slug key from the workflow name', () => {
    expect(suggestLibraryKey('Issue to reviewed MR')).toBe('issue-to-reviewed-mr');
    expect(suggestLibraryKey('Zmiana wymagań (delta) – łódź')).toBe('zmiana-wymagan-delta-lodz');
    expect(suggestLibraryKey('  3 steps ')).toBe('wf-3-steps');
    expect(suggestLibraryKey('')).toBe('');
    expect(suggestLibraryKey('x'.repeat(80))).toHaveLength(64);
  });

  it('validates the key: required, slug, unique', () => {
    expect(libraryKeyError('', [])).toBeTruthy();
    expect(libraryKeyError('A-key', [])).toBeTruthy();
    expect(libraryKeyError('a', [])).toBeTruthy();
    expect(libraryKeyError('issue-to-mr', ['issue-to-mr'])).toBeTruthy();
    expect(libraryKeyError('issue-to-mr', ['other'])).toBeNull();
  });
});
