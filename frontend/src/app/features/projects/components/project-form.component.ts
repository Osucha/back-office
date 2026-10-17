import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDividerModule } from 'ng-zorro-antd/divider';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import type { ModelProfile, Project, ProjectInput } from '../../../core/api/types';
import { reportFormError } from '../../../core/forms/server-errors';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { NotifyService } from '../../../core/ui/notify.service';
import { projectRepositories } from '../../../core/ui/repositories';
import { repositoriesArray, repositoriesValue, repositoryGroup } from '../data/repositories-form';
import { ProjectRepositoriesEditorComponent } from './project-repositories-editor.component';

/** Create / edit form for a project. Emits the ProjectInput on submit; the parent calls the API. */
@Component({
  selector: 'app-project-form',
  imports: [
    ReactiveFormsModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzSwitchModule,
    NzButtonModule,
    NzIconModule,
    NzDividerModule,
    HelpLinkComponent,
    ProjectRepositoriesEditorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form nz-form nzLayout="vertical" [formGroup]="form" (ngSubmit)="submit()">
      @if (sections() !== 'sources') {
      <div class="grid">
        <nz-form-item>
          <nz-form-label nzFor="p-key" i18n="@@projects.form.keyOptional">Short code</nz-form-label>
          <nz-form-control
            [nzErrorTip]="keyTip"
            [nzExtra]="project() ? text.keyRenameHint : text.keyHint"
          >
            <input
              nz-input
              id="p-key"
              formControlName="key"
              [placeholder]="project() ? examples.key : text.keyGenerated"
              class="mono"
              style="text-transform: uppercase"
            />
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzRequired nzFor="p-name" i18n="@@projects.form.name">Name</nz-form-label>
          <nz-form-control [nzErrorTip]="nameTip">
            <input
              nz-input
              id="p-name"
              formControlName="name"
              placeholder="Shop API"
              i18n-placeholder="@@projects.form.namePlaceholder"
            />
          </nz-form-control>
        </nz-form-item>
      </div>
      <nz-form-item>
        <nz-form-label nzFor="p-desc" i18n="@@projects.form.description">Description</nz-form-label>
        <nz-form-control>
          <textarea
            nz-input
            id="p-desc"
            formControlName="description"
            [nzAutosize]="{ minRows: 2, maxRows: 4 }"
          ></textarea>
        </nz-form-control>
      </nz-form-item>

      }
      @if (sections() === 'all') {
      <nz-divider
        nzText="Repositories"
        i18n-nzText="@@projects.form.repositories"
        nzOrientation="left"
        nzPlain
      />
      }
      @if (sections() !== 'settings') {
      <app-project-repositories-editor
        [array]="form.controls.repositories"
        [readonly]="readonly()"
      />
      }
      @if (sections() !== 'sources') {

      <nz-divider
        nzText="Issue tracker (optional)"
        i18n-nzText="@@projects.form.issueTracker"
        nzOrientation="left"
        nzPlain
      />
      <nz-form-item>
        <nz-form-control>
          <nz-switch
            formControlName="jiraEnabled"
            nzCheckedChildren="Jira"
            nzUnCheckedChildren="none"
            i18n-nzUnCheckedChildren="@@projects.form.jiraNone"
          />
          <span class="muted" style="margin-left: 8px" i18n="@@projects.form.jiraHint"
            >Needed by fetch-issue / notify / transition tasks.</span
          >
          <bo-help-link topic="project.jira" style="margin-left: 4px" />
        </nz-form-control>
      </nz-form-item>
      @if (form.controls.jiraEnabled.value) {
        <div class="grid" formGroupName="issueTracker">
          <nz-form-item>
            <nz-form-label nzRequired nzFor="p-jira" i18n="@@projects.form.jiraProjectKey"
              >Jira project key</nz-form-label
            >
            <nz-form-control [nzErrorTip]="text.jiraKeyRequired">
              <input
                nz-input
                id="p-jira"
                formControlName="jiraProjectKey"
                class="mono"
                style="text-transform: uppercase"
                [placeholder]="examples.jiraKey"
              />
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="p-jql" i18n="@@projects.form.jql">JQL filter</nz-form-label>
            <nz-form-control
              nzExtra="Used by the Jira intake watcher (below); Back Office adds the Jira project and the time window."
              i18n-nzExtra="@@projects.form.jqlHint"
            >
              <input
                nz-input
                id="p-jql"
                formControlName="jql"
                [placeholder]="examples.jql"
                class="mono"
              />
            </nz-form-control>
          </nz-form-item>
        </div>
      }

      <nz-divider
        nzText="Defaults"
        i18n-nzText="@@projects.form.defaults"
        nzOrientation="left"
        nzPlain
      />
      <div class="grid" formGroupName="defaults">
        <nz-form-item>
          <nz-form-label nzFor="p-model" i18n="@@projects.form.defaultModel"
            >Default model profile</nz-form-label
          >
          <nz-form-control
            nzExtra="Used by LLM-backed tasks without an explicit profile."
            i18n-nzExtra="@@projects.form.defaultModelHint"
          >
            <nz-select
              id="p-model"
              formControlName="modelProfileId"
              nzAllowClear
              nzShowSearch
              nzPlaceHolder="Select a model"
              i18n-nzPlaceHolder="@@projects.form.selectModel"
              [nzLoading]="profilesLoading()"
            >
              @for (p of profiles(); track p.id) {
                <nz-option
                  [nzValue]="p.id"
                  [nzLabel]="p.displayName + ' (' + p.ref.modelId + ')'"
                />
              }
            </nz-select>
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzFor="p-review-model" i18n="@@projects.form.reviewModel"
            >Review model profile</nz-form-label
          >
          <nz-form-control
            nzExtra="Used by code-review tasks for this project."
            i18n-nzExtra="@@projects.form.reviewModelHint"
          >
            <nz-select
              id="p-review-model"
              formControlName="reviewModelProfileId"
              nzAllowClear
              nzShowSearch
              nzPlaceHolder="Same as default"
              i18n-nzPlaceHolder="@@projects.form.sameAsDefault"
              [nzLoading]="profilesLoading()"
            >
              @for (p of profiles(); track p.id) {
                <nz-option
                  [nzValue]="p.id"
                  [nzLabel]="p.displayName + ' (' + p.ref.modelId + ')'"
                />
              }
            </nz-select>
          </nz-form-control>
        </nz-form-item>
      </div>

      <nz-divider
        nzText="Spec Kit"
        i18n-nzText="@@projects.form.specKit"
        nzOrientation="left"
        nzPlain
      />
      <nz-form-item>
        <nz-form-control>
          <nz-switch id="p-speckit" formControlName="specKit" />
          <label for="p-speckit" class="muted" style="margin-left: 8px" i18n="@@projects.form.specKitHint"
            >Use Spec Kit in this project: specify, plan and tasks steps and the constitution. Off: the
            designer hides the Spec Kit steps and runs refuse them.</label
          >
          @if (!specKitAllowed()) {
            <div class="muted" i18n="@@projects.form.specKitDisabled">
              An administrator turned Spec Kit off for the whole platform; this setting has no effect
              until it is allowed again.
            </div>
          }
        </nz-form-control>
      </nz-form-item>
      @if (form.controls.specKit.value) {
      <nz-form-item>
        <nz-form-label nzFor="p-constitution" i18n="@@projects.form.constitutionLabel"
          >Spec Kit constitution (markdown)</nz-form-label
        >
        <nz-form-control
          nzExtra="Written to .specify/memory/constitution.md before spec, plan and implement tasks."
          i18n-nzExtra="@@projects.form.constitutionHint"
        >
          <textarea
            nz-input
            id="p-constitution"
            class="mono-textarea"
            formControlName="constitution"
            [nzAutosize]="{ minRows: 8, maxRows: 30 }"
            placeholder="# Constitution&#10;&#10;## Principles&#10;- Hexagonal architecture ..."
            i18n-placeholder="@@projects.form.constitutionPlaceholder"
          ></textarea>
        </nz-form-control>
      </nz-form-item>
      }
      }

      @if (!readonly()) {
        <div class="form-actions">
          @if (cancellable()) {
            <button
              nz-button
              type="button"
              (click)="cancelled.emit()"
              i18n="@@projects.form.cancel"
            >
              Cancel
            </button>
          }
          <button
            nz-button
            nzType="primary"
            type="submit"
            [nzLoading]="saving()"
            [disabled]="form.invalid"
          >
            <nz-icon nzType="save" />
            @if (project()) {
              <span i18n="@@projects.form.saveChanges">Save changes</span>
            } @else {
              <span i18n="@@projects.form.create">Create project</span>
            }
          </button>
        </div>
      }
    </form>
    <ng-template #keyTip let-control>
      {{ control.errors?.server || text.keyPattern }}
    </ng-template>
    <ng-template #nameTip let-control>
      {{ control.errors?.server || text.nameRequired }}
    </ng-template>
  `,
  styles: [
    `
      .grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0 16px;
      }
      .span-2 {
        grid-column: span 2;
      }
      .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 8px;
        margin-top: 8px;
      }
      nz-divider {
        margin: 8px 0 12px;
      }
    `,
  ],
})
export class ProjectFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly modelProfiles = inject(ModelProfilesApi);
  private readonly notify = inject(NotifyService);

  /** Existing project for edit mode; null creates. */
  readonly project = input<Project | null>(null);
  readonly saving = input(false);
  readonly cancellable = input(true);
  /** Without `project.manage`: every control disabled, no save button. */
  readonly readonly = input(false);
  /** ADR 0036: the platform allows Spec Kit (otherwise the switch is shown with a note). */
  readonly specKitAllowed = input(true);
  /**
   * Which part of the form is shown (ADR 0043): `all` (create), `settings` (everything but the repositories)
   * or `sources` (only the repositories, the project tab Sources). Hidden controls keep their values, so a
   * save always sends the whole project.
   */
  readonly sections = input<'all' | 'settings' | 'sources'>('all');
  /** Failed save from the parent: field errors go onto the controls, the rest into a notification. */
  readonly serverError = input<unknown>(null);
  readonly saved = output<ProjectInput>();
  readonly cancelled = output<void>();

  readonly profiles = signal<ModelProfile[]>([]);
  readonly profilesLoading = signal(true);

  readonly text = {
    keyPattern: $localize`:@@projects.form.keyPattern:Uppercase letters, digits and underscore, 2-16 chars (e.g. SHOP)`,
    keyHint: $localize`:@@projects.form.keyHint:Back Office's own short code (CI logins, MCP, templates), not the Jira key. Leave empty to generate it from the name.`,
    keyRenameHint: $localize`:@@projects.form.keyRenameHint:Back Office's own short code, not the Jira key. Changing it does not touch Jira; past runs keep the old code.`,
    keyGenerated: $localize`:@@projects.form.keyGenerated:generated from the name`,
    jiraKeyRequired: $localize`:@@projects.form.jiraKeyRequired:Enter the key of the Jira project (the prefix of its issue keys, e.g. LFV in LFV-123)`,
    nameRequired: $localize`:@@projects.form.nameRequired:Name is required`,
  };
  /** Example values in placeholders: code and data, the same in every language. */
  readonly examples = {
    key: 'SHOP',
    jiraKey: 'LFV',
    jql: 'labels = "ai-ready" AND status = "To Do"', // i18n-ignore
  };

  readonly form = this.fb.nonNullable.group({
    // optional: generated from the name on create, unchanged on update when emptied
    key: ['', [Validators.pattern(/^([A-Z][A-Z0-9_]{1,15})?$/)]],
    name: ['', Validators.required],
    description: [''],
    repositories: repositoriesArray(),
    jiraEnabled: [false],
    issueTracker: this.fb.nonNullable.group({
      jiraProjectKey: [''],
      jql: [''],
    }),
    specKit: [false],
    constitution: [''],
    defaults: this.fb.group({
      modelProfileId: this.fb.control<string | null>(null),
      reviewModelProfileId: this.fb.control<string | null>(null),
    }),
  });

  constructor() {
    this.modelProfiles.list(true).subscribe({
      next: (list) => {
        this.profiles.set(list);
        this.profilesLoading.set(false);
      },
      error: () => this.profilesLoading.set(false),
    });
    effect(() => {
      const p = this.project();
      if (!p) return;
      const repos = this.form.controls.repositories;
      repos.clear({ emitEvent: false });
      for (const r of projectRepositories(p)) repos.push(repositoryGroup(r), { emitEvent: false });
      repos.updateValueAndValidity();
      if (this.readonly()) repos.disable({ emitEvent: false });
      this.form.patchValue({
        key: p.key,
        name: p.name,
        description: p.description ?? '',
        jiraEnabled: !!(p.issueTracker?.jiraProjectKey || p.issueTracker?.jql),
        issueTracker: {
          jiraProjectKey: p.issueTracker?.jiraProjectKey ?? '',
          jql: p.issueTracker?.jql ?? '',
        },
        specKit: !!p.specKit,
        constitution: p.constitution ?? '',
        defaults: {
          modelProfileId: p.defaults?.modelProfileId ?? null,
          reviewModelProfileId: p.defaults?.reviewModelProfileId ?? null,
        },
      });
    });
    effect(() => {
      if (this.readonly()) this.form.disable({ emitEvent: false });
      else this.form.enable({ emitEvent: false });
    });
    effect(() => {
      const err = this.serverError();
      if (err)
        reportFormError(
          this.notify,
          this.form,
          err,
          $localize`:@@projects.form.saveFailed:Could not save the project`,
        );
    });
    this.form.controls.key.valueChanges.subscribe((v) => {
      const upper = v.toUpperCase();
      if (upper !== v) this.form.controls.key.setValue(upper, { emitEvent: false });
    });
  }

  submit(): void {
    const jiraKey = this.form.controls.issueTracker.controls.jiraProjectKey;
    // the Jira key is never derived from the project key: required while Jira is on
    if (this.form.controls.jiraEnabled.value && !jiraKey.value.trim()) jiraKey.setErrors({ required: true });
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const input: ProjectInput = {
      key: v.key || undefined,
      name: v.name,
      description: v.description || undefined,
      repositories: repositoriesValue(this.form.controls.repositories),
      issueTracker: v.jiraEnabled
        ? {
            jiraProjectKey: v.issueTracker.jiraProjectKey.trim().toUpperCase(),
            jql: v.issueTracker.jql || null,
          }
        : null,
      // kept (and sent) while Spec Kit is off: turning it on again brings it back
      constitution: v.constitution || undefined,
      specKit: v.specKit,
      defaults: {
        modelProfileId: v.defaults.modelProfileId ?? null,
        reviewModelProfileId: v.defaults.reviewModelProfileId ?? null,
      },
    };
    this.saved.emit(input);
  }
}
