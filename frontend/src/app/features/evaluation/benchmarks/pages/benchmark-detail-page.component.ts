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
  Money,
} from '../../../../core/api/types';
import { IdentityService } from '../../../../core/auth/identity.service';
import { EventStreamService } from '../../../../core/events/event-stream.service';
import { ConfirmService } from '../../../../core/ui/confirm.service';
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
import {
  ATTEMPT_RESULTS,
  attemptResult,
  attemptResultLabel,
  attemptStatusBaton,
  attemptSummary,
  benchmarkBaton,
} from '../data/benchmark-view';
import {
  frontCaption,
  leaderboardToScatter,
  paretoFrontier,
  scatterOption,
  unplotted,
} from '../data/leaderboard-chart';
import { boardCostPerAccepted, boardMeanCost, comparesOnApi } from '../data/board-cost';
import {
  agentLabel,
  allOnSubscription,
  attemptKind,
  blockVerdict,
  blockTitle,
  kindName,
  mixedAgents,
  passedCount,
  rankRows,
  rowKind,
  splitByKind,
  tasksPerKind,
  unreliable,
  type WorkKind,
} from '../data/board-blocks';
import {
  benchmarkDuration,
  benchmarkMoney,
  columnDecimals,
  moneyWith,
} from '../data/benchmark-format';
import { applyShortName, shortModelNames } from '../data/model-names';
import { candidateCount, candidateKey } from '../data/candidates';
import { categoryLabel } from '../../suites/data/suite-view';
import { SortHeaderDirective } from '../../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../../core/ui/relay/table-sort';

const VIRTUAL_FROM = 100;
const PROVIDERS: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  GOOGLE: 'Google',
  MISTRAL: 'Mistral',
  OLLAMA: 'Ollama',
  OPENAI_COMPATIBLE: $localize`:@@evaluation.provider.openaiCompatible:OpenAI-compatible`,
};

/** A candidate of the attempts filter: agent and model. */
interface CandidateOption {
  key: string;
  kind: WorkKind;
  label: string;
}

/** Rows of one breakdown entry (a category, a difficulty or a task). */
interface BreakdownEntry {
  key: string;
  label: string;
  rows: LeaderboardRow[];
}

/** The breakdown entries of one kind of work. */
interface BreakdownGroup {
  kind: WorkKind;
  title: string;
  entries: BreakdownEntry[];
}

/**
 * Benchmark: status, attempt progress, one meta line of facts, the leaderboard split by kind of work (model answers
 * and coding agents are ranked, charted and compared only within their block; ADR 0059/0065) and the attempts list
 * with filters in the URL; an attempt opens in the side panel (`?attempt=<id>`).
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
          <div boHeaderMeta class="meta">
            <div class="meta-line">
              @for (fact of facts(); track $index) {
                <span class="item">{{ fact }}</span>
              }
              @if (costOnlyApi()) {
                <span class="item"
                  ><span
                    class="help"
                    nz-tooltip
                    [nzTooltipTitle]="text.apiHint"
                    tabindex="0"
                    i18n="@@evaluation.benchmark.meta.costApi"
                    >total cost ≈ {{ money(b.totalApiCost) }} (API prices; subscription, nothing
                    spent)</span
                  ></span
                >
              } @else {
                <span class="item cost-fact"
                  ><ng-container i18n="@@evaluation.benchmark.meta.cost">total cost</ng-container>
                  <bo-cost
                    [value]="b.totalCost"
                    [apiValue]="b.totalApiCost"
                    [apiHint]="text.apiHint"
                    [format]="money"
                /></span>
              }
            </div>
            <div class="meta-line">
              <span class="item"
                ><bo-provenance
                  verb="created by"
                  i18n-verb="@@evaluation.benchmark.createdBy"
                  [actor]="b.createdBy"
                  [at]="b.createdAt"
              /></span>
              <span class="item"
                ><a
                  [routerLink]="['/evaluation/suites', b.suiteId]"
                  i18n="@@evaluation.benchmark.openSuite"
                  >Open the suite</a
                ></span
              >
            </div>
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
            [summary]="progressSummary()"
          />
        </bo-page-header>

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
              <nz-tabs nzSize="small" class="breakdown">
                <nz-tab nzTitle="Overall" i18n-nzTitle="@@evaluation.benchmark.tab.overall">
                  @for (blk of blocks(); track blk.kind) {
                    <section class="block" [attr.aria-label]="blk.title">
                      <h2 class="block-title">{{ blk.title }}</h2>
                      @if (blk.verdict) {
                        <p class="verdict">{{ blk.verdict }}</p>
                      }
                      <ng-container
                        *ngTemplateOutlet="rowsTable; context: { rows: blk.rows, kind: blk.kind }"
                      />
                      <div class="card">
                        <header class="card-head">
                          <span class="hint" i18n="@@evaluation.benchmark.chartHint2"
                            >Top left is better.</span
                          >
                          @if (blk.caption) {
                            <span class="hint caption"
                              ><span class="swatch" aria-hidden="true"></span
                              >{{ blk.caption }}</span
                            >
                          }
                          @if (multiProvider()) {
                            <span
                              class="legend"
                              aria-label="Providers"
                              i18n-aria-label="@@evaluation.benchmark.providers"
                            >
                              @for (p of blk.providers; track p) {
                                <span class="model"
                                  ><span
                                    class="dot"
                                    [attr.data-provider]="p"
                                    aria-hidden="true"
                                  ></span
                                  >{{ providerName(p) }}</span
                                >
                              }
                            </span>
                          }
                        </header>
                        @defer (on viewport) {
                          <div
                            echarts
                            [options]="blk.chart"
                            class="chart"
                            role="img"
                            [attr.aria-label]="blk.chartLabel"
                          ></div>
                        } @placeholder {
                          <div class="chart chart-placeholder" aria-hidden="true"></div>
                        }
                        @if (blk.unplotted) {
                          <p class="unplotted">{{ blk.unplotted }}</p>
                        }
                      </div>
                    </section>
                  }
                </nz-tab>
                <nz-tab nzTitle="By category" i18n-nzTitle="@@evaluation.benchmark.tab.byCategory">
                  <ng-container
                    *ngTemplateOutlet="
                      breakdownTab;
                      context: { groups: byCategory(), empty: text.noByCategory }
                    "
                  />
                </nz-tab>
                <nz-tab
                  nzTitle="By difficulty"
                  i18n-nzTitle="@@evaluation.benchmark.tab.byDifficulty"
                >
                  <ng-container
                    *ngTemplateOutlet="
                      breakdownTab;
                      context: { groups: byDifficulty(), empty: text.noByDifficulty }
                    "
                  />
                </nz-tab>
                <nz-tab nzTitle="By task" i18n-nzTitle="@@evaluation.benchmark.tab.byTask">
                  <ng-container
                    *ngTemplateOutlet="
                      breakdownTab;
                      context: { groups: byTask(), empty: text.noByTask }
                    "
                  />
                </nz-tab>
              </nz-tabs>
            }
          </nz-tab>

          <nz-tab [nzTitle]="attemptsTab()">
            <bo-filter-bar
              placeholder="Search tasks or candidates"
              i18n-placeholder="@@evaluation.benchmark.attempts.searchCandidates"
              label="Filter attempts"
              i18n-label="@@evaluation.benchmark.attempts.filter"
              [query]="f.values().aq ?? ''"
              (queryChange)="f.set({ aq: $event })"
              [chips]="chips()"
              (chipRemoved)="removeChip($event)"
              (cleared)="f.clear()"
            >
              @if (kindsInAttempts().length > 1) {
                <nz-select
                  [ngModel]="f.values().akind"
                  (ngModelChange)="f.set({ akind: $event, acand: null })"
                  nzAllowClear
                  nzPlaceHolder="All kinds"
                  i18n-nzPlaceHolder="@@evaluation.benchmark.attempts.allKinds"
                  class="filter-kind"
                  aria-label="Filter attempts by kind of work"
                  i18n-aria-label="@@evaluation.benchmark.attempts.filterKind"
                >
                  @for (k of kindsInAttempts(); track k) {
                    <nz-option [nzValue]="k" [nzLabel]="kindLabel(k)" />
                  }
                </nz-select>
              }
              <nz-select
                [ngModel]="f.values().acand"
                (ngModelChange)="f.set({ acand: $event })"
                nzAllowClear
                nzShowSearch
                nzPlaceHolder="All candidates"
                i18n-nzPlaceHolder="@@evaluation.benchmark.attempts.allCandidates"
                class="filter-candidate"
                aria-label="Filter attempts by candidate"
                i18n-aria-label="@@evaluation.benchmark.attempts.filterCandidate"
              >
                @for (c of candidateFilterOptions(); track c.key) {
                  <nz-option [nzValue]="c.key" [nzLabel]="c.label" />
                }
              </nz-select>
              <nz-select
                [ngModel]="f.values().aresult"
                (ngModelChange)="f.set({ aresult: $event })"
                nzAllowClear
                nzPlaceHolder="All results"
                i18n-nzPlaceHolder="@@evaluation.benchmark.attempts.allResults"
                class="filter-result"
                aria-label="Filter attempts by result"
                i18n-aria-label="@@evaluation.benchmark.attempts.filterResult"
              >
                @for (r of results; track r) {
                  <nz-option [nzValue]="r" [nzLabel]="resultLabel(r)" />
                }
              </nz-select>
              <nz-select
                [ngModel]="f.values().atask"
                (ngModelChange)="f.set({ atask: $event })"
                nzAllowClear
                nzShowSearch
                nzPlaceHolder="All tasks"
                i18n-nzPlaceHolder="@@evaluation.benchmark.attempts.allTasks"
                class="filter-task"
                aria-label="Filter attempts by task"
                i18n-aria-label="@@evaluation.benchmark.attempts.filterTask"
              >
                @for (t of taskOptions(); track t.id) {
                  <nz-option [nzValue]="t.id" [nzLabel]="t.name" />
                }
              </nz-select>
            </bo-filter-bar>
            @if (f.active() && attempts().length) {
              <p class="counts" aria-live="polite">{{ shownText() }}</p>
            }
            @if (attemptsLoading() && !attemptsLoaded()) {
              <bo-skeleton
                variant="table"
                [rows]="8"
                [columns]="10"
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
                nzTableLayout="fixed"
                class="attempts"
              >
                <thead>
                  <tr>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="status"
                      nzWidth="40px"
                      nz-tooltip
                      nzTooltipTitle="State: only timed-out, failed, running and waiting attempts are marked"
                      i18n-nzTooltipTitle="@@evaluation.benchmark.attempts.col.statusTooltip"
                      aria-label="State"
                      i18n-aria-label="@@evaluation.benchmark.attempts.col.statusAria"
                    ></th>
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
                      nzWidth="176px"
                      i18n="@@evaluation.benchmark.col.candidate"
                    >
                      Candidate
                    </th>
                    @if (showRepetitions()) {
                      <th
                        nzShowSort
                        [boSort]="attemptSort"
                        boSortKey="rep"
                        nzWidth="72px"
                        nzAlign="right"
                        i18n="@@evaluation.benchmark.col.rep"
                      >
                        Rep
                      </th>
                    }
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="score"
                      nzWidth="104px"
                      nzAlign="right"
                      nz-tooltip
                      [nzTooltipTitle]="text.acceptRule"
                      i18n="@@evaluation.benchmark.col.scoreAccepted"
                    >
                      Score
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="cost"
                      nzWidth="136px"
                      nzAlign="right"
                      nz-tooltip
                      nzTooltipTitle="The candidate's cost; the judge's cost and the whole duration are in the attempt details"
                      i18n-nzTooltipTitle="@@evaluation.benchmark.col.costTooltip"
                      i18n="@@evaluation.benchmark.col.cost"
                    >
                      Cost
                    </th>
                    <th
                      nzShowSort
                      [boSort]="attemptSort"
                      boSortKey="candidateTime"
                      nzWidth="120px"
                      nzAlign="right"
                      nz-tooltip
                      nzTooltipTitle="The candidate's own time: from the start of its job or model call to its result, without queueing"
                      i18n-nzTooltipTitle="@@evaluation.benchmark.col.candidateTimeTooltip"
                      i18n="@@evaluation.benchmark.col.candidateTime"
                    >
                      Own time
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

    <ng-template #breakdownTab let-groups="groups" let-empty="empty">
      @for (g of groups; track g.kind) {
        @if (multiKind()) {
          <h2 class="block-title">{{ g.title }}</h2>
        }
        @for (entry of g.entries; track entry.key) {
          <h3 class="group-title">{{ entry.label }}</h3>
          <ng-container
            *ngTemplateOutlet="rowsTable; context: { rows: entry.rows, kind: g.kind }"
          />
        }
      } @empty {
        <bo-empty tone="quiet" [title]="empty" />
      }
    </ng-template>

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
        <td class="status-cell">
          @if (statusBaton(a.status); as s) {
            <span
              class="status-icon"
              role="img"
              nz-tooltip
              [nzTooltipTitle]="s.label"
              [attr.aria-label]="s.label"
              ><bo-glyph [holder]="s.holder" [live]="!!s.live" [size]="12"
            /></span>
          }
        </td>
        <td>
          <div class="cell-main">
            <span class="cell-title ellipsis" nz-tooltip [nzTooltipTitle]="a.taskName">{{
              a.taskName
            }}</span>
            <span class="cell-sub ellipsis"
              >{{ category(a.category) }} · {{ lower(a.difficulty) }}</span
            >
          </div>
        </td>
        <td>
          <div class="cell-main">
            <span class="cell-title ellipsis" nz-tooltip [nzTooltipTitle]="a.candidate?.label">{{
              attemptModel(a)
            }}</span>
            @if (multiAgent()) {
              <span class="cell-sub ellipsis">{{ agentOf(a.candidate) }}</span>
            }
          </div>
        </td>
        @if (showRepetitions()) {
          <td nzAlign="right">
            <span class="mono-cell">{{ a.repetition }}</span>
          </td>
        }
        <td nzAlign="right">
          <span
            class="score-cell"
            nz-tooltip
            [nzTooltipTitle]="acceptNote(a)"
            [attr.aria-label]="acceptNote(a)"
            ><span class="mono-cell strong">{{
              a.score !== null && a.score !== undefined ? fixed(a.score, 1) : '-'
            }}</span>
            @if (a.passed === true) {
              <bo-glyph class="mark" holder="done" [size]="12" />
            } @else if (a.passed === false) {
              <nz-icon class="mark no" nzType="close" />
            } @else {
              <span class="mark" aria-hidden="true"></span>
            }
          </span>
        </td>
        <td nzAlign="right">
          @if (attemptOnApi(a)) {
            <bo-cost [value]="a.apiCost" [usage]="a.usage" [format]="attemptMoney()" />
            @if (!allSub()) {
              <ng-container *ngTemplateOutlet="apiMark" />
            }
          } @else {
            <bo-cost
              [value]="a.cost"
              [apiValue]="a.apiCost"
              [apiHint]="text.apiHint"
              [usage]="a.usage"
              [format]="attemptMoney()"
            />
          }
        </td>
        <td nzAlign="right">
          <span class="mono-cell">{{ duration(a.candidateLatencyMs) }}</span>
        </td>
      </tr>
    </ng-template>

    <ng-template #rowsTable let-rows="rows" let-kind="kind">
      @let ordered = ranked(rows);
      @let mixed = mixedAgents(rows);
      @let fmt = tableMoney(ordered);
      <nz-table
        [nzData]="ordered"
        [nzShowPagination]="false"
        nzSize="middle"
        nzTableLayout="fixed"
        class="board"
      >
        <thead>
          <tr>
            <th nzWidth="44px" nzAlign="right">#</th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="model"
              nzWidth="184px"
              i18n="@@evaluation.board.model"
            >
              Model
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="score"
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
              boSortKey="passed"
              nzWidth="144px"
              nzAlign="right"
              nz-tooltip
              [nzTooltipTitle]="passedHeader(kind)"
              i18n="@@evaluation.board.accepted"
            >
              Accepted
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="perAccepted"
              nzWidth="152px"
              nzAlign="right"
              nz-tooltip
              [nzTooltipTitle]="allSub() ? text.perAcceptedApiTip : text.perAcceptedTip"
              i18n="@@evaluation.board.costPerAcceptedShort"
            >
              Cost / accepted
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="meanCost"
              nzWidth="168px"
              nzAlign="right"
              nz-tooltip
              [nzTooltipTitle]="allSub() ? text.meanCostApiTip : text.meanCostTip"
              i18n="@@evaluation.board.meanCostPerAttempt"
            >
              Mean cost per attempt
            </th>
            <th
              nzShowSort
              [boSort]="boardSort"
              boSortKey="latency"
              nzWidth="104px"
              nzAlign="right"
              nz-tooltip
              nzTooltipTitle="The candidate's own time from the start of its job or model call to its result, without queueing for a runner, the judge or retries, over succeeded attempts: the median; the 95th percentile on hover"
              i18n-nzTooltipTitle="@@evaluation.board.ownTimeP50Tooltip"
              i18n="@@evaluation.board.latencyP50"
            >
              Time p50
            </th>
            @if (showStability()) {
              <th
                nzShowSort
                [boSort]="boardSort"
                boSortKey="stability"
                nzWidth="112px"
                nzAlign="right"
                nz-tooltip
                nzTooltipTitle="Standard deviation of the score across repetitions (lower is steadier)"
                i18n-nzTooltipTitle="@@evaluation.board.stabilityTooltip"
                i18n="@@evaluation.board.stability"
              >
                Stability
              </th>
            }
          </tr>
        </thead>
        <tbody>
          @for (r of boardSort.apply(ordered); track candidateKey(r.candidate ?? r)) {
            <tr [class.dim]="isUnreliable(r)">
              <td nzAlign="right">
                <span class="mono-cell">{{ perAccepted(r) ? ordered.indexOf(r) + 1 : '—' }}</span>
              </td>
              <td>
                <div class="cell-main">
                  <span
                    class="cell-title ellipsis"
                    nz-tooltip
                    [nzTooltipTitle]="r.candidate?.label ?? r.modelDisplayName"
                  >
                    @if (multiProvider()) {
                      <span class="dot" [attr.data-provider]="r.provider" aria-hidden="true"></span>
                    }
                    {{ rowName(r) }}
                  </span>
                  @if (mixed) {
                    <span class="cell-sub ellipsis"
                      >{{ agentOf(r.candidate) }} · {{ attemptsText(r.attempts) }}</span
                    >
                  }
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
                <span
                  class="mono-cell"
                  nz-tooltip
                  [nzTooltipTitle]="passedNote(r, kind)"
                  tabindex="0"
                  >{{ passed(r) }}/{{ r.attempts ?? 0 }}</span
                >
              </td>
              <td nzAlign="right">
                @if (perAccepted(r); as cost) {
                  <span class="money">{{ fmt.perAccepted(cost) }}</span>
                  @if (onApi(r) && !allSub()) {
                    <ng-container *ngTemplateOutlet="apiMark" />
                  }
                } @else {
                  <span class="mono-cell quiet">—</span>
                }
              </td>
              <td nzAlign="right">
                @if (allSub()) {
                  <bo-cost [value]="r.meanApiCost" [format]="fmt.mean" />
                } @else {
                  <bo-cost
                    [value]="r.meanCost"
                    [apiValue]="r.meanApiCost"
                    [apiHint]="text.apiHint"
                    [format]="fmt.mean"
                  />
                }
              </td>
              <td nzAlign="right">
                <span class="mono-cell" nz-tooltip [nzTooltipTitle]="latencyNote(r)" tabindex="0">{{
                  duration(r.p50LatencyMs)
                }}</span>
              </td>
              @if (showStability()) {
                <td nzAlign="right">
                  <span class="mono-cell">{{
                    r.stability !== null && r.stability !== undefined ? fixed(r.stability, 1) : '-'
                  }}</span>
                </td>
              }
            </tr>
          }
        </tbody>
      </nz-table>
    </ng-template>

    <ng-template #apiMark>
      <span
        class="api-mark"
        nz-tooltip
        [nzTooltipTitle]="text.apiHint"
        tabindex="0"
        [attr.aria-label]="text.apiHint"
        i18n="@@evaluation.board.apiMark"
        >API</span
      >
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
      .meta {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      /* Every item starts with its separator; the line is shifted left by one separator and clipped, so the dot at
         the start of each wrapped line is hidden and no line ends with a dangling one. */
      .meta-line {
        display: flex;
        align-items: baseline;
        flex-wrap: wrap;
        margin-left: -20px;
        padding: 2px 0;
        /* overflow on the shifted box itself would keep the dot visible: clip its first 20px instead */
        clip-path: inset(0 0 0 20px);
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .meta-line .item {
        display: inline-flex;
        align-items: baseline;
        white-space: nowrap;
      }
      .meta-line .item::before {
        content: '·';
        display: inline-block;
        width: 20px;
        text-align: center;
      }
      .cost-fact {
        gap: 4px;
      }
      .help {
        border-bottom: 1px dotted var(--bo-ink-3);
        cursor: help;
      }
      .verdict {
        margin: 0 0 8px;
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      .caption {
        display: inline-flex;
        align-items: center;
        gap: 8px;
      }
      .swatch {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: var(--bo-accent);
        flex: none;
      }
      tr.dim td,
      tr.dim .cell-title,
      tr.dim .strong,
      tr.dim .money,
      tr.dim .mono-cell {
        color: var(--bo-ink-3);
      }
      .status-cell {
        text-align: center;
      }
      .status-icon {
        display: inline-flex;
        cursor: help;
      }
      .score-cell {
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }
      .mark {
        display: inline-flex;
        width: 12px;
        justify-content: center;
      }
      .mark.no {
        font-size: 12px;
        color: var(--bo-ink-3);
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
      .model {
        display: inline-flex;
        align-items: center;
        gap: 8px;
      }
      .dot {
        display: inline-block;
        width: 8px;
        height: 8px;
        margin-right: 4px;
        border-radius: 50%;
        background: var(--bo-idle);
        flex: none;
        vertical-align: middle;
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
      .block + .block {
        margin-top: 32px;
      }
      .block-title {
        margin: 16px 0 8px;
        font-size: 14px;
        font-weight: 600;
        color: var(--bo-ink);
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
      .hint,
      .unplotted {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .unplotted {
        margin: 0;
        padding: 0 16px 12px;
      }
      .legend {
        display: flex;
        gap: 12px;
        margin-left: auto;
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .chart {
        height: 220px;
      }
      .chart-placeholder {
        margin: 16px;
        height: 188px;
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
      .ellipsis {
        display: block;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .board th,
      .attempts th {
        white-space: nowrap;
      }
      .board ::ng-deep .ant-table-column-title,
      .attempts ::ng-deep .ant-table-column-title {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .board ::ng-deep thead > tr > th {
        position: sticky;
        top: 0;
        z-index: 2;
      }
      .money {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
        color: var(--bo-ink);
        white-space: nowrap;
      }
      .score {
        display: flex;
        align-items: center;
        gap: 8px;
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
      .api-mark {
        margin-left: 4px;
        font-family: var(--bo-font-mono);
        font-size: 12px;
        color: var(--bo-ink-3);
        white-space: nowrap;
        cursor: help;
      }
      .counts {
        margin: 0 0 8px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .filter-kind {
        width: 176px;
      }
      .filter-candidate {
        width: 200px;
      }
      .filter-result {
        width: 176px;
      }
      .filter-task {
        width: 220px;
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
    akind: 'string',
    acand: 'string',
    aresult: 'string',
    atask: 'string',
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

  readonly category = categoryLabel;
  readonly fixed = fixed;
  readonly money = benchmarkMoney;
  readonly duration = benchmarkDuration;
  readonly passed = passedCount;
  readonly ranked = rankRows;
  readonly mixedAgents = mixedAgents;
  readonly kindLabel = (k: WorkKind) => kindName(k);
  readonly resultLabel = attemptResultLabel;
  readonly results = ATTEMPT_RESULTS;
  readonly statusBaton = attemptStatusBaton;
  readonly text = {
    attemptsRunning: $localize`:@@evaluation.benchmark.attemptsRunning:attempts running`,
    noScores: $localize`:@@evaluation.benchmark.noScores:no scores yet`,
    start: $localize`:@@evaluation.benchmark.startAction:Start benchmark`,
    noAttemptMatch: $localize`:@@evaluation.benchmark.attempts.noMatch:No attempt matches these filters.`,
    noAttempts: $localize`:@@evaluation.benchmark.attempts.empty:No attempts yet.`,
    clearFilters: $localize`:@@evaluation.clearFiltersAction:Clear filters`,
    noByCategory: $localize`:@@evaluation.benchmark.noByCategory:No breakdown by category yet.`,
    noByDifficulty: $localize`:@@evaluation.benchmark.noByDifficulty:No breakdown by difficulty yet.`,
    noByTask: $localize`:@@evaluation.benchmark.noByTask:No breakdown by task yet.`,
    apiHint: $localize`:@@evaluation.board.apiHint:Estimated at API list prices; paid by the subscription (Claude Code with your own login).`,
    acceptRule: $localize`:@@evaluation.board.acceptRule:An attempt is accepted when its score is at least 70; a coding task also needs its verification (the tests) to pass.`,
    perAcceptedTip: $localize`:@@evaluation.board.costPerAcceptedApiTooltip:Cost per accepted result: total cost / passed attempts: failed and timed-out attempts are paid by the passed ones. Marked API: estimated at API list prices, paid by the subscription`,
    perAcceptedApiTip: $localize`:@@evaluation.board.costPerAcceptedAllApiTooltip:Cost per accepted result: total cost at API list prices / passed attempts: failed and timed-out attempts are paid by the passed ones. The subscription paid; nothing was spent`,
    meanCostTip: $localize`:@@evaluation.board.meanCostTooltip:What the candidate spent per attempt, failed and timed-out attempts included`,
    meanCostApiTip: $localize`:@@evaluation.board.meanCostApiTooltip:The candidate's cost per attempt at API list prices, failed and timed-out attempts included`,
  };
  readonly onApi = comparesOnApi;
  readonly perAccepted = boardCostPerAccepted;
  readonly attemptsTab = computed(() => {
    const n = this.attempts().length;
    return $localize`:@@evaluation.benchmark.tab.attempts:Attempts · ${n}:count:`;
  });
  readonly lower = (v: string | null | undefined) => humanize(v).toLowerCase();
  readonly candidateKey = candidateKey;
  readonly providerName = (p: string | null | undefined) => PROVIDERS[p ?? ''] ?? humanize(p);
  readonly trackAttempt = (_: number, a: Attempt) => a.id;

  readonly canRun = computed(() => this.identity.can('evaluation.run'));
  readonly baton = computed(() => benchmarkBaton(this.benchmark()?.status));
  readonly tabIndex = computed(() => (this.f.values().tab === 'attempts' ? 1 : 0));

  /** Model names without what all of them repeat (a common "(login)" suffix, a common vendor word). */
  private readonly names = computed(() => {
    const all = [
      ...(this.benchmark()?.models ?? []).map((m) => m.displayName ?? ''),
      ...(this.leaderboard()?.overall ?? []).map((r) => r.modelDisplayName ?? ''),
      ...this.attempts().map((a) => a.modelDisplayName ?? ''),
    ].filter(Boolean);
    const short = shortModelNames(all);
    return {
      map: new Map(all.map((n, i) => [n.trim(), short.names[i]])),
      suffix: short.suffix,
      prefix: short.prefix,
    };
  });
  readonly shortName = (name: string | null | undefined): string =>
    this.names().map.get((name ?? '').trim()) ?? name ?? '';
  readonly rowName = (r: LeaderboardRow): string =>
    this.shortName(r.modelDisplayName) || (r.modelProfileId ?? '');

  readonly allSub = computed(() => allOnSubscription(this.leaderboard()?.overall));
  /** The benchmark spent nothing (every candidate and the judge on a subscription): show the API figure only. */
  readonly costOnlyApi = computed(() => {
    const b = this.benchmark();
    return !(b?.totalCost?.amount ?? 0) && (b?.totalApiCost?.amount ?? 0) > 0;
  });
  readonly multiProvider = computed(
    () =>
      new Set((this.leaderboard()?.overall ?? []).map((r) => r.provider ?? '').filter(Boolean))
        .size > 1,
  );
  readonly showStability = computed(() => (this.benchmark()?.repetitions ?? 1) >= 2);
  readonly showRepetitions = this.showStability;

  /** The facts of the meta line: candidates, judge, repetitions, where coding runs, the common model suffix. */
  readonly facts = computed(() => {
    const b = this.benchmark();
    if (!b) return [];
    const refs = b.candidateRefs ?? [];
    const out = [
      icu(
        $localize`:@@evaluation.benchmark.meta.candidates:{count, plural, one {# candidate} other {# candidates}}`,
        { count: refs.length || candidateCount(b) },
      ),
    ];
    const judgeName = b.judge?.displayName;
    const judge = judgeName ? applyShortName(judgeName, this.names()) : b.judgeModelProfileId;
    if (judge) out.push($localize`:@@evaluation.benchmark.meta.judge:judged by ${judge}:judge:`);
    out.push(
      icu(
        $localize`:@@evaluation.benchmark.meta.repetitions:{count, plural, one {# repetition} other {# repetitions}}`,
        { count: b.repetitions ?? 1 },
      ),
    );
    const coding =
      refs.some((c) => !!c.harness) ||
      (this.leaderboard()?.overall ?? []).some((r) => rowKind(r) === 'coding');
    if (coding)
      out.push(
        b.runnerTarget === 'PERSONAL'
          ? $localize`:@@evaluation.benchmark.meta.runnerPersonal:coding tasks on your personal runner`
          : $localize`:@@evaluation.benchmark.meta.runnerShared:coding tasks on shared runners`,
      );
    const suffix = this.names().suffix;
    if (suffix)
      out.push(
        this.allSub()
          ? $localize`:@@evaluation.benchmark.meta.modelsLogin:models from your Claude login (subscription)`
          : $localize`:@@evaluation.benchmark.meta.modelsSuffix:models: „${suffix}:suffix:”`,
      );
    return out;
  });

  readonly progressSummary = computed(() => {
    const s = attemptSummary(this.attempts());
    return s.length ? s : null;
  });
  /** "12 of 36 attempts" while a filter is on. */
  readonly shownText = computed(() =>
    icu(
      $localize`:@@evaluation.benchmark.attempts.shown:{shown} of {count, plural, one {# attempt} other {# attempts}}`,
      { shown: this.filteredAttempts().length, count: this.attempts().length },
    ),
  );

  private readonly taskCounts = computed(() => {
    const b = this.benchmark();
    return tasksPerKind({
      attempts: this.attempts(),
      leaderboard: this.leaderboard(),
      candidates: b?.candidateRefs,
      repetitions: b?.repetitions,
      totalAttempts: b?.progress?.total,
    });
  });

  /** One block per kind of work: its title, chart and the candidates the chart leaves out. */
  readonly blocks = computed(() => {
    const tokens = this.tokens();
    const byProvider = this.multiProvider();
    return splitByKind(this.leaderboard()?.overall).map((blk) => {
      const missing = unplotted(blk.rows).map(this.rowName);
      const names = missing.join(', ');
      const count = blk.rows.length - missing.length;
      return {
        ...blk,
        title: blockTitle(blk, this.taskCounts()[blk.kind]),
        chart: scatterOption(blk.rows, tokens, this.rowName, byProvider),
        verdict: this.verdict(blk.rows),
        caption: frontCaption(paretoFrontier(leaderboardToScatter(blk.rows)).length, byProvider),
        providers: [...new Set(blk.rows.map((r) => r.provider ?? '').filter(Boolean))],
        unplotted: missing.length
          ? $localize`:@@evaluation.board.unplotted:No accepted result: ${names}:names:`
          : '',
        chartLabel: icu(
          $localize`:@@evaluation.benchmark.chartLabelCandidates:Scatter chart of mean score against cost per accepted result for {count, plural, one {# candidate} other {# candidates}}`,
          { count },
        ),
      };
    });
  });
  readonly multiKind = computed(() => this.blocks().length > 1);

  readonly byCategory = computed(() =>
    this.group(
      Object.entries(this.leaderboard()?.byCategory ?? {}).map(([key, rows]) => ({
        key,
        label: categoryLabel(key),
        rows,
      })),
    ),
  );
  readonly byDifficulty = computed(() =>
    this.group(
      Object.entries(this.leaderboard()?.byDifficulty ?? {}).map(([key, rows]) => ({
        key,
        label: humanize(key),
        rows,
      })),
    ),
  );
  readonly byTask = computed(() =>
    this.group(
      (this.leaderboard()?.byTask ?? []).map((t) => ({
        key: t.taskId ?? t.taskName ?? '',
        label: t.taskName ?? '',
        rows: t.rows ?? [],
      })),
    ),
  );

  /** The candidates of the benchmark for the attempts filter; the agent is added when a model name repeats. */
  readonly candidateOptions = computed<CandidateOption[]>(() => {
    const b = this.benchmark();
    const models = new Map((b?.models ?? []).map((m) => [m.id, m.displayName]));
    const seen = new Map<string, { kind: WorkKind; model: string; agent: string }>();
    for (const c of b?.candidateRefs ?? [])
      seen.set(candidateKey(c), {
        kind: c.harness ? 'coding' : 'answer',
        model: this.shortName(models.get(c.modelProfileId) ?? c.label),
        agent: c.agentName,
      });
    for (const a of this.attempts()) {
      const key = this.attemptKey(a);
      if (!seen.has(key))
        seen.set(key, {
          kind: attemptKind(a),
          model: this.attemptModel(a),
          agent: a.candidate?.agentName ?? '',
        });
    }
    const counts = new Map<string, number>();
    for (const v of seen.values()) counts.set(v.model, (counts.get(v.model) ?? 0) + 1);
    return [...seen.entries()].map(([key, v]) => ({
      key,
      kind: v.kind,
      label: (counts.get(v.model) ?? 0) > 1 && v.agent ? `${v.model} · ${v.agent}` : v.model,
    }));
  });
  readonly candidateFilterOptions = computed(() => {
    const kind = this.f.values().akind;
    return this.candidateOptions().filter((c) => !kind || c.kind === kind);
  });
  readonly multiAgent = computed(
    () => new Set(this.attempts().map((a) => a.candidate?.agentKey ?? '')).size > 1,
  );
  readonly kindsInAttempts = computed(() => {
    const kinds = new Set<WorkKind>([
      ...this.attempts().map(attemptKind),
      ...this.candidateOptions().map((c) => c.kind),
    ]);
    return (['answer', 'coding'] as WorkKind[]).filter((k) => kinds.has(k));
  });

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
    return this.attempts().filter(
      (a) =>
        (!v.atask || a.taskId === v.atask) &&
        (!v.akind || attemptKind(a) === v.akind) &&
        (!v.acand || this.attemptKey(a) === v.acand) &&
        (!v.aresult || attemptResult(a) === v.aresult) &&
        (!q ||
          [a.taskName ?? '', this.attemptCandidate(a)].some((x) => x.toLowerCase().includes(q))),
    );
  });
  /** Column sorting of the attempts (`?sort=`); keyboard navigation follows the same order. */
  readonly attemptSort = tableSort<Attempt>({
    status: (a) => a.status,
    task: (a) => a.taskName,
    model: (a) => this.attemptModel(a),
    rep: (a) => a.repetition,
    score: (a) => a.score,
    passed: (a) => a.passed,
    cost: (a) => a.apiCost?.amount ?? a.cost?.amount,
    judgeCost: (a) => a.judgeApiCost?.amount ?? a.judgeCost?.amount,
    candidateTime: (a) => a.candidateLatencyMs,
    duration: (a) => a.durationMs,
  });
  readonly attemptRows = computed(() => this.attemptSort.apply(this.filteredAttempts()));
  /**
   * Column sorting of the leaderboards (`?board=`); without one the rows are in rank order (cost per accepted result,
   * cheapest first) and the rank (#) stays that order whatever column is sorted.
   */
  readonly boardSort = tableSort<LeaderboardRow>(
    {
      model: (r) => this.rowName(r),
      score: (r) => r.meanScore,
      passed: (r) => r.passRate,
      perAccepted: (r) => boardCostPerAccepted(r)?.amount,
      meanCost: (r) => boardMeanCost(r),
      latency: (r) => r.p50LatencyMs,
      stability: (r) => r.stability,
    },
    { param: 'board', default: { key: 'perAccepted', order: 'ascend' } },
  );
  readonly virtual = computed(() => this.attemptRows().length > VIRTUAL_FROM);
  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    const selected = $localize`:@@evaluation.benchmarks.chip.selected:selected`;
    if (v.akind) {
      const kind = kindName(v.akind as WorkKind);
      out.push({
        id: 'akind',
        label: $localize`:@@evaluation.benchmark.chip.kind:Kind: ${kind}:kind:`,
      });
    }
    if (v.acand) {
      const candidate = this.candidateOptions().find((c) => c.key === v.acand)?.label ?? selected;
      out.push({
        id: 'acand',
        label: $localize`:@@evaluation.benchmark.chip.candidate:Candidate: ${candidate}:candidate:`,
      });
    }
    if (v.aresult) {
      const result = (ATTEMPT_RESULTS as readonly string[]).includes(v.aresult)
        ? attemptResultLabel(v.aresult as (typeof ATTEMPT_RESULTS)[number])
        : selected;
      out.push({
        id: 'aresult',
        label: $localize`:@@evaluation.benchmark.chip.result:Result: ${result}:result:`,
      });
    }
    if (v.atask) {
      const task = this.taskOptions().find((t) => t.id === v.atask)?.name ?? selected;
      out.push({
        id: 'atask',
        label: $localize`:@@evaluation.benchmark.chip.task:Task: ${task}:task:`,
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
        this.loadAttempts();
      });
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
    const model = this.attemptCandidate(a);
    const state =
      attemptStatusBaton(a.status)?.label ??
      attemptResultLabel(a.passed === true ? 'accepted' : 'notAccepted');
    return $localize`:@@evaluation.benchmark.attemptLabel:${task}:task: by ${model}:model:, ${state}:state:`;
  }

  attemptsText(n: number | null | undefined): string {
    return icu(
      $localize`:@@evaluation.chart.tooltip.attempts:{count, plural, one {# attempt} other {# attempts}}`,
      { count: n ?? 0 },
    );
  }

  /** "Passed attempts of all attempts (failed and timed-out included). <rule>" for the column header. */
  passedHeader(kind: WorkKind): string {
    return (
      $localize`:@@evaluation.board.passedTooltip:Accepted attempts of all attempts, failed and timed-out ones included.` +
      ' ' +
      this.passRule(kind)
    );
  }

  /** "80% accepted. <rule>" for a cell; a warning first when fewer than half were accepted. */
  passedNote(r: LeaderboardRow, kind: WorkKind): string {
    const rate = fixed((r.passRate ?? 0) * 100, 0);
    const note =
      $localize`:@@evaluation.board.passedNote:${rate}:rate:% accepted.` +
      ' ' +
      this.passRule(kind);
    return unreliable(r)
      ? $localize`:@@evaluation.board.unreliable:Fewer than half of the attempts accepted: cheap, but unreliable.` +
          ' ' +
          note
      : note;
  }

  readonly isUnreliable = unreliable;

  /** The agent of a candidate in words (built-in agents in the UI language). */
  readonly agentOf = agentLabel;

  /** "Cheapest accepted result: Sonnet · $0.075 · 5/7 accepted · Best score: Opus · 84.6". */
  verdict(rows: LeaderboardRow[]): string {
    const { cheapest, best } = blockVerdict(rows);
    const parts: string[] = [];
    if (cheapest) {
      const model = this.rowName(cheapest);
      const cost = benchmarkMoney(boardCostPerAccepted(cheapest));
      const accepted = `${passedCount(cheapest)}/${cheapest.attempts ?? 0}`;
      parts.push(
        $localize`:@@evaluation.board.verdictCheapest:Cheapest accepted result: ${model}:model: · ${cost}:cost: · ${accepted}:accepted: accepted`,
      );
    }
    if (best) {
      const model = this.rowName(best);
      const score = fixed(best.meanScore ?? 0, 1);
      parts.push(
        $localize`:@@evaluation.board.verdictBest:Best score: ${model}:model: · ${score}:score:`,
      );
    }
    return parts.join(' · ');
  }

  /** One decimals rule per money column of a table (three when any amount is below $1). */
  tableMoney(rows: LeaderboardRow[]): {
    perAccepted: (v: Money | number | null | undefined) => string;
    mean: (v: Money | number | null | undefined) => string;
  } {
    const sub = this.allSub();
    return {
      perAccepted: moneyWith(columnDecimals(rows.map((r) => boardCostPerAccepted(r)?.amount))),
      mean: moneyWith(
        columnDecimals(
          rows.flatMap((r) =>
            sub ? [r.meanApiCost?.amount] : [r.meanCost?.amount, r.meanApiCost?.amount],
          ),
        ),
      ),
    };
  }

  /** A subscription attempt (nothing spent, an API figure): shown at API prices. */
  attemptOnApi(a: Attempt): boolean {
    return !(a.cost?.amount ?? 0) && (a.apiCost?.amount ?? 0) > 0;
  }

  /** The money format of the attempts' cost column, from the amounts shown. */
  readonly attemptMoney = computed(() =>
    moneyWith(
      columnDecimals(
        this.filteredAttempts().flatMap((a) =>
          this.attemptOnApi(a) ? [a.apiCost?.amount] : [a.cost?.amount, a.apiCost?.amount],
        ),
      ),
    ),
  );

  /** "accepted" / "not accepted" with the rule of the attempt's kind, for the score cell. */
  acceptNote(a: Attempt): string {
    const rule = this.passRule(attemptKind(a));
    if (a.passed === true) return attemptResultLabel('accepted') + '. ' + rule;
    if (a.passed === false) return attemptResultLabel('notAccepted') + '. ' + rule;
    return rule;
  }

  passRule(kind: WorkKind): string {
    return kind === 'coding'
      ? $localize`:@@evaluation.board.passRuleCoding:A coding attempt is accepted when its verification (the tests) passes and its score is at least 70.`
      : $localize`:@@evaluation.board.passRuleAnswer:A model answer is accepted when its score is at least 70.`;
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

  /** All attempts of the benchmark; the tab filters them here (kind, candidate, result, task, search). */
  loadAttempts(): void {
    this.attemptsLoading.set(true);
    this.api.attempts(this.benchmarkId()).subscribe({
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
    if (id === 'akind') this.f.set({ akind: null, acand: null });
    else if (id === 'acand') this.f.set({ acand: null });
    else if (id === 'aresult') this.f.set({ aresult: null });
    else if (id === 'atask') this.f.set({ atask: null });
  }

  /** "agent|model" of an attempt, as the candidate filter keys it. */
  attemptKey(a: Attempt): string {
    return candidateKey(a.candidate ?? { agentKey: '', modelProfileId: a.modelProfileId });
  }

  /** The short model name of an attempt. */
  attemptModel(a: Attempt): string {
    return this.shortName(a.modelDisplayName) || (a.candidate?.label ?? '');
  }

  /** ADR 0059: "Claude Code · Claude Sonnet"; the model for attempts made before candidates. */
  attemptCandidate(a: Attempt): string {
    return a.candidate?.label ?? a.modelDisplayName ?? '';
  }

  /** ADR 0046: p95 and how many succeeded attempts the times come from (p95 is the longest one below 20). */
  latencyNote(r: LeaderboardRow): string {
    const count = r.latencySamples ?? 0;
    const p95 = benchmarkDuration(r.p95LatencyMs);
    return (
      $localize`:@@evaluation.board.p95:p95 ${p95}:p95:` +
      ' · ' +
      icu(
        $localize`:@@evaluation.board.latencyNote:{count, plural, =0 {no succeeded attempt} one {from # succeeded attempt} other {from # succeeded attempts}}`,
        { count },
      )
    );
  }

  openAttempt(a: Attempt): void {
    if (a.id) this.panel.open(a.id);
  }

  /** Breakdown entries per kind of work: each entry keeps only the rows of that kind. */
  private group(entries: BreakdownEntry[]): BreakdownGroup[] {
    const titles = new Map(this.blocks().map((b) => [b.kind, b.title]));
    return (['answer', 'coding'] as WorkKind[])
      .map((kind) => ({
        kind,
        title: titles.get(kind) ?? kindName(kind),
        entries: entries
          .map((e) => ({ ...e, rows: e.rows.filter((r) => rowKind(r) === kind) }))
          .filter((e) => e.rows.length > 0),
      }))
      .filter((g) => g.entries.length > 0);
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
