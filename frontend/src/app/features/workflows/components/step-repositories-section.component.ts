import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type { ProjectRepository, ValidationIssue } from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { accessLabel, purposeLabel } from '../../../core/ui/repositories';
import {
  type StepAccess,
  derivedAccess,
  initialExplicitList,
  pickerRows,
  repositoryIssues,
  setRepositoryAccess,
  stepRepositories,
  toggleRepository,
  unknownKeys,
  withStepRepositories,
} from '../data/step-repositories';

/**
 * "Repositories" of a runner step (IMPLEMENT / SPEC_KIT) in the designer (ADR 0034): the default
 * (the project's primary repository, access derived from push) or an explicit list with READ or
 * PUSH_MR per repository, written to `overrides.config.repositories`.
 */
@Component({
  selector: 'app-step-repositories-section',
  imports: [FormsModule, NzRadioModule, NzCheckboxModule, NzSelectModule, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="srs">
      @if (!repositories().length) {
        <p class="state bad" i18n="@@workflows.repos.noRepositories">
          This project has no repository, so this coding step cannot run. Add one in the project
          settings or use an LLM step.
        </p>
      } @else {
        <nz-radio-group
          [ngModel]="explicit() ? 'explicit' : 'default'"
          (ngModelChange)="setMode($event)"
          [nzDisabled]="readonly()"
          nzSize="small"
          [attr.aria-label]="text.mode"
        >
          <label nz-radio nzValue="default" i18n="@@workflows.repos.modeDefault"
            >Use the primary repository</label
          >
          <label nz-radio nzValue="explicit" i18n="@@workflows.repos.modeExplicit"
            >Choose repositories</label
          >
        </nz-radio-group>

        @if (!explicit()) {
          @if (primary(); as p) {
            <p class="quiet">
              <span class="mono">{{ p.key }}</span> ·
              <span>{{ accessText(defaultAccess()) }}</span>
            </p>
          } @else {
            <p class="state bad" i18n="@@workflows.repos.noPrimary">
              The project has no primary repository: choose the repositories of this step.
            </p>
          }
        } @else {
          <ul class="rows" [attr.aria-label]="text.list">
            @for (row of rows(); track row.repository.key) {
              <li class="row">
                <label
                  nz-checkbox
                  [ngModel]="row.checked"
                  (ngModelChange)="toggle(row.repository.key, $event)"
                  [nzDisabled]="readonly()"
                >
                  <span class="mono">{{ row.repository.key }}</span>
                  @if (row.repository.purpose) {
                    <span class="chip">{{ purpose(row.repository.purpose) }}</span>
                  }
                  @if (row.repository.primary) {
                    <span class="chip primary" i18n="@@workflows.repos.primary">primary</span>
                  }
                </label>
                @if (row.checked) {
                  <nz-select
                    nzSize="small"
                    class="access"
                    [ngModel]="row.access"
                    (ngModelChange)="setAccess(row.repository.key, $event)"
                    [nzDisabled]="readonly()"
                    [attr.aria-label]="accessLabelFor(row.repository.key)"
                  >
                    <nz-option nzValue="READ" [nzLabel]="accessText('READ')" />
                    <nz-option
                      nzValue="PUSH_MR"
                      [nzLabel]="row.pushDisabled ? text.pushOffOption : accessText('PUSH_MR')"
                      [nzDisabled]="row.pushDisabled && row.access !== 'PUSH_MR'"
                    />
                  </nz-select>
                }
              </li>
            }
          </ul>
          @if (!pushes()) {
            <p class="quiet" i18n="@@workflows.repos.pushOff">
              Push is off for this step, so it can only read repositories.
            </p>
          }
          @for (k of unknown(); track k) {
            <p class="state bad">{{ unknownText(k) }}</p>
          }
          @if (!list()?.length) {
            <p class="state warn" i18n="@@workflows.repos.emptyList">
              Choose at least one repository, or use the primary repository.
            </p>
          }
        }
      }
      @for (e of issues(); track $index) {
        <p class="state bad" role="alert">{{ e.message }}</p>
      }
      <p class="quiet hints" i18n="@@workflows.repos.hints">
        Least privilege: choose Read unless the step must change that repository. The agent never
        merges; a push only opens a merge request. Each repository is cloned into
        /workspace/&lt;key&gt;; the agent works in the primary one.
      </p>
      <bo-help-link topic="step.repositories" variant="text"
        ><span i18n="@@workflows.repos.guide">How step repositories work</span></bo-help-link
      >
    </div>
  `,
  styles: [
    `
      .srs {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin-bottom: 8px;
      }
      .rows {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .row {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 8px;
      }
      .access {
        width: 210px;
        margin-left: auto;
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
      .chip.primary {
        background: var(--bo-agent-soft);
        border-color: transparent;
        color: var(--bo-agent-ink);
      }
      .quiet {
        margin: 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .state {
        margin: 0;
        font-size: 12px;
        padding: 6px 8px;
        border-radius: var(--bo-radius-control);
      }
      .state.warn {
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
      }
      .state.bad {
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
    `,
  ],
})
export class StepRepositoriesSectionComponent {
  /** The project's repositories (display order). */
  readonly repositories = input<readonly ProjectRepository[]>([]);
  /** The node's `overrides.config`. */
  readonly config = input<Record<string, unknown> | null | undefined>(null);
  /** Whether the step pushes (config `push`, template, schema default). */
  readonly pushes = input(true);
  /** The node's validation issues (errors and warnings); those of `task.config.repositories` show here. */
  readonly nodeIssues = input<readonly ValidationIssue[]>([]);
  readonly readonly = input(false);

  /** The new `overrides.config` (undefined when it became empty). */
  readonly configChange = output<Record<string, unknown> | undefined>();

  readonly text = {
    mode: $localize`:@@workflows.repos.modeLabel:Repositories of this step`,
    list: $localize`:@@workflows.repos.listLabel:Project repositories`,
    pushOffOption: $localize`:@@workflows.repos.pushOffOption:Push and merge request (push is off)`,
  };

  readonly list = computed(() => stepRepositories(this.config()));
  readonly explicit = computed(() => this.list() !== null);
  readonly primary = computed(() => this.repositories().find((r) => r.primary) ?? null);
  readonly defaultAccess = computed(() => derivedAccess(this.pushes()));
  readonly rows = computed(() => pickerRows(this.repositories(), this.list() ?? [], this.pushes()));
  readonly unknown = computed(() => unknownKeys(this.repositories(), this.list() ?? []));
  readonly issues = computed(() => repositoryIssues(this.nodeIssues()));

  accessText(access: StepAccess): string {
    return accessLabel(access);
  }
  purpose = purposeLabel;

  accessLabelFor(key: string): string {
    return $localize`:@@workflows.repos.accessLabel:Access to ${key}:key:`;
  }
  unknownText(key: string): string {
    return $localize`:@@workflows.repos.unknown:The project has no repository ${key}:key:; untick it or fix the project.`;
  }

  setMode(mode: 'default' | 'explicit'): void {
    if (this.readonly()) return;
    if (mode === 'default') {
      if (this.explicit()) this.emit(null);
    } else if (!this.explicit()) {
      this.emit(initialExplicitList(this.repositories(), this.pushes()));
    }
  }

  toggle(key: string, on: boolean): void {
    if (this.readonly()) return;
    this.emit(toggleRepository(this.repositories(), this.list() ?? [], key, on));
  }

  setAccess(key: string, access: StepAccess): void {
    if (this.readonly()) return;
    if (access === 'PUSH_MR' && !this.pushes()) return;
    this.emit(setRepositoryAccess(this.list() ?? [], key, access));
  }

  private emit(list: ReturnType<typeof stepRepositories>): void {
    this.configChange.emit(withStepRepositories(this.config(), list));
  }
}
