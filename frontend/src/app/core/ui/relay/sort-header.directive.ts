import { DestroyRef, Directive, effect, inject, input } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NzThAddOnComponent } from 'ng-zorro-antd/table';
import type { SortControl, SortOrder } from './table-sort';

/**
 * A sortable column header bound to a [TableSort]: shows the direction and changes it on click; the rows are sorted by
 * the table's owner (`sort.apply`), so keyboard navigation sees the same order. Needs `nzShowSort` on the same `<th>`,
 * which attaches NG-ZORRO's sorter:
 *
 *   <th nzShowSort [boSort]="sort" boSortKey="name">Name</th>
 */
@Directive({ selector: 'th[boSort]' })
export class SortHeaderDirective {
  readonly boSort = input.required<SortControl>();
  readonly boSortKey = input.required<string>();

  private readonly th = inject(NzThAddOnComponent);

  constructor() {
    // the header shows the state from the URL (also after back / forward)
    effect(() => {
      const order = this.boSort().order(this.boSortKey());
      if (this.th.sortOrder !== order) this.th.setSortOrder(order);
    });
    this.th.nzSortOrderChange
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((order) => this.boSort().set(this.boSortKey(), order as SortOrder));
  }
}
