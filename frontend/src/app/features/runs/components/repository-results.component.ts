import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import type { RepositoryResult } from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { icu } from '../../../core/i18n/icu';
import { pushStateLabel, repositoryRows } from '../data/repository-results';

/**
 * Per-repository results of a runner step or of the whole run (ADR 0034): key and purpose, access,
 * branch, short commit, diff stat, merge request link, pushed / not pushed and the error. The agent
 * never merges; a push only opens a merge request (ADR 0031).
 */
@Component({
  selector: 'app-repository-results',
  imports: [NzIconModule, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (rows().length) {
      <section class="rr-block" [attr.aria-labelledby]="titleId">
        <h3 class="rr-title" [id]="titleId">
          <span i18n="@@runs.repos.title">Repositories</span>
          <bo-help-link topic="run.repositories" />
        </h3>
        <ul class="rr-list">
          @for (x of rows(); track x.key) {
            <li class="rr" [attr.data-state]="x.state">
              <div class="rr-head">
                <span class="mono rr-key" [attr.title]="x.url">{{ x.key }}</span>
                @if (x.purpose) {
                  <span class="chip">{{ x.purpose }}</span>
                }
                @if (x.primary) {
                  <span class="chip primary" i18n="@@runs.repos.primary">primary</span>
                }
                @if (x.access) {
                  <span class="chip access">{{ x.access }}</span>
                }
                <span class="spacer"></span>
                <span class="state" [attr.data-state]="x.state">{{ stateLabel(x.state) }}</span>
              </div>
              @if (x.branch || x.commit || x.diff) {
                <div class="rr-meta mono">
                  @if (x.branch) {
                    <span>{{ x.branch }}</span>
                  }
                  @if (x.commit) {
                    <span [attr.title]="x.commitSha">{{ x.commit }}</span>
                  }
                  @if (x.diff; as d) {
                    <span class="diff" [attr.aria-label]="diffLabel(d)"
                      ><span class="ins">+{{ d.insertions }}</span>
                      <span class="del">−{{ d.deletions }}</span></span
                    >
                  }
                </div>
              }
              @if (x.mergeRequestUrl) {
                <a
                  class="rr-mr"
                  [href]="x.mergeRequestUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  [attr.aria-label]="mrLabel(x.key)"
                >
                  <nz-icon nzType="pull-request" />
                  <span i18n="@@runs.repos.mergeRequest">Merge request</span>
                  <nz-icon nzType="export" />
                </a>
              }
              @if (x.error) {
                <p class="rr-error">{{ x.error }}</p>
              }
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: [
    `
      .rr-block {
        margin: 12px 0;
      }
      .rr-title {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 12px;
        font-weight: 600;
        color: var(--bo-ink-2);
        margin: 0 0 6px;
      }
      .rr-list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .rr {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-control);
        padding: 6px 8px;
        font-size: 12px;
      }
      .rr[data-state='failed'] {
        border-color: var(--bo-blocked);
      }
      .rr-head {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 6px;
      }
      .rr-key {
        font-weight: 600;
        color: var(--bo-ink);
      }
      .chip {
        font-size: 11px;
        padding: 0 6px;
        border-radius: 999px;
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        color: var(--bo-ink-2);
      }
      .chip.primary {
        background: var(--bo-agent-soft);
        border-color: transparent;
        color: var(--bo-agent-ink);
      }
      .spacer {
        flex: 1;
      }
      .state {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .state[data-state='pushed'] {
        color: var(--bo-done-ink);
      }
      .state[data-state='failed'] {
        color: var(--bo-blocked-ink);
      }
      .rr-meta {
        display: flex;
        flex-wrap: wrap;
        gap: 10px;
        margin-top: 4px;
        color: var(--bo-ink-3);
        font-size: 11.5px;
        overflow-wrap: anywhere;
      }
      .ins {
        color: var(--bo-done-ink);
      }
      .del {
        color: var(--bo-blocked-ink);
      }
      .rr-mr {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        margin-top: 4px;
      }
      .rr-error {
        margin: 4px 0 0;
        color: var(--bo-blocked-ink);
        overflow-wrap: anywhere;
      }
    `,
  ],
})
export class RepositoryResultsComponent {
  readonly results = input<readonly RepositoryResult[] | null | undefined>([]);

  readonly rows = computed(() => repositoryRows(this.results()));
  readonly titleId = `rr-${Math.random().toString(36).slice(2, 8)}`;
  readonly stateLabel = pushStateLabel;

  diffLabel(d: { insertions: number; deletions: number; files: number }): string {
    return icu(
      $localize`:@@runs.repos.diffLabel:{files, plural, one {# file changed} other {# files changed}}: +{insertions} −{deletions}`,
      { files: d.files, insertions: d.insertions, deletions: d.deletions },
    );
  }

  mrLabel(key: string): string {
    return $localize`:@@runs.repos.mrLabel:Open the merge request of ${key}:key:`;
  }
}
