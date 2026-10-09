import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  model,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { Observable, Subject, catchError, debounceTime, of, switchMap } from 'rxjs';

type LookupRow = { id?: string; displayName?: string; name?: string; email?: string };
import { AdminApi } from '../api/admin.service';

export interface PrincipalOption {
  id: string;
  label: string;
  hint?: string;
}

/**
 * Search-as-you-type picker for users (`GET /users/lookup`, at least 2 characters) or groups
 * (`GET /groups/lookup`). Two-way `[(value)]`: a single id or, with `multiple`, an id array.
 * `known` supplies labels for ids selected before (lookups only return what matches the query).
 */
@Component({
  selector: 'app-principal-picker',
  imports: [FormsModule, NzSelectModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-select
      [nzMode]="multiple() ? 'multiple' : 'default'"
      nzShowSearch
      nzServerSearch
      nzAllowClear
      [nzPlaceHolder]="placeholder()"
      [nzDisabled]="disabled()"
      [nzLoading]="loading()"
      [nzNotFoundContent]="notFound()"
      [ngModel]="value()"
      (ngModelChange)="value.set($event)"
      (nzOnSearch)="search($event)"
      [attr.aria-label]="ariaLabel() || placeholder()"
      style="width: 100%"
    >
      @for (o of options(); track o.id) {
        <nz-option nzCustomContent [nzValue]="o.id" [nzLabel]="o.label">
          <div>{{ o.label }}</div>
          @if (o.hint) {
            <div class="muted" style="font-size: 12px">{{ o.hint }}</div>
          }
        </nz-option>
      }
    </nz-select>
  `,
})
export class PrincipalPickerComponent {
  private readonly api = inject(AdminApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly kind = input<'user' | 'group'>('user');
  readonly multiple = input(false);
  readonly disabled = input(false);
  readonly placeholder = input($localize`:@@core.picker.placeholder:Search by name or e-mail`);
  readonly ariaLabel = input<string | null>(null);
  readonly known = input<PrincipalOption[]>([]);
  readonly value = model<string | string[] | null>(null);

  readonly loading = signal(false);
  /** The lookup answered 403: the caller may not search users/groups. */
  readonly forbidden = signal(false);
  private readonly results = signal<PrincipalOption[]>([]);
  private readonly query = signal('');
  private readonly search$ = new Subject<string>();

  readonly options = computed(() => {
    const byId = new Map<string, PrincipalOption>();
    for (const o of this.known()) byId.set(o.id, o);
    for (const o of this.results()) byId.set(o.id, o);
    const selected = this.value();
    const ids = Array.isArray(selected) ? selected : selected ? [selected] : [];
    for (const id of ids) if (!byId.has(id)) byId.set(id, { id, label: `${id.slice(0, 8)}…` });
    return [...byId.values()];
  });
  readonly notFound = computed(() =>
    this.forbidden()
      ? $localize`:@@core.picker.forbidden:Searching needs project.members.manage or admin.users.view`
      : this.kind() === 'user' && this.query().trim().length < 2
        ? $localize`:@@core.picker.typeMore:Type at least 2 characters`
        : $localize`:@@core.picker.noMatches:No matches`,
  );

  constructor() {
    this.search$
      .pipe(
        debounceTime(250),
        switchMap((q) => {
          const kind = untracked(() => this.kind());
          if (kind === 'user' && q.trim().length < 2) return of<LookupRow[]>([]);
          this.loading.set(true);
          this.forbidden.set(false);
          const source: Observable<LookupRow[]> =
            kind === 'user' ? this.api.lookupUsers(q.trim()) : this.api.lookupGroups(q.trim());
          return source.pipe(
            catchError((err: unknown) => {
              this.forbidden.set(err instanceof HttpErrorResponse && err.status === 403);
              return of<LookupRow[]>([]);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((list) => {
        this.loading.set(false);
        this.results.set(
          list
            .filter((x) => !!x.id)
            .map((x) => ({
              id: x.id!,
              label: x.displayName || x.name || x.email || x.id!,
              hint: x.email && x.displayName ? x.email : undefined,
            })),
        );
      });
    // Groups can be listed without a query.
    effect(() => {
      if (this.kind() === 'group') untracked(() => this.search$.next(''));
    });
  }

  search(q: string): void {
    this.query.set(q);
    this.search$.next(q);
  }
}
