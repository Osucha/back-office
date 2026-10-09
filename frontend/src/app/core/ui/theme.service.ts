import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { AuthApi } from '../api/auth.service';
import type { Theme } from '../api/types';
import { IdentityService } from '../auth/identity.service';

const STORAGE_KEY = 'backoffice.theme';

/** Resolves SYSTEM against the OS preference. */
export function resolveTheme(theme: Theme | undefined, prefersDark: boolean): 'light' | 'dark' {
  if (theme === 'DARK') return 'dark';
  if (theme === 'LIGHT') return 'light';
  return prefersDark ? 'dark' : 'light';
}

/**
 * Applies the user's theme preference (`me.preferences.theme`). The Relay tokens
 * (`styles/relay/tokens.css`) switch on `html[data-theme]`; NG-ZORRO follows because its theme is
 * mapped onto the same tokens, so no second stylesheet is loaded. `html.bo-dark` is kept for
 * component styles that still use `:host-context(html.bo-dark)`. The last choice is remembered
 * locally so the login page does not flash light for a dark-mode user.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  private readonly identity = inject(IdentityService);
  private readonly api = inject(AuthApi);
  private readonly prefersDark = signal(false);
  private readonly local = signal<Theme | undefined>(readStored());

  /** The preference in effect (SYSTEM / LIGHT / DARK). */
  readonly preference = computed<Theme>(() => {
    const me = this.identity.me();
    return (me ? me.preferences?.theme : this.local()) ?? 'SYSTEM';
  });
  /** The resolved theme. */
  readonly applied = computed(() => resolveTheme(this.preference(), this.prefersDark()));

  constructor() {
    const media = this.doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)');
    if (media) {
      this.prefersDark.set(media.matches);
      const listener = (e: MediaQueryListEvent) => this.prefersDark.set(e.matches);
      media.addEventListener?.('change', listener);
      inject(DestroyRef).onDestroy(() => media.removeEventListener?.('change', listener));
    }
    effect(() => {
      this.apply(this.applied());
      if (this.identity.me()) store(this.preference());
    });
  }

  /** Saves the preference (`PUT /me/preferences`); applied at once, reverted if the save fails. */
  set(theme: Theme): void {
    const me = this.identity.me();
    this.local.set(theme);
    store(theme);
    if (!me) return;
    const previous = me.preferences ?? { theme: 'SYSTEM', pageSize: 20 };
    const next = { ...previous, theme };
    this.identity.setPreferences(next);
    this.api.updatePreferences(next).subscribe({
      next: (saved) => this.identity.setPreferences({ ...next, ...saved }),
      error: () => this.identity.setPreferences(previous),
    });
  }

  /** Light <-> dark (from SYSTEM it picks the opposite of what is shown). */
  toggle(): void {
    this.set(this.applied() === 'dark' ? 'LIGHT' : 'DARK');
  }

  private apply(mode: 'light' | 'dark'): void {
    const root = this.doc.documentElement;
    root.dataset.theme = mode;
    root.classList.toggle('bo-dark', mode === 'dark');
  }
}

function readStored(): Theme | undefined {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === 'DARK' || v === 'LIGHT' || v === 'SYSTEM' ? v : undefined;
  } catch {
    return undefined;
  }
}

function store(theme: Theme): void {
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // storage unavailable (private mode)
  }
}
