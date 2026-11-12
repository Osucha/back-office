import type { LibrarySource, ProjectRepository } from '../../../core/api/types';
import {
  deliverableRepositories,
  sourceOptions,
  sourceValues,
  sourcesFromValues,
} from './workflow-sources';

const repo = (key: string, primary = false): ProjectRepository =>
  ({ key, url: `https://git/${key}.git`, defaultBranch: 'main', primary }) as ProjectRepository;
const lib = (key: string): LibrarySource =>
  ({ key, name: key, url: `https://git/${key}.git` }) as LibrarySource;

describe('workflow sources (ADR 0063)', () => {
  it('turns sources into select values and back, without duplicates', () => {
    const sources = [
      { key: 'app', library: false },
      { key: 'docs', library: true },
    ];
    expect(sourceValues(sources)).toEqual(['repo:app', 'lib:docs']);
    expect(sourceValues(null)).toEqual([]);
    expect(sourcesFromValues(['repo:app', 'lib:docs', 'repo:app', 'plain', 'lib:'])).toEqual([
      { key: 'app', library: false },
      { key: 'docs', library: true },
      { key: 'plain', library: false },
    ]);
    expect(sourcesFromValues(null)).toEqual([]);
  });

  it('offers the project repositories and the library sources, keeping a missing selected key', () => {
    const o = sourceOptions(
      [repo('app', true), repo('web')],
      [lib('docs')],
      [
        { key: 'gone', library: false },
        { key: 'old-lib', library: true },
        { key: 'app', library: false },
      ],
    );
    expect(o.repositories.map((x) => [x.value, x.unknown])).toEqual([
      ['repo:app', false],
      ['repo:web', false],
      ['repo:gone', true],
    ]);
    expect(o.library.map((x) => [x.value, x.unknown])).toEqual([
      ['lib:docs', false],
      ['lib:old-lib', true],
    ]);
  });

  it('delivers to the project repositories of the sources, else the primary one', () => {
    const repos = [repo('app'), repo('web', true)];
    expect(deliverableRepositories([], repos)).toEqual(['web']);
    expect(deliverableRepositories(null, [repo('only')])).toEqual(['only']);
    expect(deliverableRepositories(null, [])).toEqual([]);
    expect(
      deliverableRepositories(
        [
          { key: 'app', library: false },
          { key: 'docs', library: true },
        ],
        repos,
      ),
    ).toEqual(['app']);
    // only library sources: nothing can be delivered (library sources are never written to)
    expect(deliverableRepositories([{ key: 'docs', library: true }], repos)).toEqual([]);
  });
});
