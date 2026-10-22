import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { FleetApi } from '../../../core/api/fleet.service';
import type {
  LlmCredentials,
  PersonalRegistrationToken,
  PersonalRunner,
  Runner,
} from '../../../core/api/types';
import { CodeBlockComponent } from '../../../core/ui/code-block.component';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { absoluteTime } from '../../../core/ui/relay/format';
import {
  BUILD_COMMANDS,
  forShell,
  setupCommands,
  sortRunners,
  type Shell,
} from '../data/personal-runner';

/**
 * Account → My computer as a runner (ADR 0040): a personal runner pool that runs the agent steps of your private
 * projects on your own computer (runner-controller with the DOCKER backend), optionally with your own Claude Code
 * login instead of the Back Office LLM gateway.
 */
@Component({
  selector: 'app-personal-runner-section',
  imports: [FormsModule, NzButtonModule, NzIconModule, NzRadioModule, CodeBlockComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="intro" i18n="@@account.runner.intro">
      Run the agent steps of your private projects on your own computer, like a GitLab runner: a
      small controller in Docker asks Back Office for work and starts each step in a sandbox
      container. Team projects keep using the shared runner fleet.
    </p>

    @if (!loaded()) {
      <div class="ph" aria-hidden="true"></div>
    } @else {
      <fieldset class="credentials">
        <legend i18n="@@account.runner.credentials">How Claude Code reaches the model</legend>
        <nz-radio-group
          [ngModel]="credentials()"
          (ngModelChange)="choose($event)"
          [nzDisabled]="busy()"
        >
          <label nz-radio nzValue="LOCAL_LOGIN">
            <span i18n="@@account.runner.localLogin"
              >My own Claude login (subscription) - not metered by Back Office</span
            >
          </label>
          <label nz-radio nzValue="GATEWAY">
            <span i18n="@@account.runner.gateway"
              >Back Office LLM gateway - metered, budgets apply</span
            >
          </label>
        </nz-radio-group>
        @if (credentials() === 'LOCAL_LOGIN') {
          <p class="warn" i18n="@@account.runner.localLoginWarning">
            Check that your Claude licence allows automated use like this. The login token is
            available to the agent inside the sandbox, so use it only for repositories and issues
            you trust. OpenCode steps always use the gateway.
          </p>
        }
      </fieldset>

      @if (runner(); as r) {
        <h3 class="sub" i18n="@@account.runner.computers">Connected computers</h3>
        <ul class="runners">
          @for (n of runners(); track n.id) {
            <li>
              <span class="dot" [class.on]="n.online" aria-hidden="true"></span>
              <span class="name">{{ n.name }}</span>
              @if (n.online) {
                <span class="state" i18n="@@account.runner.online">online</span>
              } @else {
                <span
                  class="state muted"
                  [title]="abs(n.lastSeenAt)"
                  i18n="@@account.runner.lastSeen"
                  >offline, last seen {{ ago(n.lastSeenAt) }}</span
                >
              }
              <span class="muted">{{ n.version }}</span>
              <button
                nz-button
                nzSize="small"
                nzDanger
                type="button"
                [nzLoading]="revoking() === n.id"
                (click)="revoke(n)"
                i18n="@@account.runner.revoke"
              >
                Disconnect
              </button>
            </li>
          } @empty {
            <li class="muted" i18n="@@account.runner.none">
              No computer connected yet. Steps of your private projects wait until one is.
            </li>
          }
        </ul>
        <p class="muted small" i18n="@@account.runner.queue">
          Waiting: {{ r.queued }} · running: {{ r.running }}
        </p>
      }

      <div class="actions">
        <button
          nz-button
          nzType="primary"
          type="button"
          [nzLoading]="busy()"
          (click)="connect()"
          i18n="@@account.runner.connect"
        >
          Connect this computer
        </button>
        @if (runner()) {
          <button
            nz-button
            type="button"
            nzDanger
            (click)="remove()"
            i18n="@@account.runner.remove"
          >
            Remove my personal runner
          </button>
        }
      </div>

      @if (issued(); as t) {
        <div
          class="tabs"
          role="tablist"
          aria-label="Shell"
          i18n-aria-label="@@account.runner.shell"
        >
          @for (s of shells; track s.id) {
            <button
              type="button"
              role="tab"
              class="tab"
              [attr.aria-selected]="shell() === s.id"
              [class.on]="shell() === s.id"
              (click)="shell.set(s.id)"
            >
              {{ s.label }}
            </button>
          }
        </div>
        <ol class="steps">
          <li>
            <span i18n="@@account.runner.stepImages"
              >Docker must be running. Build the three images once, in the root of the Back Office
              repository (skip it when your organisation publishes them):</span
            >
            <app-code-block
              label="Build"
              i18n-label="@@account.runner.buildLabel"
              [code]="buildCommands"
            />
          </li>
          @if (setup()) {
            <li>
              <span i18n="@@account.runner.stepSecrets"
                >Put your secrets into the shell (they stay on your computer):</span
              >
              <app-code-block
                label="Shell"
                i18n-label="@@account.runner.secretsLabel"
                [code]="setup()"
              />
            </li>
          }
          <li>
            <span i18n="@@account.runner.stepRun"
              >Start the runner in the same shell. The token works once and expires on
              {{ abs(t.expiresAt) }}:</span
            >
            <app-code-block
              label="docker run"
              [code]="command()"
              [secret]="true"
              [primary]="true"
            />
          </li>
        </ol>
      }
    }
  `,
  styles: [
    `
      .intro {
        margin: 0 0 12px;
        font-size: 13px;
        color: var(--bo-ink-2);
        max-width: 80ch;
      }
      .credentials {
        margin: 0 0 12px;
        padding: 0;
        border: 0;
      }
      .credentials legend {
        font-size: var(--bo-fs-dense);
        font-weight: 600;
        margin-bottom: 6px;
      }
      .credentials label {
        display: block;
        margin-bottom: 4px;
      }
      .warn {
        margin: 8px 0 0;
        padding: 8px 10px;
        border-radius: 6px;
        font-size: 12px;
        background: var(--bo-warn-soft, var(--bo-surface-2));
        border: 1px solid var(--bo-line);
        max-width: 80ch;
      }
      .sub {
        margin: 16px 0 8px;
        font-size: var(--bo-fs-dense);
        font-weight: 600;
      }
      .runners {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 6px;
        font-size: 13px;
      }
      .runners li {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
      }
      .dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: var(--bo-ink-3);
      }
      .dot.on {
        background: var(--bo-success, #2e9d5b);
      }
      .name {
        font-weight: 500;
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
        margin: 6px 0 0;
      }
      .actions {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
        margin: 12px 0;
      }
      .tabs {
        display: inline-flex;
        gap: 2px;
        padding: 3px;
        margin-bottom: 8px;
        border-radius: 8px;
        background: var(--bo-surface-3);
        border: 1px solid var(--bo-line);
      }
      .tab {
        height: 26px;
        padding: 0 12px;
        border: 0;
        border-radius: 6px;
        background: transparent;
        color: var(--bo-ink-2);
        font-size: 13px;
        font-weight: 500;
        cursor: pointer;
      }
      .tab.on {
        background: var(--bo-surface);
        color: var(--bo-ink);
        box-shadow: 0 0 0 1px var(--bo-line);
      }
      .steps {
        margin: 0;
        padding-left: 20px;
        display: grid;
        gap: 8px;
        font-size: 13px;
      }
      code {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .ph {
        height: 80px;
      }
    `,
  ],
})
export class PersonalRunnerSectionComponent implements OnInit {
  private readonly api = inject(FleetApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);

  readonly shells: { id: Shell; label: string }[] = [
    { id: 'bash', label: 'bash / zsh' },
    { id: 'powershell', label: 'PowerShell' },
  ];

  readonly buildCommands = BUILD_COMMANDS;
  readonly runner = signal<PersonalRunner | null>(null);
  readonly loaded = signal(false);
  readonly busy = signal(false);
  readonly revoking = signal<string | null>(null);
  readonly issued = signal<PersonalRegistrationToken | null>(null);
  readonly shell = signal<Shell>(navigator.userAgent.includes('Windows') ? 'powershell' : 'bash');
  /** Before the pool exists: what it will be created with. */
  private readonly wanted = signal<LlmCredentials>('LOCAL_LOGIN');

  readonly credentials = computed<LlmCredentials>(
    () => this.runner()?.llmCredentials ?? this.wanted(),
  );
  readonly runners = computed<Runner[]>(() => sortRunners(this.runner()?.runners ?? []));
  readonly command = computed(() => forShell(this.issued()?.dockerCommand ?? '', this.shell()));
  readonly setup = computed(() =>
    setupCommands(this.shell(), this.credentials() === 'LOCAL_LOGIN'),
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.myRunner().subscribe({
      next: (r) => {
        this.runner.set(r ?? null);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  choose(value: LlmCredentials): void {
    if (!this.runner()) {
      this.wanted.set(value);
      return;
    }
    this.busy.set(true);
    this.api.enableMyRunner(value).subscribe({
      next: (r) => {
        this.runner.set(r);
        this.issued.set(null);
        this.busy.set(false);
        this.message.success(
          $localize`:@@account.runner.saved:Saved. Restart your runner with the new command.`,
        );
      },
      error: () => this.busy.set(false),
    });
  }

  /** Creates the pool when needed, then a single-use registration token and the command. */
  connect(): void {
    this.busy.set(true);
    this.api.enableMyRunner(this.credentials()).subscribe({
      next: (r) => {
        this.runner.set(r);
        this.api.createMyRunnerToken().subscribe({
          next: (t) => {
            this.issued.set(t);
            this.busy.set(false);
          },
          error: () => this.busy.set(false),
        });
      },
      error: () => this.busy.set(false),
    });
  }

  async revoke(n: Runner): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@account.runner.revokeTitle:Disconnect "${n.name}:name:"?`,
      $localize`:@@account.runner.revokeContent:Its runner token stops working; steps it is running go back to the queue.`,
      $localize`:@@account.runner.revokeConfirm:Disconnect`,
    );
    if (!ok || !n.id) return;
    this.revoking.set(n.id);
    this.api.revokeMyRunner(n.id).subscribe({
      next: () => {
        this.revoking.set(null);
        this.load();
      },
      error: () => this.revoking.set(null),
    });
  }

  async remove(): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@account.runner.removeTitle:Remove your personal runner?`,
      $localize`:@@account.runner.removeContent:Your computers are disconnected and steps of your private projects use the shared runner fleet again.`,
      $localize`:@@account.runner.removeConfirm:Remove`,
    );
    if (!ok) return;
    this.api.disableMyRunner().subscribe(() => {
      this.runner.set(null);
      this.issued.set(null);
    });
  }

  ago(at: string | null | undefined): string {
    return at ? timeAgo(at) : '-';
  }

  abs(at: string | null | undefined): string {
    return at ? absoluteTime(at) : '';
  }
}
