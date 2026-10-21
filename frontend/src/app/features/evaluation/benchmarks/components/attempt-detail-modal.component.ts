import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import type { AttemptDetail } from '../../../../core/api/types';
import { MarkdownViewerComponent } from '../../../../core/ui/markdown-viewer.component';
import { formatDuration } from '../../../../core/ui/pipes/duration.pipe';
import { batonOf } from '../../../../core/ui/relay/baton';
import { BatonComponent } from '../../../../core/ui/relay/baton.component';
import { CostComponent } from '../../../../core/ui/relay/cost.component';
import { absoluteTime, formatUsage } from '../../../../core/ui/relay/format';
import { GlyphComponent } from '../../../../core/ui/relay/glyph.component';
import { SidePanelComponent } from '../../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../../core/ui/relay/table-sort';
import { humanize } from '../../../../core/ui/status-tag.component';
import { fixed } from '../../../../core/i18n/numbers';
import { categoryLabel } from '../../suites/data/suite-view';

/** One benchmark attempt in the side panel: baton, score, cost, output, judge and verification. */
@Component({
  selector: 'app-attempt-detail-modal',
  imports: [
    NzTabsModule,
    NzTableModule,
    BatonComponent,
    CostComponent,
    GlyphComponent,
    MarkdownViewerComponent,
    SidePanelComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      [open]="open()"
      [title]="attempt()?.taskName ?? text.attempt"
      [subtitle]="subtitle()"
      [defaultWidth]="680"
      storageKey="backoffice.panelWidth.attempt"
      bodyLabel="Attempt details"
      i18n-bodyLabel="@@evaluation.attempt.bodyLabel"
      (closed)="closed.emit()"
    >
      @if (attempt(); as a) {
        <bo-baton boPanelHeader [baton]="baton()" />
      }
      @if (loading() && !attempt()) {
        <bo-skeleton
          variant="list"
          [rows]="6"
          label="Loading the attempt"
          i18n-label="@@evaluation.attempt.loading"
        />
      } @else if (attempt(); as a) {
        <dl class="facts">
          <div>
            <dt i18n="@@evaluation.attempt.score">Score</dt>
            <dd class="big">
              {{ a.score !== null && a.score !== undefined ? fixed(a.score, 1) : '-' }}
            </dd>
          </div>
          <div>
            <dt i18n="@@evaluation.attempt.tests">Tests</dt>
            <dd>
              @if (a.passed === true) {
                <span class="word"
                  ><bo-glyph holder="done" [size]="12" />
                  <ng-container i18n="@@evaluation.attempt.passed">passed</ng-container></span
                >
              } @else if (a.passed === false) {
                <span class="word"
                  ><bo-glyph holder="blocked" [size]="12" />
                  <ng-container i18n="@@evaluation.attempt.failed">failed</ng-container></span
                >
              } @else {
                <span class="quiet" i18n="@@evaluation.attempt.notRun">not run</span>
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@evaluation.attempt.cost">Cost</dt>
            <dd><bo-cost [value]="a.cost" [usage]="a.usage" /></dd>
          </div>
          <div>
            <dt i18n="@@evaluation.attempt.duration">Duration</dt>
            <dd class="mono">{{ duration(a.durationMs) }}</dd>
          </div>
          <div>
            <dt i18n="@@evaluation.attempt.tokens">Tokens</dt>
            <dd class="small">{{ usage() || '-' }}</dd>
          </div>
          <div>
            <dt i18n="@@evaluation.attempt.runnerJob">Runner job</dt>
            <dd class="mono">{{ a.runnerJobId ? a.runnerJobId.slice(0, 8) : '-' }}</dd>
          </div>
        </dl>
        <p class="when">
          @if (a.startedAt) {
            <ng-container i18n="@@evaluation.attempt.started"
              >started {{ abs(a.startedAt) }}</ng-container
            >
          }
          @if (a.finishedAt) {
            ·
            <ng-container i18n="@@evaluation.attempt.finished"
              >finished {{ abs(a.finishedAt) }}</ng-container
            >
          }
        </p>
        @if (a.error) {
          <div class="error" role="alert">
            <bo-glyph holder="blocked" [size]="13" />
            <div>
              <strong i18n="@@evaluation.attempt.errorTitle">The attempt failed.</strong>
              {{ a.error }}
            </div>
          </div>
        }
        <nz-tabs nzSize="small">
          <nz-tab nzTitle="Output" i18n-nzTitle="@@evaluation.attempt.tab.output">
            @if (a.output) {
              <app-markdown [source]="a.output" />
            } @else {
              <p class="quiet" i18n="@@evaluation.attempt.noOutput">No output recorded.</p>
            }
          </nz-tab>
          <nz-tab nzTitle="Judge" i18n-nzTitle="@@evaluation.attempt.tab.judge">
            @if (a.judgeCriteria?.length) {
              <nz-table
                [nzData]="criteria()"
                [nzShowPagination]="false"
                nzSize="small"
                style="margin-bottom: 12px"
              >
                <thead>
                  <tr>
                    <th
                      nzShowSort
                      [boSort]="criteriaSort"
                      boSortKey="name"
                      nzWidth="34%"
                      i18n="@@evaluation.attempt.criterion"
                    >
                      Criterion
                    </th>
                    <th
                      nzShowSort
                      [boSort]="criteriaSort"
                      boSortKey="score"
                      nzWidth="86px"
                      nzAlign="right"
                      i18n="@@evaluation.attempt.col.score"
                    >
                      Score
                    </th>
                    <th
                      nzShowSort
                      [boSort]="criteriaSort"
                      boSortKey="comment"
                      i18n="@@evaluation.attempt.comment"
                    >
                      Comment
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @for (c of criteria(); track $index) {
                    <tr>
                      <td>{{ c.name }}</td>
                      <td nzAlign="right" class="mono">{{ c.score }}</td>
                      <td class="small">{{ c.comment }}</td>
                    </tr>
                  }
                </tbody>
              </nz-table>
            }
            @if (a.judgeRationale) {
              <h3 class="sec" i18n="@@evaluation.attempt.rationale">Rationale</h3>
              <app-markdown [source]="a.judgeRationale" />
            } @else if (!a.judgeCriteria?.length) {
              <p class="quiet" i18n="@@evaluation.attempt.notJudged">Not judged yet.</p>
            }
          </nz-tab>
          @if (a.verification) {
            <nz-tab nzTitle="Verification" i18n-nzTitle="@@evaluation.attempt.tab.verification">
              <p class="verify">
                <span class="mono">{{ a.verification.command }}</span>
                <span class="word">
                  <bo-glyph
                    [holder]="a.verification.exitCode === 0 ? 'done' : 'blocked'"
                    [size]="12"
                  />
                  <ng-container i18n="@@evaluation.attempt.exit"
                    >exit {{ a.verification.exitCode }}</ng-container
                  >
                </span>
              </p>
              <pre class="code-block">{{ a.verification.outputTail }}</pre>
            </nz-tab>
          }
        </nz-tabs>
      }
    </bo-side-panel>
  `,
  styles: [
    `
      .facts {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 12px 16px;
        margin: 0 0 8px;
      }
      dt {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      dd {
        margin: 2px 0 0;
        font-size: 13px;
      }
      dd.big {
        font-family: var(--bo-font-mono);
        font-size: 20px;
        font-weight: 600;
        line-height: 1.2;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .small {
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .quiet,
      .when {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .when {
        margin: 0 0 12px;
      }
      .word {
        display: inline-flex;
        align-items: center;
        gap: 5px;
      }
      .error {
        display: flex;
        gap: 10px;
        align-items: baseline;
        padding: 10px 12px;
        margin-bottom: 12px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-blocked-soft);
        color: var(--bo-ink);
        font-size: 13px;
        box-shadow: inset 3px 0 0 var(--bo-blocked);
      }
      .sec {
        font-size: 12px;
        font-weight: 600;
        color: var(--bo-ink-2);
        margin: 12px 0 6px;
      }
      .verify {
        display: flex;
        gap: 12px;
        align-items: center;
      }
    `,
  ],
})
export class AttemptDetailModalComponent {
  readonly open = input(false);
  readonly loading = input(false);
  readonly attempt = input<AttemptDetail | null>(null);
  readonly closed = output<void>();

  readonly baton = computed(() => batonOf.attempt(this.attempt()?.status));
  readonly usage = computed(() => formatUsage(this.attempt()?.usage));
  readonly subtitle = computed(() => {
    const a = this.attempt();
    if (!a) return null;
    const repetition = a.repetition ?? '-';
    return [
      a.modelDisplayName,
      $localize`:@@evaluation.attempt.repetition:repetition ${repetition}:repetition:`,
      categoryLabel(a.category),
      humanize(a.difficulty).toLowerCase(),
    ].join(' · ');
  });
  /** Column sorting of the judge criteria (URL `?criteriaSort=`; the page's tables use their own params). */
  readonly criteriaSort = tableSort<NonNullable<AttemptDetail['judgeCriteria']>[number]>(
    {
      name: (c) => c.name,
      score: (c) => c.score,
      comment: (c) => c.comment,
    },
    { param: 'criteriaSort' },
  );
  readonly criteria = computed(() => this.criteriaSort.apply(this.attempt()?.judgeCriteria ?? []));
  readonly text = { attempt: $localize`:@@evaluation.attempt.title:Attempt` };
  readonly fixed = fixed;
  readonly duration = formatDuration;
  readonly abs = absoluteTime;
}
