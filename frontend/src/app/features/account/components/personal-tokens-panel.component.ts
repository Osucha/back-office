import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { AuthApi } from '../../../core/api/auth.service';
import type { PersonalToken, PersonalTokenCreated } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { compareKeys } from '../../../core/i18n/compare';
import { icu } from '../../../core/i18n/icu';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { copyToClipboard } from '../../../core/ui/clipboard';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { TimeAgoPipe } from '../../../core/ui/pipes/time-ago.pipe';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import {
  DEFAULT_TOKEN_DAYS,
  maxTokenDays,
  tokenState,
  validateTokenForm,
} from '../data/personal-tokens';

/** The caller's personal access tokens (`bo_pat_...`): list, create (shown once), revoke. */
@Component({
  selector: 'app-personal-tokens-panel',
  imports: [
    DatePipe,
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzModalModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzSelectModule,
    NzCheckboxModule,
    NzAlertModule,
    TimeAgoPipe,
    BatonComponent,
    EmptyComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <p class="lead" i18n="@@account.tokens.lead">
        For scripts and the CLI: send <code>Authorization: Bearer bo_pat_…</code>. A token acts with
        your permissions, optionally narrowed, and stops working when it expires, is revoked or your
        account is deactivated.
      </p>
      @if (canCreate()) {
        <button nz-button nzType="primary" type="button" (click)="openCreate()">
          <nz-icon nzType="plus" /> <span i18n="@@account.tokens.create">Create token</span>
        </button>
      }
    </div>
    @if (loading() && !loaded()) {
      <bo-skeleton
        variant="table"
        [rows]="2"
        [columns]="6"
        label="Loading tokens"
        i18n-label="@@account.tokens.loading"
      />
    } @else if (!tokens().length) {
      <bo-empty
        tone="quiet"
        title="No personal access tokens."
        i18n-title="@@account.tokens.emptyTitle"
        detail="Create one for a script or your IDE; it is shown once."
        i18n-detail="@@account.tokens.emptyDetail"
        help="account.tokens"
      />
    } @else {
      <nz-table
        [nzData]="tokens()"
        [nzLoading]="loading()"
        [nzShowPagination]="false"
        nzSize="small"
      >
        <thead>
          <tr>
            <th i18n="@@account.tokens.colName">Name</th>
            <th nzWidth="140px" i18n="@@account.tokens.colPrefix">Prefix</th>
            <th i18n="@@account.tokens.colPermissions">Permissions</th>
            <th nzWidth="120px" i18n="@@account.tokens.colExpires">Expires</th>
            <th nzWidth="110px" i18n="@@account.tokens.colLastUsed">Last used</th>
            <th nzWidth="100px" i18n="@@account.tokens.colState">State</th>
            <th nzWidth="90px"></th>
          </tr>
        </thead>
        <tbody>
          @for (t of tokens(); track t.id) {
            <tr>
              <td class="name">{{ t.name }}</td>
              <td class="mono">{{ t.prefix }}…</td>
              <td>
                @for (p of t.permissions ?? []; track p) {
                  <span class="perm mono">{{ p }}</span>
                } @empty {
                  <span class="muted" i18n="@@account.tokens.allPermissions">All of yours</span>
                }
                @if (t.allowMcpGateDecisions) {
                  <span class="perm mono" i18n="@@account.tokens.mcpGatesTag">MCP gate decisions</span>
                }
              </td>
              <td class="num" [title]="t.expiresAt | date: 'medium'">
                {{ t.expiresAt | date: 'mediumDate' }}
              </td>
              <td class="num">
                @if (t.lastUsedAt) {
                  {{ t.lastUsedAt | timeAgo }}
                } @else {
                  <ng-container i18n="@@account.tokens.neverUsed">Never</ng-container>
                }
              </td>
              <td>
                <bo-baton
                  size="sm"
                  [holder]="
                    state(t) === 'active' ? 'done' : state(t) === 'revoked' ? 'blocked' : 'idle'
                  "
                  [label]="stateLabels[state(t)]"
                />
              </td>
              <td>
                @if (state(t) === 'active') {
                  <button
                    nz-button
                    nzSize="small"
                    nzDanger
                    type="button"
                    [nzLoading]="revoking() === t.id"
                    (click)="revoke(t)"
                    i18n="@@account.tokens.revoke"
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

    <nz-modal
      [nzVisible]="modalOpen()"
      [nzTitle]="created() ? text.createdTitle : text.createTitle"
      [nzFooter]="modalFooter"
      (nzOnCancel)="closeModal()"
    >
      <ng-container *nzModalContent>
        @if (created(); as c) {
          <nz-alert
            nzType="warning"
            nzShowIcon
            nzMessage="Copy the token now. It is shown only once."
            i18n-nzMessage="@@account.tokens.copyNow"
          />
          <div class="token-row">
            <input
              nz-input
              class="mono"
              [value]="c.token"
              readonly
              aria-label="New token"
              i18n-aria-label="@@account.tokens.newToken"
            />
            <button nz-button nzType="primary" type="button" (click)="copy(c.token)">
              <nz-icon nzType="copy" /> <span i18n="@@account.tokens.copy">Copy</span>
            </button>
          </div>
        } @else {
          <form nz-form nzLayout="vertical" (submit)="$event.preventDefault(); create()">
            <nz-form-item>
              <nz-form-label nzRequired nzFor="pat-name" i18n="@@account.tokens.name"
                >Name</nz-form-label
              >
              <nz-form-control
                [nzValidateStatus]="touched() && errors().name ? 'error' : ''"
                [nzErrorTip]="errors().name ?? ''"
              >
                <input
                  nz-input
                  id="pat-name"
                  name="name"
                  [(ngModel)]="name"
                  placeholder="release script"
                  i18n-placeholder="@@account.tokens.namePlaceholder"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzRequired nzFor="pat-days" i18n="@@account.tokens.days"
                >Expires in (days)</nz-form-label
              >
              <nz-form-control
                [nzValidateStatus]="touched() && errors().days ? 'error' : ''"
                [nzErrorTip]="errors().days ?? ''"
                [nzExtra]="maxDaysHint()"
              >
                <nz-input-number
                  id="pat-days"
                  name="days"
                  [(ngModel)]="days"
                  [nzMin]="1"
                  [nzMax]="maxDays()"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzFor="pat-perms" i18n="@@account.tokens.limit"
                >Limit to permissions (optional)</nz-form-label
              >
              <nz-form-control
                nzExtra="Empty = all of your permissions. A token never has more than you."
                i18n-nzExtra="@@account.tokens.limitHelp"
              >
                <nz-select
                  id="pat-perms"
                  name="perms"
                  nzMode="multiple"
                  [(ngModel)]="permissions"
                  nzPlaceHolder="All of my permissions"
                  i18n-nzPlaceHolder="@@account.tokens.limitPlaceholder"
                >
                  @for (p of myPermissions(); track p) {
                    <nz-option [nzValue]="p" [nzLabel]="p" />
                  }
                </nz-select>
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-control
                nzExtra="Lets an AI agent in your IDE approve or reject gates with this token. Off by default: text the agent reads can be prompt injection."
                i18n-nzExtra="@@account.tokens.mcpGatesHelp"
              >
                <label nz-checkbox name="mcpGates" [(ngModel)]="allowMcpGateDecisions">
                  <span i18n="@@account.tokens.mcpGates">Allow gate decisions via MCP</span>
                </label>
              </nz-form-control>
            </nz-form-item>
          </form>
        }
      </ng-container>
      <ng-template #modalFooter>
        @if (created()) {
          <button
            nz-button
            nzType="primary"
            type="button"
            (click)="closeModal()"
            i18n="@@account.tokens.copied"
          >
            I copied the token
          </button>
        } @else {
          <button nz-button type="button" (click)="closeModal()" i18n="@@account.tokens.cancel">
            Cancel
          </button>
          <button
            nz-button
            nzType="primary"
            type="button"
            [nzLoading]="creating()"
            (click)="create()"
            i18n="@@account.tokens.submit"
          >
            Create token
          </button>
        }
      </ng-template>
    </nz-modal>
  `,
  styles: [
    `
      .toolbar {
        display: flex;
        gap: 12px;
        align-items: flex-start;
        margin-bottom: 12px;
      }
      .lead {
        margin: 0;
        flex: 1;
        color: var(--bo-ink-2);
        font-size: var(--bo-fs-dense);
        max-width: 72ch;
      }
      .lead code {
        font-size: 12px;
        background: var(--bo-surface-3);
        padding: 1px 4px;
        border-radius: 4px;
      }
      .name {
        font-weight: 500;
      }
      .perm {
        display: inline-block;
        margin: 1px 4px 1px 0;
        padding: 0 6px;
        border-radius: var(--bo-radius-chip);
        background: var(--bo-surface-3);
        color: var(--bo-ink-2);
        font-size: 11px;
        line-height: 18px;
      }
      .token-row {
        display: flex;
        gap: 8px;
        margin-top: 12px;
      }
    `,
  ],
})
export class PersonalTokensPanelComponent {
  private readonly api = inject(AuthApi);
  private readonly identity = inject(IdentityService);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly pendingSaves = inject(PendingSavesService);

  /** `PlatformSettings.personalTokenMaxDays` when known. */
  readonly platformMaxDays = input<number | null>(null);

  readonly tokens = signal<PersonalToken[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly revoking = signal<string | null>(null);
  readonly modalOpen = signal(false);
  readonly creating = signal(false);
  readonly created = signal<PersonalTokenCreated | null>(null);
  readonly touched = signal(false);
  name = '';
  days: number | null = DEFAULT_TOKEN_DAYS;
  permissions: string[] = [];
  /** ADR 0029: decide_gate through the MCP server needs this explicit opt-in. */
  allowMcpGateDecisions = false;

  readonly canCreate = computed(() => this.identity.can('tokens.personal'));
  readonly maxDays = computed(() => maxTokenDays(this.platformMaxDays()));
  readonly myPermissions = computed(() => {
    const me = this.identity.me();
    const all = new Set(me?.platformPermissions ?? []);
    for (const p of me?.projects ?? []) for (const x of p.permissions ?? []) all.add(x);
    return [...all].sort(compareKeys);
  });
  readonly state = tokenState;
  readonly stateLabels: Record<ReturnType<typeof tokenState>, string> = {
    active: $localize`:@@account.tokens.stateActive:active`,
    expired: $localize`:@@account.tokens.stateExpired:expired`,
    revoked: $localize`:@@account.tokens.stateRevoked:revoked`,
  };
  readonly text = {
    createdTitle: $localize`:@@account.tokens.createdTitle:Token created`,
    createTitle: $localize`:@@account.tokens.createTitle:Create personal access token`,
  };
  readonly maxDaysHint = computed(() =>
    icu(
      $localize`:@@account.tokens.maxDays:{max, plural, one {Maximum # day.} other {Maximum # days.}}`,
      { max: this.maxDays() },
    ),
  );

  errors() {
    return validateTokenForm(this.name, this.days, this.maxDays());
  }

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.tokens().subscribe({
      next: (t) => {
        this.tokens.set(t);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  openCreate(): void {
    this.name = '';
    this.days = Math.min(DEFAULT_TOKEN_DAYS, this.maxDays());
    this.permissions = [];
    this.allowMcpGateDecisions = false;
    this.touched.set(false);
    this.created.set(null);
    this.modalOpen.set(true);
  }

  closeModal(): void {
    this.modalOpen.set(false);
    this.created.set(null);
  }

  create(): void {
    this.touched.set(true);
    const e = this.errors();
    if (e.name || e.days) return;
    this.creating.set(true);
    this.pendingSaves
      .track(
        this.api.createToken({
          name: this.name.trim(),
          expiresInDays: this.days!,
          permissions: this.permissions,
          allowMcpGateDecisions: this.allowMcpGateDecisions,
        }),
      )
      .subscribe({
        next: (c) => {
          this.creating.set(false);
          this.created.set(c);
          this.load();
        },
        error: () => this.creating.set(false),
      });
  }

  async revoke(t: PersonalToken): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@account.tokens.revokeTitle:Revoke token "${t.name}:name:"?`,
      $localize`:@@account.tokens.revokeContent:Scripts and tools that use it stop working immediately. This cannot be undone.`,
      $localize`:@@account.tokens.revokeConfirm:Revoke token`,
    );
    if (!ok || !t.id) return;
    this.revoking.set(t.id);
    this.api.revokeToken(t.id).subscribe({
      next: () => {
        this.revoking.set(null);
        this.message.success($localize`:@@account.tokens.revoked:Token revoked`);
        this.load();
      },
      error: () => this.revoking.set(null),
    });
  }

  async copy(text: string): Promise<void> {
    if (await copyToClipboard(text))
      this.message.success($localize`:@@account.tokens.tokenCopied:Token copied`);
    else
      this.message.warning(
        $localize`:@@account.tokens.clipboardUnavailable:Clipboard unavailable; select the token and copy it`,
      );
  }
}
