import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, url } from './api-base';
import type {
  Attempt,
  AttemptDetail,
  Benchmark,
  BenchmarkInput,
  BenchmarkSummary,
  Leaderboard,
} from './types';

@Injectable({ providedIn: 'root' })
export class BenchmarksApi {
  private readonly http = inject(HttpClient);

  list(): Observable<BenchmarkSummary[]> {
    return this.http.get<BenchmarkSummary[]>(url('benchmarks'));
  }

  get(benchmarkId: string): Observable<Benchmark> {
    return this.http.get<Benchmark>(url('benchmarks', benchmarkId));
  }

  create(input: BenchmarkInput): Observable<Benchmark> {
    return this.http.post<Benchmark>(url('benchmarks'), input);
  }

  delete(benchmarkId: string): Observable<void> {
    return this.http.delete<void>(url('benchmarks', benchmarkId));
  }

  start(benchmarkId: string): Observable<Benchmark> {
    return this.http.post<Benchmark>(url('benchmarks', benchmarkId, 'start'), {});
  }

  cancel(benchmarkId: string): Observable<Benchmark> {
    return this.http.post<Benchmark>(url('benchmarks', benchmarkId, 'cancel'), {});
  }

  attempts(
    benchmarkId: string,
    filter: { taskId?: string | null; modelProfileId?: string | null } = {},
  ): Observable<Attempt[]> {
    return this.http.get<Attempt[]>(url('benchmarks', benchmarkId, 'attempts'), {
      params: params({ taskId: filter.taskId, modelProfileId: filter.modelProfileId }),
    });
  }

  attempt(benchmarkId: string, attemptId: string): Observable<AttemptDetail> {
    return this.http.get<AttemptDetail>(url('benchmarks', benchmarkId, 'attempts', attemptId));
  }

  leaderboard(benchmarkId: string): Observable<Leaderboard> {
    return this.http.get<Leaderboard>(url('benchmarks', benchmarkId, 'leaderboard'));
  }
}
