import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ConnectorsApi } from '../../../core/api/connectors.service';
import type {
  Connector,
  ConnectorKind,
  ConnectorTest,
  ProjectRepository,
  Scope,
} from '../../../core/api/types';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { HelpTipComponent } from '../../../core/ui/help-tip.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import {
  CONNECTOR_AUTHS,
  type ConnectorDraft,
  type ConnectorRow,
  connectorAuthLabel,
  connectorDraftOf,
  connectorHost,
  connectorKindLabel,
  connectorKinds,
  connectorPayload,
  connectorProblems,
  connectorRows,
  emptyConnector,
} from '../data/connector-form';

/**
 * Connectors (ADR 0064): the tools agents use. With a `projectId` (project tab Connectors) it lists the project's
 * connectors and, read-only, the platform ones the project can use; without one (library page) the platform
 * connectors. The editor is a side panel: MCP server (URL, authentication, write-only secret) or GitLab read-only (a
 * project repository), the host to allow in the sandbox network policy and the last test.
 */
@Component({
  selector: 'app-connectors-panel',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    NzButtonModule,
    NzCardModule,
    NzCheckboxModule,
    NzFormModule,
    NzIconModule,
    NzInputModule,
    NzRadioModule,
    NzSelectModule,
    NzTableModule,
    NzTooltipModule,
    EmptyComponent,
    HelpTipComponent,
    ProvenanceComponent,
    ReadonlyFormDirective,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (projectId()) {
      <nz-card [nzTitle]="cardTitle" [nzExtra]="cardActions">
        <ng-container *ngTemplateOutlet="list" />
      </nz-card>
      <ng-template #cardTitle
        ><span i18n="@@library.connectors.project.title">Connectors</span>
        <bo-help-tip [text]="text.projectIntro" style="margin-left: 6px"
      /></ng-template>
      <ng-template #cardActions>
        @if (canManage()) {
          <button nz-button nzType="primary" type="button" (click)="create()" data-add-connector>
            <nz-icon nzType="plus" />
            <span i18n="@@library.connectors.add">Add connector</span>
          </button>
        }
      </ng-template>
    } @else {
      <ng-container *ngTemplateOutlet="list" />
    }

    <ng-template #list>
      @if (loaded() && !rows().length) {
        <bo-empty
          title="No connectors yet"
          i18n-title="@@library.connectors.empty.title"
          detail="A connector is a tool agents use: an MCP server, with its credentials, or GitLab read-only. Agents list the connectors they use."
          i18n-detail="@@library.connectors.empty.detail"
        />
      } @else {
        <nz-table
          #t
          [nzData]="rows()"
          [nzLoading]="!loaded()"
          [nzShowPagination]="false"
          nzSize="middle"
          class="relay-table"
        >
          <thead>
            <tr>
              <th i18n="@@library.connectors.col.connector">Connector</th>
              <th nzWidth="170px" i18n="@@library.connectors.col.kind">Kind</th>
              <th i18n="@@library.connectors.col.endpoint">Endpoint</th>
              <th nzWidth="150px" i18n="@@library.connectors.col.test">Last test</th>
              <th nzWidth="190px" i18n="@@library.connectors.col.changed">Changed</th>
            </tr>
          </thead>
          <tbody>
            @for (r of t.data; track r.connector.id) {
              <tr
                class="row"
                [class.shadowed]="r.shadowed"
                tabindex="0"
                [attr.data-connector]="r.connector.key"
                (click)="edit(r)"
                (keydown.enter)="edit(r)"
              >
                <td>
                  <div class="cell-line">
                    <span class="name">{{ r.connector.name }}</span>
                    @if (projectId() && r.platform) {
                      <span class="chip outline" i18n="@@library.connectors.platformChip"
                        >platform</span
                      >
                    }
                    @if (r.shadowed) {
                      <span
                        class="chip"
                        data-shadowed
                        nz-tooltip
                        [nzTooltipTitle]="text.shadowedHelp"
                        i18n="@@library.connectors.shadowed"
                        >replaced by the project's</span
                      >
                    }
                  </div>
                  <div class="mono muted small">{{ r.connector.key }}</div>
                  @if (r.connector.description) {
                    <div class="muted small clamp">{{ r.connector.description }}</div>
                  }
                </td>
                <td>{{ kindLabel(r.connector.kind) }}</td>
                <td class="small">
                  @if (r.connector.kind === 'GITLAB_READ') {
                    <span class="mono">{{ r.connector.repository || text.primaryRepository }}</span>
                  } @else {
                    <span class="mono">{{ hostOf(r.connector) }}</span>
                    <div class="muted">{{ authLabel(r.connector.auth) }}</div>
                  }
                </td>
                <td>
                  @if (r.connector.lastTest; as lt) {
                    <span
                      class="chip"
                      [class.ok]="lt.ok"
                      [class.failed]="!lt.ok"
                      [attr.data-test-ok]="lt.ok"
                      nz-tooltip
                      [nzTooltipTitle]="lt.message || null"
                      >{{ lt.ok ? text.testOk : text.testFailed }}</span
                    >
                    <div><bo-provenance [at]="lt.testedAt" /></div>
                  } @else {
                    <span class="quiet" i18n="@@library.connectors.notTested">not tested</span>
                  }
                </td>
                <td>
                  <bo-provenance [actor]="r.connector.updatedBy" [at]="r.connector.updatedAt" />
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </ng-template>

    <bo-side-panel
      [open]="!!draft()"
      [title]="editing() ? editing()!.connector.name : text.newTitle"
      [subtitle]="editing()?.platform && projectId() ? text.platformReadOnly : null"
      storageKey="backoffice.panelWidth.connector"
      initialFocus="#con-name"
      (closed)="close()"
    >
      @if (draft(); as d) {
        <form nz-form nzLayout="vertical" [boReadonly]="!canEdit()" (ngSubmit)="save()">
          <div class="grid">
            <nz-form-item>
              <nz-form-label nzRequired nzFor="con-name" i18n="@@library.connectors.form.name"
                >Name</nz-form-label
              >
              <nz-form-control>
                <input
                  nz-input
                  id="con-name"
                  name="name"
                  [ngModel]="d.name"
                  (ngModelChange)="patch({ name: $event })"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label
                nzFor="con-key"
                [nzTooltipTitle]="text.keyHelp"
                i18n="@@library.connectors.form.key"
                >Key</nz-form-label
              >
              <nz-form-control>
                <input
                  nz-input
                  id="con-key"
                  name="key"
                  class="mono"
                  [placeholder]="editing() ? '' : text.keyPlaceholder"
                  [ngModel]="d.key"
                  (ngModelChange)="patch({ key: $event })"
                  [readOnly]="!!editing()"
                />
              </nz-form-control>
            </nz-form-item>
          </div>
          <nz-form-item>
            <nz-form-label
              nzFor="con-desc"
              [nzTooltipTitle]="text.descriptionHelp"
              i18n="@@library.connectors.form.description"
              >Description</nz-form-label
            >
            <nz-form-control>
              <textarea
                nz-input
                id="con-desc"
                name="description"
                [nzAutosize]="{ minRows: 2, maxRows: 6 }"
                [ngModel]="d.description"
                (ngModelChange)="patch({ description: $event })"
              ></textarea>
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label i18n="@@library.connectors.form.kind">Kind</nz-form-label>
            <nz-form-control>
              <nz-radio-group
                name="kind"
                [ngModel]="d.kind"
                (ngModelChange)="patch({ kind: $event })"
              >
                @for (k of kinds(); track k) {
                  <label nz-radio-button [nzValue]="k" [attr.data-kind]="k">{{
                    kindLabel(k)
                  }}</label>
                }
              </nz-radio-group>
            </nz-form-control>
          </nz-form-item>

          @if (d.kind === 'MCP_HTTP') {
            <nz-form-item>
              <nz-form-label
                nzRequired
                nzFor="con-url"
                [nzTooltipTitle]="text.urlHelp"
                i18n="@@library.connectors.form.url"
                >URL</nz-form-label
              >
              <nz-form-control>
                <input
                  nz-input
                  id="con-url"
                  name="url"
                  class="mono"
                  [placeholder]="examples.url"
                  [ngModel]="d.url"
                  (ngModelChange)="patch({ url: $event })"
                />
              </nz-form-control>
            </nz-form-item>
            @if (draftHost(); as host) {
              <p class="host" data-host>
                <span i18n="@@library.connectors.form.host">Host to allow:</span>
                <span class="mono">{{ host }}</span>
                <bo-help-tip [text]="text.hostHelp" />
              </p>
            }
            <nz-form-item>
              <nz-form-label [nzTooltipTitle]="text.authHelp" i18n="@@library.connectors.form.auth"
                >Authentication</nz-form-label
              >
              <nz-form-control>
                <nz-radio-group
                  name="auth"
                  [ngModel]="d.auth"
                  (ngModelChange)="patch({ auth: $event })"
                >
                  @for (a of auths; track a) {
                    <label nz-radio-button [nzValue]="a" [attr.data-auth]="a">{{
                      authLabel(a)
                    }}</label>
                  }
                </nz-radio-group>
              </nz-form-control>
            </nz-form-item>
            @if (d.auth === 'HEADER') {
              <nz-form-item>
                <nz-form-label
                  nzRequired
                  nzFor="con-header"
                  i18n="@@library.connectors.form.headerName"
                  >Header name</nz-form-label
                >
                <nz-form-control>
                  <input
                    nz-input
                    id="con-header"
                    name="headerName"
                    class="mono"
                    [placeholder]="examples.header"
                    [ngModel]="d.headerName"
                    (ngModelChange)="patch({ headerName: $event })"
                  />
                </nz-form-control>
              </nz-form-item>
            }
            @if (d.auth !== 'NONE') {
              <nz-form-item>
                <nz-form-label
                  [nzRequired]="!hasSecret()"
                  nzFor="con-secret"
                  [nzTooltipTitle]="text.secretHelp"
                  i18n="@@library.connectors.form.secret"
                  >Secret</nz-form-label
                >
                <nz-form-control>
                  <input
                    nz-input
                    id="con-secret"
                    name="secret"
                    type="password"
                    autocomplete="new-password"
                    [placeholder]="hasSecret() ? text.secretStored : ''"
                    [disabled]="d.removeSecret"
                    [ngModel]="d.secret"
                    (ngModelChange)="patch({ secret: $event })"
                  />
                  @if (hasSecret()) {
                    <label
                      nz-checkbox
                      name="removeSecret"
                      class="remove-secret"
                      data-remove-secret
                      [ngModel]="d.removeSecret"
                      (ngModelChange)="patch({ removeSecret: $event, secret: '' })"
                      ><span i18n="@@library.connectors.form.removeSecret"
                        >Remove secret</span
                      ></label
                    >
                  }
                </nz-form-control>
              </nz-form-item>
            }
          } @else {
            <nz-form-item>
              <nz-form-label
                nzFor="con-repo"
                [nzTooltipTitle]="text.repositoryHelp"
                i18n="@@library.connectors.form.repository"
                >Repository</nz-form-label
              >
              <nz-form-control>
                <nz-select
                  nzId="con-repo"
                  name="repository"
                  nzAllowClear
                  [nzPlaceHolder]="text.primaryRepository"
                  [ngModel]="d.repository || null"
                  (ngModelChange)="patch({ repository: $event ?? '' })"
                >
                  @for (r of repositories(); track r.key) {
                    <nz-option [nzValue]="r.key" [nzLabel]="r.key" />
                  }
                </nz-select>
              </nz-form-control>
            </nz-form-item>
          }

          @if (editing(); as r) {
            <section class="test" data-test-section>
              <div class="test-head">
                <span class="label" i18n="@@library.connectors.form.lastTest">Last test</span>
                <bo-help-tip [text]="text.testHelp" />
                @if (canEdit()) {
                  <button
                    nz-button
                    nzSize="small"
                    type="button"
                    data-test-button
                    [nzLoading]="testing()"
                    (click)="test(r.connector)"
                  >
                    <nz-icon nzType="api" />
                    <span i18n="@@library.connectors.test">Test</span>
                  </button>
                }
              </div>
              @if (r.connector.lastTest; as lt) {
                <p class="result" [class.ok]="lt.ok" [class.failed]="!lt.ok" data-test-result>
                  <strong>{{ lt.ok ? text.testOk : text.testFailed }}</strong>
                  <bo-provenance [at]="lt.testedAt" />
                </p>
                @if (lt.message) {
                  <p class="small message">{{ lt.message }}</p>
                }
                @if (lt.tools?.length) {
                  <div class="chips" data-test-tools>
                    <span class="small muted" i18n="@@library.connectors.tools">Tools:</span>
                    @for (tool of lt.tools; track tool) {
                      <span class="chip mono">{{ tool }}</span>
                    }
                  </div>
                }
              } @else {
                <p class="small muted" i18n="@@library.connectors.notTestedYet">
                  Not tested yet. Save the connector, then test it.
                </p>
              }
            </section>
          }

          @if (canEdit()) {
            @for (p of problems(); track p) {
              <p class="problem">{{ p }}</p>
            }
            <div class="actions">
              <button
                nz-button
                nzType="primary"
                type="submit"
                [nzLoading]="saving()"
                [disabled]="problems().length > 0"
                i18n="@@library.connectors.form.save"
              >
                Save
              </button>
              @if (editing(); as r) {
                <button
                  nz-button
                  nzDanger
                  type="button"
                  (click)="remove(r.connector)"
                  i18n="@@library.connectors.form.delete"
                >
                  Delete
                </button>
              }
            </div>
          }
        </form>
      }
    </bo-side-panel>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .row {
        cursor: pointer;
      }
      .row.shadowed td {
        opacity: 0.6;
      }
      .cell-line {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        align-items: center;
      }
      .name {
        font-weight: 500;
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .clamp {
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .chip.ok,
      .result.ok strong {
        color: var(--bo-done-ink);
      }
      .chip.ok {
        background: var(--bo-done-soft);
      }
      .chip.failed,
      .result.failed strong {
        color: var(--bo-blocked-ink);
      }
      .chip.failed {
        background: var(--bo-blocked-soft);
      }
      .grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 0 12px;
      }
      .host {
        display: flex;
        gap: 6px;
        align-items: center;
        margin: -12px 0 16px;
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .remove-secret {
        margin-top: 6px;
      }
      .test {
        border-top: 1px solid var(--bo-line);
        padding-top: 12px;
        margin-bottom: 16px;
      }
      .test-head {
        display: flex;
        gap: 6px;
        align-items: center;
        margin-bottom: 6px;
      }
      .test-head .label {
        font-weight: 500;
      }
      .test-head button {
        margin-left: auto;
      }
      .result {
        display: flex;
        gap: 8px;
        align-items: baseline;
        margin: 0 0 4px;
      }
      .message {
        margin: 0 0 6px;
        white-space: pre-wrap;
        word-break: break-word;
      }
      .actions {
        display: flex;
        gap: 8px;
      }
      .problem {
        color: var(--bo-blocked-ink);
        font-size: 12px;
        margin: 0 0 8px;
      }
    `,
  ],
})
export class ConnectorsPanelComponent {
  private readonly api = inject(ConnectorsApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);

  /** The project of the tab; null = the platform connectors (library page). */
  readonly projectId = input<string | null>(null);
  /** The project's repositories (GitLab read-only connectors use one of them). */
  readonly repositories = input<readonly ProjectRepository[]>([]);
  /** Whether the user may change connectors of this scope (project: `project.connections.manage`; platform: `library.manage`). */
  readonly canManage = input(false);

  readonly items = signal<Connector[]>([]);
  readonly loaded = signal(false);
  readonly draft = signal<ConnectorDraft | null>(null);
  readonly editing = signal<ConnectorRow | null>(null);
  readonly saving = signal(false);
  readonly testing = signal(false);

  readonly rows = computed(() => connectorRows(this.items(), this.projectId()));
  /** Platform connectors are read-only in a project's tab. */
  readonly canEdit = computed(() => {
    const r = this.editing();
    return this.canManage() && !(r?.platform && this.projectId());
  });
  readonly hasSecret = computed(() => !!this.editing()?.connector.hasSecret);
  readonly kinds = computed<ConnectorKind[]>(() => connectorKinds(!!this.projectId()));
  readonly problems = computed(() => {
    const d = this.draft();
    return d
      ? connectorProblems(d, { hasSecret: this.hasSecret(), projectScope: !!this.projectId() })
      : [];
  });
  readonly draftHost = computed(() => connectorHost(this.draft()?.url));

  readonly auths = CONNECTOR_AUTHS;
  readonly kindLabel = connectorKindLabel;
  readonly authLabel = connectorAuthLabel;
  readonly text = {
    newTitle: $localize`:@@library.connectors.form.newTitle:New connector`,
    platformReadOnly: $localize`:@@library.connectors.platformReadOnly:Platform connector: administrators change it in the library.`,
    projectIntro: $localize`:@@library.connectors.project.intro:Tools the agents of this project use: the project's connectors and the platform's. A project connector replaces the platform connector of the same key. Agents list the connectors they use by key.`,
    shadowedHelp: $localize`:@@library.connectors.shadowed.help:A project connector with the same key replaces this one in this project.`,
    primaryRepository: $localize`:@@library.connectors.primaryRepository:Primary repository`,
    testOk: $localize`:@@library.connectors.test.ok:ok`,
    testFailed: $localize`:@@library.connectors.test.failed:failed`,
    keyHelp: $localize`:@@library.connectors.form.key.help:Agents list connectors by key. Empty: derived from the name. Cannot change later.`,
    keyPlaceholder: $localize`:@@library.connectors.form.key.placeholder:from the name`,
    descriptionHelp: $localize`:@@library.connectors.form.description.help:What the tool is for; shown to people choosing connectors for an agent.`,
    urlHelp: $localize`:@@library.connectors.form.url.help:The MCP endpoint (streamable HTTP), http:// or https://.`,
    hostHelp: $localize`:@@library.connectors.form.host.help:Agents reach this host from the sandbox only when the sandbox network policy allows it. Ask an administrator to add it to the allowed hosts.`,
    authHelp: $localize`:@@library.connectors.form.auth.help:Bearer token sends "Authorization: Bearer <secret>"; custom header sends the secret in a header you name.`,
    secretHelp: $localize`:@@library.connectors.form.secret.help:Stored encrypted and never shown again. The agent process gets it only as an environment variable; it is redacted from every log.`,
    secretStored: $localize`:@@library.connectors.form.secret.stored:stored - leave empty to keep`,
    repositoryHelp: $localize`:@@library.connectors.form.repository.help:The agent uses glab with the read-only CI token of this repository (pipelines, job logs, merge requests). Empty: the primary repository.`,
    testHelp: $localize`:@@library.connectors.test.help:Tests from Back Office, not from the sandbox: whether the server answers (for an MCP server, the MCP handshake with the stored secret) or the repository token works.`,
  };
  /** Example values in placeholders: code and data, the same in every language. */
  readonly examples = { url: 'https://mcp.example.com/mcp', header: 'X-Api-Key' }; // i18n-ignore

  constructor() {
    effect(() => {
      const projectId = this.projectId();
      untracked(() => this.load(projectId));
    });
  }

  load(projectId = this.projectId()): void {
    this.api.list(projectId).subscribe({
      next: (list) => {
        this.items.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  hostOf(c: Connector): string {
    return c.host ?? connectorHost(c.url) ?? c.url ?? '';
  }

  create(): void {
    this.editing.set(null);
    this.draft.set(emptyConnector());
  }

  edit(r: ConnectorRow): void {
    this.editing.set(r);
    this.draft.set(connectorDraftOf(r.connector));
  }

  close(): void {
    this.draft.set(null);
    this.editing.set(null);
  }

  patch(change: Partial<ConnectorDraft>): void {
    const d = this.draft();
    if (d) this.draft.set({ ...d, ...change });
  }

  private scope(): Scope {
    const projectId = this.projectId();
    return projectId ? { type: 'PROJECT', projectId } : { type: 'GLOBAL', projectId: null };
  }

  save(): void {
    const d = this.draft();
    if (!d || this.problems().length || !this.canEdit()) return;
    const existing = this.editing()?.connector;
    const scope = existing?.scope ?? this.scope();
    const input = connectorPayload(d, scope, { hasSecret: !!existing?.hasSecret });
    this.saving.set(true);
    const req = existing?.id ? this.api.update(existing.id, input) : this.api.create(input);
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.message.success(
          $localize`:@@library.connectors.saved:Connector "${saved.name}:name:" saved`,
        );
        this.upsert(saved);
        // stay in the editor so the connector can be tested right away
        const row = this.rows().find((r) => r.connector.id === saved.id) ?? null;
        this.editing.set(row);
        this.draft.set(connectorDraftOf(saved));
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.message.error(problemMessage(e));
      },
    });
  }

  test(c: Connector): void {
    if (!c.id) return;
    this.testing.set(true);
    this.api.test(c.id).subscribe({
      next: (result) => {
        this.testing.set(false);
        this.applyTest(c.id!, result);
      },
      error: (e: unknown) => {
        this.testing.set(false);
        this.message.error(problemMessage(e));
      },
    });
  }

  private applyTest(id: string, result: ConnectorTest): void {
    const current = this.items().find((x) => x.id === id);
    if (!current) return;
    this.upsert({ ...current, lastTest: result });
    const r = this.editing();
    if (r?.connector.id === id)
      this.editing.set(this.rows().find((x) => x.connector.id === id) ?? null);
  }

  private upsert(c: Connector): void {
    const list = this.items();
    this.items.set(
      list.some((x) => x.id === c.id) ? list.map((x) => (x.id === c.id ? c : x)) : [...list, c],
    );
  }

  async remove(c: Connector): Promise<void> {
    if (!c.id) return;
    const ok = await this.confirm.danger(
      $localize`:@@library.connectors.deleteTitle:Delete connector "${c.name}:name:"?`,
      $localize`:@@library.connectors.deleteContent:Agents that list its key skip it from then on (the workflow's agent audit reports it).`,
      $localize`:@@library.connectors.deleteConfirm:Delete connector`,
    );
    if (!ok) return;
    this.api.delete(c.id).subscribe(() => {
      this.close();
      this.items.set(this.items().filter((x) => x.id !== c.id));
    });
  }
}
