import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { IdentityService } from '../../../core/auth/identity.service';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import type { ConnectionOwner } from '../data/connection-types';
import { ConnectionsListComponent } from './connections-list.component';

/**
 * My account → Connections (ADR 0035): the caller's personal Jira connections. A personal connection
 * acts as the person in Jira, and only with their own action: a write they approve or a run they
 * start. Nobody else can see or use it; administrators see that it exists. Needs `connections.personal`.
 */
@Component({
  selector: 'app-personal-connections-panel',
  imports: [ConnectionsListComponent, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="lede">
      <span i18n="@@connections.personal.lede"
        >Your own Jira connections. Workflow steps set to act as the person who approves or starts them
        use one of these to comment or move issues as you, and only after your approval or when you
        start the run. Nobody else can see or use them; administrators only see that they exist.</span
      >
      <bo-help-link topic="connections.personal" variant="text"
        ><span i18n="@@connections.personal.help">How personal connections work</span></bo-help-link
      >
    </p>
    @if (allowed()) {
      <app-connections-list
        [owner]="owner"
        [canManage]="true"
        emptyTitle="You have no personal connections."
        i18n-emptyTitle="@@connections.personal.empty"
      />
    } @else {
      <p class="quiet" i18n="@@connections.personal.notAllowed">
        Your role does not include personal connections (permission connections.personal).
      </p>
    }
  `,
  styles: [
    `
      .lede {
        margin: 0 0 12px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        max-width: 80ch;
      }
      .quiet {
        color: var(--bo-ink-3);
        font-size: 13px;
      }
    `,
  ],
})
export class PersonalConnectionsPanelComponent {
  private readonly identity = inject(IdentityService);
  readonly owner: ConnectionOwner = { kind: 'personal' };
  readonly allowed = computed(() => {
    this.identity.me();
    return this.identity.can('connections.personal');
  });
}
