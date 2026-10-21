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
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type { Observable } from 'rxjs';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import {
  KNOWN_CAPABILITIES,
  capabilityLabel,
  capabilityPurpose,
  connectionGuide,
  draftOf,
  emptyDraft,
  excessWriteScopes,
  jiraPermissionNames,
  normalizeProjectKeys,
  normalizeScopes,
  permissionsFor,
  recommendedScopes,
  toInput,
  uncoveredCapabilities,
  validateDraft,
  type ConnectionDraft,
  type DraftErrors,
} from '../data/connection-model';
import type { Connection, ConnectionOwner, ConnectionVerification } from '../data/connection-types';
import { ConnectionsApi } from '../data/connections-api.service';
import { VerificationResultComponent } from './verification-result.component';

/** create: a new connection; edit: change it (token kept unless replaced); rotate: only the token. */
export type EditorMode = 'create' | 'edit' | 'rotate';

/**
 * Add / edit a connection, or replace its token, with a step-by-step guide that follows the selected
 * capabilities and token kind (ADR 0035: scoped tokens recommended; a classic token needs the "can do
 * everything your account can" acknowledgement). The token is write-only: the server never returns
 * it. After saving, the dialog shows the result of the automatic check against Jira.
 */
@Component({
  selector: 'app-connection-editor',
  imports: [
    FormsModule,
    NzAlertModule,
    NzButtonModule,
    NzCheckboxModule,
    NzIconModule,
    NzInputModule,
    NzModalModule,
    NzRadioModule,
    NzSelectModule,
    HelpLinkComponent,
    VerificationResultComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-modal
      [nzVisible]="open()"
      [nzTitle]="title()"
      [nzWidth]="mode() === 'rotate' ? 560 : 980"
      [nzFooter]="footer"
      [nzMaskClosable]="false"
      (nzOnCancel)="closed.emit()"
    >
      <ng-container *nzModalContent>
        @if (result(); as r) {
          <div class="saved">
            <p class="lead">
              <span i18n="@@connections.editor.savedLead"
                >Saved. Back Office checked the connection against Jira:</span
              >
            </p>
            @if (r.verification) {
              <app-verification-result
                [verification]="r.verification"
                [capabilities]="r.capabilities"
              />
            } @else {
              <p class="muted" i18n="@@connections.editor.notChecked">
                The connection was not checked yet. Use Test in the list.
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
                  <p class="lead" i18n="@@connections.editor.testedLead">
                    Test result. Nothing is saved yet:
                  </p>
                  <app-verification-result
                    [verification]="t"
                    [capabilities]="draft().capabilities"
                  />
                </section>
              }
              @if (mode() !== 'rotate') {
                <div class="row2">
                  <div class="field">
                    <label for="ce-type" i18n="@@connections.form.type">Type</label>
                    <nz-select id="ce-type" name="type" [ngModel]="'JIRA'" [nzDisabled]="true">
                      <nz-option nzValue="JIRA" nzLabel="Jira" />
                    </nz-select>
                  </div>
                  <div class="field">
                    <label for="ce-name" i18n="@@connections.form.name">Name</label>
                    <input
                      nz-input
                      id="ce-name"
                      name="name"
                      [ngModel]="draft().name"
                      (ngModelChange)="set('name', $event)"
                      placeholder="Jira reader"
                      i18n-placeholder="@@connections.form.namePlaceholder"
                      [attr.aria-invalid]="!!shown().name"
                    />
                    @if (shown().name) {
                      <div class="ferr">{{ shown().name }}</div>
                    }
                  </div>
                </div>
                <div class="field">
                  <label for="ce-site" i18n="@@connections.form.siteUrl">Site URL</label>
                  <input
                    nz-input
                    id="ce-site"
                    name="siteUrl"
                    class="mono"
                    [ngModel]="draft().siteUrl"
                    (ngModelChange)="set('siteUrl', $event)"
                    [placeholder]="examples.site"
                    [attr.aria-invalid]="!!shown().siteUrl"
                  />
                  @if (shown().siteUrl) {
                    <div class="ferr">{{ shown().siteUrl }}</div>
                  }
                </div>
                <div class="field">
                  <label for="ce-email" i18n="@@connections.form.email">Account e-mail</label>
                  <input
                    nz-input
                    id="ce-email"
                    name="accountEmail"
                    type="email"
                    [ngModel]="draft().accountEmail"
                    (ngModelChange)="set('accountEmail', $event)"
                    [placeholder]="guide().role === 'writer' ? examples.writer : examples.reader"
                    [attr.aria-invalid]="!!shown().accountEmail"
                  />
                  @if (shown().accountEmail) {
                    <div class="ferr">{{ shown().accountEmail }}</div>
                  }
                </div>
              }

              @if (mode() !== 'rotate') {
                <fieldset class="field">
                  <legend i18n="@@connections.form.tokenKind">Kind of API token</legend>
                  <nz-radio-group
                    name="tokenKind"
                    [ngModel]="draft().tokenKind"
                    (ngModelChange)="setKind($event)"
                    class="kinds"
                  >
                    <label nz-radio nzValue="SCOPED" class="kind">
                      <span class="cap-name" i18n="@@connections.form.kindScoped"
                        >Scoped API token (recommended)</span
                      >
                      <span class="cap-text" i18n="@@connections.form.kindScopedHint"
                        >Limited to the scopes you choose at Atlassian, even when it belongs to your
                        own account.</span
                      >
                    </label>
                    <label nz-radio nzValue="CLASSIC" class="kind">
                      <span class="cap-name" i18n="@@connections.form.kindClassic"
                        >Classic API token</span
                      >
                      <span class="cap-text" i18n="@@connections.form.kindClassicHint"
                        >Has every permission of its account. Only for a dedicated, least-privileged
                        account.</span
                      >
                    </label>
                  </nz-radio-group>
                </fieldset>
              }

              <div class="field">
                <label for="ce-secret" i18n="@@connections.form.token">API token</label>
                @if (mode() === 'edit' && !replacing()) {
                  <div class="stored">
                    <span class="muted" i18n="@@connections.form.tokenStored">Token stored</span>
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      (click)="replacing.set(true)"
                      i18n="@@connections.form.replaceToken"
                    >
                      Replace token
                    </button>
                  </div>
                } @else {
                  <input
                    nz-input
                    id="ce-secret"
                    name="secret"
                    type="password"
                    autocomplete="new-password"
                    class="mono"
                    [ngModel]="draft().secret"
                    (ngModelChange)="set('secret', $event)"
                    [attr.aria-invalid]="!!shown().secret"
                    aria-describedby="ce-secret-hint"
                  />
                  <div class="hint" id="ce-secret-hint" i18n="@@connections.form.tokenHint">
                    The Atlassian API token. It is stored encrypted and never shown again.
                  </div>
                  @if (shown().secret) {
                    <div class="ferr">{{ shown().secret }}</div>
                  }
                }
              </div>
              <div class="field">
                <label for="ce-expiry" i18n="@@connections.form.expiry">Token expiry date</label>
                <input
                  nz-input
                  id="ce-expiry"
                  name="secretExpiresAt"
                  type="date"
                  class="date"
                  [ngModel]="draft().secretExpiresAt"
                  (ngModelChange)="set('secretExpiresAt', $event ?? '')"
                  aria-describedby="ce-expiry-hint"
                />
                <div class="hint" id="ce-expiry-hint" i18n="@@connections.form.expiryHint">
                  Optional reminder: the list warns 30 days before the token expires; from 14 days
                  before, the people who manage the connection get a Your turn card and a desktop
                  notification.
                </div>
              </div>

              @if (mode() !== 'rotate') {
                <fieldset class="field caps">
                  <legend i18n="@@connections.form.capabilities">Capabilities</legend>
                  @for (c of capabilityOptions; track c) {
                    <label
                      nz-checkbox
                      class="cap"
                      [ngModel]="has(c)"
                      (ngModelChange)="toggle(c, $event)"
                      [name]="'cap-' + c"
                    >
                      <span class="cap-name">{{ capLabel(c) }}</span>
                      <span class="cap-text">{{ purpose(c) }} {{ permissionsText(c) }}</span>
                    </label>
                  }
                  @for (c of unknownCapabilities(); track c) {
                    <label
                      nz-checkbox
                      class="cap"
                      [ngModel]="true"
                      (ngModelChange)="toggle(c, $event)"
                      [name]="'cap-' + c"
                    >
                      <span class="cap-name mono">{{ c }}</span>
                    </label>
                  }
                  @if (shown().capabilities) {
                    <div class="ferr">{{ shown().capabilities }}</div>
                  }
                </fieldset>
                <div class="field">
                  <label for="ce-keys" i18n="@@connections.form.allowedProjects"
                    >Allowed Jira projects</label
                  >
                  <nz-select
                    id="ce-keys"
                    name="allowedProjectKeys"
                    nzMode="tags"
                    [nzTokenSeparators]="[',', ' ', ';']"
                    [ngModel]="draft().allowedProjectKeys"
                    (ngModelChange)="setKeys($event)"
                    nzPlaceHolder="Any project"
                    i18n-nzPlaceHolder="@@connections.form.anyProject"
                    nzNotFoundContent=" "
                  />
                  <div class="hint" i18n="@@connections.form.allowedProjectsHint">
                    Jira project keys this connection may touch, for example SHOP. Empty: any
                    project.
                  </div>
                  @if (shown().allowedProjectKeys) {
                    <div class="ferr">{{ shown().allowedProjectKeys }}</div>
                  }
                </div>
                @if (draft().tokenKind === 'SCOPED') {
                  <div class="field">
                    <label for="ce-scopes" i18n="@@connections.form.scopes"
                      >Scopes of the token</label
                    >
                    <nz-select
                      id="ce-scopes"
                      name="tokenScopes"
                      nzMode="tags"
                      class="mono"
                      [nzTokenSeparators]="[',', ' ', ';']"
                      [ngModel]="draft().tokenScopes"
                      (ngModelChange)="setScopes($event)"
                      nzNotFoundContent=" "
                      [attr.aria-invalid]="!!shown().tokenScopes"
                    />
                    <div class="hint">
                      <span i18n="@@connections.form.scopesHint"
                        >The scopes you ticked when you created the token. Back Office checks them
                        against the capabilities.</span
                      >
                      <button
                        nz-button
                        nzType="link"
                        nzSize="small"
                        type="button"
                        class="inline-link"
                        (click)="useRecommended()"
                        i18n="@@connections.form.useRecommended"
                      >
                        Use the recommended scopes
                      </button>
                    </div>
                    @for (c of uncovered(); track c) {
                      <div class="fwarn" role="status" i18n="@@connections.form.scopeMissing">
                        The scopes do not cover {{ capLabel(c) }}.
                      </div>
                    }
                    @if (extraWrites().length) {
                      <div class="fwarn" role="status" i18n="@@connections.form.scopeExcess">
                        Write scopes no capability needs: {{ extraWrites().join(', ') }}.
                      </div>
                    }
                    @if (shown().tokenScopes) {
                      <div class="ferr">{{ shown().tokenScopes }}</div>
                    }
                  </div>
                  <div class="field">
                    <label for="ce-cloud" i18n="@@connections.form.cloudId"
                      >Cloud id of the site (optional)</label
                    >
                    <input
                      nz-input
                      id="ce-cloud"
                      name="cloudId"
                      class="mono"
                      [ngModel]="draft().cloudId"
                      (ngModelChange)="set('cloudId', $event ?? '')"
                      [attr.aria-invalid]="!!shown().cloudId"
                    />
                    <div class="hint" i18n="@@connections.form.cloudIdHint">
                      Empty: Back Office reads it from the site (/_edge/tenant_info) when you save.
                      Scoped tokens are sent to api.atlassian.com/ex/jira/(cloud id); issue links
                      keep the site URL.
                    </div>
                    @if (shown().cloudId) {
                      <div class="ferr">{{ shown().cloudId }}</div>
                    }
                  </div>
                } @else if (!draft().classicAcknowledged) {
                  <label
                    nz-checkbox
                    class="ack"
                    name="acknowledgeClassicToken"
                    [ngModel]="draft().acknowledgeClassicToken"
                    (ngModelChange)="set('acknowledgeClassicToken', $event)"
                  >
                    <span i18n="@@connections.form.acknowledge"
                      >I understand: this classic token can do everything its account can in Jira.
                      Back Office limits each step to its capability, but a leaked token is not
                      limited.</span
                    >
                  </label>
                  @if (shown().acknowledgeClassicToken) {
                    <div class="ferr">{{ shown().acknowledgeClassicToken }}</div>
                  }
                }
                @if (!draft().allowedProjectKeys.length) {
                  <div class="field">
                    <label for="ce-sample" i18n="@@connections.form.sampleKey"
                      >Sample project key for the check</label
                    >
                    <input
                      nz-input
                      id="ce-sample"
                      name="verifyProjectKey"
                      class="mono key-input"
                      [ngModel]="draft().verifyProjectKey"
                      (ngModelChange)="set('verifyProjectKey', ($event ?? '').toUpperCase())"
                      placeholder="SHOP"
                      i18n-placeholder="@@connections.form.sampleKeyPlaceholder"
                    />
                    <div class="hint" i18n="@@connections.form.sampleKeyHint">
                      Back Office checks the permissions in this Jira project.
                    </div>
                    @if (shown().verifyProjectKey) {
                      <div class="ferr">{{ shown().verifyProjectKey }}</div>
                    }
                  </div>
                }
              }
              <button type="submit" hidden></button>
            </form>

            @if (mode() !== 'rotate') {
              <aside class="guide" aria-labelledby="ce-guide-title">
                <h3 id="ce-guide-title">
                  @if (draft().tokenKind === 'SCOPED') {
                    <span i18n="@@connections.guide.titleScoped">Create a scoped API token</span>
                  } @else if (personal()) {
                    <span i18n="@@connections.guide.titlePersonal">Use your own classic token</span>
                  } @else if (guide().role === 'writer') {
                    <span i18n="@@connections.guide.titleWriter">Set up a writer account</span>
                  } @else if (guide().role === 'reader') {
                    <span i18n="@@connections.guide.titleReader">Set up a read-only account</span>
                  } @else {
                    <span i18n="@@connections.guide.titleNone">Set up the Jira account</span>
                  }
                </h3>
                @if (guide().steps.length) {
                  <ol>
                    @for (s of guide().steps; track $index) {
                      <li>{{ s }}</li>
                    }
                  </ol>
                } @else {
                  <p class="muted" i18n="@@connections.guide.pickCapability">
                    Pick the capabilities first: the steps follow them.
                  </p>
                }
                <ul class="notes">
                  @if (personal()) {
                    <li i18n="@@connections.guide.notePersonal">
                      A personal connection is only used for steps you approve or runs you start;
                      nobody else can see or use it.
                    </li>
                  } @else {
                    <li i18n="@@connections.guide.noteSeparate">
                      Use separate connections for reading and for writing: steps that only read
                      never get a token that can write.
                    </li>
                  }
                  <li i18n="@@connections.guide.noteScoped">
                    A scoped token is limited by its scopes, so it is least privilege even on a
                    personal account. A classic token inherits every permission of its account.
                  </li>
                  <li i18n="@@connections.guide.noteWriteScope">
                    Moving issues needs write:issue:jira, which also allows editing issues; Back
                    Office still only moves them, after your approval.
                  </li>
                </ul>
                <p class="links">
                  <bo-help-link topic="connections.leastPrivilege" variant="text"
                    ><span i18n="@@connections.guide.readLeastPrivilege"
                      >Least privilege, step by step</span
                    ></bo-help-link
                  >
                  <bo-help-link topic="admin.connections" variant="text"
                    ><span i18n="@@connections.guide.readConnections"
                      >How connections are picked</span
                    ></bo-help-link
                  >
                </p>
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
            i18n="@@connections.editor.done"
          >
            Done
          </button>
        } @else {
          <button
            nz-button
            type="button"
            (click)="closed.emit()"
            i18n="@@connections.editor.cancel"
          >
            Cancel
          </button>
          @if (mode() !== 'rotate') {
            <button
              nz-button
              type="button"
              [nzLoading]="testing()"
              [disabled]="saving()"
              (click)="test()"
              title="Checks the account and its Jira permissions now, without saving anything"
              i18n-title="@@connections.editor.testHint"
              i18n="@@connections.editor.test"
            >
              Test before saving
            </button>
          }
          <button nz-button nzType="primary" type="button" [nzLoading]="saving()" (click)="save()">
            @if (mode() === 'rotate') {
              <span i18n="@@connections.editor.saveToken">Replace token</span>
            } @else {
              <span i18n="@@connections.editor.saveCheck">Save and check</span>
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
        grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
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
        grid-template-columns: 140px minmax(0, 1fr);
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
      .kinds {
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .kind {
        display: flex;
        align-items: flex-start;
        margin: 0 !important;
        white-space: normal;
      }
      .ack {
        align-items: flex-start;
        font-size: 12.5px;
      }
      .inline-link {
        padding: 0 4px;
        height: auto;
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
      .key-input {
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
      .guide ol {
        margin: 0 0 10px;
        padding-left: 18px;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .notes {
        margin: 0 0 8px;
        padding: 8px 0 0 16px;
        border-top: 1px solid var(--bo-line);
        display: flex;
        flex-direction: column;
        gap: 4px;
        color: var(--bo-ink-3);
      }
      .links {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 14px;
        margin: 0;
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
export class ConnectionEditorComponent {
  private readonly api = inject(ConnectionsApi);

  readonly open = input(false);
  readonly owner = input.required<ConnectionOwner>();
  /** The connection to edit or rotate; null to create one. */
  readonly connection = input<Connection | null>(null);
  readonly mode = input<EditorMode>('create');

  readonly saved = output<Connection>();
  readonly closed = output<void>();

  readonly draft = signal<ConnectionDraft>(emptyDraft());
  readonly replacing = signal(false);
  readonly submitted = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly result = signal<Connection | null>(null);
  readonly testing = signal(false);
  /** Result of "Test before saving" for the current draft (cleared when the draft changes). */
  readonly tested = signal<ConnectionVerification | null>(null);

  readonly capabilityOptions = KNOWN_CAPABILITIES;
  readonly capLabel = capabilityLabel;
  readonly purpose = capabilityPurpose;
  readonly examples = {
    reader: 'backoffice-jira-reader@your-company', // i18n-ignore (example account)
    writer: 'backoffice-jira-writer@your-company', // i18n-ignore (example account)
    site: 'https://acme.atlassian.net', // i18n-ignore (example URL)
  };

  readonly title = computed(() => {
    const name = this.connection()?.name ?? '';
    switch (this.mode()) {
      case 'edit':
        return $localize`:@@connections.editor.editTitle:Edit connection ${name}:name:`;
      case 'rotate':
        return $localize`:@@connections.editor.rotateTitle:Replace the token of ${name}:name:`;
      default:
        return $localize`:@@connections.editor.createTitle:Add a Jira connection`;
    }
  });
  readonly personal = computed(() => this.owner().kind === 'personal');
  readonly guide = computed(() =>
    connectionGuide(
      this.draft().capabilities,
      this.draft().allowedProjectKeys,
      this.draft().tokenKind,
      this.personal(),
    ),
  );
  /** Scoped: capabilities the listed scopes do not cover, and write scopes nobody needs (live hints). */
  readonly uncovered = computed(() =>
    this.draft().tokenKind === 'SCOPED' && this.draft().tokenScopes.length
      ? uncoveredCapabilities(this.draft().capabilities, this.draft().tokenScopes)
      : [],
  );
  readonly extraWrites = computed(() =>
    this.draft().tokenKind === 'SCOPED'
      ? excessWriteScopes(this.draft().capabilities, this.draft().tokenScopes)
      : [],
  );
  readonly unknownCapabilities = computed(() =>
    this.draft().capabilities.filter((c) => !(KNOWN_CAPABILITIES as readonly string[]).includes(c)),
  );
  private readonly errors = computed<DraftErrors>(() => {
    const d = this.draft();
    if (this.mode() === 'rotate')
      return d.secret.trim()
        ? {}
        : {
            secret: $localize`:@@connections.form.error.secret:Paste the API token of the account.`,
          };
    return validateDraft(d, this.mode() === 'create');
  });
  /** Errors are shown after the first save attempt. */
  readonly shown = computed<DraftErrors>(() => (this.submitted() ? this.errors() : {}));

  constructor() {
    effect(() => {
      const isOpen = this.open();
      const c = this.connection();
      untracked(() => {
        if (!isOpen) return;
        this.draft.set(c ? draftOf(c) : emptyDraft());
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

  set<K extends keyof ConnectionDraft>(key: K, value: ConnectionDraft[K]): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
    this.tested.set(null);
  }

  has(c: string): boolean {
    return this.draft().capabilities.includes(c);
  }

  toggle(c: string, on: boolean): void {
    this.draft.update((d) => ({
      ...d,
      capabilities: on
        ? [...d.capabilities.filter((x) => x !== c), c]
        : d.capabilities.filter((x) => x !== c),
    }));
  }

  setKeys(values: string[]): void {
    this.set('allowedProjectKeys', normalizeProjectKeys(values ?? []));
  }

  setScopes(values: string[]): void {
    this.set('tokenScopes', normalizeScopes(values ?? []));
  }

  setKind(kind: 'CLASSIC' | 'SCOPED'): void {
    this.set('tokenKind', kind);
  }

  /** Fills the recommended granular scopes of the selected capabilities. */
  useRecommended(): void {
    this.set('tokenScopes', recommendedScopes(this.draft().capabilities));
  }

  permissionsText(c: string): string {
    const perms = permissionsFor([c]);
    const names = jiraPermissionNames(perms);
    return perms.length === 1
      ? $localize`:@@connections.capability.permission:Jira permission: ${names}:permissions:.`
      : $localize`:@@connections.capability.permissions:Jira permissions: ${names}:permissions:.`;
  }

  /** "Test before saving": the draft is checked against Jira; nothing is stored (ADR 0030). */
  test(): void {
    this.submitted.set(true);
    if (Object.keys(this.errors()).length || this.testing()) return;
    const existing = this.connection();
    const d = this.draft();
    const keepStored = this.mode() === 'edit' && !this.replacing();
    this.testing.set(true);
    this.error.set(null);
    this.tested.set(null);
    this.api
      .testDraft(
        this.owner(),
        toInput(keepStored ? { ...d, secret: '' } : d),
        this.mode() === 'edit' ? existing?.id : null,
        d.verifyProjectKey || null,
      )
      .subscribe({
        next: (v) => {
          this.testing.set(false);
          this.tested.set(v);
        },
        error: (err: unknown) => {
          this.testing.set(false);
          this.error.set(
            err instanceof HttpErrorResponse && err.status === 403
              ? $localize`:@@connections.editor.testForbidden:You may not test connections here.`
              : problemMessage(err),
          );
        },
      });
  }

  save(): void {
    this.submitted.set(true);
    if (Object.keys(this.errors()).length || this.saving()) return;
    const owner = this.owner();
    const existing = this.connection();
    const d = this.draft();
    let call: Observable<Connection>;
    if (this.mode() === 'rotate' && existing)
      call = this.api.rotateSecret(owner, existing.id, {
        secret: d.secret.trim(),
        secretExpiresAt: d.secretExpiresAt || null,
      });
    else if (this.mode() === 'edit' && existing)
      call = this.api.update(
        owner,
        existing.id,
        toInput(this.replacing() ? d : { ...d, secret: '' }),
      );
    else call = this.api.create(owner, toInput(d));
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
        // 401 / 403 / 0 are reported by the interceptor; still keep the dialog open with a hint.
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 403
            ? $localize`:@@connections.editor.forbidden:You may not change this connection.`
            : problemMessage(err),
        );
      },
    });
  }
}
