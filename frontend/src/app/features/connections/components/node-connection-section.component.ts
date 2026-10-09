import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { Subject, catchError, of, switchMap } from 'rxjs';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import {
  ALLOW_ACTING_USER_KEY,
  FALLBACK_KEY,
  actAsOf,
  capabilityLabel,
  configFlag,
  isWriteCapability,
  pinOptions,
  pinnedConnection,
  resolutionView,
  sortCapabilities,
  withActAs,
  withFlag,
  type ActAs,
} from '../data/connection-model';
import type { Connection, ConnectionResolution } from '../data/connection-types';
import { ConnectionsApi } from '../data/connections-api.service';
import { CONNECTION_CHIP_STYLES } from './chip-styles';

interface ResolveKey {
  projectId: string;
  capabilities: string[];
  pinned: string | null;
}

/**
 * "Jira connection" of a Jira task node in the workflow designer (ADR 0030, ADR 0035): the capabilities
 * the step needs and **Act as**: the project's system connection (default; still checked for the
 * step's capabilities), a specific connection (`jiraConnection` = its id), or the person who approves
 * the write / starts the run (`ACTING_USER`, their own personal connection; `fallbackToSystem` lets the
 * system connection write when they have none). Write steps on the system connection may let the
 * approver switch to their own connection (`allowActingUser`). Shows the connection the step would use.
 */
@Component({
  selector: 'app-node-connection-section',
  imports: [FormsModule, NzCheckboxModule, NzRadioModule, NzSelectModule, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ncs">
      <div class="needs">
        <span class="label" i18n="@@connections.node.needs">needs:</span>
        @for (c of required(); track c) {
          <span class="cchip cap">{{ capLabel(c) }}</span>
        }
      </div>

      <fieldset class="actas" [disabled]="readonly()">
        <legend class="label" i18n="@@connections.node.actAs">Act as</legend>
        <nz-radio-group
          [ngModel]="actAs()"
          (ngModelChange)="setActAs($event)"
          [nzDisabled]="readonly()"
          [name]="selectId + '-actas'"
          class="modes"
        >
          <label nz-radio nzValue="SYSTEM" i18n="@@connections.node.actAs.system"
            >System connection of the project</label
          >
          <label nz-radio nzValue="SPECIFIC" i18n="@@connections.node.actAs.specific"
            >A specific connection</label
          >
          <label nz-radio nzValue="ACTING_USER">
            @if (writes()) {
              <span i18n="@@connections.node.actAs.approver">The person who approves the write</span>
            } @else {
              <span i18n="@@connections.node.actAs.starter">The person who starts the run</span>
            }
          </label>
        </nz-radio-group>
      </fieldset>

      @if (actAs() === 'SPECIFIC') {
        <label class="label" [attr.for]="selectId" i18n="@@connections.node.pinLabel"
          >Connection</label
        >
        <nz-select
          [nzId]="selectId"
          nzSize="small"
          [ngModel]="pinned()"
          (ngModelChange)="pin($event)"
          [nzDisabled]="readonly() || !projectId()"
          nzPlaceHolder="Pick a connection"
          i18n-nzPlaceHolder="@@connections.node.pickConnection"
          class="picker"
        >
          @for (o of options(); track o.id) {
            <nz-option
              [nzValue]="o.id"
              [nzLabel]="o.disabled ? o.label + ' - ' + o.reason : o.label"
              [nzDisabled]="o.disabled"
            />
          }
          @if (pinned() && !knownPinned()) {
            <nz-option [nzValue]="pinned()" [nzLabel]="text.unknownPinned" />
          }
        </nz-select>
      }

      @if (actAs() === 'ACTING_USER') {
        <p class="state info">
          @if (writes()) {
            <span i18n="@@connections.node.actingWrite"
              >Writes through the personal connection of the person who approves it (My account →
              Connections), in their name. Nobody else's connection is ever used.</span
            >
          } @else {
            <span i18n="@@connections.node.actingRead"
              >Reads through the personal connection of the person who starts the run. Runs started by
              the Jira intake watcher or CI have no such person.</span
            >
          }
        </p>
        <label
          nz-checkbox
          class="flag"
          [ngModel]="fallback()"
          (ngModelChange)="setFlag(fallbackKey, $event)"
          [nzDisabled]="readonly()"
        >
          <span i18n="@@connections.node.fallback"
            >Without a personal connection, the system connection may be used</span
          >
        </label>
      }

      @if (actAs() === 'SYSTEM' && writes()) {
        <label
          nz-checkbox
          class="flag"
          [ngModel]="allowActingUser()"
          (ngModelChange)="setFlag(allowKey, $event)"
          [nzDisabled]="readonly()"
        >
          <span i18n="@@connections.node.allowActingUser"
            >The approver may write with their own personal connection instead</span
          >
        </label>
      }

      @if (!projectId()) {
        <p class="quiet" i18n="@@connections.node.noProject">
          Save the workflow in a project to see which connection it uses.
        </p>
      } @else if (showResolution()) {
        @if (view(); as v) {
          <p class="state" [class]="v.tone" aria-live="polite">
            @if (actAs() === 'ACTING_USER') {
              <span i18n="@@connections.node.fallbackIs">Fallback:</span>
            }
            {{ v.text }}
            @if (v.guide) {
              <bo-help-link topic="connections.leastPrivilege" variant="text"
                ><span i18n="@@connections.node.guide">How to set up connections</span></bo-help-link
              >
            }
          </p>
        } @else if (loading()) {
          <p class="quiet" i18n="@@connections.node.resolving">Finding the connection…</p>
        }
      }
      <bo-help-link topic="connections.actAs" variant="text"
        ><span i18n="@@connections.node.actAsHelp">About Act as</span></bo-help-link
      >
    </div>
  `,
  styles: [
    CONNECTION_CHIP_STYLES,
    `
      .ncs {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin-bottom: 8px;
      }
      .needs {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        align-items: center;
      }
      .label {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      fieldset {
        border: 0;
        margin: 0;
        padding: 0;
        min-width: 0;
      }
      .actas legend {
        border: 0;
        width: auto;
        margin: 0 0 2px;
        padding: 0;
        line-height: 1.4;
      }
      .modes {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .modes label {
        font-size: 12.5px;
        white-space: normal;
      }
      .flag {
        font-size: 12px;
      }
      .state {
        margin: 0;
        font-size: 12px;
        padding: 6px 8px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
        color: var(--bo-ink-2);
      }
      .state.ok {
        background: var(--bo-done-soft);
        color: var(--bo-done-ink);
      }
      .state.warn {
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
      }
      .state.bad {
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
      .quiet {
        margin: 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .picker {
        width: 100%;
      }
    `,
  ],
})
export class NodeConnectionSectionComponent {
  private readonly api = inject(ConnectionsApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly projectId = input<string | null>(null);
  /** `PaletteTask.requiredCapabilities` of the node's template. */
  readonly requiredCapabilities = input<readonly string[]>([]);
  /** The node's `overrides.config`. */
  readonly config = input<Record<string, unknown> | null | undefined>(null);
  readonly readonly = input(false);

  /** The new `overrides.config` (undefined when it became empty). */
  readonly configChange = output<Record<string, unknown> | undefined>();

  readonly selectId = `ncs-${Math.random().toString(36).slice(2, 8)}`;
  readonly fallbackKey = FALLBACK_KEY;
  readonly allowKey = ALLOW_ACTING_USER_KEY;
  readonly text = {
    unknownPinned: $localize`:@@connections.node.unknownPinned:Pinned connection (not available)`,
  };
  readonly capLabel = capabilityLabel;

  readonly required = computed(() => sortCapabilities(this.requiredCapabilities()));
  /** The step writes to Jira (comment, transition): the acting person is the approver. */
  readonly writes = computed(() => this.required().some(isWriteCapability));
  readonly actAs = computed(() => actAsOf(this.config()));
  readonly pinned = computed(() => pinnedConnection(this.config()));
  readonly fallback = computed(() => configFlag(this.config(), FALLBACK_KEY));
  readonly allowActingUser = computed(() => configFlag(this.config(), ALLOW_ACTING_USER_KEY));
  /** ACTING_USER shows the system resolution only when it is the fallback. */
  readonly showResolution = computed(() => this.actAs() !== 'ACTING_USER' || this.fallback());
  readonly usable = signal<Connection[]>([]);
  readonly options = computed(() => pinOptions(this.usable(), this.required()));
  readonly knownPinned = computed(() => this.usable().some((c) => c.id === this.pinned()));
  readonly resolution = signal<ConnectionResolution | null>(null);
  readonly loading = signal(false);
  readonly view = computed(() => {
    const r = this.resolution();
    return r ? resolutionView(r) : null;
  });

  private readonly resolve$ = new Subject<ResolveKey | null>();
  private lastKey = '';

  constructor() {
    effect(() => {
      const id = this.projectId();
      untracked(() => {
        this.usable.set([]);
        if (id)
          this.api
            .usable(id)
            .pipe(catchError(() => of([] as Connection[])))
            .subscribe((list) => this.usable.set(list));
      });
    });
    effect(() => {
      const projectId = this.projectId();
      const capabilities = this.required();
      const pinned = this.pinned();
      const shown = this.showResolution();
      const key = JSON.stringify([projectId, capabilities, pinned, shown]);
      untracked(() => {
        if (key === this.lastKey) return;
        this.lastKey = key;
        this.resolve$.next(
          projectId && capabilities.length && shown ? { projectId, capabilities, pinned } : null,
        );
      });
    });
    this.resolve$
      .pipe(
        switchMap((k) => {
          this.resolution.set(null);
          if (!k) return of(null);
          this.loading.set(true);
          return this.api
            .resolve(k.projectId, k.capabilities, k.pinned)
            .pipe(catchError(() => of(null)));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => {
        this.loading.set(false);
        this.resolution.set(r);
      });
  }

  setActAs(mode: ActAs): void {
    if (this.readonly() || mode === this.actAs()) return;
    // a specific connection starts with the first one that covers the step
    const first = mode === 'SPECIFIC' ? (this.options().find((o) => !o.disabled)?.id ?? null) : null;
    this.configChange.emit(withActAs(this.config(), mode, first));
  }

  pin(id: string | null): void {
    if (this.readonly() || !id || id === this.pinned()) return;
    this.configChange.emit(withActAs(this.config(), 'SPECIFIC', id));
  }

  setFlag(key: string, on: boolean): void {
    if (this.readonly()) return;
    this.configChange.emit(withFlag(this.config(), key, on));
  }
}
