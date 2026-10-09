import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { EffectivePermissions, PermissionDefinition } from '../../../core/api/types';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import {
  effectChip,
  effectiveChip,
  groupEffective,
  sourceLabel,
} from '../data/effective-permissions';
import { StateChipComponent } from './state-chip.component';

/**
 * Per permission: System | Groups | User | Effective | Source, grouped by module. Decisions are
 * baton-like chips: allow (done, check), deny (blocked, octagon), inherit (idle, hollow circle).
 */
@Component({
  selector: 'app-effective-permissions-table',
  imports: [SkeletonComponent, StateChipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!data()) {
      <bo-skeleton
        variant="table"
        [rows]="6"
        [columns]="5"
        label="Loading permissions"
        i18n-label="@@admin.effective.loading"
      />
    } @else {
      <div class="legend" aria-hidden="true">
        <app-state-chip
          tone="done"
          label="allow"
          i18n-label="@@admin.effective.legend.allow"
          size="sm"
        />
        <app-state-chip
          tone="blocked"
          label="deny"
          i18n-label="@@admin.effective.legend.deny"
          size="sm"
        />
        <app-state-chip
          tone="idle"
          label="inherit"
          i18n-label="@@admin.effective.legend.inherit"
          size="sm"
        />
        <span i18n="@@admin.effective.legend.rule"
          >Closest level wins: user over groups over system; within groups deny wins.</span
        >
      </div>
      <div
        class="wrap"
        tabindex="0"
        role="region"
        aria-label="Effective permissions"
        i18n-aria-label="@@admin.effective.regionLabel"
      >
        <table class="bo-table">
          <thead>
            <tr>
              <th scope="col" i18n="@@admin.effective.col.permission">Permission</th>
              <th scope="col" class="c-level" i18n="@@admin.effective.col.system">System</th>
              <th scope="col" class="c-groups" i18n="@@admin.effective.col.groups">Groups</th>
              <th scope="col" class="c-level" i18n="@@admin.effective.col.user">User</th>
              <th scope="col" class="c-eff" i18n="@@admin.effective.col.effective">Effective</th>
              <th scope="col" class="c-src" i18n="@@admin.effective.col.source">Source</th>
            </tr>
          </thead>
          <tbody>
            @for (m of modules(); track m.module) {
              <tr class="module-row">
                <th scope="rowgroup" colspan="6">
                  {{ m.module }}
                  <span class="count" i18n="@@admin.effective.moduleCount"
                    >{{ m.granted }} of {{ m.rows.length }} allowed</span
                  >
                </th>
              </tr>
              @for (r of m.rows; track r.permission) {
                <tr>
                  <td>
                    <div class="perm">{{ r.permission }}</div>
                    @if (r.description) {
                      <div class="desc">{{ r.description }}</div>
                    }
                  </td>
                  <td>
                    <app-state-chip
                      size="sm"
                      [tone]="chip(r.system).holder"
                      [label]="chip(r.system).label"
                    />
                  </td>
                  <td>
                    <div class="groups">
                      @for (g of r.groups; track g.name) {
                        <app-state-chip
                          size="sm"
                          [tone]="chip(g.effect).holder"
                          [label]="g.name + ': ' + chip(g.effect).label"
                        />
                      } @empty {
                        <app-state-chip
                          size="sm"
                          tone="idle"
                          label="inherit"
                          i18n-label="@@admin.effective.groupsInherit"
                        />
                      }
                    </div>
                  </td>
                  <td>
                    <app-state-chip
                      size="sm"
                      [tone]="chip(r.user).holder"
                      [label]="chip(r.user).label"
                    />
                  </td>
                  <td>
                    <app-state-chip [tone]="effective(r).holder" [label]="effective(r).label" />
                  </td>
                  <td class="src">{{ source(r) }}</td>
                </tr>
              }
            } @empty {
              <tr>
                <td colspan="6" class="muted" i18n="@@admin.effective.empty">
                  No platform permissions are defined.
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styles: [
    `
      .legend {
        display: flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
        margin-bottom: 10px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .legend span {
        margin-left: 6px;
      }
      .wrap:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .wrap {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        overflow: auto;
        max-height: 70vh;
      }
      table {
        width: 100%;
        border-collapse: separate;
        border-spacing: 0;
        font-size: 13px;
      }
      thead th {
        position: sticky;
        top: 0;
        z-index: 2;
        background: var(--bo-surface-2);
        text-align: left;
        font-weight: 500;
        font-size: 12px;
        color: var(--bo-ink-2);
        padding: 8px 12px;
        border-bottom: 1px solid var(--bo-line);
      }
      td {
        padding: 7px 12px;
        border-bottom: 1px solid var(--bo-line);
        vertical-align: middle;
      }
      tbody tr:last-child td {
        border-bottom: 0;
      }
      tbody tr:not(.module-row):hover td {
        background: var(--bo-surface-2);
      }
      .module-row th {
        text-align: left;
        padding: 10px 12px 6px;
        font-size: 12px;
        font-weight: 600;
        color: var(--bo-ink);
        background: var(--bo-surface);
        border-bottom: 1px solid var(--bo-line);
        text-transform: none;
      }
      .count {
        margin-left: 6px;
        font-weight: 400;
        color: var(--bo-ink-3);
      }
      .perm {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .desc {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .c-level {
        width: 100px;
      }
      .c-groups {
        width: 220px;
      }
      .c-eff {
        width: 120px;
      }
      .c-src {
        width: 190px;
      }
      .groups {
        display: flex;
        gap: 4px;
        flex-wrap: wrap;
      }
      .src {
        font-size: 12px;
        color: var(--bo-ink-2);
      }
    `,
  ],
})
export class EffectivePermissionsTableComponent {
  readonly data = input<EffectivePermissions | null>(null);
  readonly catalogue = input<PermissionDefinition[]>([]);
  readonly modules = computed(() => groupEffective(this.data(), this.catalogue()));
  readonly chip = effectChip;
  readonly effective = effectiveChip;
  readonly source = sourceLabel;
}
