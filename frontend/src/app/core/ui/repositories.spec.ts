import type { Project } from '../api/types';
import { primaryRepository, projectRepositories, shortRepoUrl } from './repositories';

const base: Project = {
  id: 'p1',
  key: 'SHOP',
  name: 'Shop',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

describe('project repositories (ADR 0034)', () => {
  it('uses the list, falls back to the deprecated single repository, or is empty', () => {
    const list = [
      { key: 'docs', url: 'u', defaultBranch: 'main', purpose: 'DOCS' as const, primary: false },
    ];
    expect(projectRepositories({ ...base, repositories: list })).toBe(list);
    expect(
      projectRepositories({
        ...base,
        repository: { url: 'https://g/a.git', defaultBranch: 'dev' },
      }),
    ).toEqual([
      {
        key: 'main',
        url: 'https://g/a.git',
        defaultBranch: 'dev',
        purpose: 'SOURCE',
        primary: true,
      },
    ]);
    expect(projectRepositories({ ...base, repository: null })).toEqual([]);
    expect(projectRepositories(null)).toEqual([]);
    expect(primaryRepository({ ...base, repositories: list })).toBeNull();
  });

  it('shortens git URLs', () => {
    expect(shortRepoUrl('https://gitlab.example.com/group/shop-api.git')).toBe(
      'gitlab.example.com/group/shop-api',
    );
    expect(shortRepoUrl('git@gitlab.example.com:group/x.git')).toBe('gitlab.example.com:group/x');
  });
});
