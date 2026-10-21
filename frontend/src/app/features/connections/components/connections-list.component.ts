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
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import {
  capabilityLabel,
  expiryChip,
  isWriteCapability,
  sortCapabilities,
  tokenKindLabel,
  verificationChip,
} from '../data/connection-model';
import type { Connection, ConnectionOwner } from '../data/connection-types';
import { ConnectionsApi } from '../data/connections-api.service';
import { CONNECTION_CHIP_STYLES } from './chip-styles';
import { ConnectionEditorComponent, type EditorMode } from './connection-editor.component';
import { VerificationResultComponent } from './verification-result.component';

/**
 * The connections of the platform or of one project: list with capability, check, expiry and legacy
 * chips; Test, Edit, Replace token and Delete for those who may manage them. Shared by
 * Administration → Connections and the project's Connections tab.
 */
@Component({
  selector: 'app-connections-list',
  imports: [
    DatePipe,
    FormsModule,
    NzButtonModule,
    NzDropDownModule,
    NzIconModule,
    NzInputModule,
    NzTableModule,
    NzTooltipModule,
    EmptyComponent,
    HelpLinkComponent,
    SkeletonComponent,
    SortHeaderDirective,
    ConnectionEditorComponent,
    VerificationResultComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (legacy(); as l) {
      <div class="legacy-notice" role="note">
        <nz-icon nzType="warning" aria-hidden="true" />
        <div>
          <strong i18n="@@connections.legacy.noticeTitle">Legacy Jira credential in use</strong>
          <p i18n="@@connections.legacy.notice">
            This credential comes from environment variables and has every capability. Migrate to
            scoped connections: create a reader and a writer connection, then remove JIRA_API_TOKEN.
          </p>
          <bo-help-link topic="admin.connections" variant="text"
            ><span i18n="@@connections.legacy.howToMigrate">How to migrate</span></bo-help-link
          >
        </div>
      </div>
    }

    @if (canManage() && connections().length) {
      <div class="toolbar">
        <button nz-button nzType="primary" type="button" (click)="openEditor('create', null)">
          <nz-icon nzType="plus" /> <span i18n="@@connections.list.add">Add connection</span>
        </button>
      </div>
    }

    @if (loading() && !loaded()) {
      <bo-skeleton
        variant="table"
        [rows]="3"
        [columns]="5"
        label="Loading connections"
        i18n-label="@@connections.list.loading"
      />
    } @else if (!connections().length) {
      <bo-empty
        [title]="emptyTitle() ?? text.emptyTitle"
        [detail]="text.emptyDetail"
        [actionLabel]="canManage() ? text.add : null"
        help="connections.leastPrivilege"
        guideLabel="Least privilege, step by step"
        i18n-guideLabel="@@connections.list.emptyGuide"
        (action)="openEditor('create', null)"
      />
    } @else {
      <nz-table [nzData]="rows()" [nzShowPagination]="false" nzSize="middle">
        <thead>
          <tr>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="name"
              i18n="@@connections.list.col.connection"
            >
              Connection
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="capabilities"
              nzWidth="220px"
              i18n="@@connections.list.col.capabilities"
            >
              Capabilities
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="projects"
              nzWidth="166px"
              i18n="@@connections.list.col.projects"
            >
              Allowed projects
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="status"
              nzWidth="200px"
              i18n="@@connections.list.col.status"
            >
              Status
            </th>
            <th nzWidth="190px" nzAlign="right">
              <span class="sr-only" i18n="@@connections.list.col.actions">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          @for (c of rows(); track c.id) {
            <tr>
              <td>
                <div class="cell-main">
                  <div class="cell-line">
                    <span class="cell-title">{{ c.name }}</span>
                    @if (c.legacy) {
                      <span
                        class="cchip warn"
                        [title]="text.legacyTitle"
                        i18n="@@connections.list.legacyChip"
                        >legacy</span
                      >
                    } @else {
                      <span
                        class="cchip"
                        [class.ok]="c.tokenKind === 'SCOPED'"
                        [title]="c.tokenKind === 'SCOPED' ? text.scopedTitle : text.classicTitle"
                        >{{ kindLabel(c.tokenKind) }}</span
                      >
                    }
                  </div>
                  <span class="key">{{ c.siteUrl }}</span>
                  <span class="cell-sub">{{ c.accountEmail }}</span>
                </div>
              </td>
              <td>
                <span class="cchips">
                  @for (cap of caps(c); track cap) {
                    <span class="cchip cap" [title]="isWrite(cap) ? text.write : text.read">{{
                      capLabel(cap)
                    }}</span>
                  }
                </span>
              </td>
              <td>
                @if (c.allowedProjectKeys.length) {
                  <span class="key">{{ c.allowedProjectKeys.join(', ') }}</span>
                } @else {
                  <span class="quiet" i18n="@@connections.list.anyProject">any project</span>
                }
              </td>
              <td>
                <span class="cchips">
                  <span class="cchip" [class]="vchip(c).tone">{{ vchip(c).label }}</span>
                  @if (echip(c); as e) {
                    <span class="cchip" [class]="e.tone" [title]="e.title ?? ''">{{
                      e.label
                    }}</span>
                  }
                </span>
              </td>
              <td nzAlign="right">
                <div class="row-actions">
                  <button
                    nz-button
                    nzSize="small"
                    nzType="text"
                    type="button"
                    (click)="toggle(c.id)"
                    [attr.aria-expanded]="expanded().has(c.id)"
                    [attr.aria-label]="detailsLabel(c)"
                    nz-tooltip
                    [nzTooltipTitle]="text.details"
                  >
                    <nz-icon [nzType]="expanded().has(c.id) ? 'up' : 'down'" />
                  </button>
                  @if (canManage()) {
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      [nzLoading]="busy() === c.id"
                      (click)="test(c)"
                      i18n="@@connections.list.test"
                    >
                      Test
                    </button>
                    @if (!c.legacy) {
                      <button
                        nz-button
                        nzSize="small"
                        nzType="text"
                        type="button"
                        nz-dropdown
                        [nzDropdownMenu]="menu"
                        nzTrigger="click"
                        nzPlacement="bottomRight"
                        [attr.aria-label]="moreLabel(c)"
                      >
                        <nz-icon nzType="more" />
                      </button>
                      <nz-dropdown-menu #menu="nzDropdownMenu">
                        <ul nz-menu>
                          <li nz-menu-item (click)="openEditor('edit', c)">
                            <nz-icon nzType="edit" />
                            <span i18n="@@connections.list.edit">Edit</span>
                          </li>
                          <li nz-menu-item (click)="openEditor('rotate', c)">
                            <nz-icon nzType="key" />
                            <span i18n="@@connections.list.replaceToken">Replace token</span>
                          </li>
                          <li nz-menu-item nzDanger (click)="remove(c)">
                            <nz-icon nzType="delete" />
                            <span i18n="@@connections.list.delete">Delete</span>
                          </li>
                        </ul>
                      </nz-dropdown-menu>
                    }
                  }
                </div>
              </td>
            </tr>
            <tr [nzExpand]="expanded().has(c.id)">
              <div class="details">
                @if (c.verification) {
                  <app-verification-result
                    [verification]="c.verification"
                    [capabilities]="c.capabilities"
                  />
                } @else {
                  <p class="quiet" i18n="@@connections.list.notChecked">Not checked yet.</p>
                }
                @if (c.legacy) {
                  <p class="quiet small" i18n="@@connections.list.legacyDetail">
                    Results of the legacy credential are not stored; test it again to see them.
                  </p>
                }
                @if (canManage()) {
                  <div class="test-row">
                    <label [attr.for]="'sample-' + c.id" i18n="@@connections.list.testInProject"
                      >Test in Jira project</label
                    >
                    <input
                      nz-input
                      nzSize="small"
                      class="mono sample"
                      [id]="'sample-' + c.id"
                      [ngModel]="sample()[c.id] ?? ''"
                      (ngModelChange)="setSample(c.id, $event)"
                      placeholder="SHOP"
                      i18n-placeholder="@@connections.list.samplePlaceholder"
                    />
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      [nzLoading]="busy() === c.id"
                      (click)="test(c, sample()[c.id])"
                      i18n="@@connections.list.testThere"
                    >
                      Test there
                    </button>
                  </div>
                }
                <p class="quiet small">
                  @if (c.secretRotatedAt) {
                    <span i18n="@@connections.list.rotated"
                      >Token replaced {{ c.secretRotatedAt | date: 'mediumDate' }}.</span
                    >
                  }
                  @if (c.secretExpiresAt) {
                    <span i18n="@@connections.list.expires"
                      >Token expires {{ c.secretExpiresAt | date: 'mediumDate' }}.</span
                    >
                  }
                  @if (c.updatedBy?.displayName) {
                    <span i18n="@@connections.list.updatedBy"
                      >Last changed by {{ c.updatedBy?.displayName }}.</span
                    >
                  }
                </p>
              </div>
            </tr>
          }
        </tbody>
      </nz-table>
    }

    <app-connection-editor
      [open]="!!editing()"
      [owner]="owner()"
      [connection]="editing()?.connection ?? null"
      [mode]="editing()?.mode ?? 'create'"
      (saved)="onSaved($event)"
      (closed)="editing.set(null)"
    />
  `,
  styles: [
    RELAY_TABLE_STYLES,
    CONNECTION_CHIP_STYLES,
    `
      .toolbar {
        display: flex;
        justify-content: flex-end;
        margin-bottom: 12px;
      }
      .row-actions {
        display: inline-flex;
        gap: 4px;
        align-items: center;
      }
      .legacy-notice {
        display: flex;
        gap: 10px;
        align-items: flex-start;
        padding: 10px 14px;
        margin-bottom: 14px;
        border-radius: var(--bo-radius-card);
        background: var(--bo-you-soft);
        color: var(--bo-ink);
        font-size: 13px;
      }
      .legacy-notice nz-icon {
        color: var(--bo-you-ink);
        margin-top: 3px;
      }
      .legacy-notice p {
        margin: 2px 0 4px;
        color: var(--bo-ink-2);
      }
      .details {
        display: flex;
        flex-direction: column;
        gap: 8px;
        padding: 4px 8px;
      }
      .test-row {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 12px;
        flex-wrap: wrap;
      }
      .sample {
        width: 120px;
      }
      .small {
        font-size: 12px;
        margin: 0;
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
      }
    `,
  ],
})
export class ConnectionsListComponent {
  private readonly api = inject(ConnectionsApi);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly confirm = inject(ConfirmService);

  readonly owner = input.required<ConnectionOwner>();
  readonly canManage = input(false);
  /** Title of the empty state (defaults to a generic one). */
  readonly emptyTitle = input<string | null>(null);

  /** The list after a connection was added, changed, tested or deleted. */
  readonly changed = output<Connection[]>();

  readonly connections = signal<Connection[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly busy = signal<string | null>(null);
  readonly expanded = signal<ReadonlySet<string>>(new Set());
  readonly sample = signal<Partial<Record<string, string>>>({});
  readonly editing = signal<{ mode: EditorMode; connection: Connection | null } | null>(null);

  readonly legacy = computed(() => this.connections().find((c) => c.legacy) ?? null);

  readonly capLabel = capabilityLabel;
  readonly kindLabel = tokenKindLabel;
  readonly isWrite = isWriteCapability;
  readonly vchip = (c: Connection) => verificationChip(c.verification);
  readonly echip = (c: Connection) => expiryChip(c);
  readonly caps = (c: Connection) => sortCapabilities(c.capabilities ?? []);

  /** Column sorting (URL `?connectionSort=`: the list is embedded in pages with tables of their own). */
  readonly sort = tableSort<Connection>(
    {
      name: (c) => c.name,
      capabilities: (c) =>
        this.caps(c)
          .map((cap) => this.capLabel(cap))
          .join(', '),
      projects: (c) => c.allowedProjectKeys.join(', '),
      status: (c) => this.vchip(c).label,
    },
    { param: 'connectionSort' },
  );
  readonly rows = computed(() => this.sort.apply(this.connections()));

  readonly text = {
    emptyTitle: $localize`:@@connections.list.emptyTitle:No connections yet.`,
    emptyDetail: $localize`:@@connections.list.emptyDetail:A connection is a Jira account for some capabilities only. Give each one the least privilege it needs: a reader for fetching issues, a writer for comments and transitions.`,
    add: $localize`:@@connections.list.addAction:Add connection`,
    details: $localize`:@@connections.list.details:Check details`,
    read: $localize`:@@connections.list.readCap:read-only capability`,
    write: $localize`:@@connections.list.writeCap:write capability`,
    legacyTitle: $localize`:@@connections.list.legacyTitle:From the JIRA_URL / JIRA_EMAIL / JIRA_API_TOKEN environment variables: read-only here, every capability.`,
    scopedTitle: $localize`:@@connections.list.scopedTitle:Atlassian API token with scopes: it can only do what its scopes allow.`,
    classicTitle: $localize`:@@connections.list.classicTitle:Classic Atlassian API token: it can do everything its account can.`,
  };

  constructor() {
    effect(() => {
      this.owner();
      untracked(() => this.load());
    });
  }

  detailsLabel(c: Connection): string {
    return $localize`:@@connections.list.detailsLabel:Check details of ${c.name}:name:`;
  }

  moreLabel(c: Connection): string {
    return $localize`:@@connections.list.moreLabel:More actions for ${c.name}:name:`;
  }

  load(): void {
    this.loading.set(true);
    this.api.list(this.owner()).subscribe({
      next: (list) => {
        this.connections.set(list);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  private set(list: Connection[]): void {
    this.connections.set(list);
    this.changed.emit(list);
  }

  private replace(c: Connection): void {
    const list = this.connections();
    this.set(
      list.some((x) => x.id === c.id) ? list.map((x) => (x.id === c.id ? c : x)) : [...list, c],
    );
  }

  toggle(id: string): void {
    this.expanded.update((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  setSample(id: string, value: string): void {
    this.sample.update((m) => ({ ...m, [id]: (value ?? '').toUpperCase() }));
  }

  openEditor(mode: EditorMode, connection: Connection | null): void {
    this.editing.set({ mode, connection });
  }

  onSaved(c: Connection): void {
    this.replace(c);
  }

  test(c: Connection, projectKey?: string | null): void {
    this.busy.set(c.id);
    this.api.verify(this.owner(), c.id, projectKey?.trim() || null).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.replace(updated);
        this.expanded.update((s) => new Set([...s, c.id]));
      },
      error: (err: unknown) => {
        this.busy.set(null);
        this.showError(
          $localize`:@@connections.list.testFailed:The connection could not be tested`,
          err,
        );
      },
    });
  }

  async remove(c: Connection): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@connections.list.deleteTitle:Delete connection ${c.name}:name:?`,
      $localize`:@@connections.list.deleteText:Jira steps that use it switch to another connection with the needed capabilities, or fail when there is none. Steps that pin it fail.`,
      $localize`:@@connections.list.deleteOk:Delete`,
    );
    if (!ok) return;
    this.busy.set(c.id);
    this.api.remove(this.owner(), c.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.set(this.connections().filter((x) => x.id !== c.id));
        this.message.success($localize`:@@connections.list.deleted:Connection deleted`);
      },
      error: () => this.busy.set(null),
    });
  }

  private showError(title: string, err: unknown): void {
    if (err instanceof HttpErrorResponse && [0, 401, 403].includes(err.status)) return;
    this.notify.error(title, problemMessage(err));
  }
}
