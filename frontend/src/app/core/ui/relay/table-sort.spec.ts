import { TestBed } from '@angular/core/testing';
import { compareValues, parseSort, serverSort, sortRows, tableSort } from './table-sort';

describe('table sort', () => {
  const rows = [
    { name: 'Żaneta', score: 70, at: '2026-10-02T10:00:00Z' },
    { name: 'adam', score: null, at: '2026-10-01T10:00:00Z' },
    { name: 'Ewa', score: 90, at: null },
    { name: 'Bartek', score: 70, at: '2026-10-03T10:00:00Z' },
  ];

  it('compares text by locale, numbers numerically, empty last in both directions', () => {
    expect(compareValues('a', 'B')).toBeLessThan(0);
    expect(compareValues(2, 10)).toBeLessThan(0);
    expect(compareValues(null, 1)).toBeGreaterThan(0);
    expect(sortRows(rows, (r) => r.name, 'ascend').map((r) => r.name)).toEqual(['adam', 'Bartek', 'Ewa', 'Żaneta']);
    expect(sortRows(rows, (r) => r.score, 'descend').map((r) => r.name)).toEqual(['Ewa', 'Żaneta', 'Bartek', 'adam']);
    expect(sortRows(rows, (r) => r.score, 'ascend').map((r) => r.name)).toEqual(['Żaneta', 'Bartek', 'Ewa', 'adam']);
    expect(sortRows(rows, (r) => r.at, 'descend').map((r) => r.name)).toEqual(['Bartek', 'Żaneta', 'adam', 'Ewa']);
    expect(sortRows(rows, (r) => r.name, null)).toEqual(rows);
  });

  it('reads only known columns from the URL value', () => {
    expect(parseSort('name.desc', ['name'])).toEqual({ key: 'name', order: 'descend' });
    expect(parseSort('other.asc', ['name'])).toBeNull();
    expect(parseSort('name', ['name'])).toBeNull();
  });

  it('keeps the state in memory without a router and ignores the reset of another header', () => {
    const sort = TestBed.runInInjectionContext(() => tableSort<(typeof rows)[number]>({ name: (r) => r.name, score: (r) => r.score }));
    expect(sort.apply(rows)).toEqual(rows);
    sort.set('score', 'descend');
    sort.set('name', null);
    expect(sort.order('score')).toBe('descend');
    expect(sort.apply(rows)[0].name).toBe('Ewa');
    sort.set('score', null);
    expect(sort.state()).toBeNull();
  });

  it('gives a server-paged table the value of the API sort parameter', () => {
    const sort = TestBed.runInInjectionContext(() => serverSort(['cost', 'createdAt']));
    expect(sort.query()).toBeNull();
    sort.set('createdAt', 'descend');
    expect(sort.query()).toBe('createdAt.desc');
    sort.set('cost', 'ascend');
    expect(sort.query()).toBe('cost.asc');
  });
});
