import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  untracked,
} from '@angular/core';
import type { WorkflowRun, WorkflowRunSummary } from '../../api/types';
import { RunDetailsStore } from '../../state/run-details.store';
import { buildTrack, type TrackSegment } from './relay-track';
import { RelayTrackComponent } from './relay-track.component';

/**
 * Relay track for a run: a full `WorkflowRun` is drawn directly; a `WorkflowRunSummary` (lists) is
 * loaded through `RunDetailsStore` (lazily, cached, four at a time) and shows a quiet placeholder
 * until then.
 *
 *   <bo-run-track [run]="row" />                       mini
 *   <bo-run-track [run]="run()" variant="full" (segmentSelected)="..." />
 */
@Component({
  selector: 'bo-run-track',
  imports: [RelayTrackComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (full(); as r) {
      <bo-relay-track
        [segments]="segments()"
        [variant]="variant()"
        [selectedNodeId]="selectedNodeId()"
        (segmentSelected)="segmentSelected.emit($event)"
      />
    } @else {
      <span class="placeholder" aria-hidden="true"></span>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .placeholder {
        display: block;
        width: min(100%, 96px);
        height: 6px;
        margin-bottom: 5px;
        border-radius: 999px;
        background: var(--bo-surface-3);
      }
    `,
  ],
})
export class RunTrackComponent {
  private readonly store = inject(RunDetailsStore);

  readonly run = input.required<WorkflowRun | WorkflowRunSummary>();
  readonly variant = input<'mini' | 'full'>('mini');
  readonly selectedNodeId = input<string | null>(null);
  readonly ahead = input(true);
  readonly segmentSelected = output<TrackSegment>();

  readonly full = computed<WorkflowRun | null>(() => {
    const r = this.run();
    if ('nodes' in r && r.nodes) return r as WorkflowRun;
    const loaded = untracked(() => this.store.get(r));
    return loaded();
  });
  readonly segments = computed(() => {
    const r = this.full();
    return r ? buildTrack(r, { ahead: this.ahead() }) : [];
  });
}
