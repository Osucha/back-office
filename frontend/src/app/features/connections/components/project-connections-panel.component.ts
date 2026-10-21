import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import type { ConnectionOwner } from '../data/connection-types';
import { ConnectionsListComponent } from './connections-list.component';
import { ProjectJiraConnectionsComponent } from './project-jira-connections.component';

/**
 * Project tab "Connections" (ADR 0030, ADR 0035): the project's Jira settings (system connection and
 * the list of further connections its steps may use, chosen from the project's and the platform's
 * connections) and the project's own Jira connections (owners manage both with
 * `project.connections.manage`, everyone with `project.view` sees them).
 */
@Component({
  selector: 'app-project-connections-panel',
  imports: [HelpLinkComponent, ConnectionsListComponent, ProjectJiraConnectionsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="block" aria-labelledby="pc-jira">
      <h2 id="pc-jira">
        <span i18n="@@connections.project.jiraTitle">Jira connections of this project</span>
        <bo-help-link topic="project.jiraConnections" />
      </h2>
      <p class="lede" i18n="@@connections.project.jiraLede">
        The system connection is the default of every Jira step. Steps may also name a connection,
        or act as the person who approves or starts them (that person's own connection, never anyone
        else's).
      </p>
      <app-project-jira-connections
        [projectId]="projectId()"
        [canManage]="canManage()"
        [refresh]="changes()"
      />
    </section>

    <section class="block" aria-labelledby="pc-own">
      <h2 id="pc-own">
        <span i18n="@@connections.project.ownTitle">Connections of this project</span>
        <bo-help-link topic="project.connections" />
      </h2>
      <p class="lede" i18n="@@connections.project.lede">
        Jira accounts of this project. Choose one of them, or a platform connection, as the system
        connection above.
      </p>
      <app-connections-list
        [owner]="owner()"
        [canManage]="canManage()"
        emptyTitle="This project has no connections of its own."
        i18n-emptyTitle="@@connections.project.empty"
        (changed)="changes.set(changes() + 1)"
      />
    </section>
  `,
  styles: [
    `
      .block {
        max-width: 1180px;
        margin-bottom: 24px;
      }
      h2 {
        display: flex;
        align-items: center;
        gap: 4px;
        margin: 0 0 4px;
        font-size: 15px;
        font-weight: 600;
      }
      .lede {
        margin: 0 0 12px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        max-width: 80ch;
      }
    `,
  ],
})
export class ProjectConnectionsPanelComponent {
  readonly projectId = input.required<string>();
  /** `project.connections.manage` on this project. */
  readonly canManage = input(false);

  readonly owner = computed<ConnectionOwner>(() => ({
    kind: 'project',
    projectId: this.projectId(),
  }));
  /** Bumped when the project's own connections change: the Jira settings reload their choices. */
  readonly changes = signal(0);
}
