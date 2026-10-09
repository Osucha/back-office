import { ChangeDetectionStrategy, Component, effect, input, signal } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { REPOSITORY_PURPOSES } from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { purposeLabel } from '../../../core/ui/repositories';
import {
  type RepositoriesArray,
  addRepository,
  moveRepository,
  removeRepository,
  setPrimary,
} from '../data/repositories-form';

/**
 * "Repositories" section of the project form (ADR 0034): zero or more git repositories with key,
 * URL, default branch, purpose, description and an optional single primary. The FormArray belongs
 * to the parent form (server field errors `repositories[0].url` land on its controls).
 */
@Component({
  selector: 'app-project-repositories-editor',
  imports: [
    ReactiveFormsModule,
    FormsModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzSwitchModule,
    NzButtonModule,
    NzIconModule,
    NzTooltipModule,
    HelpLinkComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="intro">
      <span class="muted" i18n="@@projects.repos.intro"
        >The agent runner clones these repositories into /workspace/&lt;key&gt; and pushes only
        branches with a merge request. Back Office never calls the SCM API; CI calls Back
        Office.</span
      >
      <bo-help-link topic="project.repositories" />
    </div>
    <!-- reading rev() re-renders the list when the parent changes the array -->
    @let revision = rev();
    @if (revision >= 0 && !array().length) {
      <p class="hint empty">
        <span i18n="@@projects.repos.none"
          >No repository - this project runs LLM work only. Add one to use coding steps.</span
        >
        <bo-help-link topic="project.noRepository" />
      </p>
    } @else if (!hasPrimary()) {
      <p class="hint" i18n="@@projects.repos.noPrimary">
        No primary repository: every coding step must name the repositories it uses.
      </p>
    }
    @if (array().hasError('manyPrimaries')) {
      <p class="hint error" role="alert" i18n="@@projects.repos.manyPrimaries">
        Only one repository can be primary.
      </p>
    }
    <ol class="repos">
      @for (g of array().controls; track g; let i = $index, first = $first, last = $last) {
        <li class="repo" [formGroup]="g" [attr.aria-label]="rowLabel(i, g.controls.key.value)">
          <div class="row-head">
            <span class="index mono">{{ i + 1 }}</span>
            <label class="primary">
              <nz-switch
                nzSize="small"
                [ngModel]="g.controls.primary.value"
                [ngModelOptions]="{ standalone: true }"
                (ngModelChange)="togglePrimary(i, $event)"
                [nzDisabled]="readonly()"
                [attr.aria-label]="primaryLabel(g.controls.key.value)"
              />
              <span i18n="@@projects.repos.primary">Primary</span>
            </label>
            <span class="spacer"></span>
            @if (!readonly()) {
              <button
                nz-button
                nzType="text"
                nzSize="small"
                type="button"
                [disabled]="first"
                (click)="move(i, -1)"
                [attr.aria-label]="upLabel(g.controls.key.value)"
                nz-tooltip
                nzTooltipTitle="Move up"
                i18n-nzTooltipTitle="@@projects.repos.up"
              >
                <nz-icon nzType="arrow-up" />
              </button>
              <button
                nz-button
                nzType="text"
                nzSize="small"
                type="button"
                [disabled]="last"
                (click)="move(i, 1)"
                [attr.aria-label]="downLabel(g.controls.key.value)"
                nz-tooltip
                nzTooltipTitle="Move down"
                i18n-nzTooltipTitle="@@projects.repos.down"
              >
                <nz-icon nzType="arrow-down" />
              </button>
              <button
                nz-button
                nzType="text"
                nzSize="small"
                nzDanger
                type="button"
                (click)="remove(i)"
                [attr.aria-label]="removeLabel(g.controls.key.value)"
                nz-tooltip
                nzTooltipTitle="Remove"
                i18n-nzTooltipTitle="@@projects.repos.remove"
              >
                <nz-icon nzType="delete" />
              </button>
            }
          </div>
          <div class="grid">
            <nz-form-item>
              <nz-form-label nzRequired [nzFor]="id(i, 'key')" i18n="@@projects.repos.key"
                >Key</nz-form-label
              >
              <nz-form-control [nzErrorTip]="keyTip">
                <input nz-input class="mono" [id]="id(i, 'key')" formControlName="key" />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label [nzFor]="id(i, 'purpose')" i18n="@@projects.repos.purpose"
                >Purpose</nz-form-label
              >
              <nz-form-control>
                <nz-select [nzId]="id(i, 'purpose')" formControlName="purpose">
                  @for (p of purposes; track p) {
                    <nz-option [nzValue]="p" [nzLabel]="purposeLabel(p)" />
                  }
                </nz-select>
              </nz-form-control>
            </nz-form-item>
            <nz-form-item class="span-2">
              <nz-form-label nzRequired [nzFor]="id(i, 'url')" i18n="@@projects.repos.url"
                >Git URL</nz-form-label
              >
              <nz-form-control [nzErrorTip]="urlTip">
                <input
                  nz-input
                  [id]="id(i, 'url')"
                  formControlName="url"
                  [placeholder]="examples.url"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzRequired [nzFor]="id(i, 'branch')" i18n="@@projects.repos.branch"
                >Default branch</nz-form-label
              >
              <nz-form-control [nzErrorTip]="requiredTip">
                <input
                  nz-input
                  class="mono"
                  [id]="id(i, 'branch')"
                  formControlName="defaultBranch"
                  [placeholder]="examples.branch"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label [nzFor]="id(i, 'desc')" i18n="@@projects.repos.description"
                >Description</nz-form-label
              >
              <nz-form-control>
                <input nz-input [id]="id(i, 'desc')" formControlName="description" />
              </nz-form-control>
            </nz-form-item>
          </div>
        </li>
      }
    </ol>
    @if (!readonly()) {
      <button nz-button nzType="dashed" type="button" class="add" (click)="add()">
        <nz-icon nzType="plus" />
        <span i18n="@@projects.repos.add">Add repository</span>
      </button>
    }
    <ng-template #keyTip let-control>
      @if (control.errors?.server) {
        {{ control.errors.server }}
      } @else if (control.errors?.duplicate) {
        <span i18n="@@projects.repos.keyDuplicate">Another repository already uses this key</span>
      } @else {
        <span i18n="@@projects.repos.keyPattern"
          >Lowercase letters, digits and dashes, starting with a letter (at most 31)</span
        >
      }
    </ng-template>
    <ng-template #urlTip let-control>
      @if (control.errors?.server) {
        {{ control.errors.server }}
      } @else {
        <span i18n="@@projects.repos.urlRequired">Git URL is required</span>
      }
    </ng-template>
    <ng-template #requiredTip let-control>
      @if (control.errors?.server) {
        {{ control.errors.server }}
      } @else {
        <span i18n="@@projects.repos.required">Required</span>
      }
    </ng-template>
  `,
  styles: [
    `
      .intro {
        display: flex;
        align-items: flex-start;
        gap: 6px;
        font-size: 12px;
        margin-bottom: 8px;
      }
      .hint {
        margin: 0 0 8px;
        font-size: 12px;
        padding: 6px 8px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
        color: var(--bo-ink-2);
      }
      .hint.error {
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
      .repos {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .repo {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-control);
        padding: 8px 12px 0;
        background: var(--bo-surface);
      }
      .row-head {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 4px;
      }
      .index {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .primary {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        cursor: pointer;
      }
      .spacer {
        flex: 1;
      }
      .grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0 16px;
      }
      .span-2 {
        grid-column: span 2;
      }
      .add {
        margin: 8px 0 12px;
      }
      @media (max-width: 600px) {
        .grid {
          grid-template-columns: minmax(0, 1fr);
        }
        .span-2 {
          grid-column: auto;
        }
      }
    `,
  ],
})
export class ProjectRepositoriesEditorComponent {
  readonly array = input.required<RepositoriesArray>();
  readonly readonly = input(false);

  readonly purposes = REPOSITORY_PURPOSES;
  readonly purposeLabel = purposeLabel;
  readonly examples = {
    url: 'https://gitlab.example.com/group/shop-api.git',
    branch: 'main',
  };
  private readonly uid = Math.random().toString(36).slice(2, 7);
  /** Bumped on every event of the array, so changes made by the parent (load, reset) render. */
  readonly rev = signal(0);

  constructor() {
    effect((onCleanup) => {
      const sub = this.array().events.subscribe(() => this.rev.update((n) => n + 1));
      onCleanup(() => sub.unsubscribe());
    });
  }

  hasPrimary(): boolean {
    return this.array().controls.some((g) => g.controls.primary.value);
  }

  id(i: number, field: string): string {
    return `p-repo-${this.uid}-${i}-${field}`;
  }

  add(): void {
    if (this.readonly()) return;
    addRepository(this.array());
  }

  remove(i: number): void {
    if (this.readonly()) return;
    removeRepository(this.array(), i);
  }

  move(i: number, delta: number): void {
    if (this.readonly()) return;
    moveRepository(this.array(), i, delta);
  }

  togglePrimary(i: number, on: boolean): void {
    if (this.readonly()) return;
    setPrimary(this.array(), i, on);
  }

  rowLabel(i: number, key: string): string {
    const n = i + 1;
    return $localize`:@@projects.repos.rowLabel:Repository ${n}:n:: ${key}:key:`;
  }
  primaryLabel(key: string): string {
    return $localize`:@@projects.repos.primaryLabel:${key}:key: is the primary repository`;
  }
  upLabel(key: string): string {
    return $localize`:@@projects.repos.upLabel:Move ${key}:key: up`;
  }
  downLabel(key: string): string {
    return $localize`:@@projects.repos.downLabel:Move ${key}:key: down`;
  }
  removeLabel(key: string): string {
    return $localize`:@@projects.repos.removeLabel:Remove repository ${key}:key:`;
  }
}
