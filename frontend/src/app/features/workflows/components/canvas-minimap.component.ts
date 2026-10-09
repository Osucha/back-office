import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import type { Point } from '../../../core/api/types';
import type { Rect } from '../data/auto-layout';

export interface MinimapNode {
  id: string;
  rect: Rect;
  /** CSS colour (a Relay token) of the node kind. */
  color: string;
  selected: boolean;
}

const W = 188;
const H = 112;
const PAD = 6;

/**
 * Canvas overview drawn with the Relay tokens: nodes as small rectangles in their kind colours,
 * the visible area as an accent rectangle. Click or drag to move the view there.
 */
@Component({
  selector: 'app-canvas-minimap',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="w"
      [attr.height]="h"
      [attr.viewBox]="'0 0 ' + w + ' ' + h"
      role="img"
      aria-label="Overview of the workflow; click to move the view"
      i18n-aria-label="@@workflows.minimap.label"
      (pointerdown)="onPointer($event, true)"
      (pointermove)="onPointer($event, false)"
    >
      @if (view(); as v) {
        <rect
          class="view"
          [attr.x]="v.x"
          [attr.y]="v.y"
          [attr.width]="v.w"
          [attr.height]="v.h"
          rx="2"
        />
      }
      @for (n of shapes(); track n.id) {
        <rect
          [attr.x]="n.x"
          [attr.y]="n.y"
          [attr.width]="n.w"
          [attr.height]="n.h"
          rx="1.5"
          [style.fill]="n.color"
          [class.sel]="n.selected"
        />
      }
    </svg>
  `,
  styles: [
    `
      :host {
        display: block;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: 8px;
        padding: 4px;
        line-height: 0;
      }
      svg {
        cursor: pointer;
        touch-action: none;
        overflow: hidden;
      }
      rect {
        opacity: 0.75;
      }
      rect.sel {
        opacity: 1;
        stroke: var(--bo-accent);
        stroke-width: 1.5;
      }
      rect.view {
        opacity: 1;
        fill: var(--bo-accent-soft);
        stroke: var(--bo-accent);
        stroke-width: 1.5;
      }
    `,
  ],
})
export class CanvasMinimapComponent {
  readonly nodes = input<MinimapNode[]>([]);
  /** vflow viewport: screen = flow * zoom + (x, y). */
  readonly viewport = input<{ x: number; y: number; zoom: number }>({ x: 0, y: 0, zoom: 1 });
  /** Size of the canvas in screen pixels. */
  readonly canvasSize = input<{ width: number; height: number }>({ width: 0, height: 0 });
  /** Flow point to centre the view on. */
  readonly centerOn = output<Point>();

  readonly w = W;
  readonly h = H;

  /** Flow bounds of the nodes plus the visible area, and the scale into the minimap. */
  private readonly frame = computed(() => {
    const ns = this.nodes();
    const v = this.viewport();
    const c = this.canvasSize();
    const vis = {
      x: -v.x / v.zoom,
      y: -v.y / v.zoom,
      width: c.width / v.zoom,
      height: c.height / v.zoom,
    };
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const n of ns) {
      minX = Math.min(minX, n.rect.x);
      minY = Math.min(minY, n.rect.y);
      maxX = Math.max(maxX, n.rect.x + n.rect.width);
      maxY = Math.max(maxY, n.rect.y + n.rect.height);
    }
    if (!ns.length) return null;
    // Bounds of the nodes only (a stable scale while dragging); the view rectangle is clipped.
    const margin = 40;
    minX -= margin;
    minY -= margin;
    maxX += margin;
    maxY += margin;
    const scale = Math.min((W - 2 * PAD) / (maxX - minX || 1), (H - 2 * PAD) / (maxY - minY || 1));
    const offX = PAD + (W - 2 * PAD - (maxX - minX) * scale) / 2;
    const offY = PAD + (H - 2 * PAD - (maxY - minY) * scale) / 2;
    return { minX, minY, scale, offX, offY, vis };
  });

  readonly shapes = computed(() => {
    const f = this.frame();
    if (!f) return [];
    return this.nodes().map((n) => ({
      id: n.id,
      x: f.offX + (n.rect.x - f.minX) * f.scale,
      y: f.offY + (n.rect.y - f.minY) * f.scale,
      w: Math.max(2, n.rect.width * f.scale),
      h: Math.max(2, n.rect.height * f.scale),
      color: n.color,
      selected: n.selected,
    }));
  });

  readonly view = computed(() => {
    const f = this.frame();
    if (!f) return null;
    return {
      x: f.offX + (f.vis.x - f.minX) * f.scale,
      y: f.offY + (f.vis.y - f.minY) * f.scale,
      w: f.vis.width * f.scale,
      h: f.vis.height * f.scale,
    };
  });

  onPointer(event: PointerEvent, down: boolean): void {
    if (!down && !(event.buttons & 1)) return;
    const f = this.frame();
    const svg = event.currentTarget as SVGSVGElement | null;
    if (!f || !svg) return;
    if (down) svg.setPointerCapture?.(event.pointerId);
    const box = svg.getBoundingClientRect();
    const mx = event.clientX - box.left;
    const my = event.clientY - box.top;
    this.centerOn.emit({
      x: f.minX + (mx - f.offX) / f.scale,
      y: f.minY + (my - f.offY) / f.scale,
    });
    event.preventDefault();
  }
}
