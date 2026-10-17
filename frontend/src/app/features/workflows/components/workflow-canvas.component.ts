import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import {
  Vflow,
  VflowComponent,
  type ComponentNode,
  type Connection,
  type ConnectionSettings,
  type CurveFactory,
  type Edge,
  type EdgeSelectChange,
  type NodeDragEndEvent,
  type NodePositionChange,
  type NodeSelectedChange,
  type ViewportState,
} from 'ngx-vflow';
import { debounceTime } from 'rxjs';
import type {
  NodeExecution,
  NodeKind,
  Point,
  WorkflowCanvas,
  WorkflowDefinitionInput,
  WorkflowEdge,
  WorkflowPalette,
} from '../../../core/api/types';
import { ThemeService } from '../../../core/ui/theme.service';
import { estimateNodeSize, type Rect, type Size } from '../data/auto-layout';
import { routeEdge } from '../data/edge-routing';
import { workspaceRegions } from '../data/workspaces';
import { neighbourInDirection, type NavDirection } from '../data/graph-nav';
import {
  NODE_KIND_META,
  buildEdgeData,
  buildNodeData,
  indexPalette,
  portFromHandleId,
  takenPorts,
  type CanvasEdgeData,
  type CanvasIssueCounts,
  type CanvasNodeData,
} from '../data/graph-model';
import { CanvasMinimapComponent, type MinimapNode } from './canvas-minimap.component';
import { INPUT_HANDLE_ID, NODE_COMPONENTS } from './workflow-nodes';

export const NODE_DRAG_MIME = 'application/x-backoffice-node';

/** Opening zoom never goes below this (cards stay readable); larger graphs are panned instead. */
export const MIN_READABLE_ZOOM = 0.6;
/** Minimap default: shown from this many nodes on (the user can toggle it). */
const MINIMAP_FROM_NODES = 8;
const MINIMAP_KEY = 'backoffice.designer.minimap';

export interface PaletteDrop {
  kind: NodeKind;
  templateKey?: string | null;
  position: Point;
}

/**
 * The n8n-like canvas: renders a `WorkflowDefinitionInput` with ngx-vflow, keeps node/edge objects
 * stable (vflow caches models by identity) and reports user intent to the parent, which owns
 * the definition. Edges are routed orthogonally around the cards (`routeEdge`); loops run in lanes
 * above or below. Also used read-only on the run page with execution statuses.
 */
@Component({
  selector: 'app-workflow-canvas',
  imports: [Vflow, NzButtonModule, NzIconModule, NzTooltipModule, CanvasMinimapComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="canvas"
      #host
      tabindex="0"
      role="application"
      aria-label="Workflow canvas. Arrow keys move the selection along the edges."
      i18n-aria-label="@@workflows.canvas.label"
      aria-roledescription="workflow canvas"
      i18n-aria-roledescription="@@workflows.canvas.roledescription"
      (dragover)="onDragOver($event)"
      (drop)="onDrop($event)"
      (keydown)="onKeydown($event)"
      (pointerdown)="focusOnPointer($event)"
    >
      <vflow
        view="auto"
        [nodes]="flowNodes()"
        [edges]="flowEdges()"
        [background]="background()"
        [connection]="connectionSettings()"
        [minZoom]="0.1"
        [maxZoom]="2"
        [snapGrid]="[10, 10]"
        [elevateNodesOnSelect]="true"
        [entitiesSelectable]="true"
        (connect)="onConnect($event)"
        (nodeDragEnd)="onDragEnd($event)"
        (nodesChanges.position)="onPositionChanges($event)"
        (nodesChanges.select)="onNodeSelect($event)"
        (edgesChanges.select)="onEdgeSelect($event)"
      >
        <ng-template edge let-ctx>
          <svg:g customTemplateEdge class="wf-edge-group">
            <svg:path
              selectable
              class="wf-edge"
              [class.selected]="ctx.selected()"
              [class.taken]="ctx.data()?.taken"
              [class.error]="ctx.data()?.hasError"
              [attr.d]="ctx.path()"
              [attr.marker-end]="ctx.markerEnd()"
            />
            <svg:path selectable class="wf-edge-hit" [attr.d]="ctx.path()" />
          </svg:g>
        </ng-template>
      </vflow>

      @if (regions().length) {
        <!-- ADR 0044: each run workspace as a region around its steps; follows pan and zoom, never takes clicks -->
        <svg class="ws-overlay" aria-hidden="true">
          <g [attr.transform]="regionTransform()">
            @for (r of regions(); track r.key) {
              <rect
                class="ws-region"
                [attr.x]="r.x"
                [attr.y]="r.y"
                [attr.width]="r.width"
                [attr.height]="r.height"
                rx="14"
                [attr.stroke]="r.color"
                [attr.fill]="r.color"
              />
              <text class="ws-label" [attr.x]="r.x + 12" [attr.y]="r.y + 18" [attr.fill]="r.color">{{ r.name }}</text>
            }
          </g>
        </svg>
        <ul class="sr-only">
          @for (r of regions(); track r.key) {
            <li>{{ regionText(r.name, r.nodeIds.length) }}</li>
          }
        </ul>
      }

      @if (minimapVisible()) {
        <app-canvas-minimap
          class="minimap"
          [nodes]="minimapNodes()"
          [viewport]="viewport()"
          [canvasSize]="hostSize()"
          (centerOn)="centerOn($event)"
        />
      }

      <div
        class="controls"
        role="toolbar"
        aria-label="Canvas controls"
        i18n-aria-label="@@workflows.canvas.controls"
      >
        <button
          nz-button
          nzSize="small"
          type="button"
          (click)="zoomBy(1 / 1.2)"
          aria-label="Zoom out"
          i18n-aria-label="@@workflows.canvas.zoomOut"
          nz-tooltip
          nzTooltipTitle="Zoom out"
          i18n-nzTooltipTitle="@@workflows.canvas.zoomOut"
        >
          <nz-icon nzType="zoom-out" />
        </button>
        <span class="zoom" aria-live="polite">{{ zoomLabel() }}</span>
        <button
          nz-button
          nzSize="small"
          type="button"
          (click)="zoomBy(1.2)"
          aria-label="Zoom in"
          i18n-aria-label="@@workflows.canvas.zoomIn"
          nz-tooltip
          nzTooltipTitle="Zoom in"
          i18n-nzTooltipTitle="@@workflows.canvas.zoomIn"
        >
          <nz-icon nzType="zoom-in" />
        </button>
        <button
          nz-button
          nzSize="small"
          type="button"
          (click)="resetView()"
          aria-label="Readable view from START"
          i18n-aria-label="@@workflows.canvas.readable"
          nz-tooltip
          nzTooltipTitle="Readable view from START"
          i18n-nzTooltipTitle="@@workflows.canvas.readable"
        >
          <nz-icon nzType="aim" />
        </button>
        <button
          nz-button
          nzSize="small"
          type="button"
          (click)="fitView()"
          aria-label="Fit everything"
          i18n-aria-label="@@workflows.canvas.fit"
          nz-tooltip
          nzTooltipTitle="Fit everything"
          i18n-nzTooltipTitle="@@workflows.canvas.fit"
        >
          <nz-icon nzType="expand" />
        </button>
        @if (showMinimap()) {
          <button
            nz-button
            nzSize="small"
            type="button"
            [nzType]="minimapVisible() ? 'default' : 'text'"
            (click)="toggleMinimap()"
            [attr.aria-pressed]="minimapVisible()"
            [attr.aria-label]="minimapVisible() ? text.hideOverview : text.showOverview"
            nz-tooltip
            [nzTooltipTitle]="minimapVisible() ? text.hideOverview : text.showOverview"
          >
            <nz-icon nzType="border-outer" />
          </button>
        }
      </div>
      @if (!readonly() && !flowNodes().length) {
        <div class="empty-hint" i18n="@@workflows.canvas.empty">
          Drag nodes from the palette or load a template.
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
        min-height: 320px;
      }
      .ws-overlay {
        position: absolute;
        inset: 0;
        width: 100%;
        height: 100%;
        pointer-events: none;
        overflow: visible;
      }
      .ws-region {
        fill-opacity: 0.06;
        stroke-width: 1.5;
        stroke-dasharray: 6 4;
      }
      .ws-label {
        font-size: 12px;
        font-weight: 600;
        letter-spacing: 0.02em;
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
        margin: 0;
        padding: 0;
      }
      .canvas {
        position: relative;
        height: 100%;
        outline: none;
        border-radius: 6px;
        overflow: hidden;
        border: 1px solid var(--bo-line);
        background: var(--bo-bg);
      }
      .canvas:focus-visible {
        box-shadow: 0 0 0 2px var(--bo-accent);
      }
      vflow {
        width: 100%;
        height: 100%;
      }
      .minimap {
        position: absolute;
        right: 10px;
        bottom: 10px;
        z-index: 2;
      }
      .controls {
        position: absolute;
        left: 10px;
        bottom: 10px;
        display: flex;
        gap: 4px;
        align-items: center;
        background: var(--bo-surface);
        padding: 4px;
        border-radius: 8px;
        border: 1px solid var(--bo-line);
        z-index: 2;
      }
      .zoom {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        color: var(--bo-ink-2);
        min-width: 40px;
        text-align: center;
        font-variant-numeric: tabular-nums;
      }
      .empty-hint {
        position: absolute;
        inset: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        pointer-events: none;
        color: var(--bo-ink-3);
      }
      :host ::ng-deep .wf-edge {
        fill: none;
        stroke: var(--bo-ink-3);
        stroke-width: 1.5;
        transition: stroke 0.12s;
      }
      :host ::ng-deep .wf-edge.taken {
        stroke: var(--bo-done);
        stroke-width: 2.5;
      }
      :host ::ng-deep .wf-edge.error {
        stroke: var(--bo-blocked);
        stroke-dasharray: 6 4;
      }
      :host ::ng-deep .wf-edge.selected {
        stroke: var(--bo-accent);
        stroke-width: 2.5;
      }
      :host ::ng-deep .wf-edge-hit {
        fill: none;
        stroke: transparent;
        stroke-width: 16px;
        cursor: pointer;
      }
      :host ::ng-deep .default-handle {
        fill: var(--bo-surface);
        stroke: var(--bo-ink-2);
        stroke-width: 1.5;
        cursor: crosshair;
      }
      :host ::ng-deep .default-handle:hover {
        fill: var(--bo-accent);
      }
    `,
  ],
})
export class WorkflowCanvasComponent {
  private readonly destroyRef = inject(DestroyRef);

  readonly text = {
    hideOverview: $localize`:@@workflows.canvas.hideOverview:Hide the overview`,
    showOverview: $localize`:@@workflows.canvas.showOverview:Show the overview`,
  };

  readonly definition = input.required<WorkflowDefinitionInput>();
  readonly palette = input<WorkflowPalette | null>(null);
  readonly readonly = input(false);
  readonly issues = input<CanvasIssueCounts | null>(null);
  /** Run mode: node executions drive status colours and taken ports. */
  readonly executions = input<NodeExecution[] | null>(null);
  readonly currentNodeId = input<string | null>(null);
  readonly selectedNodeId = input<string | null>(null);
  /** Whether the overview may be shown at all (the user toggles it on top of this). */
  readonly showMinimap = input(true);
  /** Initial viewport (persisted `canvas`); when null the readable opening view is used. */
  readonly initialViewport = input<WorkflowCanvas | null>(null);

  readonly nodeSelected = output<string | null>();
  readonly edgeSelected = output<string | null>();
  readonly connected = output<Omit<WorkflowEdge, 'id'>>();
  readonly nodeMoved = output<{ id: string; position: Point }>();
  /** A drag finished (the parent closes its undo step). */
  readonly moveEnded = output<void>();
  readonly deleteRequested = output<{ nodeIds: string[]; edgeIds: string[] }>();
  readonly viewportChanged = output<WorkflowCanvas>();
  readonly dropped = output<PaletteDrop>();

  private readonly vflow = viewChild(VflowComponent);
  private readonly host = viewChild<ElementRef<HTMLElement>>('host');

  readonly flowNodes = signal<ComponentNode<CanvasNodeData>[]>([]);
  readonly flowEdges = signal<Edge<CanvasEdgeData>[]>([]);
  private readonly nodeMap = new Map<string, ComponentNode<CanvasNodeData>>();
  private readonly edgeMap = new Map<string, Edge<CanvasEdgeData>>();
  private readonly selectedNodes = new Set<string>();
  private readonly selectedEdges = new Set<string>();
  private viewportApplied = false;

  /** Rendered card sizes (flow units), measured after each render; estimates until then. */
  private readonly sizes = signal<ReadonlyMap<string, Size>>(new Map());
  readonly hostSize = signal({ width: 0, height: 0 });
  readonly zoom = signal(1);
  readonly zoomLabel = computed(() => `${Math.round(this.zoom() * 100)}%`);
  readonly viewport = computed(() => this.vflow()?.viewport() ?? { x: 0, y: 0, zoom: 1 });
  private readonly selection = signal<string | null>(null);

  private readonly minimapPref = signal<'on' | 'off' | null>(readMinimapPref());
  readonly minimapVisible = computed(() => {
    if (!this.showMinimap()) return false;
    const pref = this.minimapPref();
    if (pref) return pref === 'on';
    return this.definition().nodes.length >= MINIMAP_FROM_NODES;
  });

  protected readonly theme = inject(ThemeService);
  /** Dot grid in the Relay neutrals (vflow draws it with SVG attributes, so literal colours). */
  readonly background = computed(() =>
    this.theme.applied() === 'dark'
      ? { type: 'dots' as const, gap: 20, size: 1.2, color: '#2d3137', backgroundColor: '#141518' }
      : { type: 'dots' as const, gap: 20, size: 1.2, color: '#d9d5cc', backgroundColor: '#f6f5f2' },
  );

  readonly connectionSettings = computed<ConnectionSettings>(() => {
    const readonly = this.readonly();
    return {
      curve: 'smooth-step',
      marker: { type: 'arrow-closed', color: '#5a73e0' },
      mode: 'strict',
      allowSelfConnections: true,
      validator: (c) =>
        !readonly &&
        c.sourceHandleType === 'source' &&
        c.targetHandleType === 'target' &&
        !!portFromHandleId(c.sourceHandle),
    };
  });

  /** Node rectangles at their current positions (drag included). */
  readonly rects = computed<Map<string, Rect>>(() => {
    const def = this.definition();
    const sizes = this.sizes();
    const palette = indexPalette(this.palette());
    const out = new Map<string, Rect>();
    for (const n of def.nodes) {
      const model = this.nodeMap.get(n.id);
      const p = model ? model.point() : n.position;
      const s = sizes.get(n.id) ?? estimateNodeSize(n, palette);
      out.set(n.id, { x: p.x, y: p.y, width: s.width, height: s.height });
    }
    return out;
  });

  /** ADR 0044: run workspaces drawn around their steps (flow coordinates). */
  readonly regions = computed(() => workspaceRegions(this.definition(), this.rects()));
  readonly regionTransform = computed(() => {
    const v = this.viewport();
    return `translate(${v.x} ${v.y}) scale(${v.zoom})`;
  });

  regionText(name: string, steps: number): string {
    return $localize`:@@workflows.canvas.workspaceRegion:Workspace ${name}:name: with ${steps}:count: steps`;
  }

  readonly minimapNodes = computed<MinimapNode[]>(() => {
    const rects = this.rects();
    const sel = this.selection();
    return this.definition().nodes.map((n) => ({
      id: n.id,
      rect: rects.get(n.id)!,
      color:
        n.kind === 'END' && n.end?.outcome === 'FAILURE'
          ? 'var(--bo-blocked)'
          : n.kind === 'NOTE'
            ? 'var(--bo-line-strong)'
            : NODE_KIND_META[n.kind].color,
      selected: n.id === sel,
    }));
  });

  /** Loop lanes: back edges over overlapping stretches get their own lane (inner = shorter). */
  private readonly laneIndex = computed(() => {
    const rects = this.rects();
    const back = this.definition()
      .edges.map((e) => ({ e, s: rects.get(e.from), t: rects.get(e.to) }))
      .filter((x) => x.s && x.t && x.t.x <= x.s.x + 10)
      .map((x) => ({ id: x.e.id, lo: x.t!.x, hi: x.s!.x + x.s!.width }))
      .sort((a, b) => a.hi - a.lo - (b.hi - b.lo));
    const placed: Array<{ lo: number; hi: number; k: number }> = [];
    const out = new Map<string, number>();
    for (const b of back) {
      let k = 0;
      while (placed.some((p) => p.k === k && p.lo < b.hi && b.lo < p.hi)) k++;
      placed.push({ ...b, k });
      out.set(b.id, k);
    }
    return out;
  });

  /** One curve factory for every edge: orthogonal routes around the other cards. */
  private readonly routedCurve: CurveFactory = (params) => {
    if (params.mode !== 'edge') {
      return {
        path: `M${params.sourcePoint.x},${params.sourcePoint.y} L${params.targetPoint.x},${params.targetPoint.y}`,
      };
    }
    const edge = params.edge;
    const rects = this.rects();
    const obstacles: Rect[] = [];
    for (const [id, r] of rects) if (id !== edge.source && id !== edge.target) obstacles.push(r);
    const route = routeEdge(params.sourcePoint, params.targetPoint, obstacles, {
      sourceRect: rects.get(edge.source),
      targetRect: rects.get(edge.target),
      laneIndex: this.laneIndex().get(edge.id) ?? 0,
    });
    return {
      path: route.path,
      labelPoints: { start: route.label, center: route.label, end: route.label },
    };
  };

  constructor() {
    // Keep vflow node/edge objects in sync with the definition without recreating them.
    effect(() => {
      const def = this.definition();
      const paletteIndex = indexPalette(this.palette());
      const executions = this.executions();
      const issues = this.issues();
      const readonly = this.readonly();
      const currentNodeId = this.currentNodeId();
      untracked(() =>
        this.syncNodes(def, paletteIndex, executions, issues, readonly, currentNodeId),
      );
      untracked(() => this.syncEdges(def, executions, issues));
      untracked(() => this.scheduleMeasure());
    });

    // External selection (e.g. clicking an issue) -> select the node on the canvas.
    effect(() => {
      const id = this.selectedNodeId();
      untracked(() => this.applySelection(id));
    });

    effect(() => {
      const flow = this.vflow();
      if (!flow) return;
      flow.viewportChange$
        .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef))
        .subscribe((v: ViewportState) => {
          this.zoom.set(v.zoom);
          this.viewportChanged.emit({ x: v.x, y: v.y, zoom: v.zoom });
        });
      flow.initialized$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((ready) => {
        if (!ready || this.viewportApplied) return;
        this.viewportApplied = true;
        const initial = this.initialViewport();
        if (initial && initial.zoom && initial.zoom >= 0.2) {
          flow.viewportTo({ x: initial.x ?? 0, y: initial.y ?? 0, zoom: initial.zoom });
          this.zoom.set(initial.zoom);
        } else {
          // Let the cards render and be measured first.
          setTimeout(() => {
            this.measure();
            this.resetView();
          }, 120);
        }
      });
    });

    const observer = new ResizeObserver((entries) => {
      const r = entries[0]?.contentRect;
      if (r) this.hostSize.set({ width: r.width, height: r.height });
    });
    effect(() => {
      const el = this.host()?.nativeElement;
      if (el) observer.observe(el);
    });
    this.destroyRef.onDestroy(() => observer.disconnect());
  }

  // ---------------------------------------------------------------- public API for the parent

  /** Rendered sizes of the cards (for "Tidy layout"). */
  measuredSizes(): ReadonlyMap<string, Size> {
    this.measure();
    return this.sizes();
  }

  fitView(): void {
    this.vflow()?.fitView({ padding: 0.12, duration: 250 });
  }

  /**
   * The opening view: everything when it fits at a readable zoom (>= 60 %, <= 100 %); otherwise a
   * readable zoom with START at the left edge (replay: the current node in the middle) and the rows
   * in view.
   */
  resetView(): void {
    const flow = this.vflow();
    const el = this.host()?.nativeElement;
    if (!flow || !el) return;
    const rects = [...this.rects().values()];
    if (!rects.length) return;
    const W = el.clientWidth;
    const H = el.clientHeight;
    const pad = 32;
    const minX = Math.min(...rects.map((r) => r.x));
    const minY = Math.min(...rects.map((r) => r.y));
    const maxX = Math.max(...rects.map((r) => r.x + r.width));
    const maxY = Math.max(...rects.map((r) => r.y + r.height));
    const bw = maxX - minX;
    const bh = maxY - minY;
    const fit = Math.min((W - 2 * pad) / bw, (H - 2 * pad - 40) / bh);
    let zoom: number;
    let x: number;
    let y: number;
    if (fit >= MIN_READABLE_ZOOM) {
      zoom = Math.min(1, fit);
      x = (W - bw * zoom) / 2 - minX * zoom;
      y = (H - 40 - bh * zoom) / 2 - minY * zoom;
    } else {
      zoom = MIN_READABLE_ZOOM;
      // Replay: the node where the baton is now; designer: START at the left edge.
      const current = this.readonly() ? this.currentNodeId() : null;
      const cur = current ? this.rects().get(current) : undefined;
      const start = this.definition().nodes.find((n) => n.kind === 'START');
      const sx = start ? (this.rects().get(start.id)?.x ?? minX) : minX;
      x = cur ? W / 2 - (cur.x + cur.width / 2) * zoom : pad - sx * zoom;
      y =
        bh * zoom <= H - 2 * pad - 40 ? (H - 40 - bh * zoom) / 2 - minY * zoom : pad - minY * zoom;
    }
    flow.viewportTo({ x, y, zoom });
    this.zoom.set(zoom);
  }

  /** Brings one node into view (issue list "jump to node") at a readable zoom. */
  focusNode(id: string): void {
    const zoom = Math.max(this.viewport().zoom, 0.8);
    this.centerNode(id, zoom);
  }

  zoomBy(factor: number): void {
    const flow = this.vflow();
    const el = this.host()?.nativeElement;
    if (!flow || !el) return;
    const v = flow.viewport();
    const zoom = Math.min(2, Math.max(0.1, v.zoom * factor));
    // Zoom around the centre of the canvas.
    const cx = el.clientWidth / 2;
    const cy = el.clientHeight / 2;
    const fx = (cx - v.x) / v.zoom;
    const fy = (cy - v.y) / v.zoom;
    flow.viewportTo({ x: cx - fx * zoom, y: cy - fy * zoom, zoom });
    this.zoom.set(zoom);
  }

  /** Centres the view on a flow point (minimap). */
  centerOn(point: Point): void {
    const flow = this.vflow();
    const el = this.host()?.nativeElement;
    if (!flow || !el) return;
    const zoom = flow.viewport().zoom;
    flow.viewportTo({
      x: el.clientWidth / 2 - point.x * zoom,
      y: el.clientHeight / 2 - point.y * zoom,
      zoom,
    });
  }

  toggleMinimap(): void {
    const next = this.minimapVisible() ? 'off' : 'on';
    this.minimapPref.set(next);
    try {
      localStorage.setItem(MINIMAP_KEY, next);
    } catch {
      // storage unavailable
    }
  }

  /** Converts a document point (e.g. drop event) into flow coordinates. */
  toFlowPoint(point: Point): Point {
    const flow = this.vflow();
    return flow ? flow.documentPointToFlowPoint(point) : point;
  }

  /** Flow coordinates of the visible centre (where nodes added by click are placed). */
  viewportCenter(): Point {
    const el = this.host()?.nativeElement;
    if (!el) return { x: 100, y: 100 };
    const r = el.getBoundingClientRect();
    return this.toFlowPoint({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
  }

  focus(): void {
    this.host()?.nativeElement.focus({ preventScroll: true });
  }

  /** The pan / zoom layer swallows mousedown, so give the canvas keyboard focus explicitly. */
  focusOnPointer(event: PointerEvent): void {
    const target = event.target as HTMLElement | null;
    if (target?.closest('.controls, app-canvas-minimap, input, textarea, button')) return;
    this.focus();
  }

  // ---------------------------------------------------------------- vflow events

  onConnect(c: Connection): void {
    const fromPort = portFromHandleId(c.sourceHandle);
    if (!fromPort || this.readonly()) return;
    this.connected.emit({ from: c.source, fromPort, to: c.target });
  }

  onDragEnd(event: NodeDragEndEvent): void {
    if (this.readonly()) return;
    this.nodeMoved.emit({ id: event.node.id, position: event.node.point() });
    this.moveEnded.emit();
  }

  /** vflow reports every position change (drag frames, multi-select drags); the page rounds and stores it. */
  onPositionChanges(changes: NodePositionChange[]): void {
    if (this.readonly()) return;
    for (const c of changes) this.nodeMoved.emit({ id: c.id, position: c.point });
  }

  onNodeSelect(changes: NodeSelectedChange[]): void {
    for (const c of changes) {
      if (c.selected) this.selectedNodes.add(c.id);
      else this.selectedNodes.delete(c.id);
    }
    const selected = changes.find((c) => c.selected);
    if (selected) {
      this.selection.set(selected.id);
      this.nodeSelected.emit(selected.id);
    } else if (this.selectedNodes.size === 0) {
      this.selection.set(null);
      this.nodeSelected.emit(null);
    }
  }

  onEdgeSelect(changes: EdgeSelectChange[]): void {
    for (const c of changes) {
      if (c.selected) this.selectedEdges.add(c.id);
      else this.selectedEdges.delete(c.id);
    }
    const selected = changes.find((c) => c.selected);
    if (selected) this.edgeSelected.emit(selected.id);
    else if (this.selectedEdges.size === 0) this.edgeSelected.emit(null);
  }

  onKeydown(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return;
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    const arrows: Record<string, NavDirection> = {
      ArrowLeft: 'left',
      ArrowRight: 'right',
      ArrowUp: 'up',
      ArrowDown: 'down',
    };
    const dir = arrows[event.key];
    if (dir) {
      event.preventDefault();
      this.navigate(dir);
      return;
    }
    if (this.readonly()) return;
    if (event.key !== 'Delete' && event.key !== 'Backspace') return;
    if (!this.selectedNodes.size && !this.selectedEdges.size) return;
    event.preventDefault();
    this.deleteRequested.emit({
      nodeIds: Array.from(this.selectedNodes),
      edgeIds: Array.from(this.selectedEdges),
    });
    this.selectedNodes.clear();
    this.selectedEdges.clear();
  }

  /** Arrow keys: along the edges (→ / ←) or to the nearest node above / below (↑ / ↓). */
  navigate(dir: NavDirection): void {
    const def = this.definition();
    const current = this.selection();
    let next: string | null;
    if (!current || !def.nodes.some((n) => n.id === current)) {
      next = def.nodes.find((n) => n.kind === 'START')?.id ?? def.nodes[0]?.id ?? null;
    } else {
      next = neighbourInDirection(def, current, dir, this.rects());
    }
    if (!next) return;
    this.applySelection(next);
    this.nodeSelected.emit(next);
    this.ensureVisible(next);
  }

  // ---------------------------------------------------------------- drag from the palette

  onDragOver(event: DragEvent): void {
    if (this.readonly() || !event.dataTransfer?.types.includes(NODE_DRAG_MIME)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  }

  onDrop(event: DragEvent): void {
    if (this.readonly()) return;
    const raw = event.dataTransfer?.getData(NODE_DRAG_MIME);
    if (!raw) return;
    event.preventDefault();
    let payload: { kind: NodeKind; templateKey?: string | null };
    try {
      payload = JSON.parse(raw) as { kind: NodeKind; templateKey?: string | null };
    } catch {
      return;
    }
    const point = this.toFlowPoint({ x: event.clientX, y: event.clientY });
    this.dropped.emit({
      kind: payload.kind,
      templateKey: payload.templateKey ?? null,
      position: { x: point.x - 20, y: point.y - 20 },
    });
    this.host()?.nativeElement.focus();
  }

  // ---------------------------------------------------------------- selection and view helpers

  private applySelection(id: string | null): void {
    for (const n of this.nodeMap.values()) n.selected?.set(n.id === id);
    this.selectedNodes.clear();
    if (id) this.selectedNodes.add(id);
    this.selection.set(id);
  }

  private centerNode(id: string, zoom: number): void {
    const flow = this.vflow();
    const el = this.host()?.nativeElement;
    const r = this.rects().get(id);
    if (!flow || !el || !r) return;
    flow.viewportTo({
      x: el.clientWidth / 2 - (r.x + r.width / 2) * zoom,
      y: el.clientHeight / 2 - (r.y + r.height / 2) * zoom,
      zoom,
    });
    this.zoom.set(zoom);
  }

  /** Pans (keeping the zoom) when the node is not fully visible. */
  private ensureVisible(id: string): void {
    const el = this.host()?.nativeElement;
    const r = this.rects().get(id);
    if (!el || !r) return;
    const v = this.viewport();
    const left = r.x * v.zoom + v.x;
    const top = r.y * v.zoom + v.y;
    const right = left + r.width * v.zoom;
    const bottom = top + r.height * v.zoom;
    const margin = 24;
    if (
      left < margin ||
      top < margin ||
      right > el.clientWidth - margin ||
      bottom > el.clientHeight - 56
    )
      this.centerNode(id, v.zoom);
  }

  // ---------------------------------------------------------------- measuring

  private measureQueued = false;

  private scheduleMeasure(): void {
    if (this.measureQueued) return;
    this.measureQueued = true;
    requestAnimationFrame(() => {
      this.measureQueued = false;
      this.measure();
    });
  }

  private measure(): void {
    const el = this.host()?.nativeElement;
    if (!el) return;
    const current = this.sizes();
    let changed = false;
    const next = new Map(current);
    el.querySelectorAll<HTMLElement>('.wf-node[data-node-id]').forEach((card) => {
      const id = card.dataset['nodeId'];
      if (!id || !card.offsetWidth) return;
      const size = { width: card.offsetWidth, height: card.offsetHeight };
      const prev = current.get(id);
      if (!prev || prev.width !== size.width || prev.height !== size.height) {
        next.set(id, size);
        changed = true;
      }
    });
    for (const id of next.keys())
      if (!this.nodeMap.has(id)) {
        next.delete(id);
        changed = true;
      }
    if (changed) this.sizes.set(next);
  }

  // ---------------------------------------------------------------- sync

  private syncNodes(
    def: WorkflowDefinitionInput,
    paletteIndex: ReturnType<typeof indexPalette>,
    executions: NodeExecution[] | null,
    issues: CanvasIssueCounts | null,
    readonly: boolean,
    currentNodeId: string | null,
  ): void {
    const connected = new Map<string, Set<string>>();
    for (const e of def.edges) {
      if (!connected.has(e.from)) connected.set(e.from, new Set());
      connected.get(e.from)!.add(e.fromPort);
    }
    const seen = new Set<string>();
    let membershipChanged = false;
    for (const n of def.nodes) {
      seen.add(n.id);
      const data = buildNodeData(n, {
        palette: paletteIndex,
        executions,
        currentNodeId,
        issues,
        readonly,
        connectedPorts: connected.get(n.id),
      });
      const existing = this.nodeMap.get(n.id);
      if (existing) {
        existing.data!.set(data);
        const p = existing.point();
        if (p.x !== n.position.x || p.y !== n.position.y) existing.point.set({ ...n.position });
        existing.draggable?.set(!readonly);
      } else {
        this.nodeMap.set(n.id, {
          id: n.id,
          type: NODE_COMPONENTS[n.kind] ?? NODE_COMPONENTS.NOTE,
          point: signal({ ...n.position }),
          data: signal(data),
          draggable: signal(!readonly),
          selected: signal(n.id === this.selection()),
        });
        membershipChanged = true;
      }
    }
    for (const id of Array.from(this.nodeMap.keys())) {
      if (!seen.has(id)) {
        this.nodeMap.delete(id);
        this.selectedNodes.delete(id);
        membershipChanged = true;
      }
    }
    if (membershipChanged || this.flowNodes().length !== this.nodeMap.size) {
      this.flowNodes.set(Array.from(this.nodeMap.values()));
    }
  }

  private syncEdges(
    def: WorkflowDefinitionInput,
    executions: NodeExecution[] | null,
    issues: CanvasIssueCounts | null,
  ): void {
    const taken = takenPorts(executions);
    const seen = new Set<string>();
    let membershipChanged = false;
    for (const e of def.edges) {
      seen.add(e.id);
      const data = buildEdgeData(e, taken, issues);
      const existing = this.edgeMap.get(e.id);
      if (
        existing &&
        existing.source === e.from &&
        existing.target === e.to &&
        existing.sourceHandle === `port:${e.fromPort}`
      ) {
        const prev = existing.data!();
        if (prev.taken !== data.taken || prev.hasError !== data.hasError || prev.edge !== e) {
          existing.data!.set(data);
          existing.markers!.set({ end: { type: 'arrow-closed', color: markerColor(data) } });
          existing.edgeLabels!.set(edgeLabel(e));
        }
      } else {
        this.edgeMap.set(e.id, {
          id: e.id,
          source: e.from,
          target: e.to,
          sourceHandle: `port:${e.fromPort}`,
          targetHandle: INPUT_HANDLE_ID,
          type: 'template',
          curve: signal(this.routedCurve),
          data: signal(data),
          edgeLabels: signal(edgeLabel(e)),
          markers: signal({ end: { type: 'arrow-closed', color: markerColor(data) } }),
          selected: signal(false),
        });
        membershipChanged = true;
      }
    }
    for (const id of Array.from(this.edgeMap.keys())) {
      if (!seen.has(id)) {
        this.edgeMap.delete(id);
        this.selectedEdges.delete(id);
        membershipChanged = true;
      }
    }
    if (membershipChanged || this.flowEdges().length !== this.edgeMap.size) {
      this.flowEdges.set(Array.from(this.edgeMap.values()));
    }
  }
}

/** Edges carry their label only when it says more than the port name (shown on the card). */
function edgeLabel(e: WorkflowEdge) {
  if (!e.label) return {};
  return {
    center: {
      type: 'default' as const,
      text: e.label,
      style: {
        fontSize: '11px',
        fontFamily: 'var(--bo-font-ui)',
        color: 'var(--bo-ink-2)',
        background: 'var(--bo-surface)',
        border: '1px solid var(--bo-line)',
        borderRadius: '10px',
        padding: '0 7px',
        lineHeight: '16px',
        whiteSpace: 'nowrap',
      },
    },
  };
}

function markerColor(data: CanvasEdgeData): string {
  // SVG marker colours are attributes (no CSS variables): mid-tones that read in both themes.
  if (data.hasError) return '#d0493a';
  if (data.taken) return '#3f9a52';
  return '#7c828a';
}

function readMinimapPref(): 'on' | 'off' | null {
  try {
    const v = localStorage.getItem(MINIMAP_KEY);
    return v === 'on' || v === 'off' ? v : null;
  } catch {
    return null;
  }
}
