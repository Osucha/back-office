/**
 * Custom ngx-vflow node components, one per node kind, sharing `NodeShellComponent` for the
 * frame, header, status colour, issue badges and handles. Register new kinds in
 * `NODE_COMPONENTS` (and `NODE_KIND_META` in ../data/graph-model.ts). Card sizes are mirrored by
 * `estimateNodeSize` in ../data/auto-layout.ts (widths 240 / 180, 16 px body lines, 22 px ports).
 */
import { ChangeDetectionStrategy, Component, Type, computed, input } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { CustomNodeComponent, Vflow } from 'ngx-vflow';
import type { NodeKind } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { humanize } from '../../../core/ui/status-tag.component';
import { capabilityLabels, pinnedConnection } from '../../connections/public-api';
import { NODE_KIND_META, portHandleId, type CanvasNodeData } from '../data/graph-model';

export const INPUT_HANDLE_ID = 'in';

@Component({
  selector: 'app-node-shell',
  imports: [Vflow, NzIconModule, NzTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (data(); as d) {
      <div
        class="wf-node"
        selectable
        [class.selected]="selected()"
        [class.has-error]="d.errorCount > 0"
        [class.has-warning]="d.errorCount === 0 && d.warningCount > 0"
        [class.compact]="compact()"
        [attr.data-status]="d.status ?? ''"
        [attr.data-kind]="d.node.kind"
        [attr.data-node-id]="d.node.id"
        [style.--accent]="accent()"
        [attr.aria-label]="ariaLabel()"
      >
        @if (d.hasInput) {
          <handle type="target" position="left" [id]="inputHandleId" />
        }
        <div class="wf-head">
          <nz-icon [nzType]="meta().icon" class="wf-kind-icon" />
          <span class="wf-kind">{{ meta().title }}</span>
          <span class="wf-badges">
            @if (d.attempts > 1) {
              <span class="badge attempts" nz-tooltip [nzTooltipTitle]="attemptsLabel(d.attempts)"
                >×{{ d.attempts }}</span
              >
            }
            @if (d.errorCount) {
              <span class="badge error" nz-tooltip [nzTooltipTitle]="errorsLabel(d.errorCount)"
                ><span aria-hidden="true">!</span> {{ d.errorCount }}</span
              >
            } @else if (d.warningCount) {
              <span
                class="badge warning"
                nz-tooltip
                [nzTooltipTitle]="warningsLabel(d.warningCount)"
                >{{ d.warningCount }}</span
              >
            }
            @if (d.status) {
              <span
                class="status-dot"
                [attr.data-status]="d.status"
                [title]="statusLabel(d.status)"
              ></span>
            }
          </span>
        </div>
        <div class="wf-title" [title]="d.node.name">{{ d.node.name || d.node.id }}</div>
        <div class="wf-body"><ng-content /></div>
        @if (d.ports.length) {
          <div class="wf-ports">
            @for (p of d.ports; track p) {
              <div class="wf-port" [class.taken]="takenPorts().has(p)">
                <span class="wf-port-label">{{ p }}</span>
                <handle type="source" position="right" [id]="handleId(p)" />
              </div>
            }
          </div>
        }
      </div>
    }
  `,
  styles: [
    `
      .wf-node {
        position: relative;
        box-sizing: border-box;
        width: 240px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line-strong);
        border-radius: 10px;
        box-shadow: inset 4px 0 0 var(--accent, var(--bo-ink));
        font-size: 12px;
        line-height: 16px;
        color: var(--bo-ink);
        cursor: grab;
        user-select: none;
        transition:
          box-shadow var(--bo-dur-fast) var(--bo-ease),
          border-color var(--bo-dur-fast) var(--bo-ease);
      }
      .wf-node.compact {
        width: 180px;
      }
      .wf-node:hover {
        border-color: var(--bo-ink-3);
      }
      .wf-node.has-error {
        border-color: var(--bo-blocked);
      }
      .wf-node.has-warning {
        border-color: var(--bo-you);
      }
      .wf-node.selected {
        border-color: var(--bo-accent);
        box-shadow:
          inset 4px 0 0 var(--accent, var(--bo-ink)),
          0 0 0 3px var(--bo-accent-soft),
          0 6px 16px rgba(0, 0, 0, 0.08);
      }
      .wf-node[data-kind='NOTE'] {
        background: var(--bo-surface-2);
        border-style: dashed;
        box-shadow: none;
      }
      /* Run replay: the node takes the colour of whoever held the baton there. */
      .wf-node[data-status='RUNNING'],
      .wf-node[data-status='CURRENT'] {
        border-color: var(--bo-agent);
        background: var(--bo-agent-soft);
      }
      .wf-node[data-status='WAITING'] {
        border-color: var(--bo-you);
        background: var(--bo-you-soft);
      }
      .wf-node[data-kind='WAIT_FOR_CI'][data-status='WAITING'] {
        border-color: var(--bo-ci);
        background: var(--bo-ci-soft);
      }
      .wf-node[data-status='SUCCEEDED'] {
        border-color: var(--bo-done);
      }
      .wf-node[data-status='FAILED'] {
        border-color: var(--bo-blocked);
        background: var(--bo-blocked-soft);
      }
      .wf-node[data-status='SKIPPED'] {
        opacity: 0.55;
      }
      .wf-head {
        display: flex;
        align-items: center;
        gap: 6px;
        height: 24px;
        box-sizing: border-box;
        padding: 8px 10px 0 14px;
        color: var(--bo-ink-2);
        font-size: 11px;
        font-weight: 500;
        letter-spacing: 0.02em;
      }
      .wf-kind-icon {
        color: var(--accent, var(--bo-ink));
        font-size: 13px;
      }
      .wf-node[data-kind='NOTE'] .wf-kind-icon,
      .wf-node[data-kind='START'] .wf-kind-icon {
        color: var(--bo-ink-2);
      }
      .wf-badges {
        margin-left: auto;
        display: inline-flex;
        gap: 4px;
        align-items: center;
      }
      .badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: 2px;
        min-width: 18px;
        height: 16px;
        padding: 0 5px;
        border-radius: 8px;
        font-size: 10.5px;
        font-weight: 600;
        line-height: 16px;
        color: var(--bo-on-ink);
        background: var(--bo-ink-2);
        font-variant-numeric: tabular-nums;
      }
      .badge.error {
        background: var(--bo-blocked);
        color: #ffffff;
      }
      .badge.warning {
        background: var(--bo-you);
        color: #1c1e21;
      }
      .status-dot {
        width: 9px;
        height: 9px;
        border-radius: 50%;
        background: var(--bo-idle);
        display: inline-block;
      }
      .status-dot[data-status='RUNNING'],
      .status-dot[data-status='CURRENT'] {
        background: var(--bo-agent);
        animation: pulse 1.6s infinite;
      }
      .status-dot[data-status='WAITING'] {
        background: var(--bo-you);
      }
      .status-dot[data-status='SUCCEEDED'] {
        background: var(--bo-done);
      }
      .status-dot[data-status='FAILED'] {
        background: var(--bo-blocked);
      }
      @keyframes pulse {
        0% {
          box-shadow: 0 0 0 0 var(--bo-agent-soft);
        }
        100% {
          box-shadow: 0 0 0 6px transparent;
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .status-dot {
          animation: none !important;
        }
      }
      .wf-title {
        height: 26px;
        box-sizing: border-box;
        padding: 2px 12px 6px 14px;
        font-weight: 600;
        font-size: 13px;
        line-height: 18px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .wf-body:empty {
        display: none;
      }
      .wf-body {
        padding: 0 12px 8px 14px;
        color: var(--bo-ink-2);
        min-height: 16px;
      }
      .wf-ports {
        border-top: 1px solid var(--bo-line);
        padding: 4px 0 5px;
        display: flex;
        flex-direction: column;
      }
      .wf-port {
        position: relative;
        display: flex;
        justify-content: flex-end;
        align-items: center;
        height: 22px;
        padding-right: 14px;
        color: var(--bo-ink-2);
        font-family: var(--bo-font-mono);
        font-size: 11px;
      }
      .wf-port.taken {
        color: var(--bo-done-ink);
        font-weight: 600;
      }
    `,
  ],
})
export class NodeShellComponent {
  readonly data = input.required<CanvasNodeData>();
  readonly selected = input(false);
  readonly compact = input(false);
  readonly takenPorts = input<Set<string>>(new Set());
  readonly inputHandleId = INPUT_HANDLE_ID;
  readonly meta = computed(() => NODE_KIND_META[this.data().node.kind]);
  /** Kind accent; an END node with a failure outcome is brick. */
  readonly accent = computed(() => {
    const n = this.data().node;
    return n.kind === 'END' && n.end?.outcome === 'FAILURE'
      ? 'var(--bo-blocked)'
      : this.meta().color;
  });
  readonly ariaLabel = computed(() => {
    const d = this.data();
    const parts = [`${this.meta().title}: ${d.node.name || d.node.id}`];
    if (d.errorCount) parts.push(errorsLabel(d.errorCount));
    if (d.warningCount) parts.push(warningsLabel(d.warningCount));
    if (d.ports.length)
      parts.push($localize`:@@workflows.node.ports:ports ${d.ports.join(', ')}:ports:`);
    return parts.join(', ');
  });
  readonly attemptsLabel = attemptsLabel;
  readonly errorsLabel = errorsLabel;
  readonly warningsLabel = warningsLabel;
  readonly statusLabel = humanize;
  readonly handleId = portHandleId;
}

function attemptsLabel(count: number): string {
  return icu(
    $localize`:@@workflows.node.attempts:{count, plural, one {# attempt} other {# attempts}}`,
    { count },
  );
}

function errorsLabel(count: number): string {
  return icu($localize`:@@workflows.node.errors:{count, plural, one {# error} other {# errors}}`, {
    count,
  });
}

function warningsLabel(count: number): string {
  return icu(
    $localize`:@@workflows.node.warnings:{count, plural, one {# warning} other {# warnings}}`,
    { count },
  );
}

/** Common base: exposes `data`/`selected` from CustomNodeComponent to the per-kind templates. */
abstract class WorkflowNodeBase extends CustomNodeComponent<CanvasNodeData> {
  readonly taken = computed(() => new Set(this.data()?.takenPortsList ?? []));
}

@Component({
  imports: [NodeShellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (data(); as d) {
    <app-node-shell [data]="d" [selected]="selected()" [compact]="true" [takenPorts]="taken()">
      <span i18n="@@workflows.node.entryPoint">Entry point</span>
    </app-node-shell>
  }`,
})
export class StartNodeComponent extends WorkflowNodeBase {}

@Component({
  imports: [NodeShellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (data(); as d) {
    <app-node-shell [data]="d" [selected]="selected()" [compact]="true" [takenPorts]="taken()">
      @if (d.node.end?.outcome === 'FAILURE') {
        <span class="outcome fail" i18n="@@workflows.node.outcomeFailure">Outcome: failure</span>
      } @else {
        <span class="outcome" i18n="@@workflows.node.outcomeSuccess">Outcome: success</span>
      }
    </app-node-shell>
  }`,
  styles: [
    `
      .outcome {
        font-weight: 600;
        color: var(--bo-done-ink);
      }
      .outcome.fail {
        color: var(--bo-blocked-ink);
      }
    `,
  ],
})
export class EndNodeComponent extends WorkflowNodeBase {}

@Component({
  imports: [NodeShellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (data(); as d) {
    <app-node-shell [data]="d" [selected]="selected()" [takenPorts]="taken()">
      <div class="tpl">
        @if (d.node.task?.templateKey; as key) {
          <span class="key" [title]="key">{{ key }}</span>
        } @else {
          <span class="key" i18n="@@workflows.node.noTemplate">no template</span>
        }
        @if (d.executor) {
          <span class="exec">{{ d.executor }}</span>
        }
      </div>
      @if (d.interfaces.length || hasOverrides() || d.requiredCapabilities?.length) {
        <div class="ifaces">
          @if (d.requiredCapabilities?.length) {
            <span class="jira" [title]="jiraTitle()">Jira</span>
          }
          @for (i of d.interfaces; track i) {
            <span class="iface">{{ i }}</span>
          }
          @if (hasOverrides()) {
            <span class="ov" i18n="@@workflows.node.overrides">overrides</span>
          }
        </div>
      }
    </app-node-shell>
  }`,
  styles: [
    `
      .tpl {
        display: flex;
        gap: 6px;
        align-items: center;
        justify-content: space-between;
        height: 16px;
      }
      .key {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .exec {
        flex: none;
        font-size: 10px;
        line-height: 14px;
        padding: 0 5px;
        border-radius: 3px;
        background: var(--bo-surface-3);
        color: var(--bo-ink-2);
        font-weight: 600;
      }
      .ifaces {
        display: flex;
        gap: 4px;
        overflow: hidden;
        height: 16px;
        margin-top: 4px;
      }
      .iface,
      .ov {
        flex: none;
        font-size: 10.5px;
        line-height: 16px;
        padding: 0 6px;
        border-radius: 999px;
        background: var(--bo-accent-soft);
        color: var(--bo-agent-ink);
      }
      .ov {
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
      }
      .jira {
        flex: none;
        font-size: 10.5px;
        line-height: 16px;
        padding: 0 6px;
        border-radius: 999px;
        background: var(--bo-ci-soft);
        color: var(--bo-ci-ink);
      }
    `,
  ],
})
export class TaskNodeComponent extends WorkflowNodeBase {
  /** "needs: Read issues, Move issues" (+ pinned) for the Jira chip. */
  readonly jiraTitle = computed(() => {
    const d = this.data();
    const needs = capabilityLabels(d?.requiredCapabilities ?? []);
    return pinnedConnection(d?.node.task?.overrides?.config)
      ? $localize`:@@workflows.node.jiraPinned:Jira connection needs: ${needs}:needs: (pinned connection)`
      : $localize`:@@workflows.node.jiraNeeds:Jira connection needs: ${needs}:needs:`;
  });
  readonly hasOverrides = computed(() => {
    const o = this.data()?.node.task?.overrides;
    if (!o) return false;
    return Boolean(
      o.modelProfileId ||
      o.promptAppend ||
      o.promptReplace ||
      o.systemPromptAppend ||
      o.addSkills?.length ||
      o.removeSkills?.length ||
      (o.config && Object.keys(o.config).length),
    );
  });
}

@Component({
  imports: [NodeShellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (data(); as d) {
    <app-node-shell [data]="d" [selected]="selected()" [takenPorts]="taken()">
      <ul class="branches">
        @for (b of d.node.decision?.branches ?? []; track b.id) {
          <li>
            <span class="bid">{{ b.id }}</span>
            <span class="expr" [title]="b.expression">{{ b.expression }}</span>
          </li>
        }
        @if (defaultPort(); as dp) {
          <li>
            <span class="bid">{{ dp }}</span>
            <span class="expr other" i18n="@@workflows.node.otherwise">otherwise</span>
          </li>
        }
      </ul>
    </app-node-shell>
  }`,
  styles: [
    `
      .branches {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
      }
      li {
        display: flex;
        gap: 6px;
        align-items: baseline;
        min-width: 0;
        height: 16px;
      }
      .bid {
        flex: none;
        font-weight: 600;
        color: var(--bo-agent-ink);
      }
      .expr {
        font-family: var(--bo-font-mono);
        font-size: 10.5px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .expr.other {
        font-family: inherit;
        font-style: italic;
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class DecisionNodeComponent extends WorkflowNodeBase {
  /** The default branch when it is a port of its own (not one of the branch ids). */
  readonly defaultPort = computed(() => {
    const dec = this.data()?.node.decision;
    const d = dec?.defaultBranch;
    return d && !(dec?.branches ?? []).some((b) => b.id === d) ? d : null;
  });
}

@Component({
  imports: [NodeShellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (data(); as d) {
    <app-node-shell [data]="d" [selected]="selected()" [takenPorts]="taken()">
      <div
        class="q"
        [class.two]="(d.node.gate?.question?.length ?? 0) > 30"
        [title]="d.node.gate?.question ?? ''"
      >
        {{ d.node.gate?.question }}
      </div>
    </app-node-shell>
  }`,
  styles: [
    `
      .q {
        display: -webkit-box;
        -webkit-line-clamp: 1;
        -webkit-box-orient: vertical;
        overflow: hidden;
        max-height: 16px;
      }
      .q.two {
        -webkit-line-clamp: 2;
        max-height: 32px;
      }
    `,
  ],
})
export class HumanGateNodeComponent extends WorkflowNodeBase {}

@Component({
  imports: [NodeShellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (data(); as d) {
    <app-node-shell [data]="d" [selected]="selected()" [takenPorts]="taken()">
      <div>
        <span i18n="@@workflows.node.signal">signal</span>
        <span class="sig">{{ d.node.ci?.signal || '?' }}</span>
        @if (d.node.ci?.timeoutSeconds) {
          <span class="muted" i18n="@@workflows.node.timeout"> · timeout {{ timeout() }}</span>
        }
      </div>
    </app-node-shell>
  }`,
  styles: [
    `
      .sig {
        font-family: var(--bo-font-mono);
        font-weight: 600;
        color: var(--bo-ci-ink);
      }
      .muted {
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class WaitForCiNodeComponent extends WorkflowNodeBase {
  readonly timeout = computed(() => {
    const s = this.data()?.node.ci?.timeoutSeconds ?? 0;
    if (s >= 3600 && s % 3600 === 0) return `${s / 3600} h`;
    if (s >= 60 && s % 60 === 0) return `${s / 60} min`;
    return `${s} s`;
  });
}

@Component({
  imports: [NodeShellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (data(); as d) {
    <app-node-shell [data]="d" [selected]="selected()" [takenPorts]="taken()">
      <div class="note">{{ d.node.note?.text }}</div>
    </app-node-shell>
  }`,
  styles: [
    `
      .note {
        white-space: pre-wrap;
        max-height: 112px;
        overflow: hidden;
      }
    `,
  ],
})
export class NoteNodeComponent extends WorkflowNodeBase {}

export const NODE_COMPONENTS: Record<NodeKind, Type<CustomNodeComponent<CanvasNodeData>>> = {
  START: StartNodeComponent,
  END: EndNodeComponent,
  TASK: TaskNodeComponent,
  DECISION: DecisionNodeComponent,
  HUMAN_GATE: HumanGateNodeComponent,
  WAIT_FOR_CI: WaitForCiNodeComponent,
  NOTE: NoteNodeComponent,
};
