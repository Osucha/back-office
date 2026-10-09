import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Shape-true loading placeholder (never a spinner for page loads).
 *
 *   <bo-skeleton variant="table" [rows]="8" [columns]="6" />
 *   <bo-skeleton variant="cards" [rows]="3" />
 *   <bo-skeleton variant="list" [rows]="5" />
 *   <bo-skeleton variant="detail" />          header + two columns
 */
@Component({
  selector: 'bo-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'status', 'aria-live': 'polite', '[attr.data-variant]': 'variant()' },
  template: `
    <span class="sr-only">{{ label() }}</span>
    @switch (variant()) {
      @case ('table') {
        <div class="table" aria-hidden="true">
          <div class="row head">
            @for (c of cols(); track $index) {
              <span class="bone" [style.width.%]="c"></span>
            }
          </div>
          @for (r of rowList(); track r) {
            <div class="row">
              @for (c of cols(); track $index) {
                <span class="bone" [style.width.%]="c * (0.6 + ((r * 7 + c) % 5) / 10)"></span>
              }
            </div>
          }
        </div>
      }
      @case ('cards') {
        <div class="cards" aria-hidden="true">
          @for (r of rowList(); track r) {
            <div class="card">
              <span class="bone chip"></span>
              <span class="bone line" style="width: 70%"></span>
              <span class="bone line thin" style="width: 45%"></span>
            </div>
          }
        </div>
      }
      @case ('detail') {
        <div class="detail" aria-hidden="true">
          <span class="bone line thin" style="width: 12%"></span>
          <span class="bone line tall" style="width: 38%"></span>
          <span class="bone line thin" style="width: 26%"></span>
          <span class="bone bar"></span>
          <div class="cols">
            <div class="panel"></div>
            <div class="panel"></div>
          </div>
        </div>
      }
      @default {
        <div class="list" aria-hidden="true">
          @for (r of rowList(); track r) {
            <div class="item">
              <span class="bone dot"></span>
              <span class="bone line" [style.width.%]="50 + ((r * 13) % 35)"></span>
            </div>
          }
        </div>
      }
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .bone {
        display: block;
        height: 10px;
        border-radius: 4px;
        background: linear-gradient(
          90deg,
          var(--bo-surface-3) 25%,
          var(--bo-line) 45%,
          var(--bo-surface-3) 65%
        );
        background-size: 300% 100%;
        animation: bo-shimmer 1.6s ease-in-out infinite;
      }
      @keyframes bo-shimmer {
        from {
          background-position: 100% 0;
        }
        to {
          background-position: 0 0;
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .bone {
          animation: none;
        }
      }
      .table {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        overflow: hidden;
      }
      .row {
        display: flex;
        gap: 24px;
        align-items: center;
        height: calc(var(--bo-row-h) + 6px);
        padding: 0 12px;
        border-bottom: 1px solid var(--bo-line);
      }
      .row:last-child {
        border-bottom: 0;
      }
      .row.head {
        background: var(--bo-surface-2);
      }
      .row .bone {
        flex: none;
        max-width: 22%;
      }
      .cards {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
        gap: 12px;
      }
      .card {
        display: flex;
        flex-direction: column;
        gap: 10px;
        padding: 16px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
      }
      .chip {
        width: 84px;
        height: 18px;
        border-radius: 999px;
      }
      .line {
        height: 12px;
      }
      .line.thin {
        height: 9px;
      }
      .line.tall {
        height: 20px;
      }
      .list {
        display: flex;
        flex-direction: column;
        gap: 14px;
        padding: 8px 0;
      }
      .item {
        display: flex;
        align-items: center;
        gap: 10px;
      }
      .dot {
        width: 12px;
        height: 12px;
        border-radius: 50%;
      }
      .detail {
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .bar {
        height: 8px;
        margin: 10px 0;
        border-radius: 999px;
      }
      .cols {
        display: grid;
        grid-template-columns: 2fr 3fr;
        gap: 16px;
      }
      .panel {
        height: 280px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
      }
    `,
  ],
})
export class SkeletonComponent {
  readonly variant = input<'table' | 'cards' | 'list' | 'detail'>('list');
  readonly rows = input(5);
  readonly columns = input(5);
  readonly label = input($localize`:@@core.skeleton.loading:Loading`);

  readonly rowList = computed(() => Array.from({ length: this.rows() }, (_, i) => i));
  readonly cols = computed(() =>
    Array.from({ length: this.columns() }, (_, i) => [18, 12, 16, 10, 14, 8, 12][i % 7]),
  );
}
