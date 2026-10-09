import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';

export type Density = 'comfortable' | 'compact';
const STORAGE_KEY = 'backoffice.density';

/**
 * Row density preference (Relay: comfortable 32 px rows, compact 28 px). The contract has no
 * density field in `UserPreferences`, so it is a per-browser preference. Applied as
 * `html[data-density]`, which switches the density tokens in `styles/relay/tokens.css`.
 */
@Injectable({ providedIn: 'root' })
export class DensityService {
  private readonly doc = inject(DOCUMENT);
  readonly density = signal<Density>(readStored());

  constructor() {
    effect(() => {
      const d = this.density();
      this.doc.documentElement.dataset.density = d;
      try {
        localStorage.setItem(STORAGE_KEY, d);
      } catch {
        // storage unavailable
      }
    });
  }

  set(d: Density): void {
    this.density.set(d);
  }

  toggle(): void {
    this.density.update((d) => (d === 'compact' ? 'comfortable' : 'compact'));
  }
}

function readStored(): Density {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'compact' ? 'compact' : 'comfortable';
  } catch {
    return 'comfortable';
  }
}
