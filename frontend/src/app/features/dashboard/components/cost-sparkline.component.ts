import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { NgxEchartsDirective } from 'ngx-echarts';
import type { Money } from '../../../core/api/types';
import { formatCost } from '../../../core/ui/relay/format';
import { ThemeService } from '../../../core/ui/theme.service';

export interface CostPoint {
  day?: string;
  cost?: Money;
}

/**
 * Seven-day cost sparkline (ECharts, loaded lazily with the first chart). Without any spend in the
 * period it says so in one calm line instead of drawing a flat line.
 */
@Component({
  selector: 'app-cost-sparkline',
  imports: [NgxEchartsDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (hasSpend()) {
      <div echarts [options]="option()" class="spark" role="img" [attr.aria-label]="label()"></div>
    } @else {
      <p class="none" i18n="@@dashboard.spark.none">No spend yet this week.</p>
    }
  `,
  styles: [
    `
      .spark {
        height: 56px;
        width: 100%;
      }
      .none {
        margin: 12px 0 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class CostSparklineComponent {
  private readonly theme = inject(ThemeService);
  readonly points = input<CostPoint[]>([]);

  readonly hasSpend = computed(() => this.points().some((p) => (p.cost?.amount ?? 0) > 0));

  readonly label = computed(
    () =>
      $localize`:@@dashboard.spark.label:Daily cost, last seven days: ${this.points()
        .map((p) => `${p.day?.slice(5) ?? ''} ${formatCost(p.cost)}`)
        .join(', ')}:days:`,
  );

  readonly option = computed(() => {
    const pts = this.points();
    // --bo-accent in the applied theme (ECharts takes literal colours).
    const dark = this.theme.applied() === 'dark';
    const line = dark ? '#8fa2ff' : '#3552d6';
    const area = dark ? 'rgba(143, 162, 255, 0.16)' : 'rgba(53, 82, 214, 0.12)';
    return {
      animation: false,
      grid: { left: 2, right: 2, top: 6, bottom: 2 },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: number) => formatCost(v),
        textStyle: { fontSize: 12 },
      },
      xAxis: {
        type: 'category',
        show: false,
        data: pts.map((p) => p.day ?? ''),
        boundaryGap: false,
      },
      yAxis: { type: 'value', show: false, min: 0 },
      series: [
        {
          type: 'line',
          smooth: 0.3,
          symbol: 'circle',
          symbolSize: 4,
          showSymbol: false,
          lineStyle: { width: 2, color: line },
          itemStyle: { color: line },
          areaStyle: { color: area },
          data: pts.map((p) => p.cost?.amount ?? 0),
        },
      ],
    };
  });
}
