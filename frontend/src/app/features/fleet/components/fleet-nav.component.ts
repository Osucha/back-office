import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  SegmentedNavComponent,
  type SegmentedNavItem,
} from '../../../core/ui/relay/segmented-nav.component';

export type FleetSection = 'pools' | 'runners' | 'jobs';

/** Pools / Runners / Agent jobs switcher under the fleet page headers. */
@Component({
  selector: 'app-fleet-nav',
  imports: [SegmentedNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bo-segmented-nav
    label="Fleet sections"
    i18n-label="@@fleet.nav.label"
    [items]="items()"
    [active]="active()"
  />`,
})
export class FleetNavComponent {
  readonly active = input<FleetSection>('pools');
  readonly counts = input<Partial<Record<FleetSection, number | null>>>({});
  readonly items = computed<SegmentedNavItem[]>(() => [
    {
      id: 'pools',
      label: $localize`:@@fleet.nav.pools:Pools`,
      link: '/admin/fleet/pools',
      count: this.counts().pools,
    },
    {
      id: 'runners',
      label: $localize`:@@fleet.nav.runners:Runners`,
      link: '/admin/fleet/runners',
      count: this.counts().runners,
    },
    {
      id: 'jobs',
      label: $localize`:@@fleet.nav.jobs:Agent jobs`,
      link: '/admin/fleet/jobs',
      count: this.counts().jobs,
    },
  ]);
}
