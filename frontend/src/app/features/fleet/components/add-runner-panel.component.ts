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
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { FleetApi } from '../../../core/api/fleet.service';
import type { RegistrationToken, RunnerPool } from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { CodeBlockComponent } from '../../../core/ui/code-block.component';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import {
  expiresIn,
  fallbackHelmValues,
  helmInstallCommand,
  runnerRelease,
} from '../data/fleet-view';

/**
 * "Add runner": a small state machine in a side panel. `intro` explains what happens; "Create
 * registration token" calls `POST /admin/runner-pools/{id}/registration-tokens`; `created` shows the
 * single-use token once, the ready-to-paste values for the `back-office-runner` chart and the
 * `helm install` command. Closing the panel forgets the token.
 */
@Component({
  selector: 'app-add-runner-panel',
  imports: [
    NzAlertModule,
    NzButtonModule,
    NzIconModule,
    CodeBlockComponent,
    HelpLinkComponent,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      [open]="open() && !!pool()"
      [title]="title()"
      [subtitle]="pool()?.id ?? null"
      [defaultWidth]="640"
      storageKey="backoffice.addRunnerPanelWidth"
      bodyLabel="Add runner"
      i18n-bodyLabel="@@fleet.addRunner.bodyLabel"
      (closed)="close()"
    >
      @if (!token()) {
        <ol class="steps">
          <li i18n="@@fleet.addRunner.step1">
            <strong>Create a registration token.</strong> It is single use and expires after 24
            hours.
          </li>
          <li i18n="@@fleet.addRunner.step2">
            <strong>Install the <code>back-office-runner</code> chart</strong> in the execution
            cluster with the values shown next. The runner-controller registers itself, exchanges
            the token for a runner token and starts leasing jobs of this pool.
          </li>
          <li i18n="@@fleet.addRunner.step3">
            <strong>Watch it come online</strong> in Runners. Back Office never gets credentials for
            that cluster: the controller only calls out.
          </li>
        </ol>
        <p class="note">
          <ng-container i18n="@@fleet.addRunner.prerequisites"
            >Prerequisites in the cluster: the RuntimeClass <code>gvisor</code> (and
            <code>kata</code> for High isolation), a CNI that enforces NetworkPolicy, and optionally
            the Agent Sandbox CRDs for warm pools.</ng-container
          >
          <bo-help-link topic="fleet.addRunner" variant="text" i18n="@@fleet.addRunner.fullSteps"
            >Full steps</bo-help-link
          >
        </p>
        <p class="note">
          <ng-container i18n="@@fleet.addRunner.gitNote"
            >Agents clone and push with git credentials that stay in that cluster: per repository a
            read-only token for jobs that only read and a push token (can open a merge request,
            never merge) for jobs that push, as Secrets in the sandbox namespace (chart value
            <code>gitCredentials.repoSecrets</code>).</ng-container
          >
          <bo-help-link topic="fleet.gitAccess" variant="text" i18n="@@fleet.addRunner.gitAccess"
            >Set up git access</bo-help-link
          >
        </p>
        <button
          nz-button
          nzType="primary"
          type="button"
          [nzLoading]="busy()"
          (click)="create()"
          data-testid="create-registration-token"
          i18n="@@fleet.addRunner.createToken"
        >
          Create registration token
        </button>
      } @else if (token(); as t) {
        <nz-alert
          nzType="warning"
          nzShowIcon
          nzMessage="Copy the token now: it is shown only once."
          i18n-nzMessage="@@fleet.addRunner.copyNow"
          [nzDescription]="tokenDescription()"
        />
        <app-code-block
          class="gap"
          label="Registration token"
          i18n-label="@@fleet.addRunner.tokenLabel"
          [code]="t.token ?? ''"
          [secret]="true"
          [primary]="true"
        />
        <h3 i18n="@@fleet.addRunner.saveValues">1. Save the chart values</h3>
        <p class="note">
          @if (t.helmValues) {
            <ng-container i18n="@@fleet.addRunner.saveAs"
              >As <code>{{ release().valuesFile }}</code
              >.</ng-container
            >
          } @else {
            <ng-container i18n="@@fleet.addRunner.saveAsBuilt"
              >As <code>{{ release().valuesFile }}</code> (built here: the API returned no
              values).</ng-container
            >
          }
        </p>
        <app-code-block [label]="release().valuesFile" [code]="values()" />
        <h3 i18n="@@fleet.addRunner.installChart">2. Install the runner chart</h3>
        <app-code-block [label]="helmLabel" [code]="command()" />
        <p class="note">
          <ng-container i18n="@@fleet.addRunner.appears"
            >The runner appears under Runners within a minute. Its version, backends and runtime
            classes come from the controller. Agents need git credentials in that cluster (<code
              >gitCredentials</code
            >
            in the values).</ng-container
          >
          <bo-help-link topic="fleet.gitAccess" variant="text" i18n="@@fleet.addRunner.gitAccess"
            >Set up git access</bo-help-link
          >
        </p>
        <div class="actions">
          <button nz-button type="button" (click)="close()" i18n="@@fleet.addRunner.done">
            Done
          </button>
        </div>
      }
    </bo-side-panel>
  `,
  styles: [
    `
      .steps {
        margin: 0 0 16px;
        padding-left: 20px;
        display: grid;
        gap: 10px;
        font-size: 13px;
        line-height: 1.5;
      }
      .note {
        font-size: 12.5px;
        color: var(--bo-ink-2);
        margin: 0 0 16px;
        line-height: 1.5;
      }
      code {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      h3 {
        margin: 20px 0 6px;
        font-size: 13px;
        font-weight: 600;
      }
      .gap {
        margin-top: 12px;
      }
      .actions {
        display: flex;
        justify-content: flex-end;
      }
    `,
  ],
})
export class AddRunnerPanelComponent {
  private readonly api = inject(FleetApi);
  readonly pool = input<RunnerPool | null>(null);
  readonly open = input(false);
  readonly closed = output<void>();
  readonly created = output<void>();

  readonly token = signal<RegistrationToken | null>(null);
  readonly busy = signal(false);
  readonly helmLabel = 'helm install'; // i18n-ignore: command
  readonly release = computed(() => runnerRelease(this.pool()?.id ?? 'pool'));
  readonly command = computed(() => helmInstallCommand(this.pool()?.id ?? 'pool'));
  readonly expires = computed(() => expiresIn(this.token()?.expiresAt));
  readonly title = computed(() => {
    const name = this.pool()?.name;
    return name
      ? $localize`:@@fleet.addRunner.title:Add a runner to ${name}:pool:`
      : $localize`:@@fleet.addRunner.titleNoPool:Add a runner to the pool`;
  });
  readonly tokenDescription = computed(
    () =>
      $localize`:@@fleet.addRunner.tokenDescription:Single use, expires ${this.expires()}:expires:. Creating another one does not revoke this one.`,
  );
  readonly values = computed(() => {
    const t = this.token();
    if (!t) return '';
    return (
      t.helmValues?.trim() ||
      fallbackHelmValues(location.origin, this.pool()?.id ?? '', t.token ?? '')
    );
  });

  constructor() {
    // A different pool (or closing) never shows another pool's token.
    effect(() => {
      this.pool();
      if (!this.open()) untracked(() => this.token.set(null));
    });
  }

  create(): void {
    const id = this.pool()?.id;
    if (!id) return;
    this.busy.set(true);
    this.api.createRegistrationToken(id).subscribe({
      next: (t) => {
        this.token.set(t);
        this.busy.set(false);
        this.created.emit();
      },
      error: () => this.busy.set(false),
    });
  }

  close(): void {
    this.token.set(null);
    this.closed.emit();
  }
}
