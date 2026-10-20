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
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSkeletonModule } from 'ng-zorro-antd/skeleton';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ProjectsApi } from '../../../core/api/projects.service';
import type {
  CiEntrypoint,
  CiSnippet,
  CiToken,
  ProjectRepository,
  WorkflowDefinition,
} from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { TimeAgoPipe } from '../../../core/ui/pipes/time-ago.pipe';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { StatusTagComponent } from '../../../core/ui/status-tag.component';
import { CiExecutionsTableComponent } from '../../ci/public-api';
import {
  maskedToken,
  newEntrypoint,
  openTokenModal,
  sortTokens,
  tokenCreated,
  tokenNameError,
  validateEntrypoints,
  type TokenModalState,
} from '../data/ci-tokens';

/** Project > CI tab: executions, entrypoints editor, CI tokens and the .gitlab-ci.yml snippet. */
@Component({
  selector: 'app-project-ci-panel',
  imports: [
    FormsModule,
    NzCardModule,
    NzButtonModule,
    NzIconModule,
    NzTableModule,
    NzSelectModule,
    NzInputModule,
    NzSwitchModule,
    NzTagModule,
    NzAlertModule,
    NzModalModule,
    NzSkeletonModule,
    NzTooltipModule,
    HelpLinkComponent,
    SortHeaderDirective,
    StatusTagComponent,
    TimeAgoPipe,
    CiExecutionsTableComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="two-col" style="margin-bottom: 16px">
      <nz-card
        nzTitle="CI entrypoints"
        i18n-nzTitle="@@projects.ci.entrypoints.title"
        [nzExtra]="epExtra"
      >
        <ng-template #epExtra>
          @if (canManage()) {
            <button nz-button nzSize="small" type="button" (click)="addEntrypoint()">
              <nz-icon nzType="plus" /> <span i18n="@@projects.ci.entrypoints.add">Add</span>
            </button>
          } @else {
            <span class="muted" i18n="@@projects.ci.readOnly">read-only</span>
          }
        </ng-template>
        <p class="muted" i18n="@@projects.ci.entrypoints.intro">
          <code>backoffice-ci run &lt;key&gt;</code> starts the mapped workflow with the CI context.
          BLOCKING fails the job on a FAILURE outcome; ADVISORY never fails it.
        </p>
        @if (entrypointsLoading()) {
          <nz-skeleton [nzActive]="true" [nzParagraph]="{ rows: 2 }" />
        } @else {
          <nz-table [nzData]="entrypoints()" [nzShowPagination]="false" nzSize="small">
            <thead>
              <tr>
                <th nzWidth="150px" i18n="@@projects.ci.entrypoints.key">Key</th>
                <th i18n="@@projects.ci.entrypoints.workflow">Workflow</th>
                <th nzWidth="120px" i18n="@@projects.ci.entrypoints.mode">Mode</th>
                @if (showRepository()) {
                  <th nzWidth="170px" i18n="@@projects.ci.entrypoints.repository">Repository</th>
                }
                <th nzWidth="90px" i18n="@@projects.ci.entrypoints.diff">Diff</th>
                <th nzWidth="50px"></th>
              </tr>
            </thead>
            <tbody>
              @for (e of entrypoints(); track $index; let i = $index) {
                <tr>
                  <td>
                    <input
                      nz-input
                      nzSize="small"
                      class="mono"
                      [ngModel]="e.key"
                      (ngModelChange)="patchEntrypoint(i, { key: $event })"
                      [attr.aria-label]="keyLabel(i + 1)"
                      [disabled]="!canManage()"
                    />
                  </td>
                  <td>
                    <nz-select
                      nzSize="small"
                      style="width: 100%"
                      [ngModel]="e.workflowId || null"
                      (ngModelChange)="patchEntrypoint(i, { workflowId: $event })"
                      nzPlaceHolder="Workflow"
                      i18n-nzPlaceHolder="@@projects.ci.entrypoints.workflowPlaceholder"
                      [nzDisabled]="!canManage()"
                      nzShowSearch
                      [attr.aria-label]="workflowLabel(e.key)"
                    >
                      @for (w of workflows(); track w.id) {
                        <nz-option [nzValue]="w.id" [nzLabel]="w.name + ' (v' + w.version + ')'" />
                      }
                    </nz-select>
                  </td>
                  <td>
                    <nz-select
                      nzSize="small"
                      style="width: 100%"
                      [ngModel]="e.mode"
                      (ngModelChange)="patchEntrypoint(i, { mode: $event })"
                      [attr.aria-label]="modeLabel(e.key)"
                      [nzDisabled]="!canManage()"
                    >
                      <nz-option
                        nzValue="BLOCKING"
                        nzLabel="Blocking"
                        i18n-nzLabel="@@projects.ci.entrypoints.blocking"
                      />
                      <nz-option
                        nzValue="ADVISORY"
                        nzLabel="Advisory"
                        i18n-nzLabel="@@projects.ci.entrypoints.advisory"
                      />
                    </nz-select>
                  </td>
                  @if (showRepository()) {
                    <td>
                      <nz-select
                        nzSize="small"
                        style="width: 100%"
                        [ngModel]="e.repository ?? anyRepository"
                        (ngModelChange)="
                          patchEntrypoint(i, {
                            repository: $event === anyRepository ? null : $event,
                          })
                        "
                        [attr.aria-label]="repositoryLabel(e.key)"
                        [nzDisabled]="!canManage()"
                        nz-tooltip
                        nzTooltipTitle="Only CI jobs of this repository may start the entrypoint"
                        i18n-nzTooltipTitle="@@projects.ci.entrypoints.repositoryTooltip"
                      >
                        <nz-option [nzValue]="anyRepository" [nzLabel]="text.anyRepository" />
                        @for (r of repositories(); track r.key) {
                          <nz-option [nzValue]="r.key" [nzLabel]="r.key" />
                        }
                        @if (e.repository && !repositoryKeys().has(e.repository)) {
                          <nz-option [nzValue]="e.repository" [nzLabel]="e.repository" />
                        }
                      </nz-select>
                    </td>
                  }
                  <td>
                    <nz-switch
                      nzSize="small"
                      [ngModel]="e.uploadDiff !== false"
                      (ngModelChange)="patchEntrypoint(i, { uploadDiff: $event })"
                      nz-tooltip
                      nzTooltipTitle="Upload the MR / branch diff as the artefact diff"
                      i18n-nzTooltipTitle="@@projects.ci.entrypoints.diffTooltip"
                      [nzDisabled]="!canManage()"
                    />
                  </td>
                  <td>
                    @if (canManage()) {
                      <button
                        nz-button
                        nzSize="small"
                        nzType="text"
                        nzDanger
                        type="button"
                        (click)="removeEntrypoint(i)"
                        aria-label="Remove entrypoint"
                        i18n-aria-label="@@projects.ci.entrypoints.remove"
                      >
                        <nz-icon nzType="delete" />
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </nz-table>
          @for (err of entrypointErrors(); track err) {
            <div class="danger" style="font-size: 12px">{{ err }}</div>
          }
          @if (canManage()) {
            <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px">
              @if (entrypointsDirty()) {
                <button
                  nz-button
                  nzSize="small"
                  type="button"
                  (click)="reloadEntrypoints()"
                  i18n="@@projects.ci.entrypoints.discard"
                >
                  Discard
                </button>
              }
              <button
                nz-button
                nzSize="small"
                nzType="primary"
                type="button"
                (click)="saveEntrypoints()"
                [nzLoading]="savingEntrypoints()"
                [disabled]="!entrypointsDirty() || entrypointErrors().length > 0"
              >
                <nz-icon nzType="save" />
                <span i18n="@@projects.ci.entrypoints.save">Save entrypoints</span>
              </button>
            </div>
          } @else {
            <p class="muted" style="margin-top: 8px" i18n="@@projects.ci.entrypoints.needsManage">
              Changing entrypoints needs the <code>project.manage</code> permission.
            </p>
          }
        }
      </nz-card>

      <div class="stack">
        <nz-card nzTitle="CI tokens" i18n-nzTitle="@@projects.ci.tokens.title" [nzExtra]="tokExtra">
          <ng-template #tokExtra>
            @if (canManageTokens()) {
              <button
                nz-button
                nzSize="small"
                nzType="primary"
                type="button"
                (click)="openCreateToken()"
              >
                <nz-icon nzType="key" />
                <span i18n="@@projects.ci.tokens.create">Create token</span>
              </button>
            }
          </ng-template>
          <p class="muted" i18n="@@projects.ci.tokens.intro">
            Bearer tokens (<code>bo_ci_...</code>) the CI uses as <code>BACKOFFICE_CI_TOKEN</code>.
            Shown once; stored hashed.
          </p>
          @if (!canManageTokens()) {
            <p class="muted" i18n="@@projects.ci.tokens.needsManage">
              Creating, listing and revoking CI tokens needs the <code>project.ci.manage</code>
              permission.
            </p>
          } @else if (tokensLoading()) {
            <nz-skeleton [nzActive]="true" [nzParagraph]="{ rows: 2 }" />
          } @else if (!tokens().length) {
            <p class="muted" i18n="@@projects.ci.tokens.empty">No tokens yet.</p>
          } @else {
            <nz-table [nzData]="tokenRows()" [nzShowPagination]="false" nzSize="small">
              <thead>
                <tr>
                  <th nzShowSort [boSort]="tokenSort" boSortKey="name" i18n="@@projects.ci.tokens.name">Name</th>
                  <th nzShowSort [boSort]="tokenSort" boSortKey="prefix" nzWidth="166px" i18n="@@projects.ci.tokens.prefix">Prefix</th>
                  <th nzShowSort [boSort]="tokenSort" boSortKey="created" nzWidth="126px" i18n="@@projects.ci.tokens.created">Created</th>
                  <th nzShowSort [boSort]="tokenSort" boSortKey="lastUsed" nzWidth="126px" i18n="@@projects.ci.tokens.lastUsed">Last used</th>
                  <th nzWidth="100px"></th>
                </tr>
              </thead>
              <tbody>
                @for (t of tokenRows(); track t.id) {
                  <tr [class.revoked]="!!t.revokedAt">
                    <td>
                      {{ t.name }}
                      @if (t.revokedAt) {
                        <app-status-tag
                          status="DISABLED"
                          text="revoked"
                          i18n-text="@@projects.ci.tokens.revokedTag"
                          [showIcon]="false"
                          style="margin-left: 6px"
                        />
                      }
                    </td>
                    <td>
                      <span class="mono">{{ masked(t.prefix) }}</span>
                    </td>
                    <td>{{ t.createdAt | timeAgo }}</td>
                    <td>
                      @if (t.lastUsedAt) {
                        {{ t.lastUsedAt | timeAgo }}
                      } @else {
                        <span i18n="@@projects.ci.tokens.never">never</span>
                      }
                    </td>
                    <td>
                      @if (!t.revokedAt) {
                        <button
                          nz-button
                          nzSize="small"
                          nzDanger
                          type="button"
                          (click)="revoke(t)"
                          [nzLoading]="revoking() === t.id"
                          i18n="@@projects.ci.tokens.revoke"
                        >
                          Revoke
                        </button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </nz-table>
          }
        </nz-card>

        <nz-card
          nzTitle="GitLab CI snippet"
          i18n-nzTitle="@@projects.ci.snippet.title"
          [nzExtra]="snipExtra"
        >
          <ng-template #snipExtra>
            <button
              nz-button
              nzSize="small"
              type="button"
              (click)="copy(snippet()?.gitlabCi ?? '')"
              [disabled]="!snippet()?.gitlabCi"
            >
              <nz-icon nzType="copy" /> <span i18n="@@projects.ci.snippet.copy">Copy</span>
            </button>
          </ng-template>
          @if (snippetLoading()) {
            <nz-skeleton [nzActive]="true" [nzParagraph]="{ rows: 3 }" />
          } @else if (snippet(); as s) {
            <p class="muted">
              <span i18n="@@projects.ci.snippet.pasteInto"
                >Paste into <code>.gitlab-ci.yml</code>; set the CI/CD variables (masked, not
                protected)</span
              >
              @for (v of s.variables ?? []; track v) {
                <code style="margin-right: 4px">{{ v }}</code>
              }
              <span i18n="@@projects.ci.snippet.scriptUrl"
                >and the script URL <span class="mono">{{ s.scriptUrl }}</span
                >.</span
              >
            </p>
            <pre class="code-block" tabindex="0">{{ s.gitlabCi }}</pre>
          } @else {
            <p class="muted" i18n="@@projects.ci.snippet.unavailable">Snippet unavailable.</p>
          }
        </nz-card>
      </div>
    </div>

    <nz-card nzTitle="CI executions" i18n-nzTitle="@@projects.ci.executions.title">
      <app-ci-executions-table
        [projectId]="projectId()"
        emptyText="No CI executions for this project yet."
        i18n-emptyText="@@projects.ci.executions.empty"
      />
    </nz-card>

    <nz-modal
      [nzVisible]="modal().step !== 'closed'"
      [nzTitle]="modal().step === 'created' ? text.tokenCreated : text.createToken"
      [nzFooter]="modalFooter"
      (nzOnCancel)="closeModal()"
      [nzMaskClosable]="modal().step !== 'created'"
    >
      <ng-container *nzModalContent>
        @if (modal(); as m) {
          @if (m.step === 'naming') {
            <label for="tok-name" i18n="@@projects.ci.tokenModal.name">Token name</label>
            <input
              nz-input
              id="tok-name"
              [ngModel]="m.name"
              (ngModelChange)="setTokenName($event)"
              [placeholder]="tokenNameExample"
              style="margin: 4px 0"
              (keydown.enter)="createToken()"
            />
            @if (m.error) {
              <div class="danger" style="font-size: 12px">{{ m.error }}</div>
            }
          } @else if (m.step === 'created') {
            <nz-alert
              nzType="warning"
              nzShowIcon
              nzMessage="Copy the token now. It is shown only once and stored hashed."
              i18n-nzMessage="@@projects.ci.tokenModal.copyNow"
              style="margin-bottom: 12px"
            />
            <div class="token-box">
              <code class="mono">{{ m.token.token }}</code>
              <button nz-button nzSize="small" nzType="primary" type="button" (click)="copyToken()">
                <nz-icon [nzType]="m.copied ? 'check' : 'copy'" />
                @if (m.copied) {
                  <span i18n="@@projects.ci.tokenModal.copied">Copied</span>
                } @else {
                  <span i18n="@@projects.ci.tokenModal.copy">Copy</span>
                }
              </button>
            </div>
            <p class="muted ci-hint">
              <span i18n="@@projects.ci.tokenModal.gitlabHint"
                >Add it in GitLab under <strong>Settings › CI/CD › Variables</strong> as
                <code>BACKOFFICE_CI_TOKEN</code>: <strong>Masked</strong>,
                <strong>not Protected</strong>
                (code reviews run in merge-request pipelines, which do not see protected
                variables).</span
              >
              <bo-help-link topic="project.ciVariables" variant="text"
                ><span i18n="@@projects.ci.tokenModal.fullSteps">Full steps</span></bo-help-link
              >
            </p>
          }
        }
      </ng-container>
      <ng-template #modalFooter>
        @if (modal(); as m) {
          @if (m.step === 'naming') {
            <button
              nz-button
              type="button"
              (click)="closeModal()"
              i18n="@@projects.ci.tokenModal.cancel"
            >
              Cancel
            </button>
            <button
              nz-button
              nzType="primary"
              type="button"
              (click)="createToken()"
              [nzLoading]="creatingToken()"
              [disabled]="!!m.error"
              i18n="@@projects.ci.tokenModal.create"
            >
              Create
            </button>
          } @else {
            <button
              nz-button
              nzType="primary"
              type="button"
              (click)="closeModal()"
              i18n="@@projects.ci.tokenModal.done"
            >
              Done
            </button>
          }
        }
      </ng-template>
    </nz-modal>
  `,
  styles: [
    `
      tr.revoked td {
        color: rgba(0, 0, 0, 0.35);
      }
      .token-box {
        display: flex;
        gap: 8px;
        align-items: center;
        padding: 8px 12px;
        background: #f6f8fa;
        border: 1px solid #e6e9ef;
        border-radius: 4px;
        word-break: break-all;
      }
      .token-box code {
        flex: 1;
      }
      .ci-hint {
        margin-top: 12px;
        line-height: 1.55;
      }
    `,
  ],
})
export class ProjectCiPanelComponent {
  private readonly api = inject(ProjectsApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);

  readonly projectId = input.required<string>();
  readonly workflows = input<WorkflowDefinition[]>([]);
  /** The project's repositories (ADR 0034): an entrypoint may be limited to one of them. */
  readonly repositories = input<ProjectRepository[]>([]);
  /** `project.manage`: edit CI entrypoints. */
  readonly canManage = input(false);
  /** `project.ci.manage`: list, create and revoke CI tokens. */
  readonly canManageTokens = input(false);

  readonly entrypoints = signal<CiEntrypoint[]>([]);
  readonly entrypointsLoading = signal(false);
  readonly savingEntrypoints = signal(false);
  private savedEntrypointsJson = '[]';
  readonly entrypointsDirty = computed(
    () => JSON.stringify(this.entrypoints()) !== this.savedJson(),
  );
  private readonly savedJson = signal('[]');
  readonly entrypointErrors = computed(() =>
    validateEntrypoints(
      this.entrypoints(),
      this.repositories().map((r) => r.key),
    ),
  );

  readonly tokens = signal<CiToken[]>([]);
  readonly tokensLoading = signal(false);
  /** Column sorting of the CI tokens (URL `?tokenSort=`); the editable entrypoints keep their own order. */
  readonly tokenSort = tableSort<CiToken>(
    {
      name: (t) => t.name,
      prefix: (t) => t.prefix,
      created: (t) => t.createdAt,
      lastUsed: (t) => t.lastUsedAt,
    },
    { param: 'tokenSort' },
  );
  readonly tokenRows = computed(() => this.tokenSort.apply(this.tokens()));
  readonly revoking = signal<string | null>(null);
  readonly modal = signal<TokenModalState>({ step: 'closed' });
  readonly creatingToken = signal(false);

  readonly snippet = signal<CiSnippet | null>(null);
  readonly snippetLoading = signal(false);
  readonly masked = maskedToken;
  readonly tokenNameExample = 'gitlab-ci';
  readonly text = {
    tokenCreated: $localize`:@@projects.ci.tokenModal.createdTitle:Token created`,
    createToken: $localize`:@@projects.ci.tokenModal.createTitle:Create CI token`,
    anyRepository: $localize`:@@projects.ci.entrypoints.anyRepository:Any repository`,
  };
  /** Select value of "Any repository" (sent as `repository: null`). */
  readonly anyRepository = '';
  readonly repositoryKeys = computed(() => new Set(this.repositories().map((r) => r.key)));
  /** The repository column: when the project has repositories or an entrypoint names one. */
  readonly showRepository = computed(
    () => this.repositories().length > 0 || this.entrypoints().some((e) => !!e.repository),
  );

  repositoryLabel(key: string): string {
    return $localize`:@@projects.ci.entrypoints.repositoryLabel:Repository for ${key}:key:`;
  }

  keyLabel(n: number): string {
    return $localize`:@@projects.ci.entrypoints.keyLabel:Entrypoint key ${n}:n:`;
  }

  workflowLabel(key: string): string {
    return $localize`:@@projects.ci.entrypoints.workflowLabel:Workflow for ${key}:key:`;
  }

  modeLabel(key: string): string {
    return $localize`:@@projects.ci.entrypoints.modeLabel:Mode for ${key}:key:`;
  }

  constructor() {
    effect(() => {
      this.projectId();
      const tokens = this.canManageTokens();
      untracked(() => {
        this.reloadEntrypoints();
        if (tokens) this.reloadTokens();
        this.reloadSnippet();
      });
    });
  }

  // ---- entrypoints

  reloadEntrypoints(): void {
    this.entrypointsLoading.set(true);
    this.api.ciEntrypoints(this.projectId()).subscribe({
      next: (list) => {
        this.entrypoints.set(list);
        this.savedEntrypointsJson = JSON.stringify(list);
        this.savedJson.set(this.savedEntrypointsJson);
        this.entrypointsLoading.set(false);
      },
      error: () => this.entrypointsLoading.set(false),
    });
  }

  addEntrypoint(): void {
    this.entrypoints.update((l) => [...l, newEntrypoint(l, this.workflows()[0]?.id ?? '')]);
  }

  patchEntrypoint(index: number, changes: Partial<CiEntrypoint>): void {
    this.entrypoints.update((l) => l.map((e, i) => (i === index ? { ...e, ...changes } : e)));
  }

  removeEntrypoint(index: number): void {
    this.entrypoints.update((l) => l.filter((_, i) => i !== index));
  }

  saveEntrypoints(): void {
    if (this.entrypointErrors().length) return;
    this.savingEntrypoints.set(true);
    const payload = this.entrypoints().map(({ workflowName: _n, ...e }) => e);
    this.api.replaceCiEntrypoints(this.projectId(), payload).subscribe({
      next: (list) => {
        this.entrypoints.set(list);
        this.savedJson.set(JSON.stringify(list));
        this.savingEntrypoints.set(false);
        this.message.success($localize`:@@projects.ci.entrypoints.saved:CI entrypoints saved`);
      },
      error: () => this.savingEntrypoints.set(false),
    });
  }

  // ---- tokens

  reloadTokens(): void {
    this.tokensLoading.set(true);
    this.api.ciTokens(this.projectId()).subscribe({
      next: (list) => {
        this.tokens.set(sortTokens(list));
        this.tokensLoading.set(false);
      },
      error: () => this.tokensLoading.set(false),
    });
  }

  openCreateToken(): void {
    this.modal.set(openTokenModal(this.tokens()));
  }

  setTokenName(name: string): void {
    this.modal.set({ step: 'naming', name, error: tokenNameError(name, this.tokens()) });
  }

  createToken(): void {
    const m = this.modal();
    if (m.step !== 'naming') return;
    const error = tokenNameError(m.name, this.tokens());
    if (error) {
      this.modal.set({ ...m, error });
      return;
    }
    this.creatingToken.set(true);
    this.api.createCiToken(this.projectId(), m.name.trim()).subscribe({
      next: (created) => {
        this.creatingToken.set(false);
        this.modal.set(tokenCreated(created));
        this.reloadTokens();
      },
      error: () => this.creatingToken.set(false),
    });
  }

  async copyToken(): Promise<void> {
    const m = this.modal();
    if (m.step !== 'created') return;
    await this.copy(m.token.token);
    this.modal.set({ ...m, copied: true });
  }

  closeModal(): void {
    this.modal.set({ step: 'closed' });
  }

  async revoke(t: CiToken): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@projects.ci.revoke.title:Revoke token "${t.name}:name:"?`,
      $localize`:@@projects.ci.revoke.text:CI jobs using it will fail with 401 immediately.`,
      $localize`:@@projects.ci.revoke.ok:Revoke`,
    );
    if (!ok || !t.id) return;
    this.revoking.set(t.id);
    this.api.revokeCiToken(this.projectId(), t.id).subscribe({
      next: () => {
        this.revoking.set(null);
        this.message.success($localize`:@@projects.ci.revoke.done:Token revoked`);
        this.reloadTokens();
      },
      error: () => this.revoking.set(null),
    });
  }

  // ---- snippet

  reloadSnippet(): void {
    this.snippetLoading.set(true);
    this.api.ciSnippet(this.projectId()).subscribe({
      next: (s) => {
        this.snippet.set(s);
        this.snippetLoading.set(false);
      },
      error: () => this.snippetLoading.set(false),
    });
  }

  async copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.message.success($localize`:@@projects.ci.copy.done:Copied`);
    } catch {
      this.message.warning(
        $localize`:@@projects.ci.copy.unavailable:Clipboard unavailable; select the text and copy manually`,
      );
    }
  }
}
