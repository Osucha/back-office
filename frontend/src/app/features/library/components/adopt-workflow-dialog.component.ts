import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type {
  LibraryWorkflow,
  LibraryWorkflowSummary,
  Project,
  WorkflowDefinition,
} from '../../../core/api/types';
import { WorkflowLibraryApi } from '../../../core/api/workflow-library.service';

/**
 * "Add from library" / "Use in project" (ADR 0037): creates a linked copy of a library version in a project. Either
 * the project is fixed (opened from a project) and the entry is chosen, or the entry is fixed (opened from the
 * library) and the project is chosen among those where the caller may edit workflows.
 */
@Component({
  selector: 'app-adopt-workflow-dialog',
  imports: [FormsModule, NzModalModule, NzSelectModule, NzInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-modal
      [nzVisible]="open()"
      nzTitle="Add a workflow from the library"
      i18n-nzTitle="@@library.adopt.title"
      nzOkText="Add workflow"
      i18n-nzOkText="@@library.adopt.ok"
      nzCancelText="Cancel"
      i18n-nzCancelText="@@library.adopt.cancel"
      [nzOkDisabled]="!canSubmit()"
      [nzOkLoading]="busy()"
      (nzOnOk)="submit()"
      (nzOnCancel)="closed.emit()"
    >
      <ng-container *nzModalContent>
        <p class="lead" i18n="@@library.adopt.lead">
          The project gets a linked copy: it runs and can be adjusted like any workflow, its runs
          count towards the library metrics, and new library versions can be taken later. Step
          repositories, the Jira connection and named approvers are set in the project.
        </p>
        @if (!fixedProjectId()) {
          <label class="field-label" for="adopt-project" i18n="@@library.adopt.project"
            >Project</label
          >
          <nz-select
            id="adopt-project"
            nzShowSearch
            style="width: 100%"
            [ngModel]="projectId()"
            (ngModelChange)="projectId.set($event)"
            nzPlaceHolder="Choose a project"
            i18n-nzPlaceHolder="@@library.adopt.projectPlaceholder"
          >
            @for (p of projects(); track p.id) {
              <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' · ' + p.name" />
            }
          </nz-select>
        }
        @if (!fixedLibraryWorkflowId()) {
          <label class="field-label" for="adopt-entry" i18n="@@library.adopt.workflow"
            >Library workflow</label
          >
          <nz-select
            id="adopt-entry"
            nzShowSearch
            style="width: 100%"
            [ngModel]="entryId()"
            (ngModelChange)="selectEntry($event)"
            nzPlaceHolder="Choose a workflow"
            i18n-nzPlaceHolder="@@library.adopt.workflowPlaceholder"
          >
            @for (w of entries(); track w.id) {
              <nz-option
                [nzValue]="w.id"
                [nzLabel]="w.name + ' (' + w.key + ', v' + w.latestVersion + ')'"
              />
            }
          </nz-select>
        }
        <label class="field-label" for="adopt-version" i18n="@@library.adopt.version"
          >Version</label
        >
        <nz-select
          id="adopt-version"
          style="width: 100%"
          [ngModel]="version()"
          (ngModelChange)="version.set($event)"
          [nzDisabled]="!entry()"
        >
          @for (v of entry()?.versions ?? []; track v.version) {
            <nz-option [nzValue]="v.version" [nzLabel]="versionLabel(v.version, v.changeNote)" />
          }
        </nz-select>
        <label class="field-label" for="adopt-name" i18n="@@library.adopt.name"
          >Name in the project</label
        >
        <input
          id="adopt-name"
          nz-input
          [ngModel]="name()"
          (ngModelChange)="name.set($event)"
          [placeholder]="entry()?.name ?? ''"
        />
      </ng-container>
    </nz-modal>
  `,
  styles: [
    `
      .lead {
        margin: 0 0 12px;
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      .field-label {
        display: block;
        margin: 12px 0 4px;
        font-size: 12.5px;
        font-weight: 500;
      }
    `,
  ],
})
export class AdoptWorkflowDialogComponent {
  private readonly api = inject(WorkflowLibraryApi);
  private readonly message = inject(NzMessageService);

  readonly open = input(false);
  /** Set when opened from a project: the entry is chosen. */
  readonly fixedProjectId = input<string | null>(null);
  /** Set when opened from a library entry: the project is chosen. */
  readonly fixedLibraryWorkflowId = input<string | null>(null);
  /** Projects to choose from (where the caller may edit workflows). */
  readonly projects = input<Project[]>([]);
  readonly adopted = output<WorkflowDefinition>();
  readonly closed = output<void>();

  readonly entries = signal<LibraryWorkflowSummary[]>([]);
  readonly entry = signal<LibraryWorkflow | null>(null);
  readonly entryId = signal<string | null>(null);
  readonly projectId = signal<string | null>(null);
  readonly version = signal<number | null>(null);
  readonly name = signal('');
  readonly busy = signal(false);

  readonly targetProject = computed(() => this.fixedProjectId() ?? this.projectId());
  readonly canSubmit = computed(
    () => !!this.targetProject() && !!this.entry() && !!this.version() && !this.busy(),
  );

  constructor() {
    effect(() => {
      if (!this.open()) return;
      const fixed = this.fixedLibraryWorkflowId();
      untracked(() => {
        this.name.set('');
        this.projectId.set(null);
        this.entry.set(null);
        this.version.set(null);
        if (fixed) {
          this.selectEntry(fixed);
        } else {
          this.entryId.set(null);
          this.api.list(false).subscribe((list) => this.entries.set(list));
        }
      });
    });
  }

  selectEntry(id: string | null): void {
    this.entryId.set(id);
    this.entry.set(null);
    this.version.set(null);
    if (!id) return;
    this.api.get(id).subscribe((w) => {
      this.entry.set(w);
      this.version.set(w.latestVersion);
    });
  }

  versionLabel(version: number, note: string | null | undefined): string {
    const latest = version === this.entry()?.latestVersion;
    const head = latest
      ? $localize`:@@library.adopt.latest:v${version}:version: (latest)`
      : `v${version}`;
    return note ? `${head} · ${note}` : head;
  }

  submit(): void {
    const projectId = this.targetProject();
    const entry = this.entry();
    if (!projectId || !entry || !this.canSubmit()) return;
    this.busy.set(true);
    this.api
      .adopt(projectId, {
        libraryWorkflowId: entry.id,
        version: this.version(),
        name: this.name().trim() || null,
      })
      .subscribe({
        next: (definition) => {
          this.busy.set(false);
          this.message.success(
            $localize`:@@library.adopt.done:Workflow "${definition.name}:name:" added to the project`,
          );
          this.adopted.emit(definition);
        },
        error: () => this.busy.set(false),
      });
  }
}
