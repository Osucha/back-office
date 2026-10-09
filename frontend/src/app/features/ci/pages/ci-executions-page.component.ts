import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { ProjectsStore } from '../../../core/state/projects.store';
import type { BatonHolder } from '../../../core/ui/relay/baton';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { FilterBarComponent, type FilterChip } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { CiExecutionsTableComponent } from '../components/ci-executions-table.component';

/** CI executions across projects, with the gate result as the baton; filters live in the URL. */
@Component({
  selector: 'app-ci-executions-page',
  imports: [
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzSelectModule,
    FilterBarComponent,
    PageHeaderComponent,
    CiExecutionsTableComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="ci"
        title="CI"
        subtitle="Workflows started from a project pipeline with backoffice-ci run; in blocking mode the gate result decides the CI job."
        i18n-subtitle="@@ci.list.subtitle"
      >
        <a boHeaderActions nz-button routerLink="/settings" i18n="@@ci.list.getScript"
          >Get the CI script</a
        >
      </bo-page-header>

      <bo-filter-bar
        placeholder="Search entrypoint, merge request, ref or commit"
        i18n-placeholder="@@ci.list.search"
        label="Filter CI executions"
        i18n-label="@@ci.list.filterLabel"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [holders]="holders"
        [selectedHolders]="f.values().holder"
        (selectedHoldersChange)="f.set({ holder: $event })"
        [chips]="chips()"
        (chipRemoved)="f.set({ projectId: null })"
        (cleared)="f.clear()"
      >
        <nz-select
          [ngModel]="f.values().projectId"
          (ngModelChange)="f.set({ projectId: $event })"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="All projects"
          i18n-nzPlaceHolder="@@ci.list.allProjects"
          style="width: 200px"
          aria-label="Filter by project"
          i18n-aria-label="@@ci.list.byProject"
        >
          @for (p of store.projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' · ' + p.name" />
          }
        </nz-select>
      </bo-filter-bar>

      <app-ci-executions-table
        [projectId]="f.values().projectId"
        [holders]="f.values().holder"
        [query]="f.values().q"
        [emptyText]="f.active() ? text.noMatch : text.none"
        [emptyActionLabel]="
          f.active() ? text.clearFilters : f.values().projectId ? text.setUp : text.goToProjects
        "
        [emptyAction]="emptyAction"
      />
    </div>
  `,
})
export class CiExecutionsPageComponent {
  private readonly router = inject(Router);
  readonly store = inject(ProjectsStore);
  readonly f = urlFilters({ q: 'string', holder: 'list', projectId: 'string' });
  readonly holders: BatonHolder[] = ['agent', 'done', 'blocked', 'idle'];

  readonly text = {
    noMatch: $localize`:@@ci.list.noMatch:No CI executions match these filters.`,
    none: $localize`:@@ci.list.none:No CI executions yet. Add backoffice-ci to a project pipeline and run an entrypoint.`,
    clearFilters: $localize`:@@ci.list.clearFilters:Clear filters`,
    setUp: $localize`:@@ci.list.setUp:Set up CI for this project`,
    goToProjects: $localize`:@@ci.list.goToProjects:Go to projects`,
  };

  readonly chips = computed<FilterChip[]>(() => {
    const id = this.f.values().projectId;
    const key =
      (id && this.store.byId().get(id)?.key) || $localize`:@@ci.list.chip.selected:selected`;
    return id
      ? [{ id: 'projectId', label: $localize`:@@ci.list.chip.project:Project: ${key}:project:` }]
      : [];
  });

  readonly emptyAction = () => {
    if (this.f.values().q || this.f.values().holder.length) return this.f.clear();
    const id = this.f.values().projectId;
    return id
      ? void this.router.navigate(['/projects', id], { queryParams: { tab: 'ci' } })
      : void this.router.navigate(['/projects']);
  };

  constructor() {
    inject(BreadcrumbService).set([{ label: 'CI' }]);
    this.store.ensure();
  }
}
