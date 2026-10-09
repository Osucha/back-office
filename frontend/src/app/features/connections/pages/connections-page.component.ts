import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { NzTableModule } from 'ng-zorro-antd/table';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { CONNECTION_CHIP_STYLES } from '../components/chip-styles';
import { capabilityLabels, tokenKindLabel, verificationChip } from '../data/connection-model';
import { ConnectionsApi } from '../data/connections-api.service';
import type { ConnectionVerification, PersonalConnectionSummary } from '../data/connection-types';
import { IdentityService } from '../../../core/auth/identity.service';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { AdminNavComponent } from '../../admin/public-api';
import { ConnectionsListComponent } from '../components/connections-list.component';
import type { ConnectionOwner } from '../data/connection-types';

/**
 * Administration → Connections (ADR 0030): the platform's Jira credentials, each limited to some
 * capabilities and Jira projects. Listed with `integrations.view`, managed with
 * `admin.connections.manage`.
 */
@Component({
  selector: 'app-connections-page',
  imports: [AdminNavComponent, ConnectionsListComponent, PageHeaderComponent, NzTableModule, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.connections"
        title="Connections"
        i18n-title="@@connections.page.title"
        subtitle="Jira accounts Back Office uses, each for some capabilities only. Jira steps pick the connection with the least privilege that still covers what they need; projects can add their own."
        i18n-subtitle="@@connections.page.subtitle"
      />
      <app-connections-list
        [owner]="owner"
        [canManage]="canManage()"
        emptyTitle="No platform connections yet."
        i18n-emptyTitle="@@connections.page.empty"
      />
      @if (canManage()) {
        <section class="personal" aria-labelledby="cp-personal">
          <h2 id="cp-personal" i18n="@@connections.page.personalTitle">Personal connections</h2>
          <p class="lede" i18n="@@connections.page.personalLede">
            Connections people keep for themselves (My account → Connections). They are used only with
            their owner's approval or run start; administrators see that they exist, not their e-mail
            or token, and cannot use them.
          </p>
          @if (!personal().length) {
            <p class="quiet" i18n="@@connections.page.personalEmpty">Nobody has a personal connection.</p>
          } @else {
            <nz-table [nzData]="personal()" [nzShowPagination]="false" nzSize="small">
              <thead>
                <tr>
                  <th i18n="@@connections.page.personal.owner">Owner</th>
                  <th i18n="@@connections.page.personal.connection">Connection</th>
                  <th i18n="@@connections.page.personal.capabilities">Capabilities</th>
                  <th i18n="@@connections.page.personal.token">Token</th>
                  <th i18n="@@connections.page.personal.check">Last check</th>
                </tr>
              </thead>
              <tbody>
                @for (p of personal(); track p.id) {
                  <tr>
                    <td>{{ p.ownerName }}</td>
                    <td>
                      <span class="cell-title">{{ p.name }}</span>
                      <div class="key">{{ p.siteUrl }}</div>
                    </td>
                    <td>{{ caps(p) }}</td>
                    <td>
                      {{ kind(p.tokenKind) }}
                      @if (p.secretExpiresAt) {
                        <span class="quiet">· {{ p.secretExpiresAt | date: 'mediumDate' }}</span>
                      }
                    </td>
                    <td>
                      <span class="cchip" [class]="check(p).tone">{{ check(p).label }}</span>
                    </td>
                  </tr>
                }
              </tbody>
            </nz-table>
          }
        </section>
      }
    </div>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    CONNECTION_CHIP_STYLES,
    `
      .personal {
        margin-top: 28px;
        max-width: 1180px;
      }
      .personal h2 {
        font-size: 15px;
        font-weight: 600;
        margin: 0 0 4px;
      }
      .lede {
        margin: 0 0 12px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        max-width: 80ch;
      }
      .quiet {
        color: var(--bo-ink-3);
        font-size: 12.5px;
      }
    `,
  ],
})
export class ConnectionsPageComponent {
  private readonly identity = inject(IdentityService);
  readonly owner: ConnectionOwner = { kind: 'platform' };
  readonly canManage = computed(() => {
    this.identity.me();
    return this.identity.can('admin.connections.manage');
  });

  private readonly api = inject(ConnectionsApi);
  readonly personal = signal<PersonalConnectionSummary[]>([]);
  readonly caps = (p: PersonalConnectionSummary) => capabilityLabels(p.capabilities ?? []);
  readonly kind = tokenKindLabel;
  readonly check = (p: PersonalConnectionSummary) =>
    verificationChip(p.verificationStatus ? ({ status: p.verificationStatus } as ConnectionVerification) : null);

  constructor() {
    effect(() => {
      if (!this.canManage()) return;
      untracked(() =>
        this.api.personalSummaries().subscribe({
          next: (list) => this.personal.set(list),
          error: () => this.personal.set([]),
        }),
      );
    });
    inject(BreadcrumbService).set([
      { label: $localize`:@@connections.page.crumbAdmin:Administration`, link: '/admin' },
      { label: $localize`:@@connections.page.crumb:Connections` },
    ]);
  }
}
