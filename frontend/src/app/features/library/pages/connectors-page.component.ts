import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { IdentityService } from '../../../core/auth/identity.service';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ConnectorsPanelComponent } from '../components/connectors-panel.component';
import { LibraryNavComponent } from '../components/library-nav.component';

/**
 * Library: connectors (ADR 0064). The platform connectors every project can use: MCP servers with their credentials.
 * Administrators (`library.manage`) add and change them; projects add their own in the project tab Connectors.
 */
@Component({
  selector: 'app-connectors-page',
  imports: [
    NzButtonModule,
    NzIconModule,
    ConnectorsPanelComponent,
    LibraryNavComponent,
    PageHeaderComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        title="Library"
        i18n-title="@@library.connectors.header.title"
        subtitle="Connectors are the tools agents use: MCP servers with their credentials. Platform connectors are available in every project; a project can add its own."
        i18n-subtitle="@@library.connectors.header.subtitle"
      >
        @if (canManage()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <span i18n="@@library.connectors.add">Add connector</span>
          </button>
        }
        <app-library-nav boHeaderBelow active="connectors" />
      </bo-page-header>
      <app-connectors-panel [canManage]="canManage()" />
    </div>
  `,
})
export class ConnectorsPageComponent {
  private readonly identity = inject(IdentityService);
  private readonly panel = viewChild.required(ConnectorsPanelComponent);
  readonly canManage = computed(() => this.identity.can('library.manage'));

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@library.connectors.crumb.library:Library`, link: '/library/templates' },
      { label: $localize`:@@library.connectors.crumb.connectors:Connectors` },
    ]);
  }

  create(): void {
    this.panel().create();
  }
}
