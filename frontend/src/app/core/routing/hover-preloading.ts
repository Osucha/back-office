import { Injectable } from '@angular/core';
import type { PreloadingStrategy, Route } from '@angular/router';
import { EMPTY, Observable, Subject, filter, mergeMap, take } from 'rxjs';

/**
 * Preloads a lazy route when the user shows intent (hovering or focusing a rail item): the chunk
 * is usually in the cache before the click, so route changes stay under the 150 ms budget without
 * downloading every feature up front.
 *
 *   provideRouter(routes, withPreloading(HoverPreloadingStrategy))
 *   inject(HoverPreloadingStrategy).preload('/runs');
 */
@Injectable({ providedIn: 'root' })
export class HoverPreloadingStrategy implements PreloadingStrategy {
  private readonly requests = new Subject<string>();
  private readonly requested = new Set<string>();

  preload(route: Route, load: () => Observable<unknown>): Observable<unknown> {
    const path = route.path ?? '';
    if (!route.loadComponent && !route.loadChildren) return EMPTY;
    const first = path.split('/')[0];
    if (!first || first.startsWith(':') || first === '**') return EMPTY;
    // Every route whose first segment matches (runs, runs/:runId) is loaded on the same intent.
    const matches = (url: string) => url.replace(/^\//, '').split(/[/?#]/)[0] === first;
    if ([...this.requested].some(matches)) return load();
    return this.requests.pipe(
      filter(matches),
      take(1),
      mergeMap(() => load()),
    );
  }

  /** Signals intent to open `url` (e.g. `/runs`). */
  intent(url: string): void {
    if (this.requested.has(url)) return;
    this.requested.add(url);
    this.requests.next(url);
  }
}
