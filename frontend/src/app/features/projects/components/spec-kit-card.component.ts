import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzMessageService } from 'ng-zorro-antd/message';
import { ProjectsApi } from '../../../core/api/projects.service';
import type { SpecKitStatus } from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { MarkdownViewerComponent } from '../../../core/ui/markdown-viewer.component';
import { NotifyService } from '../../../core/ui/notify.service';

/**
 * Project → Settings, "Spec Kit" (ADR 0036): whether the project uses Spec Kit (and whether the
 * platform allows it), the constitution when it does, and "Add Spec Kit workflows" for seeded Spec Kit
 * workflows the project does not have yet (they are only seeded while Spec Kit is on).
 */
@Component({
  selector: 'app-spec-kit-card',
  imports: [NzButtonModule, NzCardModule, HelpLinkComponent, MarkdownViewerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-card [nzTitle]="title" [nzExtra]="help">
      @if (status(); as s) {
        @if (!s.allowedByPlatform) {
          <p class="muted" i18n="@@projects.specKit.platformOff">
            Spec Kit is turned off for the whole platform by an administrator. Spec Kit steps cannot
            run in any project.
          </p>
        } @else if (!s.enabled) {
          <p class="muted" i18n="@@projects.specKit.off">
            Off for this project. Turn it on in the project form to use the specify, plan and tasks
            steps; the designer hides them meanwhile.
          </p>
        } @else {
          <p class="muted" i18n="@@projects.specKit.on">
            On: Spec Kit steps can run and the constitution below is written to .specify/memory
            before each Spec Kit job.
          </p>
          @if (s.missingWorkflows.length) {
            <div class="add">
              <span i18n="@@projects.specKit.missing"
                >Workflows with Spec Kit this project does not have yet:
                {{ s.missingWorkflows.join(', ') }}</span
              >
              @if (canEdit()) {
                <button
                  nz-button
                  nzType="primary"
                  nzSize="small"
                  type="button"
                  [nzLoading]="adding()"
                  (click)="addWorkflows()"
                  i18n="@@projects.specKit.add"
                >
                  Add Spec Kit workflows
                </button>
              }
            </div>
          }
        }
      }
      @if (status()?.enabled) {
        @if (constitution()) {
          <app-markdown [source]="constitution()" />
        } @else {
          <span class="muted" i18n="@@projects.detail.noConstitution"
            >No constitution. Spec Kit runs without one.</span
          >
        }
      }
    </nz-card>
    <ng-template #help><bo-help-link topic="project.specKit" /></ng-template>
  `,
  styles: [
    `
      .muted {
        color: var(--bo-ink-3);
        margin: 0 0 8px;
      }
      .add {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        align-items: center;
        padding: 8px 10px;
        margin-bottom: 10px;
        border-radius: 8px;
        background: var(--bo-you-soft);
        color: var(--bo-ink);
      }
    `,
  ],
})
export class SpecKitCardComponent {
  private readonly api = inject(ProjectsApi);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);

  readonly projectId = input.required<string>();
  /** Re-reads the status when the project was saved (its `updatedAt`). */
  readonly version = input<string | null | undefined>(null);
  readonly constitution = input('');
  /** `workflow.edit`: may add the Spec Kit workflows. */
  readonly canEdit = input(false);
  /** Workflows were added (the host reloads its list). */
  readonly workflowsAdded = output<void>();

  readonly status = signal<SpecKitStatus | null>(null);
  readonly adding = signal(false);
  readonly title = $localize`:@@projects.specKit.title:Spec Kit`;

  constructor() {
    effect(() => {
      const id = this.projectId();
      this.version();
      untracked(() =>
        this.api.specKit(id).subscribe({
          next: (s) => this.status.set(s),
          error: () => this.status.set(null),
        }),
      );
    });
  }

  addWorkflows(): void {
    this.adding.set(true);
    this.api.addSpecKitWorkflows(this.projectId()).subscribe({
      next: (added) => {
        this.adding.set(false);
        this.status.update((s) => (s ? { ...s, missingWorkflows: [] } : s));
        this.message.success(
          $localize`:@@projects.specKit.added:Added: ${added.map((w) => w.name).join(', ')}:names:`,
        );
        this.workflowsAdded.emit();
      },
      error: (err: unknown) => {
        this.adding.set(false);
        this.notify.error(
          $localize`:@@projects.specKit.addFailed:Could not add the Spec Kit workflows`,
          problemMessage(err),
        );
      },
    });
  }
}
