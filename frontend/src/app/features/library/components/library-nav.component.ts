import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  SegmentedNavComponent,
  type SegmentedNavItem,
} from '../../../core/ui/relay/segmented-nav.component';

export type LibrarySection = 'templates' | 'interfaces' | 'skills';

/** Relay segmented control shared by the three Library pages (with optional item counts). */
@Component({
  selector: 'app-library-nav',
  imports: [SegmentedNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bo-segmented-nav
    label="Library sections"
    i18n-label="@@library.nav.label"
    [items]="items()"
    [active]="active()"
  />`,
})
export class LibraryNavComponent {
  readonly active = input<LibrarySection>('templates');
  /** Optional item counts per section (shown next to the label). */
  readonly counts = input<Partial<Record<LibrarySection, number>>>({});

  readonly items = computed<SegmentedNavItem[]>(() => {
    const c = this.counts();
    return [
      {
        id: 'templates',
        label: $localize`:@@library.nav.templates:Templates`,
        link: '/library/templates',
        count: c.templates,
      },
      {
        id: 'interfaces',
        label: $localize`:@@library.nav.interfaces:Interfaces`,
        link: '/library/interfaces',
        count: c.interfaces,
      },
      {
        id: 'skills',
        label: $localize`:@@library.nav.skills:Skills`,
        link: '/library/skills',
        count: c.skills,
      },
    ];
  });
}
