import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  SegmentedNavComponent,
  type SegmentedNavItem,
} from '../../../core/ui/relay/segmented-nav.component';

/** Suites / Benchmarks switcher under the Evaluation page headers. */
@Component({
  selector: 'app-evaluation-nav',
  imports: [SegmentedNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bo-segmented-nav
    label="Evaluation sections"
    i18n-label="@@evaluation.nav.label"
    [items]="items()"
    [active]="active()"
  />`,
})
export class EvaluationNavComponent {
  readonly active = input<'suites' | 'benchmarks'>('suites');
  readonly counts = input<{ suites?: number; benchmarks?: number }>({});

  readonly items = computed<SegmentedNavItem[]>(() => [
    {
      id: 'suites',
      label: $localize`:@@evaluation.nav.suites:Task suites`,
      link: '/evaluation/suites',
      count: this.counts().suites,
    },
    {
      id: 'benchmarks',
      label: $localize`:@@evaluation.nav.benchmarks:Benchmarks`,
      link: '/evaluation/benchmarks',
      count: this.counts().benchmarks,
    },
  ]);
}
