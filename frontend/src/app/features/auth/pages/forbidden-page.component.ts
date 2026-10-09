import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzResultModule } from 'ng-zorro-antd/result';

/** Shown by `permissionGuard` (the address bar keeps the URL that was refused). */
@Component({
  selector: 'app-forbidden-page',
  imports: [NzResultModule, NzButtonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <nz-result
        nzStatus="403"
        nzTitle="Access denied"
        i18n-nzTitle="@@auth.forbidden.title"
        [nzSubTitle]="subtitle()"
      >
        <div nz-result-extra>
          <a nz-button nzType="primary" routerLink="/dashboard" i18n="@@auth.forbidden.home"
            >Go to the dashboard</a
          >
          <a nz-button routerLink="/account" i18n="@@auth.forbidden.permissions">My permissions</a>
        </div>
      </nz-result>
    </div>
  `,
})
export class ForbiddenPageComponent {
  /** `?from=` set by the guard. */
  readonly from = input<string | undefined>();
  readonly subtitle = computed(() => {
    const page = this.from() || $localize`:@@auth.forbidden.thisPage:this page`;
    return $localize`:@@auth.forbidden.text:You do not have permission to open ${page}:page:. Ask a project owner or an administrator for access.`;
  });
}
