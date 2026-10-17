import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type {
  LibrarySource,
  ProjectRepository,
  WorkflowWorkspace,
  WorkspaceSource,
} from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { purposeLabel } from '../../../core/ui/repositories';
import { newWorkspace, workspaceColor, workspaceProblems } from '../data/workspaces';

export interface WorkspacesChange {
  workspaces: WorkflowWorkspace[];
  /** Old key -> new key (renamed) or null (removed): the steps that used it follow. */
  renames: Record<string, string | null>;
}

/**
 * The run workspaces of a workflow (ADR 0044), in the workflow properties: key and name, the sources cloned into it,
 * its setup command and the agent roles of its steps. Steps only pick a workspace (step section Workspace); the
 * canvas draws each workspace around its steps in the colour shown here.
 */
@Component({
  selector: 'app-workspaces-editor',
  imports: [FormsModule, NzButtonModule, NzCheckboxModule, NzIconModule, NzInputModule, NzSelectModule, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wse">
      @for (w of workspaces(); track $index; let i = $index) {
        <div class="ws" [style.--ws]="color(i)">
          <button type="button" class="ws-head" (click)="toggle(i)" [attr.aria-expanded]="open() === i">
            <span class="dot" aria-hidden="true"></span>
            <span class="name">{{ w.name || w.key }}</span>
            <span class="mono muted">{{ w.key }}</span>
            <span class="muted small count">{{ stepsText(w.key) }}</span>
            <nz-icon [nzType]="open() === i ? 'up' : 'down'" />
          </button>
          @if (open() === i) {
            <div class="ws-body">
              <label class="field">
                <span i18n="@@workflows.workspaces.name">Name</span>
                <input nz-input [ngModel]="w.name ?? ''" (ngModelChange)="patch(i, { name: $event })" [readonly]="readonly()" />
              </label>
              <label class="field">
                <span i18n="@@workflows.workspaces.key">Key</span>
                <input
                  nz-input
                  class="mono"
                  [ngModel]="w.key"
                  (ngModelChange)="rename(i, $event)"
                  [readonly]="readonly()"
                />
              </label>
              <label class="field">
                <span i18n="@@workflows.workspaces.description">Description</span>
                <textarea
                  nz-input
                  [nzAutosize]="{ minRows: 1, maxRows: 4 }"
                  [ngModel]="w.description ?? ''"
                  (ngModelChange)="patch(i, { description: $event })"
                  [readonly]="readonly()"
                ></textarea>
              </label>

              <div class="sub" i18n="@@workflows.workspaces.sources">Sources cloned into it</div>
              @if (!projectRepositories().length && !librarySources().length) {
                <p class="muted small" i18n="@@workflows.workspaces.noSources">
                  The project has no repositories and the library no sources.
                </p>
              }
              @for (r of projectRepositories(); track r.key) {
                <label nz-checkbox [ngModel]="has(w, r.key, false)" (ngModelChange)="toggleSource(i, r.key, false, $event)" [nzDisabled]="readonly()">
                  <span class="mono">{{ r.key }}</span>
                  @if (r.purpose) {
                    <span class="chip">{{ purpose(r.purpose) }}</span>
                  }
                  @if (r.primary) {
                    <span class="chip" i18n="@@workflows.workspaces.primary">primary</span>
                  }
                </label>
              }
              @for (s of librarySources(); track s.id) {
                <label nz-checkbox [ngModel]="has(w, s.key, true)" (ngModelChange)="toggleSource(i, s.key, true, $event)" [nzDisabled]="readonly()">
                  <span>{{ s.name }}</span>
                  <span class="mono muted small">{{ s.jobKey }}</span>
                  <span class="chip" i18n="@@workflows.workspaces.librarySource">library</span>
                </label>
              }
              <p class="muted small hint" i18n="@@workflows.workspaces.sourcesHint">
                Steps of this workspace without repositories of their own work on these sources: the project's primary
                repository among them gets the step's push access, the others are read only.
              </p>

              <label class="field">
                <span i18n="@@workflows.workspaces.setup">Setup command</span>
                <input
                  nz-input
                  class="mono"
                  [placeholder]="examples.setup"
                  [ngModel]="w.setupCommand ?? ''"
                  (ngModelChange)="patch(i, { setupCommand: $event || null })"
                  [readonly]="readOnlyText()"
                />
                <span class="muted small" i18n="@@workflows.workspaces.setupHint"
                  >Runs once per run, before the first step of the workspace (again only when you change it).</span
                >
              </label>
              <label class="field">
                <span i18n="@@workflows.workspaces.roles">Agent roles</span>
                <nz-select
                  nzMode="tags"
                  [nzPlaceHolder]="text.rolesPlaceholder"
                  [ngModel]="w.roles ?? []"
                  (ngModelChange)="patch(i, { roles: $event })"
                  [nzDisabled]="readonly()"
                ></nz-select>
                <span class="muted small" i18n="@@workflows.workspaces.rolesHint"
                  >Steps with the same role continue one agent session, e.g. implementer and reviewer taking turns. Empty =
                  any role.</span
                >
              </label>
              @for (p of problems(w); track p) {
                <p class="problem">{{ p }}</p>
              }
              @if (!readonly()) {
                <button nz-button nzDanger nzSize="small" type="button" (click)="remove(i)">
                  <nz-icon nzType="delete" />
                  <span i18n="@@workflows.workspaces.remove">Remove workspace</span>
                </button>
              }
            </div>
          }
        </div>
      } @empty {
        <p class="muted small" i18n="@@workflows.workspaces.empty">
          No workspaces: every coding step starts from a fresh directory and a new agent session.
        </p>
      }
      @if (!readonly()) {
        <button nz-button nzSize="small" type="button" (click)="add()">
          <nz-icon nzType="plus" />
          <span i18n="@@workflows.workspaces.add">Add workspace</span>
        </button>
      }
      <bo-help-link topic="workspaces" variant="text"
        ><span i18n="@@workflows.workspaces.guide">How workspaces work</span></bo-help-link
      >
    </div>
  `,
  styles: [
    `
      .wse {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .ws {
        border: 1px solid var(--bo-line);
        border-left: 4px solid var(--ws);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface);
      }
      .ws-head {
        all: unset;
        box-sizing: border-box;
        width: 100%;
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 6px 8px;
        cursor: pointer;
      }
      .ws-head:focus-visible {
        outline: 2px solid var(--ws);
      }
      .dot {
        width: 10px;
        height: 10px;
        border-radius: 3px;
        background: var(--ws);
        flex: none;
      }
      .name {
        font-weight: 500;
      }
      .count {
        margin-left: auto;
      }
      .ws-body {
        display: flex;
        flex-direction: column;
        gap: 8px;
        padding: 4px 8px 10px;
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: 2px;
        font-size: 12px;
      }
      .sub {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
      }
      .hint {
        margin: 0;
      }
      .chip {
        font-size: 11px;
        margin-left: 6px;
        padding: 0 6px;
        border-radius: 999px;
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        color: var(--bo-ink-2);
      }
      .problem {
        margin: 0;
        font-size: 12px;
        color: var(--bo-blocked-ink);
      }
    `,
  ],
})
export class WorkspacesEditorComponent {
  readonly workspaces = input<WorkflowWorkspace[]>([]);
  /** Steps per workspace key (shown in the header). */
  readonly stepCounts = input<Record<string, number>>({});
  readonly projectRepositories = input<readonly ProjectRepository[]>([]);
  readonly librarySources = input<readonly LibrarySource[]>([]);
  readonly readonly = input(false);

  readonly changed = output<WorkspacesChange>();

  readonly open = signal<number | null>(null);
  readonly purpose = purposeLabel;
  readonly color = workspaceColor;
  readonly text = {
    rolesPlaceholder: $localize`:@@workflows.workspaces.rolesPlaceholder:implementer, reviewer`,
  };
  /** Example values in placeholders: code, the same in every language. */
  readonly examples = { setup: 'npm ci' }; // i18n-ignore

  readOnlyText(): boolean {
    return this.readonly();
  }

  stepsText(key: string): string {
    const n = this.stepCounts()[key] ?? 0;
    return $localize`:@@workflows.workspaces.steps:${n}:count: steps`;
  }

  problems(w: WorkflowWorkspace): string[] {
    return workspaceProblems(w, this.workspaces());
  }

  has(w: WorkflowWorkspace, key: string, library: boolean): boolean {
    return (w.sources ?? []).some((s) => s.key === key && !!s.library === library);
  }

  toggle(i: number): void {
    this.open.set(this.open() === i ? null : i);
  }

  add(): void {
    const list = [...this.workspaces(), newWorkspace(this.workspaces())];
    this.changed.emit({ workspaces: list, renames: {} });
    this.open.set(list.length - 1);
  }

  patch(i: number, change: Partial<WorkflowWorkspace>): void {
    if (this.readonly()) return;
    const list = this.workspaces().map((w, j) => (j === i ? { ...w, ...change } : w));
    this.changed.emit({ workspaces: list, renames: {} });
  }

  rename(i: number, key: string): void {
    if (this.readonly()) return;
    const old = this.workspaces()[i];
    const next = key.trim();
    const list = this.workspaces().map((w, j) => (j === i ? { ...w, key: next, name: w.name === w.key ? next : w.name } : w));
    this.changed.emit({ workspaces: list, renames: old.key !== next ? { [old.key]: next } : {} });
  }

  toggleSource(i: number, key: string, library: boolean, on: boolean): void {
    const w = this.workspaces()[i];
    const rest = (w.sources ?? []).filter((s) => !(s.key === key && !!s.library === library));
    const sources: WorkspaceSource[] = on ? [...rest, { key, library }] : rest;
    this.patch(i, { sources });
  }

  remove(i: number): void {
    const removed = this.workspaces()[i];
    this.changed.emit({ workspaces: this.workspaces().filter((_, j) => j !== i), renames: { [removed.key]: null } });
    this.open.set(null);
  }
}
