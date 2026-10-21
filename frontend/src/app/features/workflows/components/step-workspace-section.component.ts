import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type { ProjectWorkspace, ValidationIssue } from '../../../core/api/types';
import { sourceLabels, workspaceColor } from '../../../core/ui/workspaces';
import { stepWorkspace, withStepWorkspace, type StepWorkspace } from '../data/workspaces';

/**
 * "Workspace" of a coding step (ADR 0044): only the choice. Which workspace of the project the step works in, which
 * agent role it continues and (Implement) whether it only prepares the workspace. The workspaces themselves are
 * configured in the project tab Workspaces and shared by all its workflows.
 */
@Component({
  selector: 'app-step-workspace-section',
  imports: [FormsModule, NzCheckboxModule, NzInputModule, NzSelectModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="sws">
      @if (!workspaces().length) {
        <p class="quiet" i18n="@@workflows.stepWorkspace.noneInProject">
          The project has no workspaces: this step starts from a fresh directory and a new agent
          session. Add workspaces in the project tab Workspaces.
        </p>
      } @else {
        <nz-select
          class="full"
          [ngModel]="current().workspace ?? ''"
          (ngModelChange)="set({ workspace: $event || null })"
          [nzDisabled]="readonly()"
          [attr.aria-label]="text.workspace"
        >
          <nz-option nzValue="" [nzLabel]="text.fresh" />
          @for (w of workspaces(); track w.key; let i = $index) {
            <nz-option [nzValue]="w.key" [nzLabel]="(w.name || w.key) + ' (' + w.key + ')'" />
          }
        </nz-select>
        @if (selected(); as w) {
          <p class="quiet">
            <span class="dot" [style.background]="color()"></span>
            {{ sourcesText(w) }}
          </p>
          @if (canPrepare()) {
            <label
              nz-checkbox
              [ngModel]="current().prepareOnly"
              (ngModelChange)="set({ prepareOnly: $event })"
              [nzDisabled]="readonly()"
            >
              <span i18n="@@workflows.stepWorkspace.prepareOnly"
                >Only prepare the workspace (no agent, no push)</span
              >
            </label>
          }
          @if (!current().prepareOnly) {
            <label class="field">
              <span i18n="@@workflows.stepWorkspace.role">Agent role</span>
              @if (w.roles?.length) {
                <nz-select
                  [ngModel]="current().sessionRole ?? ''"
                  (ngModelChange)="set({ sessionRole: $event || null })"
                  [nzDisabled]="readonly()"
                  [attr.aria-label]="text.role"
                >
                  <nz-option nzValue="" [nzLabel]="text.newSession" />
                  @for (r of w.roles; track r) {
                    <nz-option [nzValue]="r" [nzLabel]="r" />
                  }
                </nz-select>
              } @else {
                <input
                  nz-input
                  class="mono"
                  [placeholder]="text.rolePlaceholder"
                  [ngModel]="current().sessionRole ?? ''"
                  (ngModelChange)="set({ sessionRole: $event || null })"
                  [readonly]="readonly()"
                />
              }
              <span class="quiet" i18n="@@workflows.stepWorkspace.roleHint"
                >Steps with the same role continue one agent session; empty = a new session.</span
              >
            </label>
          }
        } @else if (current().workspace) {
          <p class="state bad">{{ unknownText(current().workspace!) }}</p>
        }
      }
      @for (e of issues(); track $index) {
        <p class="state bad" role="alert">{{ e.message }}</p>
      }
    </div>
  `,
  styles: [
    `
      .sws {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin-bottom: 8px;
      }
      .full {
        width: 100%;
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: 2px;
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .quiet {
        margin: 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .dot {
        display: inline-block;
        width: 9px;
        height: 9px;
        border-radius: 3px;
        margin-right: 4px;
      }
      .state.bad {
        margin: 0;
        font-size: 12px;
        padding: 6px 8px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
    `,
  ],
})
export class StepWorkspaceSectionComponent {
  readonly workspaces = input<readonly ProjectWorkspace[]>([]);
  /** The node's `overrides.config`. */
  readonly config = input<Record<string, unknown> | null | undefined>(null);
  /** Implement steps may only prepare the workspace. */
  readonly canPrepare = input(false);
  readonly nodeIssues = input<readonly ValidationIssue[]>([]);
  readonly readonly = input(false);

  readonly configChange = output<Record<string, unknown> | undefined>();

  readonly text = {
    workspace: $localize`:@@workflows.stepWorkspace.label:Workspace of this step`,
    fresh: $localize`:@@workflows.stepWorkspace.fresh:None: a fresh directory`,
    role: $localize`:@@workflows.stepWorkspace.roleLabel:Agent role`,
    newSession: $localize`:@@workflows.stepWorkspace.newSession:None: a new session`,
    rolePlaceholder: $localize`:@@workflows.stepWorkspace.rolePlaceholder:e.g. implementer`,
  };

  readonly current = computed(() => stepWorkspace(this.config()));
  readonly selected = computed(
    () => this.workspaces().find((w) => w.key === this.current().workspace) ?? null,
  );
  readonly color = computed(() =>
    workspaceColor(this.workspaces().findIndex((w) => w.key === this.current().workspace)),
  );
  readonly issues = computed(() =>
    (this.nodeIssues() ?? []).filter((i) => !!i.field && /(^|\.)config\.workspace\b/.test(i.field)),
  );

  sourcesText(w: ProjectWorkspace): string {
    const keys = sourceLabels(w);
    return keys.length
      ? $localize`:@@workflows.stepWorkspace.sources:Sources: ${keys.join(', ')}:sources:`
      : $localize`:@@workflows.stepWorkspace.noSources:No sources of its own: the step's repositories are cloned into it.`;
  }

  unknownText(key: string): string {
    return $localize`:@@workflows.stepWorkspace.unknownInProject:The project has no workspace ${key}:key:; choose another one.`;
  }

  set(change: Partial<StepWorkspace>): void {
    if (this.readonly()) return;
    this.configChange.emit(withStepWorkspace(this.config(), change));
  }
}
