import { Injectable, signal } from '@angular/core';
import { Observable, defer, finalize } from 'rxjs';

/** A save that has not answered after this long no longer blocks navigation (lost request). */
export const PENDING_SAVE_CEILING_MS = 30_000;

/**
 * Counts saves in flight. While one is pending, navigation inside the app is refused
 * (`pendingSavesGuard`): leaving the editor would drop the answer (validation errors, the new
 * version) and invite a second, conflicting save.
 */
@Injectable({ providedIn: 'root' })
export class PendingSavesService {
  private readonly started = new Map<number, number>();
  private nextId = 0;
  /** Number of saves in flight (for spinners); `hasPending()` also applies the ceiling. */
  readonly count = signal(0);

  /** Wraps a save: counted from subscription until completion, error or unsubscribe. */
  track<T>(source: Observable<T>): Observable<T> {
    return defer(() => {
      const finish = this.begin();
      return source.pipe(finalize(finish));
    });
  }

  begin(): () => void {
    const id = this.nextId++;
    this.started.set(id, Date.now());
    this.count.set(this.started.size);
    return () => {
      this.started.delete(id);
      this.count.set(this.started.size);
    };
  }

  hasPending(): boolean {
    const cutoff = Date.now() - PENDING_SAVE_CEILING_MS;
    for (const [id, at] of this.started) {
      if (at <= cutoff) this.started.delete(id);
    }
    this.count.set(this.started.size);
    return this.started.size > 0;
  }
}
