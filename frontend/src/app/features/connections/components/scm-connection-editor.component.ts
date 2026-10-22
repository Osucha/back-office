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
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type { ProjectRepository } from '../../../core/api/types';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { shortRepoUrl } from '../../../core/ui/repositories';
import type { ScmConnection, ScmVerification } from '../data/connection-types';
import {
  SCM_READ_CAPABILITIES,
  SCM_WRITE_CAPABILITIES,
  emptyScmDraft,
  mixesReadAndWrite,
  recommendedToken,
  scmCapabilityHint,
  scmCapabilityLabel,
  scmCapabilityPurpose,
  scmDraftOf,
  toScmInput,
  validateScmDraft,
  type ScmDraft,
  type ScmDraftErrors,
} from '../data/scm-connection-model';
import { ScmConnectionsApi } from '../data/scm-connections-api.service';
import { ScmVerificationResultComponent } from './scm-verification-result.component';

/** create: a new token; edit: change it (token kept unless replaced); rotate: only the token. */
export type ScmEditorMode = 'create' | 'edit' | 'rotate';

/**
 * Add / edit a repository token, or replace it (ADR 0051). Capabilities are grouped into read ones
 * (may reach agents when the token is verified read-only) and write ones (Back Office only), each with
 * the GitLab scope and role it needs; a side panel names the token to create in GitLab. The token is
 * write-only. "Test before saving" checks a draft without storing it; saving tests it again.
 */
@Component({
  selector: 'app-scm-connection-editor',
  imports: [
    FormsModule,
    NzAlertModule,
    NzButtonModule,
    NzCheckboxModule,
    NzInputModule,
    NzModalModule,
    NzSelectModule,
    ScmVerificationResultComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-modal
      [nzVisible]="open()"
      [nzTitle]="title()"
      [nzWidth]="mode() === 'rotate' ? 560 : 920"
      [nzFooter]="footer"
      [nzMaskClosable]="false"
      (nzOnCancel)="closed.emit()"
    >
      <ng-container *nzModalContent>
        @if (result(); as r) {
          <div class="saved">
            <p class="lead" i18n="@@connections.scm.editor.savedLead">
              Saved. Back Office tested the token against the repository:
            </p>
            @if (r.verification) {
              <app-scm-verification-result [verification]="r.verification" />
            } @else {
              <p class="muted" i18n="@@connections.scm.editor.notTested">
                The token was not tested yet. Use Test again in the list.
              </p>
            }
          </div>
        } @else {
          <div class="layout" [class.single]="mode() === 'rotate'">
            <form class="form" (submit)="$event.preventDefault(); save()" autocomplete="off">
              @if (error()) {
                <nz-alert nzType="error" nzShowIcon [nzMessage]="error()!" class="err" />
              }
              @if (tested(); as t) {
                <section class="tested" aria-live="polite">
                  <p class="lead" i18n="@@connections.scm.editor.testedLead">
                    Test result. Nothing is saved yet:
                  </p>
                  <app-scm-verification-result [verification]="t" />
                </section>
              }
              @if (mode() !== 'rotate') {
                <div class="row2">
                  <div class="field">
                    <label for="sce-repo" i18n="@@connections.scm.form.repository"
                      >Repository</label
                    >
                    <nz-select
                      nzId="sce-repo"
                      name="repositoryKey"
                      [ngModel]="draft().repositoryKey || null"
                      (ngModelChange)="set('repositoryKey', $event ?? '')"
                      [attr.aria-invalid]="!!shown().repositoryKey"
                    >
                      @for (r of repositories(); track r.key) {
                        <nz-option [nzValue]="r.key" [nzLabel]="repoLabel(r)" />
                      }
                    </nz-select>
                    @if (shown().repositoryKey) {
                      <div class="ferr">{{ shown().repositoryKey }}</div>
                    }
                  </div>
                  <div class="field">
                    <label for="sce-name" i18n="@@connections.scm.form.name">Name</label>
                    <input
                      nz-input
                      id="sce-name"
                      name="name"
                      maxlength="80"
                      [ngModel]="draft().name"
                      (ngModelChange)="set('name', $event ?? '')"
                      placeholder="GitLab reader"
                      i18n-placeholder="@@connections.scm.form.namePlaceholder"
                      [attr.aria-invalid]="!!shown().name"
                    />
                    @if (shown().name) {
                      <div class="ferr">{{ shown().name }}</div>
                    }
                  </div>
                </div>

                <fieldset class="field">
                  <legend i18n="@@connections.scm.form.readGroup">
                    Read (may be given to agents)
                  </legend>
                  @for (c of readOptions; track c) {
                    <label
                      nz-checkbox
                      class="cap"
                      [ngModel]="has(c)"
                      (ngModelChange)="toggle(c, $event)"
                      [name]="'cap-' + c"
                    >
                      <span class="cap-name">{{ capLabel(c) }}</span>
                      <span class="cap-text">{{ purpose(c) }} {{ hint(c) }}</span>
                    </label>
                  }
                </fieldset>
                <fieldset class="field">
                  <legend i18n="@@connections.scm.form.writeGroup">
                    Write (used only by Back Office)
                  </legend>
                  @for (c of writeOptions; track c) {
                    <label
                      nz-checkbox
                      class="cap"
                      [ngModel]="has(c)"
                      (ngModelChange)="toggle(c, $event)"
                      [name]="'cap-' + c"
                    >
                      <span class="cap-name">{{ capLabel(c) }}</span>
                      <span class="cap-text">{{ purpose(c) }} {{ hint(c) }}</span>
                    </label>
                  }
                  @if (shown().capabilities) {
                    <div class="ferr">{{ shown().capabilities }}</div>
                  }
                  @if (mixed()) {
                    <div class="fwarn" role="status" i18n="@@connections.scm.form.mixed">
                      With a write capability this token never reaches agents. Use a separate
                      read-only token for agent tools.
                    </div>
                  }
                </fieldset>
              }

              <div class="field">
                <label for="sce-token" i18n="@@connections.scm.form.token">Access token</label>
                @if (mode() === 'edit' && !replacing()) {
                  <div class="stored">
                    <span class="muted" i18n="@@connections.scm.form.tokenStored"
                      >Token stored</span
                    >
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      (click)="replacing.set(true)"
                      i18n="@@connections.scm.form.replaceToken"
                    >
                      Replace token
                    </button>
                  </div>
                } @else {
                  <input
                    nz-input
                    id="sce-token"
                    name="token"
                    type="password"
                    autocomplete="new-password"
                    class="mono"
                    [ngModel]="draft().token"
                    (ngModelChange)="set('token', $event ?? '')"
                    [attr.aria-invalid]="!!shown().token"
                    aria-describedby="sce-token-hint"
                  />
                  <div class="hint" id="sce-token-hint" i18n="@@connections.scm.form.tokenHint">
                    A GitLab project or group access token. It is stored encrypted and never shown
                    again.
                  </div>
                  @if (shown().token) {
                    <div class="ferr">{{ shown().token }}</div>
                  }
                }
              </div>
              <div class="field">
                <label for="sce-expiry" i18n="@@connections.scm.form.expiry"
                  >Token expiry date (optional)</label
                >
                <input
                  nz-input
                  id="sce-expiry"
                  name="secretExpiresAt"
                  type="date"
                  class="date"
                  [ngModel]="draft().secretExpiresAt"
                  (ngModelChange)="set('secretExpiresAt', $event ?? '')"
                  aria-describedby="sce-expiry-hint"
                />
                <div class="hint" id="sce-expiry-hint" i18n="@@connections.scm.form.expiryHint">
                  Empty: the expiry GitLab reports for the token. The list warns before the token
                  expires.
                </div>
              </div>
              <button type="submit" hidden></button>
            </form>

            @if (mode() !== 'rotate') {
              <aside class="guide" aria-labelledby="sce-guide-title">
                <h3 id="sce-guide-title" i18n="@@connections.scm.guide.title">
                  Create the token in GitLab
                </h3>
                @if (recommended().role; as role) {
                  <p i18n="@@connections.scm.guide.create">
                    In the repository (or its group): Settings, Access tokens. Role
                    <strong>{{ role }}</strong
                    >, scopes <span class="mono">{{ recommended().scopes.join(', ') }}</span
                    >, an expiry date.
                  </p>
                } @else {
                  <p class="muted" i18n="@@connections.scm.guide.pick">
                    Pick the capabilities first: the role and scopes follow them.
                  </p>
                }
                <ul class="notes">
                  <li i18n="@@connections.scm.guide.perLevel">
                    One token per permission level: a Reporter token with read_repository and
                    read_api for reading and CI logs, a Developer token with write_repository for
                    pushing.
                  </li>
                  <li i18n="@@connections.scm.guide.neverMaintainer">
                    Never use a Maintainer or Owner token: Back Office never merges, and the test
                    warns about such roles.
                  </li>
                  <li i18n="@@connections.scm.guide.agents">
                    Only a token with read capabilities alone, tested without write rights, may be
                    given to agents. Write tokens stay with Back Office.
                  </li>
                </ul>
              </aside>
            }
          </div>
        }
      </ng-container>
      <ng-template #footer>
        @if (result()) {
          <button
            nz-button
            nzType="primary"
            type="button"
            (click)="closed.emit()"
            i18n="@@connections.scm.editor.done"
          >
            Done
          </button>
        } @else {
          <button
            nz-button
            type="button"
            (click)="closed.emit()"
            i18n="@@connections.scm.editor.cancel"
          >
            Cancel
          </button>
          @if (mode() !== 'rotate') {
            <button
              nz-button
              type="button"
              [nzLoading]="testing()"
              [disabled]="saving() || !canTest()"
              (click)="test()"
              [title]="canTest() ? text.testHint : text.testNeedsToken"
              i18n="@@connections.scm.editor.test"
            >
              Test before saving
            </button>
          }
          <button nz-button nzType="primary" type="button" [nzLoading]="saving()" (click)="save()">
            @if (mode() === 'rotate') {
              <span i18n="@@connections.scm.editor.saveToken">Replace token</span>
            } @else {
              <span i18n="@@connections.scm.editor.save">Save and test</span>
            }
          </button>
        }
      </ng-template>
    </nz-modal>
  `,
  styles: [
    `
      .layout {
        display: grid;
        grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr);
        gap: 24px;
      }
      .layout.single {
        grid-template-columns: minmax(0, 1fr);
      }
      @media (max-width: 860px) {
        .layout {
          grid-template-columns: minmax(0, 1fr);
        }
      }
      .form {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .row2 {
        display: grid;
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
        gap: 10px;
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: 4px;
        min-width: 0;
      }
      .field > label,
      legend {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      fieldset {
        border: 0;
        margin: 0;
        padding: 0;
      }
      legend {
        margin-bottom: 4px;
        border: 0;
        width: auto;
        padding: 0;
      }
      .cap {
        display: flex;
        align-items: flex-start;
        margin: 0 0 6px !important;
      }
      .cap-name {
        font-weight: 500;
        display: block;
      }
      .cap-text {
        display: block;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .hint {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .ferr {
        font-size: 12px;
        color: var(--bo-blocked-ink);
      }
      .fwarn {
        font-size: 12px;
        color: var(--bo-you-ink);
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .stored {
        display: flex;
        align-items: center;
        gap: 10px;
      }
      .date {
        max-width: 200px;
      }
      .err {
        margin-bottom: 4px;
      }
      .guide {
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 12px 16px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        align-self: start;
      }
      .guide h3 {
        font-size: 13.5px;
        font-weight: 600;
        margin: 0 0 8px;
        color: var(--bo-ink);
      }
      .guide p {
        margin: 0 0 10px;
      }
      .notes {
        margin: 0;
        padding: 8px 0 0 16px;
        border-top: 1px solid var(--bo-line);
        display: flex;
        flex-direction: column;
        gap: 4px;
        color: var(--bo-ink-3);
      }
      .lead {
        margin: 0 0 8px;
      }
      .tested {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 10px 12px;
      }
    `,
  ],
})
export class ScmConnectionEditorComponent {
  private readonly api = inject(ScmConnectionsApi);

  readonly open = input(false);
  readonly projectId = input.required<string>();
  readonly repositories = input<readonly ProjectRepository[]>([]);
  /** The token to edit or replace; null to add one. */
  readonly connection = input<ScmConnection | null>(null);
  readonly mode = input<ScmEditorMode>('create');
  /** Preselected repository when adding. */
  readonly repositoryKey = input<string | null>(null);

  readonly saved = output<ScmConnection>();
  readonly closed = output<void>();

  readonly draft = signal<ScmDraft>(emptyScmDraft());
  readonly replacing = signal(false);
  readonly submitted = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly result = signal<ScmConnection | null>(null);
  readonly testing = signal(false);
  /** Result of "Test before saving" for the current draft (cleared when the draft changes). */
  readonly tested = signal<ScmVerification | null>(null);

  readonly readOptions = SCM_READ_CAPABILITIES;
  readonly writeOptions = SCM_WRITE_CAPABILITIES;
  readonly capLabel = scmCapabilityLabel;
  readonly purpose = scmCapabilityPurpose;
  readonly hint = scmCapabilityHint;
  readonly text = {
    testHint: $localize`:@@connections.scm.editor.testHint:Checks the token against the repository now, without saving anything`,
    testNeedsToken: $localize`:@@connections.scm.editor.testNeedsToken:Enter a new token to test the draft; Test again in the list tests the stored token`,
  };

  readonly title = computed(() => {
    const name = this.connection()?.name ?? '';
    switch (this.mode()) {
      case 'edit':
        return $localize`:@@connections.scm.editor.editTitle:Edit token ${name}:name:`;
      case 'rotate':
        return $localize`:@@connections.scm.editor.rotateTitle:Replace token ${name}:name:`;
      default:
        return $localize`:@@connections.scm.editor.createTitle:Add a repository access token`;
    }
  });
  readonly recommended = computed(() => recommendedToken(this.draft().capabilities));
  readonly mixed = computed(() => mixesReadAndWrite(this.draft().capabilities));
  /** A new token is needed to test a draft (the test endpoint never uses the stored one). */
  private readonly tokenRequired = computed(() => this.mode() !== 'edit' || this.replacing());
  readonly canTest = computed(() => this.tokenRequired());
  private readonly errors = computed<ScmDraftErrors>(() => {
    const keys = this.repositories().map((r) => r.key);
    const e = validateScmDraft(this.draft(), this.tokenRequired(), keys);
    // Replacing only the token: the other fields are not shown, so their errors are not either.
    return this.mode() === 'rotate' ? (e.token ? { token: e.token } : {}) : e;
  });
  /** Errors are shown after the first save or test attempt. */
  readonly shown = computed<ScmDraftErrors>(() => (this.submitted() ? this.errors() : {}));

  constructor() {
    effect(() => {
      const isOpen = this.open();
      const c = this.connection();
      untracked(() => {
        if (!isOpen) return;
        const firstRepo = this.repositories()[0]?.key ?? '';
        this.draft.set(c ? scmDraftOf(c) : emptyScmDraft(this.repositoryKey() ?? firstRepo));
        this.replacing.set(this.mode() === 'rotate');
        this.submitted.set(false);
        this.error.set(null);
        this.result.set(null);
        this.saving.set(false);
        this.tested.set(null);
        this.testing.set(false);
      });
    });
  }

  repoLabel(r: ProjectRepository): string {
    return `${r.key} (${shortRepoUrl(r.url)})`;
  }

  set<K extends keyof ScmDraft>(key: K, value: ScmDraft[K]): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
    this.tested.set(null);
  }

  has(c: string): boolean {
    return this.draft().capabilities.includes(c);
  }

  toggle(c: string, on: boolean): void {
    const rest = this.draft().capabilities.filter((x) => x !== c);
    this.set('capabilities', on ? [...rest, c] : rest);
  }

  /** "Test before saving": the draft is checked against the repository; nothing is stored. */
  test(): void {
    this.submitted.set(true);
    if (Object.keys(this.errors()).length || this.testing() || !this.canTest()) return;
    this.testing.set(true);
    this.error.set(null);
    this.tested.set(null);
    this.api.test(this.projectId(), toScmInput(this.draft())).subscribe({
      next: (v) => {
        this.testing.set(false);
        this.tested.set(v);
      },
      error: (err: unknown) => {
        this.testing.set(false);
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 403
            ? $localize`:@@connections.scm.editor.testForbidden:You may not test tokens of this project.`
            : problemMessage(err),
        );
      },
    });
  }

  save(): void {
    this.submitted.set(true);
    if (Object.keys(this.errors()).length || this.saving()) return;
    const existing = this.connection();
    const d = this.draft();
    const projectId = this.projectId();
    const call =
      existing && this.mode() !== 'create'
        ? this.api.update(
            projectId,
            existing.id,
            toScmInput(this.replacing() ? d : { ...d, token: '' }),
          )
        : this.api.create(projectId, toScmInput(d));
    this.saving.set(true);
    this.error.set(null);
    call.subscribe({
      next: (c) => {
        this.saving.set(false);
        this.result.set(c);
        this.saved.emit(c);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 403
            ? $localize`:@@connections.scm.editor.forbidden:You may not change the tokens of this project.`
            : problemMessage(err),
        );
      },
    });
  }
}
