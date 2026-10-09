import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzCollapseModule } from 'ng-zorro-antd/collapse';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import type { NodeKind, PaletteTask, WorkflowPalette } from '../../../core/api/types';
import { NODE_KIND_META, type NodeKindMeta } from '../data/graph-model';
import { NODE_DRAG_MIME } from './workflow-canvas.component';

export interface PalettePick {
  kind: NodeKind;
  templateKey?: string | null;
}

interface TaskGroup {
  interfaceKey: string;
  title: string;
  tasks: PaletteTask[];
}

const CONTROL_ORDER: NodeKind[] = ['START', 'END', 'DECISION', 'HUMAN_GATE', 'WAIT_FOR_CI', 'NOTE'];

/** Left drawer: control nodes + task templates grouped by interface; drag or click to add. */
@Component({
  selector: 'app-node-palette',
  imports: [
    FormsModule,
    NzInputModule,
    NzIconModule,
    NzCollapseModule,
    NzTagModule,
    NzTooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="palette">
      <nz-input-group [nzPrefix]="searchIcon" nzSize="small" class="search">
        <input
          nz-input
          [ngModel]="query()"
          (ngModelChange)="query.set($event)"
          placeholder="Search nodes and templates"
          i18n-placeholder="@@workflows.palette.search"
          aria-label="Search palette"
          i18n-aria-label="@@workflows.palette.searchLabel"
        />
      </nz-input-group>
      <ng-template #searchIcon><nz-icon nzType="search" /></ng-template>

      <h4 i18n="@@workflows.palette.control">Control</h4>
      <div class="items">
        @for (c of controls(); track c.kind) {
          <div
            class="item"
            draggable="true"
            role="button"
            tabindex="0"
            [style.--accent]="c.color"
            [attr.data-kind]="c.kind"
            (dragstart)="dragStart($event, { kind: c.kind })"
            (click)="pick.emit({ kind: c.kind })"
            (keydown.enter)="pick.emit({ kind: c.kind })"
            nz-tooltip
            [nzTooltipTitle]="c.description"
            nzTooltipPlacement="right"
          >
            <nz-icon [nzType]="c.icon" />
            <span class="text">
              <span class="label">{{ c.title }}</span>
              @if (c.defaultPorts.length) {
                <span class="ports" [attr.aria-label]="portsLabel(c.defaultPorts)">{{
                  c.defaultPorts.join(' · ')
                }}</span>
              }
            </span>
          </div>
        }
      </div>

      <h4>
        <span i18n="@@workflows.palette.tasks">Tasks</span>
        @if (loading()) {
          <nz-icon nzType="loading" nzSpin />
        }
      </h4>
      @if (!loading() && !groups().length) {
        @if (query()) {
          <p class="muted" i18n="@@workflows.palette.noMatch">No template matches.</p>
        } @else {
          <p class="muted" i18n="@@workflows.palette.noTemplates">
            No task templates. Add some in the Library.
          </p>
        }
      }
      @for (g of groups(); track g.interfaceKey) {
        <div class="group">
          <div class="group-title">{{ g.title }}</div>
          <div class="items">
            @for (t of g.tasks; track t.key) {
              <div
                class="item task"
                draggable="true"
                role="button"
                tabindex="0"
                (dragstart)="dragStart($event, { kind: 'TASK', templateKey: t.key })"
                (click)="pick.emit({ kind: 'TASK', templateKey: t.key })"
                (keydown.enter)="pick.emit({ kind: 'TASK', templateKey: t.key })"
                nz-tooltip
                [nzTooltipTitle]="t.description || t.key"
                nzTooltipPlacement="right"
              >
                <nz-icon nzType="robot" />
                <span class="text">
                  <span class="label">{{ t.name || t.key }}</span>
                  <span class="ports mono">{{ t.key }}</span>
                </span>
                @if (t.executor) {
                  <span class="exec">{{ t.executor }}</span>
                }
                @if (t.projectScoped) {
                  <nz-icon
                    nzType="project"
                    class="scoped"
                    nz-tooltip
                    nzTooltipTitle="Project template"
                    i18n-nzTooltipTitle="@@workflows.palette.projectTemplate"
                  />
                }
              </div>
            }
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .palette {
        display: flex;
        flex-direction: column;
        gap: 6px;
        padding: 8px;
        overflow: auto;
        height: 100%;
      }
      .search {
        margin-bottom: 4px;
      }
      h4 {
        margin: 8px 0 2px;
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--bo-ink-3);
        display: flex;
        gap: 6px;
        align-items: center;
      }
      .group-title {
        font-size: 12px;
        font-weight: 600;
        margin: 6px 0 2px;
      }
      .items {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .item {
        display: flex;
        align-items: flex-start;
        gap: 8px;
        padding: 7px 8px;
        border: 1px solid var(--bo-line);
        border-left: 3px solid var(--accent, var(--bo-ink));
        border-radius: 6px;
        background: var(--bo-surface);
        cursor: grab;
        font-size: 12px;
      }
      .item:hover,
      .item:focus-visible {
        border-color: var(--bo-ink-3);
        background: var(--bo-surface-2);
      }
      .item nz-icon {
        color: var(--accent, var(--bo-ink));
        margin-top: 2px;
      }
      .item[data-kind='NOTE'] nz-icon,
      .item[data-kind='START'] nz-icon {
        color: var(--bo-ink-2);
      }
      .text {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 1px;
      }
      .label {
        font-weight: 500;
        font-size: 13px;
        line-height: 18px;
        overflow-wrap: anywhere;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .ports {
        font-family: var(--bo-font-mono);
        font-size: 10.5px;
        line-height: 14px;
        color: var(--bo-ink-3);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .exec {
        flex: none;
        margin-top: 2px;
        font-size: 10px;
        line-height: 14px;
        padding: 0 4px;
        border-radius: 3px;
        background: var(--bo-surface-3);
        color: var(--bo-ink-2);
        font-weight: 600;
      }
      .scoped {
        color: var(--bo-accent) !important;
      }
      .muted {
        color: var(--bo-ink-3);
        font-size: 12px;
        margin: 4px 0;
      }
    `,
  ],
})
export class NodePaletteComponent {
  readonly palette = input<WorkflowPalette | null>(null);
  readonly loading = input(false);
  readonly pick = output<PalettePick>();

  readonly query = signal('');

  readonly controls = computed<NodeKindMeta[]>(() => {
    const q = this.query().trim().toLowerCase();
    const fromServer = new Map((this.palette()?.controlNodes ?? []).map((c) => [c.kind, c]));
    return CONTROL_ORDER.map((kind) => {
      const meta = NODE_KIND_META[kind];
      const server = fromServer.get(kind);
      return {
        ...meta,
        title: server?.title ?? meta.title,
        description: server?.description ?? meta.description,
        defaultPorts: server?.ports?.length ? server.ports : meta.defaultPorts,
      };
    }).filter((c) => !q || c.title.toLowerCase().includes(q) || c.kind.toLowerCase().includes(q));
  });

  readonly groups = computed<TaskGroup[]>(() => {
    const p = this.palette();
    const q = this.query().trim().toLowerCase();
    const tasks = (p?.tasks ?? []).filter(
      (t) =>
        !q ||
        (t.name ?? '').toLowerCase().includes(q) ||
        (t.key ?? '').toLowerCase().includes(q) ||
        (t.interfaces ?? []).some((i) => i.toLowerCase().includes(q)),
    );
    const names = new Map((p?.interfaces ?? []).map((i) => [i.key, i.name || i.key]));
    const groups = new Map<string, TaskGroup>();
    for (const t of tasks) {
      const keys = t.interfaces?.length ? t.interfaces : ['-'];
      for (const k of keys) {
        if (!groups.has(k))
          groups.set(k, {
            interfaceKey: k,
            title:
              k === '-'
                ? $localize`:@@workflows.palette.otherTasks:Other tasks`
                : (names.get(k) ?? k),
            tasks: [],
          });
        groups.get(k)!.tasks.push(t);
      }
    }
    return Array.from(groups.values()).sort((a, b) =>
      a.interfaceKey === '-' ? 1 : b.interfaceKey === '-' ? -1 : a.title.localeCompare(b.title),
    );
  });

  portsLabel(ports: readonly string[]): string {
    return $localize`:@@workflows.palette.ports:ports ${ports.join(', ')}:ports:`;
  }

  dragStart(event: DragEvent, payload: PalettePick): void {
    event.dataTransfer?.setData(NODE_DRAG_MIME, JSON.stringify(payload));
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy';
  }
}
