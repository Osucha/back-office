import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  SegmentedNavComponent,
  type SegmentedNavItem,
} from '../../../core/ui/relay/segmented-nav.component';

export type LibrarySection = 'templates' | 'skills' | 'rules' | 'agents' | 'workflows' | 'sources';

/**
 * Relay segmented control shared by the Library pages (with optional item counts), in three groups
 * (ADR 0055): Tasks (templates, skills, rules, agents - ADR 0056), Workflows and Sources.
 */
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
    const tasks = $localize`:@@library.nav.group.tasks:Tasks`;
    return [
      {
        id: 'templates',
        label: $localize`:@@library.nav.templates:Templates`,
        link: '/library/templates',
        count: c.templates,
        group: 'tasks',
        groupLabel: tasks,
      },
      {
        id: 'skills',
        label: $localize`:@@library.nav.skills:Skills`,
        link: '/library/skills',
        count: c.skills,
        group: 'tasks',
        groupLabel: tasks,
      },
      {
        id: 'rules',
        label: $localize`:@@library.nav.rules:Rules`,
        link: '/library/rules',
        count: c.rules,
        group: 'tasks',
        groupLabel: tasks,
      },
      {
        id: 'agents',
        label: $localize`:@@library.nav.agents:Agents`,
        link: '/library/agents',
        count: c.agents,
        group: 'tasks',
        groupLabel: tasks,
      },
      {
        id: 'workflows',
        label: $localize`:@@library.nav.workflows:Workflows`,
        link: '/library/workflows',
        count: c.workflows,
        group: 'workflows',
      },
      {
        id: 'sources',
        label: $localize`:@@library.nav.sources:Sources`,
        link: '/library/sources',
        count: c.sources,
        group: 'sources',
      },
    ];
  });
}
