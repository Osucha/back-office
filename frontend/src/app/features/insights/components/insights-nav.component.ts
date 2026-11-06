import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import {
  SegmentedNavComponent,
  type SegmentedNavItem,
} from '../../../core/ui/relay/segmented-nav.component';

/** Delivery / Agents switcher under the Insights page headers (ADR 0059). */
@Component({
  selector: 'app-insights-nav',
  imports: [SegmentedNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bo-segmented-nav
    label="Insights sections"
    i18n-label="@@insights.nav.label"
    [items]="items"
    [active]="active()"
  />`,
})
export class InsightsNavComponent {
  readonly active = input<'delivery' | 'agents'>('delivery');

  readonly items: SegmentedNavItem[] = [
    {
      id: 'delivery',
      label: $localize`:@@insights.nav.delivery:Delivery`,
      link: '/insights',
    },
    {
      id: 'agents',
      label: $localize`:@@insights.nav.agents:Agents`,
      link: '/insights/agents',
    },
  ];
}
