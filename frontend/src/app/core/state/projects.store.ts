import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { ProjectsApi } from '../api/projects.service';
import type { Project } from '../api/types';
import { IdentityService } from '../auth/identity.service';

const CURRENT_KEY = 'backoffice.currentProject';

/**
 * The projects the user can see (`GET /projects`), loaded once per sign-in and shared by the rail's
 * project switcher, the command palette, list filters and the home page; `refresh()` after
 * creating or deleting a project. Also tracks the *current* project: the one last opened (project
 * page, run, designer), persisted per browser, cleared with "All projects".
 */
@Injectable({ providedIn: 'root' })
export class ProjectsStore {
  private readonly api = inject(ProjectsApi);
  private readonly identity = inject(IdentityService);

  readonly projects = signal<Project[]>([]);
  readonly loaded = signal(false);
  readonly loading = signal(false);
  readonly currentId = signal<string | null>(readCurrent());

  readonly byId = computed(() => new Map(this.projects().map((p) => [p.id, p])));
  readonly current = computed(() => {
    const id = this.currentId();
    return id ? (this.byId().get(id) ?? null) : null;
  });

  private requested = false;

  constructor() {
    effect(() => {
      const signedIn = this.identity.isAuthenticated();
      untracked(() => {
        if (!signedIn) {
          this.projects.set([]);
          this.loaded.set(false);
          this.requested = false;
        }
      });
    });
    effect(() => {
      const id = this.currentId();
      try {
        if (id) localStorage.setItem(CURRENT_KEY, id);
        else localStorage.removeItem(CURRENT_KEY);
      } catch {
        // storage unavailable
      }
    });
  }

  /** Loads the list the first time it is needed. */
  ensure(): void {
    if (this.requested || !this.identity.isAuthenticated()) return;
    this.requested = true;
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (list) => {
        this.projects.set(list);
        this.loaded.set(true);
        this.loading.set(false);
        const cur = this.currentId();
        if (cur && !list.some((p) => p.id === cur)) this.currentId.set(null);
      },
      error: () => this.loading.set(false),
    });
  }

  setCurrent(projectId: string | null | undefined): void {
    if ((projectId ?? null) !== this.currentId()) this.currentId.set(projectId ?? null);
  }

  /** Replaces one project after a save (keeps the list fresh without a reload). */
  upsert(p: Project): void {
    this.projects.update((list) =>
      list.some((x) => x.id === p.id) ? list.map((x) => (x.id === p.id ? p : x)) : [...list, p],
    );
  }
}

function readCurrent(): string | null {
  try {
    return localStorage.getItem(CURRENT_KEY);
  } catch {
    return null;
  }
}

/** Two-letter monogram of a project key: SHOP -> "SH", AB_CORE -> "AC". */
export function monogram(key: string | null | undefined): string {
  const k = (key ?? '').toUpperCase();
  if (!k) return '?';
  const parts = k.split(/[_-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).slice(0, 2);
  return k.slice(0, 2);
}
