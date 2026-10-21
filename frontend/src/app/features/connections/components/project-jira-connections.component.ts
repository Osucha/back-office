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
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { NotifyService } from '../../../core/ui/notify.service';
import {
  capabilityLabels,
  moveItem,
  tokenKindLabel,
  verificationChip,
} from '../data/connection-model';
import type { Connection, ProjectJiraConnections } from '../data/connection-types';
import { ConnectionsApi } from '../data/connections-api.service';
import { CONNECTION_CHIP_STYLES } from './chip-styles';

/**
 * Project → Connections, "Jira connections of this project" (ADR 0035): the system connection (the
 * default of every Jira step, chosen from the project's and the platform's connections) and an ordered
 * list of further connections the project's steps may use. A step whose system connection lacks a
 * capability takes the least-privileged connection of this list. Personal connections never appear
 * here. Reading needs `project.view`, changing `project.connections.manage`.
 */
@Component({
  selector: 'app-project-jira-connections',
  imports: [FormsModule, NzButtonModule, NzIconModule, NzSelectModule, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pjc">
      <div class="field">
        <label for="pjc-system" class="label" i18n="@@connections.projectJira.system"
          >System connection</label
        >
        <nz-select
          nzId="pjc-system"
          class="picker"
          [ngModel]="systemId() ?? none"
          (ngModelChange)="setSystem($event)"
          [nzDisabled]="!canManage()"
        >
          <nz-option [nzValue]="none" [nzLabel]="text.noSystem" />
          @for (c of available(); track c.id) {
            <nz-option [nzValue]="c.id" [nzLabel]="optionLabel(c)" />
          }
        </nz-select>
        <p class="hint" i18n="@@connections.projectJira.systemHint">
          Every Jira step uses it unless the step chooses otherwise. It is still checked for the
          step's capabilities: when it lacks one, the step takes the least-privileged connection of
          the list below. Without a system connection, steps pick from every project and platform
          connection.
        </p>
        @if (systemConnection(); as s) {
          <p class="chips">
            <span class="cchip" [class]="vchip(s).tone">{{ vchip(s).label }}</span>
            <span class="cchip">{{ kind(s.tokenKind) }}</span>
          </p>
        }
      </div>

      <div class="field">
        <span class="label" id="pjc-list" i18n="@@connections.projectJira.list"
          >Further connections for this project's steps</span
        >
        @if (!additional().length) {
          <p class="quiet" i18n="@@connections.projectJira.listEmpty">
            None. Steps can only use the system connection, a connection they name, or the personal
            connection of the person who approves or starts them.
          </p>
        } @else {
          <ol class="list" aria-labelledby="pjc-list">
            @for (c of additional(); track c.id; let i = $index; let last = $last) {
              <li>
                <span class="name">{{ c.name }}</span>
                <span class="sub">{{ scopeLabel(c) }} · {{ caps(c) }}</span>
                @if (canManage()) {
                  <span class="actions">
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      [disabled]="i === 0"
                      (click)="move(i, -1)"
                      [attr.aria-label]="upLabel(c)"
                    >
                      <nz-icon nzType="arrow-up" />
                    </button>
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      [disabled]="last"
                      (click)="move(i, 1)"
                      [attr.aria-label]="downLabel(c)"
                    >
                      <nz-icon nzType="arrow-down" />
                    </button>
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      (click)="removeAt(i)"
                      [attr.aria-label]="removeLabel(c)"
                    >
                      <nz-icon nzType="close" />
                    </button>
                  </span>
                }
              </li>
            }
          </ol>
        }
        @if (canManage() && addable().length) {
          <nz-select
            class="picker"
            nzShowSearch
            [ngModel]="null"
            (ngModelChange)="add($event)"
            nzPlaceHolder="Add a connection to the list"
            i18n-nzPlaceHolder="@@connections.projectJira.add"
            aria-label="Add a connection to the list"
            i18n-aria-label="@@connections.projectJira.addLabel"
          >
            @for (c of addable(); track c.id) {
              <nz-option [nzValue]="c.id" [nzLabel]="optionLabel(c)" />
            }
          </nz-select>
        }
      </div>

      @if (canManage()) {
        <div class="footer">
          <bo-help-link topic="project.jiraConnections" variant="text"
            ><span i18n="@@connections.projectJira.help"
              >How steps choose a connection</span
            ></bo-help-link
          >
          <button
            nz-button
            nzType="primary"
            type="button"
            [disabled]="!dirty()"
            [nzLoading]="saving()"
            (click)="save()"
            i18n="@@connections.projectJira.save"
          >
            Save Jira connections
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    CONNECTION_CHIP_STYLES,
    `
      .pjc {
        display: flex;
        flex-direction: column;
        gap: 14px;
        max-width: 760px;
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .label {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      .picker {
        max-width: 520px;
        width: 100%;
      }
      .hint,
      .quiet {
        margin: 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .chips {
        margin: 0;
        display: flex;
        gap: 4px;
      }
      .list {
        margin: 0;
        padding: 0;
        list-style: none;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .list li {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 6px 10px;
        border-bottom: 1px solid var(--bo-line);
      }
      .list li:last-child {
        border-bottom: 0;
      }
      .name {
        font-weight: 500;
      }
      .sub {
        flex: 1;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .actions {
        display: inline-flex;
      }
      .footer {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
    `,
  ],
})
export class ProjectJiraConnectionsComponent {
  private readonly api = inject(ConnectionsApi);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);

  readonly projectId = input.required<string>();
  readonly canManage = input(false);
  /** Bumped by the host when connections were added or removed, to reload the choices. */
  readonly refresh = input(0);

  readonly none = '';
  readonly text = {
    noSystem: $localize`:@@connections.projectJira.noSystem:None: least privilege over every connection`,
  };

  private readonly loaded = signal<ProjectJiraConnections | null>(null);
  readonly systemId = signal<string | null>(null);
  readonly additionalIds = signal<string[]>([]);
  readonly saving = signal(false);

  readonly available = computed<Connection[]>(() => this.loaded()?.available ?? []);
  private readonly byId = computed(() => new Map(this.available().map((c) => [c.id, c])));
  readonly systemConnection = computed(() => {
    const id = this.systemId();
    return id ? (this.byId().get(id) ?? null) : null;
  });
  readonly additional = computed(() =>
    this.additionalIds()
      .map((id) => this.byId().get(id))
      .filter((c): c is Connection => !!c),
  );
  readonly addable = computed(() =>
    this.available().filter(
      (c) => c.id !== this.systemId() && !this.additionalIds().includes(c.id),
    ),
  );
  readonly dirty = computed(() => {
    const l = this.loaded();
    if (!l) return false;
    return (
      (l.systemConnectionId ?? null) !== this.systemId() ||
      JSON.stringify(l.additionalConnectionIds ?? []) !== JSON.stringify(this.additionalIds())
    );
  });

  readonly vchip = (c: Connection) => verificationChip(c.verification);
  readonly kind = tokenKindLabel;
  readonly caps = (c: Connection) => capabilityLabels(c.capabilities ?? []);

  constructor() {
    effect(() => {
      this.projectId();
      this.refresh();
      untracked(() => this.load());
    });
  }

  scopeLabel(c: Connection): string {
    if (c.legacy) return $localize`:@@connections.projectJira.scope.legacy:platform, legacy`;
    return c.scope === 'PROJECT'
      ? $localize`:@@connections.projectJira.scope.project:project`
      : $localize`:@@connections.projectJira.scope.platform:platform`;
  }

  optionLabel(c: Connection): string {
    return `${c.name} (${this.scopeLabel(c)}: ${this.caps(c)})`;
  }

  upLabel(c: Connection): string {
    return $localize`:@@connections.projectJira.up:Move ${c.name}:name: up`;
  }

  downLabel(c: Connection): string {
    return $localize`:@@connections.projectJira.down:Move ${c.name}:name: down`;
  }

  removeLabel(c: Connection): string {
    return $localize`:@@connections.projectJira.remove:Remove ${c.name}:name: from the list`;
  }

  load(): void {
    this.api.projectJira(this.projectId()).subscribe({
      next: (v) => this.apply(v),
      error: () => this.loaded.set(null),
    });
  }

  private apply(v: ProjectJiraConnections): void {
    this.loaded.set(v);
    this.systemId.set(v.systemConnectionId ?? null);
    this.additionalIds.set([...(v.additionalConnectionIds ?? [])]);
  }

  setSystem(id: string | null): void {
    const next = id || null;
    this.systemId.set(next);
    if (next) this.additionalIds.update((list) => list.filter((x) => x !== next));
  }

  add(id: string | null): void {
    if (id && !this.additionalIds().includes(id)) this.additionalIds.update((l) => [...l, id]);
  }

  removeAt(i: number): void {
    this.additionalIds.update((l) => l.filter((_, j) => j !== i));
  }

  move(i: number, delta: number): void {
    this.additionalIds.update((l) => moveItem(l, i, delta));
  }

  save(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.api
      .updateProjectJira(this.projectId(), {
        systemConnectionId: this.systemId(),
        additionalConnectionIds: this.additionalIds(),
      })
      .subscribe({
        next: (v) => {
          this.saving.set(false);
          this.apply(v);
          this.message.success($localize`:@@connections.projectJira.saved:Jira connections saved`);
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.notify.error(
            $localize`:@@connections.projectJira.saveFailed:Could not save the Jira connections`,
            problemMessage(err),
          );
        },
      });
  }
}
