import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { NgxEchartsDirective } from 'ngx-echarts';
import { debounceTime, filter, interval } from 'rxjs';
import { BenchmarksApi } from '../../../../core/api/benchmarks.service';
import type {
  Attempt,
  AttemptDetail,
  Benchmark,
  ExecutionTarget,
  Leaderboard,
  LeaderboardRow,
} from '../../../../core/api/types';
import { IdentityService } from '../../../../core/auth/identity.service';
import { EventStreamService } from '../../../../core/events/event-stream.service';
import { ConfirmService } from '../../../../core/ui/confirm.service';
import { formatDuration } from '../../../../core/ui/pipes/duration.pipe';
import { batonOf, type BatonHolder } from '../../../../core/ui/relay/baton';
import { BatonComponent } from '../../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../../core/ui/relay/breadcrumb.service';
import { chartTokensSignal } from '../../../../core/ui/relay/chart-tokens';
import { CostComponent } from '../../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../../core/ui/relay/empty.component';
import {
  FilterBarComponent,
  type FilterChip,
} from '../../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../../core/ui/relay/filter-state';
import { GlyphComponent } from '../../../../core/ui/relay/glyph.component';
import { listNavigation } from '../../../../core/ui/relay/list-nav';
import {
  PageHeaderComponent,
  type PageAction,
} from '../../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../../core/ui/relay/provenance.component';
import { panelParam } from '../../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../../core/ui/relay/skeleton.component';
import { RELAY_TABLE_STYLES } from '../../../../core/ui/relay/table-styles';
import { humanize } from '../../../../core/ui/status-tag.component';
import { icu } from '../../../../core/i18n/icu';
import { fixed } from '../../../../core/i18n/numbers';
import { AttemptDetailModalComponent } from '../components/attempt-detail-modal.component';
import { AttemptStripComponent } from '../components/attempt-strip.component';
import { attemptCounts, benchmarkBaton, filterAttemptsByHolder } from '../data/benchmark-view';
import { scatterOption } from '../data/leaderboard-chart';
import { categoryLabel } from '../../suites/data/suite-view';
import { SortHeaderDirective } from '../../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../../core/ui/relay/table-sort';

const VIRTUAL_FROM = 100;
const ATTEMPT_HOLDERS: BatonHolder[] = ['agent', 'done', 'blocked', 'idle'];
const PROVIDERS: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  GOOGLE: 'Google',
  MISTRAL: 'Mistral',
  OLLAMA: 'Ollama',
  OPENAI_COMPATIBLE: $localize`:@@evaluation.provider.openaiCompatible:OpenAI-compatible`,
};

/**
 * Benchmark: status, attempt strip, leaderboard (score-vs-cost chart in token colours, lazy with
 * `@defer`, and the ranking tables) and the attempts list with filters in the URL; an attempt
 * opens in the side panel (`?attempt=<id>`).
 */
@Component({
  selector: 'app-benchmark-detail-page',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzTabsModule,
    NzTableModule,
    NzSelectModule,
    NzTooltipModule,
    NgxEchartsDirective,
    AttemptDetailModalComponent,
    AttemptStripComponent,
    BatonComponent,
    CostComponent,
    EmptyComponent,
    FilterBarComponent,
    GlyphComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      @if (!benchmark()) {
        @if (notFound()) {
          <bo-empty
            title="This benchmark does not exist any more."
            i18n-title="@@evaluation.benchmark.notFound"
            actionLabel="Go to benchmarks"
            i18n-actionLabel="@@evaluation.benchmark.goToList"
            (action)="router.navigate(['/evaluation/benchmarks'])"
          />
        } @else {
          <bo-skeleton
            variant="detail"
            label="Loading the benchmark"
            i18n-label="@@evaluation.benchmark.loading"
          />
        }
      } @else if (benchmark(); as b) {
        <bo-page-header
          help="benchmarks"
          [eyebrow]="eyebrow(b.suiteName ?? '')"
          [title]="b.name"
          [menu]="menu()"
        >
          <bo-baton boHeaderStatus [baton]="baton()" />
          <div boHeaderMeta class="meta-line">
            <bo-provenance
              verb="created by"
              i18n-verb="@@evaluation.benchmark.createdBy"
              [actor]="b.createdBy"
              [at]="b.createdAt"
              [cost]="b.totalCost"
            />
            <span class="sep" aria-hidden="true">·</span>
            <a
              [routerLink]="['/evaluation/suites', b.suiteId]"
              i18n="@@evaluation.benchmark.openSuite"
              >Open the suite</a
            >
          </div>
          @if (b.status === 'DRAFT' && canRun()) {
            <button
              boHeaderActions
              nz-button
              nzType="primary"
              type="button"
              (click)="start()"
              [nzLoading]="busy() === 'start'"
            >
              <nz-icon nzType="caret-right" />
              <ng-container i18n="@@evaluation.benchmark.start">Start benchmark</ng-container>
            </button>
          }
          @if (b.status === 'RUNNING' && canRun()) {
            <button
              boHeaderActions
              nz-button
              nzDanger
              type="button"
              (click)="cancel()"
              [nzLoading]="busy() === 'cancel'"
            >
              <nz-icon nzType="stop" />
              <ng-container i18n="@@evaluation.benchmark.cancel">Cancel benchmark</ng-container>
            </button>
          }
          <app-attempt-strip
            boHeaderBelow
            [progress]="b.progress"
            [status]="b.status"
            [legend]="true"
          />
        </bo-page-header>

        <dl class="facts">
          <div class="wide">
            <dt i18n="@@evaluation.benchmark.models">Models</dt>
            <dd class="models">
              @for (m of b.models ?? []; track m.id) {
                <span class="model"
                  ><span class="dot" [attr.data-provider]="m.ref.provider" aria-hidden="true"></span
                  >{{ m.displayName }}</span
                >
              } @empty {
                <span class="mono">{{ b.modelProfileIds.length }}</span>
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@evaluation.benchmark.judge">Judge</dt>
            <dd>{{ b.judge?.displayName ?? b.judgeModelProfileId }}</dd>
          </div>
          <div>
            <dt i18n="@@evaluation.benchmark.repetitions">Repetitions</dt>
            <dd class="mono">{{ b.repetitions }}</dd>
          </div>
          <div>
            <dt i18n="@@evaluation.benchmark.harness">Harness</dt>
            <dd>{{ b.codingHarness === 'CLAUDE_CODE' ? 'Claude Code' : 'OpenCode' }}</dd>
          </div>
          <div>
            <dt i18n="@@evaluation.benchmark.runner">Coding tasks run on</dt>
            <dd>
              @if (b.runnerTarget === 'PERSONAL') {
                <ng-container i18n="@@evaluation.benchmark.runnerPersonal"
                  >personal runner</ng-container
                >
              } @else {
                <ng-container i18n="@@evaluation.benchmark.runnerShared"
                  >shared runners</ng-container
                >
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@evaluation.benchmark.totalCost">Total cost</dt>
            <dd><bo-cost [value]="b.totalCost" /></dd>
          </div>
        </dl>

        @if (waitingForRunner()) {
          <div class="runner-notice" role="note">
            <bo-glyph holder="idle" [size]="12" />
            <span i18n="@@evaluation.benchmark.waitingForRunner"
              >Coding attempts are waiting for a runner: none is online where this benchmark sends
              them. They start when one connects and fail after 24 hours.</span
            >
          </div>
        }

        <nz-tabs [nzSelectedIndex]="tabIndex()" (nzSelectedIndexChange)="setTab($event)">
          <nz-tab nzTitle="Leaderboard" i18n-nzTitle="@@evaluation.benchmark.tab.leaderboard">
            @if (!leaderboard()?.overall?.length) {
              <bo-empty
                tone="calm"
                [holder]="b.status === 'RUNNING' ? 'agent' : 'idle'"
                [holderLabel]="b.status === 'RUNNING' ? text.attemptsRunning : text.noScores"
                title="No scored attempts yet."
                i18n-title="@@evaluation.benchmark.noScored"
                detail="The leaderboard fills in as attempts finish and the judge scores them."
                i18n-detail="@@evaluation.benchmark.noScoredDetail"
                [actionLabel]="b.status === 'DRAFT' && canRun() ? text.start : null"
                (action)="start()"
              />
            } @else {
              <section class="card chart-card" aria-labelledby="chart-title">
                <header class="card-head">
                  <h2 id="chart-title" i18n="@@evaluation.benchmark.chartTitle">Score vs cost</h2>
                  <span class="hint" i18n="@@evaluation.benchmark.chartHint"
                    >Top left is better; the dashed line is the Pareto frontier.</span
                  >
                  <span
                    class="legend"
                    aria-label="Providers"
                    i18n-aria-label="@@evaluation.benchmark.providers"
                  >
                    @for (p of providersInChart(); track p) {
                      <span class="model"
                        ><span class="dot" [attr.data-provider]="p" aria-hidden="true"></span
                        >{{ providerName(p) }}</span
                      >
                    }
                  </span>
                </header>
                @defer (on viewport) {
                  <div
                    echarts
                    [options]="scatter()"
                    class="chart"
                    role="img"
                    [attr.aria-label]="chartLabel()"
                  ></div>
                } @placeholder {
                  <div class="chart chart-placeholder" aria-hidden="true"></div>
                }
              </section>

              <nz-tabs nzSize="small" class="breakdown">
                <nz-tab nzTitle="Overall" i18n-nzTitle="@@evaluation.benchmark.tab.overall">
                  <ng-container
                    *ngTemplateOutlet="rowsTable; context: { rows: leaderboard()?.overall ?? [] }"
                  />
                </nz-tab>
                <nz-tab nzTitle="By category" i18n-nzTitle="@@evaluation.benchmark.tab.byCategory">
                  @for (entry of byCategory(); track entry.key) {
                    <h3 class="group-title">{{ category(entry.key) }}</h3>
                    <ng-container *ngTemplateOutlet="rowsTable; context: { rows: entry.rows }" />
                  } @empty {
                    <bo-empty
                      tone="quiet"
                      title="No breakdown by category yet."
                      i18n-title="@@evaluation.benchmark.noByCategory"
                    />
                  }
                </nz-tab>
                <nz-tab
                  nzTitle="By difficulty"
                  i18n-nzTitle="@@evaluation.benchmark.tab.byDifficulty"
                >
                  @for (entry of byDifficulty(); track entry.key) {
                    <h3 class="group-title">{{ humanize(entry.key) }}</h3>
                    <ng-container *ngTemplateOutlet="rowsTable; context: { rows: entry.rows }" />
                  } @empty {
                    <bo-empty
                      tone="quiet"
                      title="No breakdown by difficulty yet."
                      i18n-title="@@evaluation.benchmark.noByDifficulty"
                    />
                  }
                </nz-tab>
                <nz-tab nzTitle="By task" i18n-nzTitle="@@evaluation.benchmark.tab.byTask">
                  @for (entry of leaderboard()?.byTask ?? []; track entry.taskId) {
                    <h3 class="group-title">{{ entry.taskName }}</h3>
                    <ng-container
                      *ngTemplateOutlet="rowsTable; context: { rows: entry.rows ?? [] }"
                    />
                  } @empty {
                    <bo-empty
                      tone="quiet"
                      title="No breakdown by task yet."
                      i18n-title="@@evaluation.benchmark.noByTask"
                    />
                  }
                </nz-tab>
              </nz-tabs>
            }
          </nz-tab>

          <nz-tab [nzTitle]="attemptsTab()">
            <bo-filter-bar
              placeholder="Search tasks or models"
              i18n-placeholder="@@evaluation.benchmark.attempts.search"
              label="Filter attempts"
              i18n-label="@@evaluation.benchmark.attempts.filter"
              [query]="f.values().aq ?? ''"
              (queryChange)="f.set({ aq: $event })"
              [holders]="attemptHolders"
              [selectedHolders]="f.values().aholder"
              (selectedHoldersChange)="f.set({ aholder: $event })"
              [chips]="chips()"
              (chipRemoved)="removeChip($event)"
              (cleared)="f.clear()"
            >
              <nz-select
                [ngModel]="f.values().atask"
                (ngModelChange)="f.set({ atask: $event })"
                nzAllowClear
                nzShowSearch
                nzPlaceHolder="All tasks"
                i18n-nzPlaceHolder="@@evaluation.benchmark.attempts.allTasks"
                style="width: 220px"
                aria-label="Filter attempts by task"
                i18n-aria-label="@@evaluation.benchmark.attempts.filterTask"
              >
                @for (t of taskOptions(); track t.id) {
                  <nz-option [nzValue]="t.id" [nzLabel]="t.name" />
                }
              </nz-select>
              <nz-select
                [ngModel]="f.values().amodel"
                (ngModelChange)="f.set({ amodel: $event })"
                nzAllowClear
                nzShowSearch
                nzPlaceHolder="All models"
                i18n-nzPlaceHolder="@@evaluation.benchmark.attempts.allModels"
                style="width: 190px"
                aria-label="Filter attempts by model"
                i18n-aria-label="@@evaluation.benchmark.attempts.filterModel"
              >
                @for (m of b.models ?? []; track m.id) {
                  <nz-option [nzValue]="m.id" [nzLabel]="m.displayName" />
                }
              </nz-select>
            </bo-filter-bar>
            <div
              class="counts"
              aria-label="Attempts by state"
              i18n-aria-label="@@evaluation.benchmark.attempts.byState"
            >
              @for (c of counts(); track c.label) {
                <span class="state-word"
                  ><bo-glyph [holder]="c.holder" [size]="11" /> {{ c.count }} {{ c.label }}</span
                >
              }
            </div>
            @if (attemptsLoading() && !attemptsLoaded()) {
              <bo-skeleton
                variant="table"
                [rows]="8"
                [columns]="7"
                label="Loading attempts"
                i18n-label="@@evaluation.benchmark.attempts.loading"
              />
            } @else if (!filteredAttempts().length) {
              <bo-empty
                tone="quiet"
                [title]="f.active() ? text.noAttemptMatch : text.noAttempts"
                [actionLabel]="f.active() ? text.clearFilters : null"
                [primary]="false"
                (action)="f.clear()"
              />
            } @else {
              <nz-table
                #at
                [nzData]="attemptRows()"
                [nzPageSize]="virtual() ? attemptRows().length : 50"
                [nzShowPagination]="!virtual() && attemptRows().length > 50"
                [nzVirtualItemSize]="virtual() ? 45 : 0"
                [nzVirtualForTrackBy]="trackAttempt"
                [nzScroll]="virtual() ? { y: '60vh' } : {}"
                nzSize="middle"
              >
                <thead>
                  <tr>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="status"
                      nzWidth="130px"
                      i18n="@@evaluation.benchmark.col.holder"
                    >
                      Holder
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="task"
                      i18n="@@evaluation.benchmark.col.task"
                    >
                      Task
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="model"
                      nzWidth="180px"
                      i18n="@@evaluation.benchmark.col.model"
                    >
                      Model
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="rep"
                      nzWidth="70px"
                      nzAlign="right"
                      i18n="@@evaluation.benchmark.col.rep"
                    >
                      Rep
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="score"
                      nzWidth="86px"
                      nzAlign="right"
                      i18n="@@evaluation.benchmark.col.score"
                    >
                      Score
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="passed"
                      nzWidth="100px"
                      i18n="@@evaluation.benchmark.col.tests"
                    >
                      Tests
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="cost"
                      nzWidth="100px"
                      nzAlign="right"
                      i18n="@@evaluation.benchmark.col.cost"
                    >
                      Cost
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="duration"
                      nzWidth="100px"
                      nzAlign="right"
                      i18n="@@evaluation.benchmark.col.duration"
                    >
                      Duration
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @if (virtual()) {
                    <ng-template nz-virtual-scroll let-a let-i="index">
                      <ng-container
                        *ngTemplateOutlet="attemptRow; context: { $implicit: a, i: i }"
                      />
                    </ng-template>
                  } @else {
                    @for (a of at.data; track a.id; let i = $index) {
                      <ng-container
                        *ngTemplateOutlet="attemptRow; context: { $implicit: a, i: i }"
                      />
                    }
                  }
                </tbody>
              </nz-table>
            }
          </nz-tab>
        </nz-tabs>
      }
    </div>

    <ng-template #attemptRow let-a let-i="i">
      <tr
        class="clickable-row"
        [class.active]="nav.cursor() === i || panel.value() === a.id"
        [attr.data-nav-index]="i"
        tabindex="0"
        (click)="openAttempt(a)"
        (keydown.enter)="openAttempt(a)"
        [attr.aria-label]="attemptLabel(a)"
      >
        <td><bo-baton [baton]="attemptBaton(a)" size="sm" /></td>
        <td>
          <div class="cell-main">
            <span class="cell-title">{{ a.taskName }}</span>
            <span class="cell-sub">{{ category(a.category) }} · {{ lower(a.difficulty) }}</span>
          </div>
        </td>
        <td class="ink-2">{{ a.modelDisplayName }}</td>
        <td nzAlign="right">
          <span class="mono-cell">{{ a.repetition }}</span>
        </td>
        <td nzAlign="right">
          <span class="mono-cell strong">{{
            a.score !== null && a.score !== undefined ? fixed(a.score, 1) : '-'
          }}</span>
        </td>
        <td>
          @if (a.passed === true) {
            <span class="state-word"
              ><bo-glyph holder="done" [size]="11" />
              <ng-container i18n="@@evaluation.attempt.passed">passed</ng-container></span
            >
          } @else if (a.passed === false) {
            <span class="state-word"
              ><bo-glyph holder="blocked" [size]="11" />
              <ng-container i18n="@@evaluation.attempt.failed">failed</ng-container></span
            >
          } @else {
            <span class="quiet">-</span>
          }
        </td>
        <td nzAlign="right"><bo-cost [value]="a.cost" [usage]="a.usage" /></td>
        <td nzAlign="right">
          <span class="mono-cell">{{ duration(a.durationMs) }}</span>
        </td>
      </tr>
    </ng-template>

    <ng-template #rowsTable let-rows="rows">
      <nz-table [nzData]="rows" [nzShowPagination]="false" nzSize="middle" class="board">
        <thead>
          <tr>
            <th nzWidth="44px" nzAlign="right">#</th>
            <th nzShowSort [boSort]="boardSort" boSortKey="model" i18n="@@evaluation.board.model">
              Model
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="score"
              nzWidth="200px"
              nz-tooltip
              nzTooltipTitle="Mean judge score over successful attempts"
              i18n-nzTooltipTitle="@@evaluation.board.scoreTooltip"
              i18n="@@evaluation.board.score"
            >
              Score
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="passRate"
              nzWidth="96px"
              nzAlign="right"
              i18n="@@evaluation.board.passRate"
            >
              Pass rate
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="meanCost"
              nzWidth="106px"
              nzAlign="right"
              i18n="@@evaluation.board.meanCost"
            >
              Mean cost
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="total"
              nzWidth="96px"
              nzAlign="right"
              i18n="@@evaluation.board.total"
            >
              Total
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="perDollar"
              nzWidth="96px"
              nzAlign="right"
              nz-tooltip
              nzTooltipTitle="Mean score per dollar of mean cost"
              i18n-nzTooltipTitle="@@evaluation.board.scorePerDollarTooltip"
              i18n="@@evaluation.board.scorePerDollar"
            >
              Score / $
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="latency"
              nzWidth="136px"
              nzAlign="right"
              nz-tooltip
              nzTooltipTitle="The candidate's own response time (model answer or agent job), without the judge, queueing or retries, over succeeded attempts: median / 95th percentile (the longest one below 20 attempts)"
              i18n-nzTooltipTitle="@@evaluation.board.latencyTooltip"
              i18n="@@evaluation.board.latency"
            >
              Time p50 / p95
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="stability"
              nzWidth="96px"
              nzAlign="right"
              nz-tooltip
              nzTooltipTitle="Standard deviation of the score across repetitions (lower is steadier)"
              i18n-nzTooltipTitle="@@evaluation.board.stabilityTooltip"
              i18n="@@evaluation.board.stability"
            >
              Stability
            </th>
          </tr>
        </thead>
        <tbody>
          @for (r of boardSort.apply(sorted(rows)); track r.modelProfileId) {
            <tr [class.first]="rank(r, rows) === 1">
              <td nzAlign="right">
                <span class="mono-cell">{{ rank(r, rows) }}</span>
              </td>
              <td>
                <div class="cell-main">
                  <span class="model"
                    ><span class="dot" [attr.data-provider]="r.provider" aria-hidden="true"></span
                    ><span class="cell-title">{{ r.modelDisplayName }}</span></span
                  >
                  <span class="cell-sub"
                    >{{ providerName(r.provider) }} · {{ attemptsText(r.attempts) }}</span
                  >
                </div>
              </td>
              <td>
                <div class="score">
                  <span class="mono-cell strong">{{
                    r.meanScore !== null && r.meanScore !== undefined ? fixed(r.meanScore, 1) : '-'
                  }}</span>
                  <span class="score-bar" aria-hidden="true"
                    ><span [style.width.%]="r.meanScore ?? 0"></span
                  ></span>
                </div>
              </td>
              <td nzAlign="right">
                <span class="mono-cell">{{
                  r.passRate !== null && r.passRate !== undefined
                    ? fixed(r.passRate * 100, 0) + '%'
                    : '-'
                }}</span>
              </td>
              <td nzAlign="right"><bo-cost [value]="r.meanCost" /></td>
              <td nzAlign="right"><bo-cost [value]="r.totalCost" /></td>
              <td nzAlign="right">
                <span class="mono-cell">{{
                  r.scorePerDollar !== null && r.scorePerDollar !== undefined
                    ? fixed(r.scorePerDollar, 0)
                    : '-'
                }}</span>
              </td>
              <td nzAlign="right">
                <span class="mono-cell" [attr.title]="latencyNote(r)"
                  >{{ duration(r.p50LatencyMs) }} / {{ duration(r.p95LatencyMs) }}</span
                >
              </td>
              <td nzAlign="right">
                <span class="mono-cell">{{
                  r.stability !== null && r.stability !== undefined ? fixed(r.stability, 1) : '-'
                }}</span>
              </td>
            </tr>
          }
        </tbody>
      </nz-table>
    </ng-template>

    <app-attempt-detail-modal
      [open]="!!panel.value()"
      [loading]="attemptLoading()"
      [attempt]="selectedAttempt()"
      (closed)="panel.close()"
    />
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .meta-line {
        display: flex;
        align-items: baseline;
        flex-wrap: wrap;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .sep {
        margin: 0 6px;
      }
      .runner-notice {
        display: flex;
        align-items: baseline;
        gap: 8px;
        padding: 8px 12px;
        margin: 0 0 16px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      .facts {
        display: flex;
        flex-wrap: wrap;
        gap: 12px 40px;
        margin: 0 0 16px;
        padding: 12px 16px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
      }
      .facts dt {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .facts dd {
        margin: 3px 0 0;
        font-size: 13px;
      }
      .facts .wide {
        flex: 1 1 320px;
      }
      .models {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 14px;
      }
      .model {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
      }
      .dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: var(--bo-idle);
        flex: none;
      }
      .dot[data-provider='ANTHROPIC'] {
        background: var(--bo-you);
      }
      .dot[data-provider='OPENAI'] {
        background: var(--bo-ci);
      }
      .dot[data-provider='GOOGLE'] {
        background: var(--bo-accent);
      }
      .dot[data-provider='MISTRAL'] {
        background: var(--bo-blocked);
      }
      .dot[data-provider='OLLAMA'] {
        background: var(--bo-ink-2);
      }
      .dot[data-provider='OPENAI_COMPATIBLE'] {
        background: var(--bo-done);
      }
      .card {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        margin-bottom: 16px;
      }
      .card-head {
        display: flex;
        align-items: baseline;
        flex-wrap: wrap;
        gap: 4px 12px;
        padding: 12px 16px 0;
      }
      .card-head h2 {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
      }
      .hint {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .legend {
        display: flex;
        gap: 12px;
        margin-left: auto;
      }
      .legend .model {
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .chart {
        height: 340px;
      }
      .chart-placeholder {
        margin: 16px;
        height: 308px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
      }
      .group-title {
        margin: 12px 0 8px;
        font-size: 13px;
        font-weight: 600;
      }
      .strong {
        color: var(--bo-ink);
        font-weight: 600;
      }
      .score {
        display: flex;
        align-items: center;
        gap: 10px;
      }
      .score .mono-cell {
        width: 36px;
        text-align: right;
      }
      .score-bar {
        flex: 1;
        height: 6px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        overflow: hidden;
      }
      .score-bar span {
        display: block;
        height: 100%;
        background: var(--bo-ink-2);
        border-radius: 999px;
      }
      tr.first .score-bar span {
        background: var(--bo-accent);
      }
      .counts {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 16px;
        margin: 0 0 10px;
      }
    `,
  ],
})
export class BenchmarkDetailPageComponent {
  private readonly api = inject(BenchmarksApi);
  private readonly events = inject(EventStreamService);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly identity = inject(IdentityService);
  private readonly breadcrumb = inject(BreadcrumbService);
  private readonly destroyRef = inject(DestroyRef);
  readonly router = inject(Router);

  readonly benchmarkId = input.required<string>();
  readonly f = urlFilters({
    tab: 'string',
    aq: 'string',
    aholder: 'list',
    atask: 'string',
    amodel: 'string',
  });
  readonly panel = panelParam('attempt');
  private readonly tokens = chartTokensSignal();

  readonly benchmark = signal<Benchmark | null>(null);
  readonly notFound = signal(false);
  readonly leaderboard = signal<Leaderboard | null>(null);
  readonly attempts = signal<Attempt[]>([]);
  /** ADR 0046: the runners where this benchmark's coding jobs go, for the "waiting for a runner" notice. */
  readonly targets = signal<ExecutionTarget[]>([]);
  readonly waitingForRunner = computed(() => {
    const b = this.benchmark();
    if (b?.status !== 'RUNNING') return false;
    const target = this.targets().find((t) => t.target === (b.runnerTarget ?? 'SHARED'));
    if (!target || target.onlineRunners > 0) return false;
    return this.attempts().some(
      (a) => a.kind === 'CODING_JOB' && (a.status === 'RUNNING' || a.status === 'PENDING'),
    );
  });
  readonly attemptsLoading = signal(false);
  readonly attemptsLoaded = signal(false);
  readonly busy = signal<'start' | 'cancel' | null>(null);
  readonly selectedAttempt = signal<AttemptDetail | null>(null);
  readonly attemptLoading = signal(false);

  readonly humanize = humanize;
  readonly category = categoryLabel;
  readonly fixed = fixed;
  readonly text = {
    attemptsRunning: $localize`:@@evaluation.benchmark.attemptsRunning:attempts running`,
    noScores: $localize`:@@evaluation.benchmark.noScores:no scores yet`,
    start: $localize`:@@evaluation.benchmark.startAction:Start benchmark`,
    noAttemptMatch: $localize`:@@evaluation.benchmark.attempts.noMatch:No attempt matches these filters.`,
    noAttempts: $localize`:@@evaluation.benchmark.attempts.empty:No attempts yet.`,
    clearFilters: $localize`:@@evaluation.clearFiltersAction:Clear filters`,
  };
  readonly attemptsTab = computed(() => {
    const n = this.attempts().length;
    return $localize`:@@evaluation.benchmark.tab.attempts:Attempts · ${n}:count:`;
  });
  readonly lower = (v: string | null | undefined) => humanize(v).toLowerCase();
  readonly duration = formatDuration;
  readonly providerName = (p: string | null | undefined) => PROVIDERS[p ?? ''] ?? humanize(p);
  readonly attemptBaton = (a: Attempt) => batonOf.attempt(a.status);
  readonly attemptHolders = ATTEMPT_HOLDERS;
  readonly trackAttempt = (_: number, a: Attempt) => a.id;

  readonly canRun = computed(() => this.identity.can('evaluation.run'));
  readonly baton = computed(() => benchmarkBaton(this.benchmark()?.status));
  readonly tabIndex = computed(() => (this.f.values().tab === 'attempts' ? 1 : 0));
  readonly scatter = computed(() => scatterOption(this.leaderboard()?.overall, this.tokens()));
  readonly providersInChart = computed(() => [
    ...new Set((this.leaderboard()?.overall ?? []).map((r) => r.provider ?? '').filter(Boolean)),
  ]);
  readonly chartLabel = computed(() => {
    const rows = this.sorted(this.leaderboard()?.overall ?? []);
    const top = rows[0];
    const chart = icu(
      $localize`:@@evaluation.benchmark.chartLabel:Scatter chart of mean score against mean cost per attempt for {count, plural, one {# model} other {# models}}`,
      { count: rows.length },
    );
    if (!top) return chart + '.';
    const model = top.modelDisplayName ?? '';
    const score =
      top.meanScore !== null && top.meanScore !== undefined ? fixed(top.meanScore, 1) : '-';
    return (
      chart +
      $localize`:@@evaluation.benchmark.chartLabelBest:; best score: ${model}:model:, ${score}:score:` +
      '.'
    );
  });
  readonly byCategory = computed(() =>
    Object.entries(this.leaderboard()?.byCategory ?? {}).map(([key, rows]) => ({ key, rows })),
  );
  readonly byDifficulty = computed(() =>
    Object.entries(this.leaderboard()?.byDifficulty ?? {}).map(([key, rows]) => ({ key, rows })),
  );
  readonly taskOptions = computed(() => {
    const seen = new Map<string, string>();
    for (const a of this.attempts())
      if (a.taskId && !seen.has(a.taskId)) seen.set(a.taskId, a.taskName ?? a.taskId);
    for (const t of this.leaderboard()?.byTask ?? [])
      if (t.taskId && !seen.has(t.taskId)) seen.set(t.taskId, t.taskName ?? t.taskId);
    return [...seen.entries()].map(([id, name]) => ({ id, name }));
  });
  readonly filteredAttempts = computed(() => {
    const v = this.f.values();
    const q = (v.aq ?? '').trim().toLowerCase();
    return filterAttemptsByHolder(this.attempts(), v.aholder).filter(
      (a) =>
        !q || [a.taskName ?? '', a.modelDisplayName ?? ''].some((x) => x.toLowerCase().includes(q)),
    );
  });
  readonly counts = computed(() => attemptCounts(this.attempts()));
  /** Column sorting of the attempts (`?sort=`); keyboard navigation follows the same order. */
  readonly attemptSort = tableSort<Attempt>({
    status: (a) => a.status,
    task: (a) => a.taskName,
    model: (a) => a.modelDisplayName,
    rep: (a) => a.repetition,
    score: (a) => a.score,
    passed: (a) => a.passed,
    cost: (a) => a.cost?.amount,
    duration: (a) => a.durationMs,
  });
  readonly attemptRows = computed(() => this.attemptSort.apply(this.filteredAttempts()));
  /** Column sorting of the leaderboards (`?board=`); the rank (#) stays the rank by score. */
  readonly boardSort = tableSort<LeaderboardRow>(
    {
      model: (r) => r.modelDisplayName,
      score: (r) => r.meanScore,
      passRate: (r) => r.passRate,
      meanCost: (r) => r.meanCost?.amount,
      total: (r) => r.totalCost?.amount,
      perDollar: (r) => r.scorePerDollar,
      latency: (r) => r.p50LatencyMs,
      stability: (r) => r.stability,
    },
    { param: 'board' },
  );
  readonly virtual = computed(() => this.attemptRows().length > VIRTUAL_FROM);
  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    const selected = $localize`:@@evaluation.benchmarks.chip.selected:selected`;
    if (v.atask) {
      const task = this.taskOptions().find((t) => t.id === v.atask)?.name ?? selected;
      out.push({
        id: 'atask',
        label: $localize`:@@evaluation.benchmark.chip.task:Task: ${task}:task:`,
      });
    }
    if (v.amodel) {
      const model =
        this.benchmark()?.models?.find((m) => m.id === v.amodel)?.displayName ?? selected;
      out.push({
        id: 'amodel',
        label: $localize`:@@evaluation.benchmark.chip.model:Model: ${model}:model:`,
      });
    }
    return out;
  });
  readonly menu = computed<PageAction[]>(() => {
    const items: PageAction[] = [
      {
        label: $localize`:@@evaluation.benchmark.reload:Reload benchmark`,
        icon: 'reload',
        run: () => this.reload(),
      },
    ];
    if (this.canRun() && this.benchmark()?.status !== 'RUNNING')
      items.push({
        label: $localize`:@@evaluation.benchmark.delete:Delete benchmark`,
        icon: 'delete',
        danger: true,
        run: () => void this.remove(),
      });
    return items;
  });

  readonly nav = listNavigation({
    items: this.attemptRows,
    open: (a) => this.openAttempt(a),
    group: $localize`:@@evaluation.benchmark.attemptsGroup:Attempts`,
    when: () => this.tabIndex() === 1 && !this.panel.value(),
  });

  constructor() {
    effect(() => {
      this.benchmarkId();
      untracked(() => {
        this.benchmark.set(null);
        this.leaderboard.set(null);
        this.attempts.set([]);
        this.attemptsLoaded.set(false);
        this.load();
      });
    });
    effect(() => {
      this.benchmarkId();
      this.f.values().atask;
      this.f.values().amodel;
      untracked(() => this.loadAttempts());
    });
    effect(() => {
      const id = this.panel.value();
      untracked(() => {
        this.selectedAttempt.set(null);
        if (id) this.loadAttempt(id);
      });
    });
    this.events
      .of('BenchmarkProgress', 'BenchmarkFinished')
      .pipe(
        filter(
          (e) => (e.payload as Record<string, unknown>)?.['benchmarkId'] === this.benchmarkId(),
        ),
        debounceTime(1500),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.reload());
    interval(15000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.benchmark()?.status === 'RUNNING' && this.events.state() !== 'connected')
          this.reload();
      });
  }

  eyebrow(suite: string): string {
    return $localize`:@@evaluation.benchmark.eyebrow:Benchmark · ${suite}:suite:`;
  }

  attemptLabel(a: Attempt): string {
    const task = a.taskName ?? '';
    const model = a.modelDisplayName ?? '';
    const state = this.attemptBaton(a).label;
    return $localize`:@@evaluation.benchmark.attemptLabel:${task}:task: by ${model}:model:, ${state}:state:`;
  }

  attemptsText(n: number | null | undefined): string {
    return icu(
      $localize`:@@evaluation.chart.tooltip.attempts:{count, plural, one {# attempt} other {# attempts}}`,
      { count: n ?? 0 },
    );
  }

  reload(): void {
    this.load();
    this.loadAttempts();
  }

  load(): void {
    const id = this.benchmarkId();
    this.api.get(id).subscribe({
      next: (b) => {
        this.benchmark.set(b);
        if (b.status === 'RUNNING')
          this.api
            .executionTargets()
            .subscribe({ next: (t) => this.targets.set(t), error: () => undefined });
        this.notFound.set(false);
        this.breadcrumb.set([
          { label: $localize`:@@evaluation.title:Evaluation`, link: '/evaluation/suites' },
          {
            label: $localize`:@@evaluation.nav.benchmarks:Benchmarks`,
            link: '/evaluation/benchmarks',
          },
          { label: b.name },
        ]);
      },
      error: () => this.notFound.set(!this.benchmark()),
    });
    this.api.leaderboard(id).subscribe((l) => this.leaderboard.set(l));
  }

  loadAttempts(): void {
    this.attemptsLoading.set(true);
    this.api
      .attempts(this.benchmarkId(), {
        taskId: this.f.values().atask,
        modelProfileId: this.f.values().amodel,
      })
      .subscribe({
        next: (list) => {
          this.attempts.set(list);
          this.attemptsLoading.set(false);
          this.attemptsLoaded.set(true);
        },
        error: () => {
          this.attemptsLoading.set(false);
          this.attemptsLoaded.set(true);
        },
      });
  }

  setTab(index: number): void {
    this.f.set({ tab: index === 1 ? 'attempts' : null });
  }

  removeChip(id: string): void {
    if (id === 'atask') this.f.set({ atask: null });
    else if (id === 'amodel') this.f.set({ amodel: null });
  }

  sorted(rows: LeaderboardRow[]): LeaderboardRow[] {
    return [...rows].sort((a, b) => (b.meanScore ?? -1) - (a.meanScore ?? -1));
  }

  /** Rank by mean score, whatever column the table is sorted by. */
  rank(r: LeaderboardRow, rows: LeaderboardRow[]): number {
    return this.sorted(rows).indexOf(r) + 1;
  }

  /** ADR 0046: how many succeeded attempts the times come from; p95 is the longest one below 20. */
  latencyNote(r: LeaderboardRow): string {
    const count = r.latencySamples ?? 0;
    return icu(
      $localize`:@@evaluation.board.latencyNote:{count, plural, =0 {no succeeded attempt} one {from # succeeded attempt} other {from # succeeded attempts}}`,
      { count },
    );
  }

  openAttempt(a: Attempt): void {
    if (a.id) this.panel.open(a.id);
  }

  private loadAttempt(id: string): void {
    this.attemptLoading.set(true);
    this.api.attempt(this.benchmarkId(), id).subscribe({
      next: (d) => {
        this.selectedAttempt.set(d);
        this.attemptLoading.set(false);
      },
      error: () => {
        this.attemptLoading.set(false);
        this.panel.close();
      },
    });
  }

  start(): void {
    this.busy.set('start');
    this.api.start(this.benchmarkId()).subscribe({
      next: (b) => {
        this.benchmark.set(b);
        this.busy.set(null);
        this.message.success($localize`:@@evaluation.benchmarks.started:Benchmark started`);
        this.loadAttempts();
      },
      error: () => this.busy.set(null),
    });
  }

  async cancel(): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@evaluation.benchmark.cancel.title:Cancel this benchmark?`,
      $localize`:@@evaluation.benchmark.cancel.text:Attempts that have not started are dropped; finished attempts keep their scores and costs.`,
      $localize`:@@evaluation.benchmark.cancel.ok:Cancel benchmark`,
    );
    if (!ok) return;
    this.busy.set('cancel');
    this.api.cancel(this.benchmarkId()).subscribe({
      next: (b) => {
        this.benchmark.set(b);
        this.busy.set(null);
        this.message.success($localize`:@@evaluation.benchmark.cancelled:Benchmark cancelled`);
      },
      error: () => this.busy.set(null),
    });
  }

  async remove(): Promise<void> {
    const b = this.benchmark();
    if (!b) return;
    const n = b.progress?.total ?? 0;
    const name = b.name;
    const ok = await this.confirm.danger(
      $localize`:@@evaluation.benchmark.delete.title:Delete benchmark "${name}:name:"?`,
      icu(
        $localize`:@@evaluation.benchmark.delete.text:Its {count, plural, one {# attempt} other {# attempts}} and the leaderboard are removed.`,
        { count: n },
      ),
      $localize`:@@evaluation.benchmark.delete:Delete benchmark`,
    );
    if (!ok) return;
    this.api.delete(this.benchmarkId()).subscribe(() => {
      this.message.success($localize`:@@evaluation.benchmark.deleted:Benchmark deleted`);

      void this.router.navigate(['/evaluation/benchmarks']);
    });
  }
}
